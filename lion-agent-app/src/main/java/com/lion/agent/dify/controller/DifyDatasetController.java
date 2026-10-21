package com.lion.agent.dify.controller;

import com.lion.agent.common.result.R;
import com.lion.agent.dify.dto.DifyDatasetCreateDTO;
import com.lion.agent.dify.dto.DifyDocTextDTO;
import com.lion.agent.dify.service.DifyDatasetService;
import io.github.guoshiqiufeng.dify.core.pojo.DifyPageResult;
import io.github.guoshiqiufeng.dify.dataset.dto.response.DatasetResponse;
import io.github.guoshiqiufeng.dify.dataset.dto.response.DocumentCreateResponse;
import io.github.guoshiqiufeng.dify.dataset.dto.response.DocumentInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Dify 知识库接口（转发至 Dify 平台的知识库 Dataset API）
 */
@Tag(name = "21-Dify 知识库", description = "对接 Dify 知识库：知识库管理、文档上传与维护")
@RestController
@RequestMapping("/api/dify/dataset")
@RequiredArgsConstructor
public class DifyDatasetController {

    private final DifyDatasetService difyDatasetService;

    @Operation(summary = "知识库分页列表")
    @GetMapping("/page")
    public R<DifyPageResult<DatasetResponse>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer limit) {
        return R.success(difyDatasetService.page(keyword, page, limit));
    }

    @Operation(summary = "创建知识库")
    @PostMapping("/create")
    public R<DatasetResponse> create(@Valid @RequestBody DifyDatasetCreateDTO dto) {
        return R.success(difyDatasetService.create(dto));
    }

    @Operation(summary = "删除知识库", description = "连同其下所有文档一并删除，不可恢复")
    @DeleteMapping("/{datasetId}")
    public R<Void> delete(@PathVariable String datasetId) {
        difyDatasetService.delete(datasetId);
        return R.success();
    }

    @Operation(summary = "文档分页列表")
    @GetMapping("/{datasetId}/documents")
    public R<DifyPageResult<DocumentInfo>> documents(
            @PathVariable String datasetId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer limit) {
        return R.success(difyDatasetService.documents(datasetId, keyword, page, limit));
    }

    @Operation(summary = "文本新建文档", description = "以纯文本内容创建文档，Dify 自动分段并建立索引")
    @PostMapping("/{datasetId}/documents/text")
    public R<DocumentCreateResponse> createDocumentByText(
            @PathVariable String datasetId,
            @Valid @RequestBody DifyDocTextDTO dto) {
        return R.success(difyDatasetService.createDocumentByText(datasetId, dto));
    }

    @Operation(summary = "上传文件新建文档",
            description = "multipart/form-data 上传（txt/md/pdf/word/html/xlsx 等），Dify 自动解析")
    @PostMapping(value = "/{datasetId}/documents/file", consumes = "multipart/form-data")
    public R<DocumentCreateResponse> createDocumentByFile(
            @PathVariable String datasetId,
            @RequestPart("file") MultipartFile file) {
        return R.success(difyDatasetService.createDocumentByFile(datasetId, file));
    }

    @Operation(summary = "删除文档")
    @DeleteMapping("/{datasetId}/documents/{documentId}")
    public R<Void> deleteDocument(@PathVariable String datasetId, @PathVariable String documentId) {
        difyDatasetService.deleteDocument(datasetId, documentId);
        return R.success();
    }
}
