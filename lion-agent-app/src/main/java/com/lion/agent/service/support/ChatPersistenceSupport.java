package com.lion.agent.service.support;

import com.lion.agent.common.exception.BusinessException;
import com.lion.agent.mapper.ChatMessageMapper;
import com.lion.agent.mapper.ConversationMapper;
import com.lion.agent.pojo.entity.ChatMessage;
import com.lion.agent.pojo.entity.Conversation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话持久化支撑组件
 *
 * <p>把「会话定位 / 消息落库 / 会话刷新」这些普通对话与知识库对话完全相同的逻辑收敛到一处，
 * 供 {@code ChatServiceImpl} 与 {@code KnowledgeChatServiceImpl} 复用，避免两条链路各写一遍。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatPersistenceSupport {

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;

    /** 一轮对话落库的两条消息（失败兜底场景下 assistant 可能为 null） */
    public record MessagePair(ChatMessage user, ChatMessage assistant) {
    }

    /**
     * 确定会话：为空则创建新会话，否则校验归属后复用
     *
     * @param chatType 会话类型（chat-普通对话 / kb-知识库问答），新建会话时落库，用于两类会话的列表隔离
     */
    public Long resolveConversation(Long userId, Long conversationId, String firstMessage, String chatType) {
        if (conversationId == null) {
            return createConversation(userId, firstMessage, chatType);
        }
        checkConversationOwner(conversationId, userId);
        return conversationId;
    }

    /**
     * 校验会话归属（不存在或无权限均抛业务异常）
     */
    public void checkConversationOwner(Long conversationId, Long userId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException("会话不存在");
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该会话");
        }
    }

    /**
     * 一次性保存「用户消息 + AI 回复」：单条 multi-values SQL，比两次 insert 少一次往返。
     *
     * @param assistantContent 为 null 时只保存用户消息（模型调用失败时的兜底落库）
     */
    public MessagePair saveMessages(Long conversationId, String userContent, String assistantContent) {
        LocalDateTime now = LocalDateTime.now();
        ChatMessage user = newMessage(conversationId, "user", userContent, now);
        ChatMessage assistant = assistantContent == null
                ? null : newMessage(conversationId, "assistant", assistantContent, now);
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

    /**
     * 刷新会话：更新时间必刷（会话列表按此倒序），标题仅在仍是默认值时用首条用户消息覆盖。
     * 单条 SQL 完成，失败仅告警不影响对话结果。
     */
    public void refreshConversation(Long conversationId) {
        try {
            conversationMapper.touchAndRefreshTitle(conversationId);
        } catch (Exception e) {
            log.warn("[Chat] 会话刷新失败 conversationId={} error={}", conversationId, e.getMessage());
        }
    }

    /**
     * 创建新会话，标题取用户首条消息的前 20 个字符
     */
    private Long createConversation(Long userId, String firstMessage, String chatType) {
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        String title = firstMessage == null ? "新对话" : firstMessage.trim();
        title = title.length() > 20 ? title.substring(0, 20) : title;
        conversation.setTitle(title);
        conversation.setChatType(chatType);
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationMapper.insert(conversation);
        return conversation.getId();
    }

    private ChatMessage newMessage(Long conversationId, String role, String content, LocalDateTime now) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(now);
        return message;
    }
}
