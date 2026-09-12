package com.lion.agent.splitter;

import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;

import java.util.List;

/**
 * 文档切分策略：将解析后的文档按指定规则切成块（chunk），供向量化入库。
 *
 * <p>策略模式：每种切分方式一个 {@code @Component} 实现，
 * 由 {@link SplitterStrategyRegistry} 按 type 自动收集注册。
 * 新增切分方式 = 新增一个实现类 + 声明 type，无需改动现有代码。</p>
 *
 * <p>两类策略：</p>
 * <ul>
 *   <li><b>文本切分型</b>（默认）：只依赖文本内容，调用方先解析成纯文本，再调 {@link #split(List)}；</li>
 *   <li><b>解析型</b>：分块依据在原始文件结构里（如 PDF 的页码），纯文本已丢失该信息，
 *       需覆写 {@link #parseFromSource()} 返回 true 并由调用方改调 {@link #parseAndSplit(Resource)}。</li>
 * </ul>
 */
public interface DocumentSplitterStrategy {

    /**
     * 块元数据键：块对应的原始页码。<br>
     * 仅解析型策略（如 PDF 按页切分）会写入，检索侧可据此标注答案的来源页。
     */
    String PAGE_METADATA_KEY = "page";

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

    /**
     * 是否为「解析型」策略：分块规则依赖原始文件结构，无法从已解析的纯文本还原。
     *
     * <p>返回 true 时，调用方必须改为调用 {@link #parseAndSplit(Resource)}，
     * 不要再先做通用解析（否则页边界等信息已丢失）。</p>
     */
    default boolean parseFromSource() {
        return false;
    }

    /**
     * 直接从原始文件解析并切分，仅 {@link #parseFromSource()} 返回 true 的策略需要实现。
     *
     * @param resource 落盘后的原始文件
     * @return 切分后的文档块
     */
    default List<Document> parseAndSplit(Resource resource) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " 未实现 parseAndSplit");
    }
}
