package com.lion.agent.splitter;

import org.jetbrains.annotations.NotNull;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 段落切分策略：以连续空行（两个及以上换行）为分割点。
 */
@Component
public class ParagraphSplitterStrategy implements DocumentSplitterStrategy {

    @Override
    public SplitterType type() {
        return SplitterType.PARAGRAPH;
    }

    @Override
    public List<Document> split(List<Document> docs) {
        return new ParagraphTextSplitter().split(docs);
    }

    /**
     * 按段落分割：两个或更多换行符为分割点
     */
    private static class ParagraphTextSplitter extends TextSplitter {
        @NotNull
        @Override
        protected List<String> splitText(String text) {
            return Arrays.asList(text.split("\\s*\\R\\s*\\R\\s*"));
        }
    }
}
