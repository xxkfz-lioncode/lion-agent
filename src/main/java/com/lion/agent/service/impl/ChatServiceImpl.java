package com.lion.agent.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.advisor.TokenUsageAdvisor;
import com.lion.agent.common.PageResult;
import com.lion.agent.dto.ChatRequest;
import com.lion.agent.entity.ChatMessage;
import com.lion.agent.entity.Conversation;
import com.lion.agent.exception.BusinessException;
import com.lion.agent.mapper.ChatMessageMapper;
import com.lion.agent.mapper.ConversationMapper;
import com.lion.agent.service.ChatService;
import com.lion.agent.service.ToolRegistryService;
import com.lion.agent.vo.ChatResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话服务实现（集成 Spring AI，对接千问大模型）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ToolRegistryService toolRegistryService;
    private final JdbcChatMemoryRepository jdbcChatMemoryRepository;

    /** 上传文件根目录（相对工作目录），多模态图片保存于 {uploadPath}/multimodal/yyyy/MM/dd/ 下 */
    @Value("${lion.upload.path:upload/}")
    private String uploadPath;

    /** 系统提示词模板（位于 resources/prompts/system-prompt.st，支持 {agentName} 等变量） */
    private static final PromptTemplate SYSTEM_PROMPT_TEMPLATE =
            new PromptTemplate(new ClassPathResource("prompts/system-prompt.st"));


    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatResult send(ChatRequest request) {
        long userId = StpUtil.getLoginIdAsLong();

        // 1. 确定会话（为空则自动创建新会话）
        Long conversationId = request.getConversationId();
        if (conversationId == null) {
            conversationId = createConversation(userId, request.getMessage());
        } else {
            checkConversationOwner(conversationId, userId);
        }

        // 2. 保存用户消息
        ChatMessage userMessage = new ChatMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setRole("user");
        userMessage.setContent(request.getMessage());
        userMessage.setCreatedAt(LocalDateTime.now());
        chatMessageMapper.insert(userMessage);


        // 3. 调用千问大模型（携带会话记忆）；语义缓存命中时由全局 QaCacheAdvisor 短路，
        //    直接返回历史回答并回写会话记忆，此处无需感知命中与否
        String reply = callQwen(request.getMessage(), conversationId);


        // 4. 保存 AI 回复
        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setConversationId(conversationId);
        assistantMessage.setRole("assistant");
        assistantMessage.setContent(reply);
        assistantMessage.setCreatedAt(LocalDateTime.now());
        chatMessageMapper.insert(assistantMessage);

        // 5. 更新会话标题（首轮对话时用第一条消息作为标题）
        updateTitleIfNeeded(conversationId);

        return ChatResult.builder()
                .conversationId(conversationId)
                .userMessageId(userMessage.getId())
                .assistantMessageId(assistantMessage.getId())
                .reply(reply)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatResult sendMultimodal(String message, Long conversationId,
                                     List<MultipartFile> images, List<String> imageUrls) {
        long userId = StpUtil.getLoginIdAsLong();

        // 1. 确定会话（为空则自动创建新会话）
        if (conversationId == null) {
            conversationId = createConversation(userId, message);
        } else {
            checkConversationOwner(conversationId, userId);
        }

        // 2. 解析图片：上传文件保存副本（供历史回显）；远程 URL 直接引用
        List<ImageRef> imageRefs = resolveImages(images, imageUrls);

        // 3. 保存用户消息（文本 + 图片引用，便于历史回显）
        ChatMessage userMessage = new ChatMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setRole("user");
        userMessage.setContent(buildUserContent(message, imageRefs));
        userMessage.setCreatedAt(LocalDateTime.now());
        chatMessageMapper.insert(userMessage);

        // 4. 调用多模态大模型（图片 + 文本，携带会话记忆）
        String reply = callQwenMultimodal(message, imageRefs, conversationId);

        // 5. 保存 AI 回复
        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setConversationId(conversationId);
        assistantMessage.setRole("assistant");
        assistantMessage.setContent(reply);
        assistantMessage.setCreatedAt(LocalDateTime.now());
        chatMessageMapper.insert(assistantMessage);

        // 6. 更新会话标题
        updateTitleIfNeeded(conversationId);

        return ChatResult.builder()
                .conversationId(conversationId)
                .userMessageId(userMessage.getId())
                .assistantMessageId(assistantMessage.getId())
                .reply(reply)
                .build();
    }

    @Override
    public SseEmitter stream(ChatRequest request) {
        long userId = StpUtil.getLoginIdAsLong();

        // 1. 确定会话（为空则自动创建新会话）
        Long conversationId = request.getConversationId();
        if (conversationId == null) {
            conversationId = createConversation(userId, request.getMessage());
        } else {
            checkConversationOwner(conversationId, userId);
        }

        // 2. 保存用户消息
        ChatMessage userMessage = new ChatMessage();
        userMessage.setConversationId(conversationId);
        userMessage.setRole("user");
        userMessage.setContent(request.getMessage());
        userMessage.setCreatedAt(LocalDateTime.now());
        chatMessageMapper.insert(userMessage);

        // 供 lambda 使用（lambda 中要求 effectively final）
        Long finalConversationId = conversationId;

        // 3. 创建 SSE 发射器（0L 表示不自动超时）
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean completed = new AtomicBoolean(false);

        try {
            // 先推送会话信息，便于前端拿到新会话 ID
            emitter.send(SseEmitter.event()
                    .name("start")
                    .data(Map.of("conversationId", conversationId)));
        } catch (IOException e) {
            log.error("SSE 推送会话信息失败", e);
            emitter.completeWithError(e);
            return emitter;
        }

        // 同步调用千问（含工具调用，工具由 ToolRegistryService 按需筛选）；
        // 语义缓存命中时由全局 QaCacheAdvisor 短路，直接返回历史回答
        String reply;
        try {
            reply = callQwen(request.getMessage(), finalConversationId);
        } catch (Exception e) {
            log.error("调用千问大模型失败", e);
            if (completed.compareAndSet(false, true)) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("message", "AI 服务调用失败，请稍后重试")));
                } catch (IOException ex) {
                    log.error("SSE 推送错误失败", ex);
                }
                emitter.completeWithError(e);
            }
            return emitter;
        }

        try {
            // 保存 AI 回复（语义缓存回写已由 QaCacheAdvisor 在调用链中完成）
            saveAssistantMessage(finalConversationId, reply);
            // 更新会话标题（首轮用第一条用户消息）
            updateTitleIfNeeded(finalConversationId);
            // 一次性推送完整回复 + done，并关闭连接，前端恢复输入
            emitter.send(SseEmitter.event().name("message").data(Map.of("content", reply)));
            emitter.send(SseEmitter.event().name("done").data(Map.of("reply", reply)));
            emitter.complete();
        } catch (Exception e) {
            log.error("SSE 完成处理失败", e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    @Override
    public PageResult<Conversation> listConversations(int pageNum, int pageSize, String keyword) {
        long userId = StpUtil.getLoginIdAsLong();
        var wrapper = Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getUserId, userId);
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Conversation::getTitle, keyword);
        }
        wrapper.orderByDesc(Conversation::getUpdatedAt);
        Page<Conversation> page = new Page<>(pageNum, pageSize);
        Page<Conversation> result = conversationMapper.selectPage(page, wrapper);
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    @Override
    public PageResult<ChatMessage> getMessages(Long conversationId, int pageNum, int pageSize) {
        long userId = StpUtil.getLoginIdAsLong();
        checkConversationOwner(conversationId, userId);
        var wrapper = Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByAsc(ChatMessage::getId);
        Page<ChatMessage> page = new Page<>(pageNum, pageSize);
        Page<ChatMessage> result = chatMessageMapper.selectPage(page, wrapper);
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(Long conversationId) {
        long userId = StpUtil.getLoginIdAsLong();
        checkConversationOwner(conversationId, userId);
        // 逻辑删除会话与其消息
        conversationMapper.deleteById(conversationId);
        chatMessageMapper.delete(
                Wrappers.<ChatMessage>lambdaQuery()
                        .eq(ChatMessage::getConversationId, conversationId));
        // 删除存储
        chatMemory.clear(conversationId.toString());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearAllConversations() {
        long userId = StpUtil.getLoginIdAsLong();
        List<Long> ids = conversationMapper.selectList(
                        Wrappers.<Conversation>lambdaQuery().eq(Conversation::getUserId, userId))
                .stream()
                .map(Conversation::getId)
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        // 逻辑删除关联消息
        chatMessageMapper.delete(
                Wrappers.<ChatMessage>lambdaQuery()
                        .in(ChatMessage::getConversationId, ids));
        // 逻辑删除当前用户的全部会话
        conversationMapper.delete(
                Wrappers.<Conversation>lambdaQuery()
                        .eq(Conversation::getUserId, userId));
        // 清理各会话在 AI 记忆侧的存储
        ids.forEach(id -> chatMemory.clear(id.toString()));
    }

    @Override
    public void renameConversation(Long conversationId, String title) {
        long userId = StpUtil.getLoginIdAsLong();
        checkConversationOwner(conversationId, userId);
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException("会话不存在");
        }
        conversation.setTitle(title);
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);
    }

    // ==================== 私有方法 ====================

    /**
     * 保存一条 AI 回复消息
     */
    private void saveAssistantMessage(Long conversationId, String content) {
        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setConversationId(conversationId);
        assistantMessage.setRole("assistant");
        assistantMessage.setContent(content);
        assistantMessage.setCreatedAt(LocalDateTime.now());
        chatMessageMapper.insert(assistantMessage);
    }

    /**
     * 同步调用千问大模型（通过 Spring AI ChatClient，纯文本）
     *
     * @param message        用户消息
     * @param conversationId 会话 ID，用于按会话保存/加载多轮记忆
     */
    private String callQwen(String message, Long conversationId) {
        log.info("开始请求LLM大模型（文本）......");
        long userId = StpUtil.getLoginIdAsLong();
        try {
            // 系统提示词：定义 Agent 角色（模板在 resources/prompts/system-prompt.st，用变量渲染）
            String systemPrompt = SYSTEM_PROMPT_TEMPLATE.render(Map.of("agentName", "Lion Agent"));
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .system(systemPrompt)
                    // 注入会话 ID / 用户 ID 到 Advisor 上下文（必须放在同一个 advisors 调用里，避免被覆盖）
                    .advisors(a -> a
                            .param(ChatMemory.CONVERSATION_ID, conversationId)
                            .param(TokenUsageAdvisor.USER_ID_KEY, userId))
                    // 工具按需注册：常驻（UserTools）+ 向量预筛（StarFortuneTools 等），见 ToolRegistryService
                    .tools(toolRegistryService.selectTools(message, userId));
            // 同步调用：工具调用由 Spring AI 自动处理（执行工具后再递归调用模型），返回最终文本
            return spec.user(message).call().content();
        } catch (Exception e) {
            log.error("调用千问大模型失败", e);
            throw new BusinessException("AI 服务调用失败，请检查 QWEN_API_KEY 配置或稍后重试");
        }
    }

    /**
     * 同步调用千问大模型（通过 Spring AI ChatClient，多模态：图片 + 文本）
     *
     * @param message        用户消息文本
     * @param imageRefs      图片列表（非空）
     * @param conversationId 会话 ID，用于按会话保存/加载多轮记忆
     */
    private String callQwenMultimodal(String message, List<ImageRef> imageRefs, Long conversationId) {
        log.info("开始请求LLM大模型（多模态，{} 张图片）......", imageRefs.size());
        long userId = StpUtil.getLoginIdAsLong();
        try {
            // 系统提示词：定义 Agent 角色（模板在 resources/prompts/system-prompt.st，用变量渲染）
            String systemPrompt = SYSTEM_PROMPT_TEMPLATE.render(Map.of("agentName", "Lion Agent"));
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .system(systemPrompt)
                    // 注入会话 ID / 用户 ID 到 Advisor 上下文（必须放在同一个 advisors 调用里，避免被覆盖）
                    .advisors(a -> a
                            .param(ChatMemory.CONVERSATION_ID, conversationId)
                            .param(TokenUsageAdvisor.USER_ID_KEY, userId))
                    // 工具按需注册：常驻（UserTools）+ 向量预筛（StarFortuneTools 等），见 ToolRegistryService
                    .tools(toolRegistryService.selectTools(message, userId));
            // 同步调用：工具调用由 Spring AI 自动处理，图片通过 UserSpec.media 注入用户消息
            return spec.user(u -> {
                        u.text(message);
                        for (ImageRef ref : imageRefs) {
                            // Spring AI 2.0 UserSpec.media 仅支持 (MimeType, Resource) 与 (MimeType, URL) 两种重载
                            if (ref.data() instanceof Resource resource) {
                                u.media(ref.mimeType(), resource);
                            } else if (ref.data() instanceof URL url) {
                                u.media(ref.mimeType(), url);
                            } else {
                                log.warn("忽略不支持的图片数据：{}", ref.data().getClass().getName());
                            }
                        }
                    })
                    .call().content();
        } catch (Exception e) {
            log.error("调用千问大模型失败", e);
            throw new BusinessException("AI 服务调用失败，请检查 QWEN_API_KEY 配置或稍后重试");
        }
    }

    // ==================== 多模态图片处理 ====================

    /** 图片引用：mimeType 与 data（Resource 或 URL 字符串）用于模型调用，savedPath 用于历史回显 */
    private record ImageRef(MimeType mimeType, Object data, String savedPath) {
    }

    /**
     * 解析图片：上传文件保存副本并构造 Resource；远程 URL 直接引用字符串。
     * 支持多张图片（images + imageUrls 可同时传）。
     */
    private List<ImageRef> resolveImages(List<MultipartFile> images, List<String> imageUrls) {
        List<ImageRef> refs = new ArrayList<>();
        if (images != null) {
            for (MultipartFile file : images) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                String contentType = file.getContentType();
                if (contentType == null || !contentType.startsWith("image/")) {
                    throw new BusinessException("仅支持图片文件" + (contentType == null ? "" : "（当前类型：" + contentType + "）"));
                }
                try {
                    String savedPath = saveImageToDisk(file);
                    ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                        @Override
                        public String getFilename() {
                            return file.getOriginalFilename();
                        }
                    };
                    refs.add(new ImageRef(imageMimeType(contentType), resource, savedPath));
                } catch (IOException e) {
                    throw new BusinessException("图片读取失败：" + e.getMessage());
                }
            }
        }
        if (imageUrls != null) {
            for (String url : imageUrls) {
                if (!StringUtils.hasText(url)) {
                    continue;
                }
                try {
                    // 支持 data URL 与 http(s) URL；统一转为 URL 对象传给模型
                    refs.add(new ImageRef(guessMimeType(url.trim()), new URL(url.trim()), url.trim()));
                } catch (MalformedURLException e) {
                    throw new BusinessException("图片 URL 格式不正确：" + url);
                }
            }
        }
        return refs;
    }

    /**
     * 保存上传图片到 {uploadPath}/multimodal/yyyy/MM/dd/ 目录，返回可访问的相对路径
     */
    private String saveImageToDisk(MultipartFile file) throws IOException {
        String base = uploadPath.endsWith("/") || uploadPath.endsWith("\\")
                ? uploadPath : uploadPath + "/";
        String datePath = LocalDate.now().toString().replace("-", "/");
        Path dir = Paths.get(base, "multimodal", datePath);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        String fileName = UUID.randomUUID().toString().replace("-", "") + resolveExtension(file);
        Path target = dir.resolve(fileName);
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        log.info("多模态图片已保存：{}", target);
        return base + "multimodal/" + datePath + "/" + fileName;
    }

    /**
     * 用户消息内容：文本 + 图片引用（markdown 格式），便于历史回显
     */
    private String buildUserContent(String message, List<ImageRef> refs) {
        if (refs.isEmpty()) {
            return message;
        }
        StringBuilder sb = new StringBuilder(message);
        for (ImageRef ref : refs) {
            if (StringUtils.hasText(ref.savedPath())) {
                sb.append("\n\n![image](").append(ref.savedPath()).append(")");
            }
        }
        return sb.toString();
    }

    /**
     * 从 Content-Type 解析 MimeType，解析失败回退 PNG
     */
    private MimeType imageMimeType(String contentType) {
        try {
            return MimeType.valueOf(contentType);
        } catch (Exception e) {
            return MimeTypeUtils.IMAGE_PNG;
        }
    }

    /**
     * 从图片 URL 推断 MimeType（默认 PNG）
     */
    private MimeType guessMimeType(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".jpeg") || lower.contains(".jpg")) {
            return MimeTypeUtils.IMAGE_JPEG;
        }
        if (lower.contains(".gif")) {
            return MimeTypeUtils.IMAGE_GIF;
        }
        if (lower.contains(".webp")) {
            return MimeTypeUtils.parseMimeType("image/webp");
        }
        if (lower.contains(".bmp")) {
            return MimeTypeUtils.parseMimeType("image/bmp");
        }
        return MimeTypeUtils.IMAGE_PNG;
    }

    /**
     * 从文件名 / Content-Type 推断扩展名
     */
    private String resolveExtension(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && filename.length() - dot <= 6) {
                String ext = filename.substring(dot).toLowerCase();
                if (ext.matches("\\.[a-z0-9]{1,5}")) {
                    return ext;
                }
            }
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/bmp" -> ".bmp";
            default -> ".png";
        };
    }



    /**
     * 创建新会话，标题取用户首条消息的前 20 个字符
     */
    private Long createConversation(Long userId, String firstMessage) {
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        String title = firstMessage == null ? "新对话" : firstMessage.trim();
        title = title.length() > 20 ? title.substring(0, 20) : title;
        conversation.setTitle(title);
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationMapper.insert(conversation);
        return conversation.getId();
    }

    /**
     * 校验会话归属
     */
    private void checkConversationOwner(Long conversationId, Long userId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException("会话不存在");
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该会话");
        }
    }

    /**
     * 当会话标题仍为默认"新对话"时，用最新用户消息更新为标题
     */
    private void updateTitleIfNeeded(Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !StringUtils.hasText(conversation.getTitle())
                || "新对话".equals(conversation.getTitle())) {
            ChatMessage firstUserMessage = chatMessageMapper.selectOne(
                    Wrappers.<ChatMessage>lambdaQuery()
                            .eq(ChatMessage::getConversationId, conversationId)
                            .eq(ChatMessage::getRole, "user")
                            .orderByAsc(ChatMessage::getId)
                            .last("LIMIT 1"));
            if (firstUserMessage != null) {
                String title = firstUserMessage.getContent();
                if (title != null && title.length() > 20) {
                    title = title.substring(0, 20);
                }
                conversation.setTitle(title);
                conversation.setUpdatedAt(LocalDateTime.now());
                conversationMapper.updateById(conversation);
            }
        } else {
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conversation);
        }
    }
}
