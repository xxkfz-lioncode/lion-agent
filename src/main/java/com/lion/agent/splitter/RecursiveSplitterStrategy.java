package com.lion.agent.splitter;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 递归切分策略：按 段落 → 句子 → 短句（逗号/分号）→ 空白 的优先级切分，
 * 每块控制在 {@code maxChunkChars} 字符以内。
 */
@Component
public class RecursiveSplitterStrategy implements DocumentSplitterStrategy {

    /** 单块最大字符数 */
    private final int maxChunkChars;

    public RecursiveSplitterStrategy(
            @Value("${lion.splitter.recursive.max-chunk-chars:3000}") int maxChunkChars) {
        this.maxChunkChars = maxChunkChars;
    }

    @Override
    public SplitterType type() {
        return SplitterType.RECURSIVE;
    }

    @Override
    public List<Document> split(List<Document> docs) {
        return new RecursiveTextSplitter(maxChunkChars).split(docs);
    }

    /**
     * 递归切分：按 段落 → 句子 → 短句（逗号/分号）→ 空白 的优先级切分，
     * 每块控制在 {@code maxChunkChars} 字符以内
     */
    private static class RecursiveTextSplitter extends TextSplitter {
        private static final List<Pattern> SEPARATORS = List.of(
                Pattern.compile("\\R{2,}"),               // 空行（段落）
                Pattern.compile("(?<=[。！？!?.；;])\\s*"), // 句子
                Pattern.compile("(?<=[，、；;,])\\s*"),     // 短句
                Pattern.compile("(?<=\\s)")                // 空白
        );

        private final int maxChunkChars;

        RecursiveTextSplitter(int maxChunkChars) {
            this.maxChunkChars = maxChunkChars;
        }

        @Override
        protected List<String> splitText(String text) {
            List<String> chunks = new ArrayList<>();
            recursiveSplit(text, 0, chunks);
            return chunks;
        }

        private void recursiveSplit(String text, int level, List<String> chunks) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                return;
            }
            if (trimmed.length() <= maxChunkChars || level >= SEPARATORS.size()) {
                chunks.add(trimmed);
                return;
            }
            List<String> parts = Arrays.stream(SEPARATORS.get(level).split(trimmed))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            if (parts.size() <= 1) {
                // 该层级没有可切分的点，进入下一优先级
                recursiveSplit(trimmed, level + 1, chunks);
                return;
            }
            for (String part : parts) {
                recursiveSplit(part, level, chunks);
            }
        }
    }
}
