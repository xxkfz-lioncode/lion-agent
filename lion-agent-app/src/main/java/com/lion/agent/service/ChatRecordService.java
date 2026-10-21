package com.lion.agent.service;

import com.lion.agent.common.result.PageResult;
import com.lion.agent.pojo.entity.ChatMessage;
import com.lion.agent.pojo.vo.ChatRecordStatsVo;
import com.lion.agent.pojo.vo.ConversationRecordVo;

/**
 * 会话记录查询（只读）
 *
 * <p>面向「会话记录」页面：按当前登录用户列出会话（含消息数统计），
 * 并分页查看某个会话内用户提问与 AI 回复的原文内容。</p>
 */
public interface ChatRecordService {

    /**
     * 会话列表：按标题或消息内容模糊搜索，附带消息数统计
     *
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @param keyword  关键字（会话标题 / 消息内容，均可为空）
     */
    PageResult<ConversationRecordVo> listConversations(int pageNum, int pageSize, String keyword);

    /**
     * 会话内的消息明细
     *
     * @param conversationId 会话 ID
     * @param role           角色过滤：user / assistant，为空表示全部
     * @param keyword        消息内容关键字，为空表示不过滤
     * @param asc            true（默认）= 按时间正序（从最早开始，按对话顺序阅读）；false = 倒序（从最新开始）
     */
    PageResult<ChatMessage> listMessages(Long conversationId, int pageNum, int pageSize,
                                         String role, String keyword, boolean asc);

    /**
     * 顶部统计：会话数 / 消息数 / 提问数 / 回复数
     */
    ChatRecordStatsVo stats();
}
