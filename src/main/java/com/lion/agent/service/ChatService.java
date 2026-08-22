package com.lion.agent.service;

import com.lion.agent.common.PageResult;
import com.lion.agent.dto.ChatRequest;
import com.lion.agent.entity.ChatMessage;
import com.lion.agent.entity.Conversation;
import com.lion.agent.vo.ChatResult;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 对话服务
 */
public interface ChatService {

    /**
     * 发送消息并获取 AI 回复
     */
    ChatResult send(ChatRequest request);

    /**
     * 流式发送消息（SSE），边生成边返回 AI 回复
     */
    SseEmitter stream(ChatRequest request);

    /**
     * 多模态对话（图片 + 文字）
     *
     * @param message        文本内容
     * @param conversationId 会话 ID（为空自动创建新会话）
     * @param images         上传的图片文件（可选，最多支持多张）
     * @param imageUrls      图片 URL 列表（可选，与 images 可同时使用）
     */
    ChatResult sendMultimodal(String message, Long conversationId, List<MultipartFile> images, List<String> imageUrls);

    /**
     * 获取当前用户的会话列表
     */
    PageResult<Conversation> listConversations(int pageNum, int pageSize, String keyword);

    /**
     * 获取会话详情（消息列表）
     */
    PageResult<ChatMessage> getMessages(Long conversationId, int pageNum, int pageSize);

    /**
     * 删除会话
     */
    void deleteConversation(Long conversationId);

    /**
     * 清空当前用户的所有会话
     */
    void clearAllConversations();

    /**
     * 重命名会话
     */
    void renameConversation(Long conversationId, String title);
}
