package com.lion.agent.service;

import com.lion.agent.common.PageResult;
import com.lion.agent.entity.TokenUsage;
import com.lion.agent.vo.TokenUsageVO;

import java.util.Map;

/**
 * Token 用量统计服务
 */
public interface TokenUsageService {

    /**
     * 记录一次调用用量（内部兜底 try-catch，落库失败仅告警，不影响调用主链路）
     */
    void record(TokenUsage usage);

    /**
     * 分页查询当前用户的用量记录
     *
     * @param chatType 会话类型过滤（chat / kb），为空不过滤
     */
    PageResult<TokenUsageVO> page(Long userId, int pageNum, int pageSize, String chatType);

    /**
     * 汇总统计（总调用次数 / 总 token / 今日调用次数 / 今日 token / 平均耗时）
     */
    Map<String, Object> statistics(Long userId);
}
