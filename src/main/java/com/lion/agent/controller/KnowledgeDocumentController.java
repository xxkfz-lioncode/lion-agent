package com.lion.agent.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.lion.agent.common.PageResult;
import com.lion.agent.common.Result;
import com.lion.agent.entity.KnowledgeDocument;
import com.lion.agent.service.KnowledgeDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "知识库文档")
@RestController
@RequestMapping("/api/knowledge/{knowledgeId}/documents")
@RequiredArgsConstructor
@SaCheckLogin
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService documentService;

    @Operation(summary = "文档列表")
    @GetMapping
    public Result<PageResult<KnowledgeDocument>> list(
            @PathVariable Long knowledgeId,
            @RequestParam(required = false, defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(required = false, defaultValue = "10") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) String keyword) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(documentService.listByKnowledgeId(knowledgeId, userId, pageNum, pageSize, keyword));
    }

    @Operation(summary = "上传文档")
    @PostMapping
    public Result<KnowledgeDocument> upload(@PathVariable Long knowledgeId,
                                            @RequestParam("file") MultipartFile file,
                                            @RequestParam(required = false, defaultValue = "token") String splitter) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(documentService.upload(knowledgeId, userId, file, splitter));
    }

    @Operation(summary = "删除文档")
    @DeleteMapping("/{docId}")
    public Result<Void> delete(@PathVariable Long knowledgeId,
                               @PathVariable Long docId) {
        Long userId = StpUtil.getLoginIdAsLong();
        documentService.delete(knowledgeId, docId, userId);
        return Result.success();
    }

    @Operation(summary = "预览文档内容")
    @GetMapping("/{docId}/preview")
    public Result<String> preview(@PathVariable Long knowledgeId,
                                  @PathVariable Long docId) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.success(documentService.preview(knowledgeId, docId, userId));
    }
}
