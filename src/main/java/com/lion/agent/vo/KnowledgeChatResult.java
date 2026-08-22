package com.lion.agent.vo;

import lombok.Data;

import java.util.List;

@Data
public class KnowledgeChatResult {

    private String answer;

    private List<String> referencedChunks;
}
