package com.lion.agent.service;

import com.lion.agent.common.result.PageResult;
import com.lion.agent.pojo.dto.SensitiveWordBatchRequest;
import com.lion.agent.pojo.dto.SensitiveWordRequest;
import com.lion.agent.pojo.entity.SensitiveWordEntity;
import com.lion.agent.pojo.vo.SensitiveWordCheckVo;

import java.util.List;

/**
 * 敏感词管理
 *
 * <p>词库由页面维护（存 {@code ai_sensitive_word} 表），
 * {@link com.lion.agent.advisor.SensitiveWordAdvisor} 在每次对话调用前读取启用词表做拦截，
 * 因此词表变更无需重启服务：写操作会立即失效缓存。</p>
 */
public interface SensitiveWordService {

    /**
     * 分页列表：按词模糊搜索 + 启用状态过滤
     */
    PageResult<SensitiveWordEntity> list(int pageNum, int pageSize, String keyword, Boolean enabled);

    /**
     * 新增敏感词
     */
    SensitiveWordEntity create(SensitiveWordRequest request);

    /**
     * 修改敏感词
     */
    SensitiveWordEntity update(Long id, SensitiveWordRequest request);

    /**
     * 删除敏感词
     */
    void delete(Long id);

    /**
     * 启停敏感词
     */
    SensitiveWordEntity toggle(Long id, boolean enabled);

    /**
     * 批量导入：跳过空值、去重、跳过库中已存在的词
     *
     * @return 实际新增条数
     */
    int batchImport(SensitiveWordBatchRequest request);

    /**
     * 命中检测（页面「试一试」用）：返回命中的词与拒绝话术，不落库、不调用模型
     */
    SensitiveWordCheckVo check(String text);

    /**
     * 文本匹配：返回命中的敏感词列表，未命中返回空列表
     */
    List<String> match(String text);

    /**
     * 当前启用词表（带本地缓存，供 Advisor 高频读取）
     */
    List<String> activeWords();

    /**
     * 手动刷新词表缓存
     */
    void refreshCache();
}
