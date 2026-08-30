package com.lion.agent.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库检索引用来源
 * <p>
 * 用于前端展示引用片段时标注：来自哪个知识库、哪个文件、具体内容是什么。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "知识库引用来源")
public class ChunkSource {

    @Schema(description = "知识库 ID")
    private Long knowledgeId;

    @Schema(description = "知识库名称")
    private String knowledgeName;

    @Schema(description = "来源文件名")
    private String fileName;

    @Schema(description = "引用片段内容")
    private String content;
}
