package com.lion.agent.dify.service;

import com.lion.agent.dify.dto.DifyChatSendDTO;
import com.lion.agent.dify.dto.DifyFeedbackDTO;
import com.lion.agent.dify.dto.DifyRenameDTO;
import com.lion.agent.dify.vo.DifyChatReplyVO;
import io.github.guoshiqiufeng.dify.chat.dto.response.AppParametersResponseVO;
import io.github.guoshiqiufeng.dify.chat.dto.response.MessageConversationsResponse;
import io.github.guoshiqiufeng.dify.core.pojo.DifyPageResult;
import io.github.guoshiqiufeng.dify.core.pojo.response.MessagesResponseVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Dify 对话服务（对接 dify-spring-boot4-starter 的 DifyChat）
 *
 * <p>所有方法都带 {@code appCode}：指定要调用的 Dify 应用（会话列表、历史、反馈等
 * 都与具体应用绑定，不同应用之间的会话互不通用）。
 */
public interface DifyChatService {

    /**
     * 阻塞式发送消息，等待 Dify 生成完整回复后一次性返回
     */
    DifyChatReplyVO send(DifyChatSendDTO dto);

    /**
     * 流式发送消息（SSE），事件约定与本项目 /api/chat/stream 一致：
     * message（增量片段）→ done（完整回复 + conversationId + messageId）/ error
     */
    SseEmitter stream(DifyChatSendDTO dto);

    /**
     * 会话列表（游标分页）
     */
    DifyPageResult<MessageConversationsResponse> conversations(String appCode, String lastId, Integer limit);

    /**
     * 会话聊天历史（游标分页）
     */
    DifyPageResult<MessagesResponseVO> messages(String appCode, String conversationId, String firstId, Integer limit);

    /**
     * 获取下一轮建议问题（应用未开启该功能时返回空列表）
     */
    List<String> suggested(String appCode, String messageId);

    /**
     * 消息点赞/点踩/撤销
     */
    void feedback(DifyFeedbackDTO dto);

    /**
     * 会话重命名
     */
    void rename(DifyRenameDTO dto);

    /**
     * 删除会话
     */
    void deleteConversation(String appCode, String conversationId);

    /**
     * 应用编排信息（开场白、变量配置、上传配置等）
     */
    AppParametersResponseVO parameters(String appCode);
}
