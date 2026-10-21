package com.lion.agent.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.common.result.PageResult;
import com.lion.agent.common.utils.SseEmitterUtils;
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
import com.lion.agent.service.ModelConfigService;
import com.lion.agent.service.support.ChatLlmSupport;
import com.lion.agent.service.support.ChatPersistenceSupport;
import com.lion.agent.pojo.vo.ChatResult;
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
 * 普通对话服务实现（集成 Spring AI，对接千问大模型）
 *
 * <p>职责单一：只处理「一般对话」链路（闲聊/通用咨询/工具调用），不做知识库检索。
 * 知识库问答已拆分为独立接口，见 {@code KnowledgeChatServiceImpl}（/api/knowledge/chat）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final TokenUsageMapper tokenUsageMapper;
    /** 多模态对话专用 ChatClient（独立 Bean，仅挂日志 Advisor，链路简单） */
    private final ChatClient multimodalChatClient;
    private final ChatMemory chatMemory;
    /** 模型配置管理（对话时按类型取默认模型热切换，见模型管理页面） */
    private final ModelConfigService modelConfigService;
    /** 会话/消息持久化（与知识库对话链路共用） */
    private final ChatPersistenceSupport persistence;
    /** 大模型调用（与知识库对话链路共用） */
    private final ChatLlmSupport llmSupport;
    /** 上传文件根目录（相对工作目录），多模态图片保存于 {uploadPath}/multimodal/yyyy/MM/dd/ 下 */
    @Value("${lion.upload.path:upload/}")
    private String uploadPath;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatResult send(ChatRequest request) {
        long userId = StpUtil.getLoginIdAsLong();

        // 1. 确定会话（为空则自动创建新会话）
        Long conversationId = persistence.resolveConversation(userId, request.getConversationId(),
                request.getMessage(), ChatType.CHAT.getValue());

        // 2. 生成回复（普通对话链路：记忆/工具/缓存等全局 Advisor 由 ChatLlmSupport 统一注入）
        String reply = llmSupport.call(request.getMessage(), conversationId, ChatType.CHAT.getValue(),
                request.getMessage());

        // 3. 用户消息 + AI 回复一次性落库（单条 multi-values SQL）
        ChatPersistenceSupport.MessagePair messages =
                persistence.saveMessages(conversationId, request.getMessage(), reply);

        // 4. 刷新会话（更新时间 + 首轮标题，单条 SQL）
        persistence.refreshConversation(conversationId);

        // 5. 长期记忆抽取已收敛到 LongTermMemoryAdvisor 的响应阶段（敏感词/缓存短路时不抽），此处不再调用

        return ChatResult.builder()
                .conversationId(conversationId)
                .userMessageId(messages.user().getId())
                .assistantMessageId(messages.assistant().getId())
                .reply(reply)
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatResult sendMultimodal(String message, Long conversationId,
                                     List<MultipartFile> images, List<String> imageUrls) {
        long userId = StpUtil.getLoginIdAsLong();

        // 1. 确定会话（为空则自动创建新会话）
        conversationId = persistence.resolveConversation(userId, conversationId, message, ChatType.CHAT.getValue());

        // 2. 解析图片：上传文件保存副本（供历史回显）；远程 URL 直接引用
        List<ImageRef> imageRefs = resolveImages(images, imageUrls);

        // 3. 调用多模态大模型（图片 + 文本）
        String reply = callQwenMultimodal(message, imageRefs, conversationId, ChatType.CHAT.getValue());

        // 4. 用户消息（文本 + 图片引用）+ AI 回复一次性落库
        ChatPersistenceSupport.MessagePair messages =
                persistence.saveMessages(conversationId, buildUserContent(message, imageRefs), reply);

        // 5. 刷新会话（更新时间 + 首轮标题）
        persistence.refreshConversation(conversationId);

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
        Long conversationId = persistence.resolveConversation(userId, request.getConversationId(),
                request.getMessage(), ChatType.CHAT.getValue());

        // 2. 创建 SSE 发射器（0L 表示不自动超时）
        SseEmitter emitter = new SseEmitter(0L);

        // 3. 先推送会话信息，便于前端拿到新会话 ID（推送失败说明连接已不可用，直接返回）
        if (!SseEmitterUtils.sendStart(emitter, conversationId)) {
            return emitter;
        }

        // 4. 生成回复（普通对话链路）
        String reply;
        try {
            reply = llmSupport.call(request.getMessage(), conversationId, ChatType.CHAT.getValue(), request.getMessage());
        } catch (Exception e) {
            log.error("[Chat] 生成回复失败 conversationId={}", conversationId, e);
            // 失败也要把用户消息落库，避免前端刷新后这条提问凭空消失
            persistence.saveMessages(conversationId, request.getMessage(), null);
            persistence.refreshConversation(conversationId);
            SseEmitterUtils.error(emitter, "AI 服务调用失败，请稍后重试");
            return emitter;
        }

        // 5. 用户消息 + AI 回复一次性落库（单条 multi-values SQL）
        persistence.saveMessages(conversationId, request.getMessage(), reply);
        persistence.refreshConversation(conversationId);

        // 6. 推送完整回复 + done，并关闭连接，前端恢复输入
        SseEmitterUtils.sendMessage(emitter, reply);
        SseEmitterUtils.sendDone(emitter, reply, null);
        SseEmitterUtils.complete(emitter);

        return emitter;
    }

    @Override
    public PageResult<Conversation> listConversations(int pageNum, int pageSize, String keyword, String chatType) {
        long userId = StpUtil.getLoginIdAsLong();
        var wrapper = Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getUserId, userId);
        // 会话类型过滤：普通对话页与知识库对话页各自只看到自己的会话；不传则返回全部
        if (StringUtils.hasText(chatType)) {
            wrapper.eq(Conversation::getChatType, chatType);
        }
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
        persistence.checkConversationOwner(conversationId, userId);
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
        persistence.checkConversationOwner(conversationId, userId);
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
    public void clearAllConversations(String chatType) {
        long userId = StpUtil.getLoginIdAsLong();
        var query = Wrappers.<Conversation>lambdaQuery().eq(Conversation::getUserId, userId);
        // 只清空指定类型的会话（普通对话页 / 知识库对话页各自清各自的）；不传则清空全部
        if (StringUtils.hasText(chatType)) {
            query.eq(Conversation::getChatType, chatType);
        }
        List<Long> ids = conversationMapper.selectList(query)
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
        // 逻辑删除当前用户的会话（按类型）
        var deleteQuery = Wrappers.<Conversation>lambdaQuery().eq(Conversation::getUserId, userId);
        if (StringUtils.hasText(chatType)) {
            deleteQuery.eq(Conversation::getChatType, chatType);
        }
        conversationMapper.delete(deleteQuery);
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
        persistence.checkConversationOwner(conversationId, userId);
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



}
