package com.lion.agent.splitter;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token 切分策略：按 token 数量切分，为默认切分方式。
 */
@Component
public class TokenSplitterStrategy implements DocumentSplitterStrategy {

    /** 单块目标 token 数 */
    private final int chunkSize;

    /** 低于该 token 数的块不参与 embedding */
    private final int minChunkLengthToEmbed;

    public TokenSplitterStrategy(
            @Value("${lion.splitter.token.chunk-size:800}") int chunkSize,
            @Value("${lion.splitter.token.min-chunk-length:50}") int minChunkLengthToEmbed) {
        this.chunkSize = chunkSize;
        this.minChunkLengthToEmbed = minChunkLengthToEmbed;
    }

    @Override
    public SplitterType type() {
        return SplitterType.TOKEN;
    }

    @Override
    public List<Document> split(List<Document> docs) {
        return TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkLengthToEmbed(minChunkLengthToEmbed)
                .build()
                .split(docs);
    }
}
