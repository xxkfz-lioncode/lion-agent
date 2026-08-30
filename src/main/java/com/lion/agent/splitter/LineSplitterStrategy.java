package com.lion.agent.splitter;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 按行切分策略：每个非空行为一个块。
 */
@Component
public class LineSplitterStrategy implements DocumentSplitterStrategy {

    @Override
    public SplitterType type() {
        return SplitterType.LINE;
    }

    @Override
    public List<Document> split(List<Document> docs) {
        return new LineTextSplitter().split(docs);
    }

    /**
     * 按行切分
     */
    private static class LineTextSplitter extends TextSplitter {
        @Override
        protected List<String> splitText(String text) {
            return Arrays.stream(text.split("\\R"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
    }
}
