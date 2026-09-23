package com.lion.agent.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.common.result.PageResult;
import com.lion.agent.common.constants.AdvisorConstants;
import com.lion.agent.common.utils.SseEmitterUtils;
import com.lion.agent.common.enums.ChatIntent;
import com.lion.agent.common.enums.ChatType;
import com.lion.agent.pojo.dto.ChatRequest;
import com.lion.agent.pojo.entity.ChatMessage;
import com.lion.agent.pojo.entity.Conversation;
import com.lion.agent.pojo.entity.TokenUsage;
import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.mapper.ChatMessageMapper;
import com.lion.agent.mapper.ConversationMapper;
import com.lion.agent.mapper.TokenUsageMapper;
import com.lion.agent.service.ChatService;
import com.lion.agent.config.PromptConfig;
import com.lion.agent.service.IntentRecognitionService;
import com.lion.agent.service.KnowledgeRetrievalService;
import com.lion.agent.service.ModelConfigService;
import com.lion.agent.service.ToolRegistryService;
import com.lion.agent.pojo.vo.ChatResult;
import com.lion.agent.pojo.vo.ChunkSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
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
import java.util.UUID;

/**
 * 对话服务实现（集成 Spring AI，对接千问大模型）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final TokenUsageMapper tokenUsageMapper;
    private final ChatClient chatClient;
    /** 多模态对话专用 ChatClient（独立 Bean，仅挂日志 Advisor，链路简单） */
    private final ChatClient multimodalChatClient;
    private final ChatMemory chatMemory;
    private final ToolRegistryService toolRegistryService;
    /** 系统提示词统一配置管理（模板与角色名集中维护，见 PromptConfig） */
    private final PromptConfig promptConfig;
    // 长期记忆抽取已收敛到 LongTermMemoryAdvisor 的响应阶段，本类不再直接调用 MemoryService
    /** 意图识别服务（统一入口路由前置：一般对话 / 知识库问答） */
    private final IntentRecognitionService intentRecognitionService;
    /** 知识库检索服务（高级 RAG 流水线：改写/多路召回/RRF/Rerank/门控） */
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    /** 模型配置管理（对话时按类型取默认模型热切换，见模型管理页面） */
    private final ModelConfigService modelConfigService;
    /** 上传文件根目录（相对工作目录），多模态图片保存于 {uploadPath}/multimodal/yyyy/MM/dd/ 下 */
    @Value("${lion.upload.path:upload/}")
    private String uploadPath;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatResult send(ChatRequest request) {
        long userId = StpUtil.getLoginIdAsLong();

        // 1. 确定会话（为空则自动创建新会话）
        Long conversationId = resolveConversation(userId, request.getConversationId(), request.getMessage());

        // 2. 生成回复（意图路由 → 知识库链路 / 一般对话链路）
        ChatReply answer = generateReply(userId, request, conversationId);

        // 3. 用户消息 + AI 回复一次性落库（单条 multi-values SQL）
        MessagePair messages = saveMessages(conversationId, request.getMessage(), answer.content());

        // 4. 刷新会话（更新时间 + 首轮标题，单条 SQL）
        refreshConversation(conversationId);

        // 5. 长期记忆抽取已收敛到 LongTermMemoryAdvisor 的响应阶段（敏感词/缓存短路时不抽），此处不再调用

        return ChatResult.builder()
                .conversationId(conversationId)
                .userMessageId(messages.user().getId())
                .assistantMessageId(messages.assistant().getId())
                .reply(answer.content())
                .referencedChunks(answer.chunks())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatResult sendMultimodal(String message, Long conversationId,
                                     List<MultipartFile> images, List<String> imageUrls) {
        long userId = StpUtil.getLoginIdAsLong();

        // 1. 确定会话（为空则自动创建新会话）
        conversationId = resolveConversation(userId, conversationId, message);

        // 2. 解析图片：上传文件保存副本（供历史回显）；远程 URL 直接引用
        List<ImageRef> imageRefs = resolveImages(images, imageUrls);

        // 3. 调用多模态大模型（图片 + 文本）
        String reply = callQwenMultimodal(message, imageRefs, conversationId, ChatType.CHAT.getValue());

        // 4. 用户消息（文本 + 图片引用）+ AI 回复一次性落库
        MessagePair messages = saveMessages(conversationId, buildUserContent(message, imageRefs), reply);

        // 5. 刷新会话（更新时间 + 首轮标题）
        refreshConversation(conversationId);

        return ChatResult.builder()
                .conversationId(conversationId)
                .userMessageId(messages.user().getId())
                .assistantMessageId(messages.assistant().getId())
                .reply(reply)
                .build();
    }

    /**
     * 对话流式接口（SSE）
     *
     * <p>注意：当前仍是「伪流式」——底层 {@code callQwen} 为同步调用，拿到完整回复后一次性推送。
     * 改造为真流式（{@code .stream()} 逐 token 推送）需要同时调整 Token 统计、语义缓存回写等
     * Advisor 的流式分支，属于独立改造项；此处先把推送与落库流程收敛清晰。
     *
     * <p>流程：建会话 → 推 start（前端拿会话 ID）→ 生成回复 → 消息批量落库 → 推 message/done → 关闭。
     * 任一步失败都通过 {@code error} 事件告知前端并以 completeWithError 收尾，不留悬挂连接。
     */
    @Override
    public SseEmitter stream(ChatRequest request) {
        long userId = StpUtil.getLoginIdAsLong();

        // 1. 确定会话（为空则自动创建新会话）
        Long conversationId = resolveConversation(userId, request.getConversationId(), request.getMessage());

        // 2. 创建 SSE 发射器（0L 表示不自动超时）
        SseEmitter emitter = new SseEmitter(0L);

        // 3. 先推送会话信息，便于前端拿到新会话 ID（推送失败说明连接已不可用，直接返回）
        if (!SseEmitterUtils.sendStart(emitter, conversationId)) {
            return emitter;
        }

        // 4. 生成回复（意图路由 → 知识库链路 / 一般对话链路）
        ChatReply answer;
        try {
            answer = generateReply(userId, request, conversationId);
        } catch (Exception e) {
            log.error("[Chat] 生成回复失败 conversationId={}", conversationId, e);
            // 失败也要把用户消息落库，避免前端刷新后这条提问凭空消失
            saveMessages(conversationId, request.getMessage(), null);
            refreshConversation(conversationId);
            SseEmitterUtils.error(emitter, "AI 服务调用失败，请稍后重试");
            return emitter;
        }

        // 5. 用户消息 + AI 回复一次性落库（单条 multi-values SQL）
        saveMessages(conversationId, request.getMessage(), answer.content());
        refreshConversation(conversationId);

        // 6. 推送完整回复 + done，并关闭连接，前端恢复输入
        SseEmitterUtils.sendMessage(emitter, answer.content());
        SseEmitterUtils.sendDone(emitter, answer.content(), answer.chunks());
        SseEmitterUtils.complete(emitter);

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
        // 删除统计
        tokenUsageMapper.delete( Wrappers.<TokenUsage>lambdaQuery()
                .eq(TokenUsage::getConversationId, conversationId));
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

        // 删除统计
        tokenUsageMapper.delete(
                Wrappers.<TokenUsage>lambdaQuery()
                        .in(TokenUsage::getConversationId, ids));
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

    /** 一轮对话的模型输出：回复文本 + 知识库引用来源（一般对话时为 null） */
    private record ChatReply(String content, List<ChunkSource> chunks) {
    }

    /** 一轮对话落库的两条消息（failure 场景下 assistant 可能为 null） */
    private record MessagePair(ChatMessage user, ChatMessage assistant) {
    }

    /**
     * 确定会话：为空则创建新会话，否则校验归属后复用
     */
    private Long resolveConversation(Long userId, Long conversationId, String firstMessage) {
        if (conversationId == null) {
            return createConversation(userId, firstMessage);
        }
        checkConversationOwner(conversationId, userId);
        return conversationId;
    }

    /**
     * 生成回复：意图路由 →（知识库链路：高级 RAG 检索 + 门控降级）/ 一般对话链路。
     * send 与 stream 共用，避免路由逻辑在两处各写一遍、改一处漏一处。
     */
    private ChatReply generateReply(Long userId, ChatRequest request, Long conversationId) {
        ChatIntent intent = intentRecognitionService.classify(userId, request.getMessage(), request.getKnowledgeId());
        if (intent == ChatIntent.GENERAL) {
            // 一般对话：原链路（带记忆/工具/缓存等全局 Advisor）
            return new ChatReply(callQwen(request.getMessage(), conversationId, ChatType.CHAT.getValue(),
                    request.getMessage()), null);
        }
        // 知识库链路：高级 RAG 检索（改写/多路召回/RRF/Rerank/门控）
        KnowledgeRetrievalService.RetrievalResult result =
                knowledgeRetrievalService.retrieve(userId, request.getMessage(), request.getKnowledgeId());
        if (!result.qualified()) {
            // 门控拦截：资料不足以从知识库回答，降级为一般对话由主模型兜底
            log.info("[Chat] 知识库检索未命中，降级为一般对话：{}", result.reason());
            return new ChatReply(callQwen(request.getMessage(), conversationId, ChatType.CHAT.getValue(),
                    request.getMessage()), List.of());
        }
        // 检索通过：kb-answer 模板渲染（上下文 + 问题），chatType=kb
        String prompt = promptConfig.renderKbAnswer(result.context(), request.getMessage());
        return new ChatReply(callQwen(prompt, conversationId, ChatType.KB.getValue(), request.getMessage()),
                result.chunks());
    }

    /**
     * 一次性保存「用户消息 + AI 回复」：单条 multi-values SQL，比两次 insert 少一次往返。
     *
     * @param assistantContent 为 null 时只保存用户消息（模型调用失败时的兜底落库）
     */
    private MessagePair saveMessages(Long conversationId, String userContent, String assistantContent) {
        LocalDateTime now = LocalDateTime.now();
        ChatMessage user = newMessage(conversationId, "user", userContent, now);
        ChatMessage assistant = assistantContent == null ? null
                : newMessage(conversationId, "assistant", assistantContent, now);
        List<ChatMessage> batch = assistant == null ? List.of(user) : List.of(user, assistant);
        try {
            chatMessageMapper.insertBatch(batch);
            if (user.getId() == null || (assistant != null && assistant.getId() == null)) {
                // 主键未回填只影响本次返回的消息 ID，消息本身已落库，不阻断主流程
                log.warn("[Chat] 批量插入主键回填缺失 conversationId={}", conversationId);
            }
        } catch (Exception e) {
            // 批量失败才降级逐条：此时批量未成功，不会造成重复插入
            log.warn("[Chat] 消息批量插入失败，降级为逐条插入 error={}", e.getMessage());
            batch.forEach(chatMessageMapper::insert);
        }
        return new MessagePair(user, assistant);
    }

    private ChatMessage newMessage(Long conversationId, String role, String content, LocalDateTime now) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(now);
        return message;
    }

    /**
     * 刷新会话：更新时间必刷（会话列表按此倒序），标题仅在仍是默认值时用首条用户消息覆盖。
     * 单条 SQL 完成，失败仅告警不影响对话结果。
     */
    private void refreshConversation(Long conversationId) {
        try {
            conversationMapper.touchAndRefreshTitle(conversationId);
        } catch (Exception e) {
            log.warn("[Chat] 会话刷新失败 conversationId={} error={}", conversationId, e.getMessage());
        }
    }

    /**
     * 同步调用千问大模型（通过 Spring AI ChatClient，纯文本）
     *
     * @param message        送入模型的用户消息（知识库链路为渲染后的 KB prompt）
     * @param conversationId 会话 ID，用于按会话保存/加载多轮记忆
     * @param chatType       会话类型（chat-一般对话 / kb-知识库问答），供 TokenUsageAdvisor 落库区分
     * @param rawUserMessage 未加工的用户原话，供 LongTermMemoryAdvisor 抽取长期记忆
     */
    private String callQwen(String message, Long conversationId, String chatType, String rawUserMessage) {
        log.info("开始请求LLM大模型（文本）......");
        long userId = StpUtil.getLoginIdAsLong();
        try {
            // 系统提示词：定义 Agent 角色（模板集中维护在 PromptConfig，用变量渲染）
            String systemPrompt = promptConfig.renderSystemPrompt();
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .system(systemPrompt)
                    // 注入会话 ID / 用户 ID / 原始提问到 Advisor 上下文（必须放在同一个 advisors 调用里，避免被覆盖）
                    // rawUserMessage：知识库链路的 message 是渲染后的 KB prompt，抽取长期记忆必须用未加工的用户原话
                    .advisors(a -> a
                            .param(ChatMemory.CONVERSATION_ID, conversationId)
                            .param(AdvisorConstants.USER_ID_KEY, userId)
                            .param(AdvisorConstants.CHAT_TYPE_KEY, chatType)
                            .param(AdvisorConstants.RAW_USER_MESSAGE_KEY, rawUserMessage))
                    // 工具按需注册：常驻（UserTools）+ 向量预筛（StarFortuneTools 等），见 ToolRegistryService
                    .tools(toolRegistryService.selectTools(message, userId));
            // 模型热切换：模型管理页面设置的默认 chat 模型按次覆盖；表中无默认记录时不覆盖（沿用 yml 配置）
            String chatModelName = modelConfigService.getDefaultModelName("chat");
            if (chatModelName != null) {
                // Spring AI 2.0：需传厂商专属 Options（通用 ChatOptions 会在 OpenAiChatModel 内部强转失败）
                spec.options(OpenAiChatOptions.builder().model(chatModelName));
            }
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
    private String callQwenMultimodal(String message, List<ImageRef> imageRefs, Long conversationId,String chatType) {
        log.info("开始请求LLM大模型（多模态，{} 张图片）......", imageRefs.size());
        try {
            var spec = multimodalChatClient.prompt()
                    .user(u -> {
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
                    });
            // 模型热切换：模型管理页面设置的默认 multimodal 模型按次覆盖；无默认记录时沿用 yml 配置
            String multimodalModelName = modelConfigService.getDefaultModelName("multimodal");
            if (multimodalModelName != null) {
                spec.options(OpenAiChatOptions.builder().model(multimodalModelName));
            }
            return spec.call().content();
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

}
