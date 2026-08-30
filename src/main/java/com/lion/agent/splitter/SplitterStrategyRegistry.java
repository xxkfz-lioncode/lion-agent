package com.lion.agent.splitter;

import com.lion.agent.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 切分策略注册表：Spring 启动时自动收集所有 {@link DocumentSplitterStrategy} Bean，
 * 按 type 建立索引。新增切分方式 = 新增一个 {@code @Component} 实现类，
 * 实现类声明 type() 后自动生效，无需改动注册表和调用方。
 */
@Slf4j
@Component
public class SplitterStrategyRegistry {

    private final Map<SplitterType, DocumentSplitterStrategy> strategies = new EnumMap<>(SplitterType.class);

    public SplitterStrategyRegistry(List<DocumentSplitterStrategy> strategyList) {
        for (DocumentSplitterStrategy strategy : strategyList) {
            SplitterType type = strategy.type();
            DocumentSplitterStrategy prev = strategies.put(type, strategy);
            if (prev != null) {
                log.warn("切分策略 type 重复注册，{} 被 {} 覆盖", type, strategy.getClass().getSimpleName());
            }
        }
        log.info("切分策略注册 {} 种：{}", strategies.size(), strategies.keySet());
    }

    /**
     * 按切分方式取策略，未注册抛 {@link BusinessException}
     */
    public DocumentSplitterStrategy get(String type) {
        return get(SplitterType.of(type));
    }

    /**
     * 按切分方式取策略，未注册抛 {@link BusinessException}
     */
    public DocumentSplitterStrategy get(SplitterType type) {
        DocumentSplitterStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new BusinessException("不支持的切分方式：" + type.getValue());
        }
        return strategy;
    }

    /**
     * 当前支持的全部切分方式
     */
    public Set<SplitterType> supportedTypes() {
        return strategies.keySet();
    }
}
