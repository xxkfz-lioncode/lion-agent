package com.lion.agent.splitter;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 文档切分策略：将解析后的文档按指定规则切成块（chunk），供向量化入库。
 *
 * <p>策略模式：每种切分方式一个 {@code @Component} 实现，
 * 由 {@link SplitterStrategyRegistry} 按 type 自动收集注册。
 * 新增切分方式 = 新增一个实现类 + 声明 type，无需改动现有代码。
 */
public interface DocumentSplitterStrategy {

    /**
     * 策略标识：对应前端上传时选择的切分方式，注册表按此值索引
     */
    SplitterType type();

    /**
     * 执行分片
     *
     * @param docs 解析后的原始文档
     * @return 切分后的文档块
     */
    List<Document> split(List<Document> docs);
}
