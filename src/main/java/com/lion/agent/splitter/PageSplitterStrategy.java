package com.lion.agent.splitter;

import com.lion.agent.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;

import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按页切分策略（仅 PDF）：以原始 PDF 的「页」为分块边界，默认一页一块。
 *
 * <p>这是唯一一类「解析即切分」的策略：页边界只存在于 PDF 原文中，
 * Tika 解析成纯文本后就丢失了，因此通过 {@link #parseFromSource()} 声明为解析型，
 * 由调用方直接把落盘文件交给 {@link #parseAndSplit(Resource)}。</p>
 *
 * <p>为什么用 {@link PagePdfDocumentReader} 而不是 Tika + 文本切分：
 * 它基于 PDFBox 按页提取，天然保留页边界，并可用页边距裁掉页眉/页脚噪声，
 * 对「一页一主题」的规范文档（说明书、论文、合同）召回质量明显更好。</p>
 */
@Slf4j
@Component
public class PageSplitterStrategy implements DocumentSplitterStrategy {

    /** 每个块包含的页数：1 = 一页一块 */
    private final int pagesPerDocument;

    /** 页面上边距（磅）：调大用于裁掉页眉 */
    private final int pageTopMargin;

    /** 页面下边距（磅）：调大用于裁掉页脚 */
    private final int pageBottomMargin;

    public PageSplitterStrategy(
            @Value("${lion.splitter.page.pages-per-document:1}") int pagesPerDocument,
            @Value("${lion.splitter.page.top-margin:0}") int pageTopMargin,
            @Value("${lion.splitter.page.bottom-margin:0}") int pageBottomMargin) {
        this.pagesPerDocument = pagesPerDocument > 0 ? pagesPerDocument : 1;
        this.pageTopMargin = Math.max(pageTopMargin, 0);
        this.pageBottomMargin = Math.max(pageBottomMargin, 0);
    }

    @Override
    public SplitterType type() {
        return SplitterType.PAGE;
    }

    @Override
    public boolean parseFromSource() {
        return true;
    }

    @Override
    public List<Document> parseAndSplit(Resource resource) {
        if (resource == null || !resource.exists()) {
            throw new BusinessException("文件不存在，无法按页切分");
        }
        String fileName = resource.getFilename();
        if (!isPdf(fileName)) {
            throw new BusinessException("按页切分仅支持 PDF 文件，当前文件："
                    + (fileName == null ? "未知" : fileName));
        }

        PagePdfDocumentReader reader = new PagePdfDocumentReader(resource, PdfDocumentReaderConfig.builder()
                // 每块页数（1 = 一页一块）
                .withPagesPerDocument(pagesPerDocument)
                // 页边距裁剪：去掉页眉/页脚，避免每页重复的噪声进入向量
                .withPageTopMargin(pageTopMargin)
                .withPageBottomMargin(pageBottomMargin)
                .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                        .withLeftAlignment(true)
                        .build())
                .build());

        List<Document> pageDocs = reader.get();
        if (pageDocs.isEmpty()) {
            throw new BusinessException("PDF 未解析出文本内容（可能是扫描件/图片型 PDF，需先做 OCR）");
        }

        // 把 reader 的起止页字段归一化成统一的 page 元数据，检索侧无需感知 PDF 专用字段名
        List<Document> result = new ArrayList<>(pageDocs.size());
        for (Document pageDoc : pageDocs) {
            Map<String, Object> metadata = new HashMap<>(pageDoc.getMetadata());
            Object startPage = metadata.get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER);
            if (startPage != null) {
                metadata.put(PAGE_METADATA_KEY, startPage);
            }
            result.add(Document.builder()
                    .text(pageDoc.getText())
                    .metadata(metadata)
                    .build());
        }
        log.info("按页切分完成：file={}，块数={}，pagesPerDocument={}", fileName, result.size(), pagesPerDocument);
        return result;
    }

    /**
     * 兜底实现：按页切分的依据是 PDF 原文的页边界，纯文本无法还原。
     * 正常情况下调用方会走 {@link #parseAndSplit(Resource)}，走到这里说明调用方漏判了
     * {@link #parseFromSource()}，故仅告警并原样返回，避免静默产出错误的块。
     */
    @Override
    public List<Document> split(List<Document> docs) {
        log.warn("按页切分策略收到的是已解析纯文本，页边界已丢失，将原样返回；请检查调用方是否遗漏 parseFromSource 判断");
        return docs == null ? List.of() : docs;
    }

    private boolean isPdf(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }
}
