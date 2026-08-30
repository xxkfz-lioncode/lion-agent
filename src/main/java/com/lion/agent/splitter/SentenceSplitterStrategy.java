package com.lion.agent.splitter;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 按句子切分策略：以中英文标点断句。
 */
@Component
public class SentenceSplitterStrategy implements DocumentSplitterStrategy {

    @Override
    public SplitterType type() {
        return SplitterType.SENTENCE;
    }

    @Override
    public List<Document> split(List<Document> docs) {
        return new SentenceTextSplitter().split(docs);
    }

    /**
     * 按句子切分（中英文标点断句）
     */
    private static class SentenceTextSplitter extends TextSplitter {
        private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？!?.；;])\\s*");

        @Override
        protected List<String> splitText(String text) {
            return Arrays.stream(SENTENCE_SPLIT.split(text))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
    }
}
