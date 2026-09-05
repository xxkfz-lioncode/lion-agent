package com.lion.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.common.PageResult;
import com.lion.agent.common.async.RedisTaskQueue;
import com.lion.agent.common.enums.DocumentStatus;
import com.lion.agent.common.enums.VectorType;
import com.lion.agent.model.dto.DocumentProcessTask;
import com.lion.agent.model.entity.KnowledgeDocument;
import com.lion.agent.exception.BusinessException;
import com.lion.agent.mapper.KnowledgeDocumentMapper;
import com.lion.agent.service.KnowledgeBaseService;
import com.lion.agent.service.KnowledgeDocumentService;
import com.lion.agent.service.async.DocumentProcessConsumer;
import com.lion.agent.service.retriever.InMemoryChunkStore;
import com.lion.agent.splitter.DocumentSplitterStrategy;
import com.lion.agent.splitter.SplitterStrategyRegistry;
import com.lion.agent.splitter.SplitterType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeDocumentMapper documentMapper;
    private final VectorStore vectorStore;
    private final RedisTaskQueue taskQueue;
    private final SplitterStrategyRegistry splitterStrategyRegistry;
    /** 本地内存分片副本：入库/删除时同步，供 BM25 关键词召回、窗口扩容直接读取（不依赖 Milvus 客户端） */
    private final InMemoryChunkStore chunkStore;

    @Value("${lion.upload.path:upload/}")
    private String uploadPath;

    @Value("${lion.upload.allowed-types:text/plain,text/markdown,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document}")
    private Set<String> allowedTypes;

    @Override
    public PageResult<KnowledgeDocument> listByKnowledgeId(Long knowledgeId, Long userId, int pageNum, int pageSize, String keyword) {
        knowledgeBaseService.getById(knowledgeId, userId);
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocument::getKnowledgeId, knowledgeId);
        if (StringUtils.hasText(keyword)) {
            wrapper.like(KnowledgeDocument::getFileName, keyword);
        }
        wrapper.orderByDesc(KnowledgeDocument::getCreatedAt);
        Page<KnowledgeDocument> page = new Page<>(pageNum, pageSize);
        Page<KnowledgeDocument> result = documentMapper.selectPage(page, wrapper);
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), result.getRecords());
    }

    @Override
    public KnowledgeDocument upload(Long knowledgeId, Long userId, MultipartFile file, String splitter) {
        knowledgeBaseService.getById(knowledgeId, userId);

        if (file == null || file.isEmpty()) {
            throw new BusinessException("文件不能为空");
        }
        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename)) {
            throw new BusinessException("文件名不能为空");
        }

        String contentType = file.getContentType();
        if (!allowedTypes.contains(contentType)) {
            throw new BusinessException("暂不支持的文件类型：" + contentType);
        }

        // 1. 保存文档元数据（状态：处理中）
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setKnowledgeId(knowledgeId);
        doc.setFileName(originalFilename);
        doc.setFileSize(file.getSize());
        doc.setFileType(contentType);
        doc.setSplitter(StringUtils.hasText(splitter) ? splitter : SplitterType.TOKEN.getValue());
        doc.setStatus(DocumentStatus.PROCESSING.getCode());
        documentMapper.insert(doc);

        Path savedPath = null;
        try {
            // 2. 文件落盘：upload/{knowledgeId}/{yyyy/MM}/{uuid}_{文件名}，库中存绝对路径，
            //    避免异步消费者在非项目根目录的工作目录下按相对路径解析失败
            savedPath = saveFileToDisk(knowledgeId, file, originalFilename);
            doc.setFilePath(savedPath.toAbsolutePath().normalize().toString());
            documentMapper.updateById(doc);

            // 3. 推送异步处理任务到 Redis 队列，立即返回（解析/切分/向量化由消费者后台执行）
            boolean pushed = taskQueue.push(DocumentProcessConsumer.QUEUE_NAME,
                    new DocumentProcessTask(doc.getId(), knowledgeId, doc.getFilePath(), splitter, 0));
            if (!pushed) {
                throw new BusinessException("任务入队失败，请稍后重试");
            }
            log.info("文档上传成功，已入队异步处理 docId={} knowledgeId={} splitter={}", doc.getId(), knowledgeId, splitter);
        } catch (Exception e) {
            log.error("文档上传失败，docId={}", doc.getId(), e);
            doc.setStatus(DocumentStatus.FAIL.getCode());
            doc.setFailReason(truncate(e.getMessage(), 500));
            documentMapper.updateById(doc);
            // 清理已保存的物理文件
            if (savedPath != null) {
                deletePhysicalFile(savedPath);
            }
        }

        return doc;
    }

    @Override
    public void processDocument(Long knowledgeId, Long docId, String filePath, String splitter) {
        // 幂等：已成功的文档不重复处理（可能因重试/重复入队再次消费）
        KnowledgeDocument doc = documentMapper.selectById(docId);
        if (doc == null) {
            log.warn("[DocTask] 文档不存在，docId={}", docId);
            return;
        }
        if (DocumentStatus.SUCCESS.getCode() == doc.getStatus()) {
            log.info("[DocTask] 文档已处理成功，跳过 docId={}", docId);
            return;
        }

        Path savedPath = StringUtils.hasText(filePath) ? Paths.get(filePath) : null;
        try {
            // 1. 从磁盘读取文件并解析（文件已在上传阶段落盘）
            //    直接以 FileSystemResource 读取，避免 toUri() 对中文文件名的百分号编码在 Windows 上解析失败
            if (savedPath == null || !Files.exists(savedPath)) {
                throw new BusinessException("文件不存在或已被清理：" + filePath);
            }
            TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(savedPath));
            List<Document> parsedDocs = reader.get();
            if (parsedDocs == null || parsedDocs.isEmpty()) {
                throw new BusinessException("文件内容为空，无法解析");
            }

            // 2. 按选择的切分方式分片
            List<Document> chunks = splitByStrategy(splitter, parsedDocs);

            // 3. 写入向量库（metadata 携带知识库/文档标识/分片序号，便于按知识库过滤检索、按文档删除，
            //    以及检索后做 small-to-big 窗口扩容时定位相邻分片）
            List<Document> vectorDocs = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                vectorDocs.add(Document.builder()
                        .text(chunks.get(i).getText())
                        .metadata(buildMetadata(knowledgeId, docId, doc.getFileName(), i))
                        .build());
            }
            // MilvusVectorStore.add 内部会对整批做 embedding，DashScope 单次上限 10 条，分批写入
            final int addBatchSize = 10;
            for (int i = 0; i < vectorDocs.size(); i += addBatchSize) {
                vectorStore.add(vectorDocs.subList(i, Math.min(i + addBatchSize, vectorDocs.size())));
            }
            // 向量入库成功后同步本地内存副本（BM25/窗口扩容的本地数据源）
            chunkStore.addAll(vectorDocs);

            // 4. 标记成功
            doc.setStatus(DocumentStatus.SUCCESS.getCode());
            doc.setFailReason(null);
            documentMapper.updateById(doc);
            log.info("[DocTask] 文档处理成功 docId={} chunks={}", docId, vectorDocs.size());
        } catch (Exception e) {
            log.error("文档处理失败，docId={}", docId, e);
            doc.setStatus(DocumentStatus.FAIL.getCode());
            doc.setFailReason(truncate(e.getMessage(), 500));
            documentMapper.updateById(doc);
            // 清理已保存的物理文件
            if (savedPath != null) {
                deletePhysicalFile(savedPath);
            }
        }
    }

    /**
     * 根据切分方式选择对应策略执行分片：策略由 {@link SplitterStrategyRegistry} 按 type 分发，
     * 新增切分方式只需新增一个 {@link DocumentSplitterStrategy} 实现类并声明 type()，
     * 无需改动本方法
     */
    private List<Document> splitByStrategy(String splitter, List<Document> parsedDocs) {
        if (!StringUtils.hasText(splitter)) {
            splitter = SplitterType.TOKEN.getValue();
        }
        return splitterStrategyRegistry.get(splitter).split(parsedDocs);
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long knowledgeId, Long docId, Long userId) {
        knowledgeBaseService.getById(knowledgeId, userId);
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeDocument::getId, docId)
                .eq(KnowledgeDocument::getKnowledgeId, knowledgeId);
        KnowledgeDocument doc = documentMapper.selectOne(wrapper);
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }

        // 删除向量库中的该文档分片：按 documentId 过滤删除（Milvus 原生支持按表达式删除）
        vectorStore.delete("documentId == " + docId);
        // 同步移除本地内存副本
        chunkStore.removeByDocumentId(docId);

        documentMapper.deleteById(docId);

        // 删除物理文件（忽略失败）
        if (StringUtils.hasText(doc.getFilePath())) {
            deletePhysicalFile(Paths.get(doc.getFilePath()));
        }
    }

    @Override
    public String preview(Long knowledgeId, Long docId, Long userId) {
        knowledgeBaseService.getById(knowledgeId, userId);

        KnowledgeDocument doc = documentMapper.selectById(docId);
        if (doc == null || !doc.getKnowledgeId().equals(knowledgeId)) {
            throw new BusinessException("文档不存在");
        }

        if (!StringUtils.hasText(doc.getFilePath())) {
            return "文件尚未落盘，无法预览";
        }
        if (!isTextPreviewable(doc.getFileType())) {
            return String.format("当前文件类型「%s」不支持文本预览，仅支持 text/plain、text/markdown", doc.getFileType());
        }

        Path path = Paths.get(doc.getFilePath());
        if (!Files.exists(path)) {
            return "文件已丢失，无法预览";
        }

        try {
            byte[] bytes = Files.readAllBytes(path);
            String text = new String(bytes, StandardCharsets.UTF_8);
            int maxLen = 5000;
            if (text.length() > maxLen) {
                return text.substring(0, maxLen) + "\n\n……（已截断，仅展示前 " + maxLen + " 字符）";
            }
            return text;
        } catch (IOException e) {
            log.warn("预览文档失败，docId={}", docId, e);
            return "读取文件失败：" + e.getMessage();
        }
    }

    private boolean isTextPreviewable(String fileType) {
        return "text/plain".equalsIgnoreCase(fileType) || "text/markdown".equalsIgnoreCase(fileType);
    }

    private Map<String, Object> buildMetadata(Long knowledgeId, Long docId, String fileName, int chunkIndex) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", VectorType.KB.getValue());
        metadata.put("knowledgeId", knowledgeId);
        metadata.put("documentId", docId);
        metadata.put("fileName", fileName);
        metadata.put("chunkIndex", chunkIndex);
        return metadata;
    }

    private Path saveFileToDisk(Long knowledgeId, MultipartFile file, String originalFilename) throws IOException {
        Path dir = Paths.get(uploadPath, String.valueOf(knowledgeId), LocalDate.now().toString().replace("-", "/"));
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        String safeName = UUID.randomUUID().toString().replace("-", "") + "_" + originalFilename;
        Path target = dir.resolve(safeName);
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private void deletePhysicalFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除物理文件失败，path={}", path, e);
        }
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() > maxLen ? str.substring(0, maxLen) : str;
    }
}
