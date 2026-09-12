package com.lion.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lion.agent.common.PageResult;
import com.lion.agent.common.async.RedisTaskQueue;
import com.lion.agent.common.enums.DocumentStatus;
import com.lion.agent.common.enums.VectorType;
import com.lion.agent.common.util.LazyMilvusVectorStore;
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
import io.milvus.client.MilvusServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeDocumentMapper documentMapper;
    private final MilvusServiceClient milvusClient;
    private final EmbeddingModel embeddingModel;
    private final RedisTaskQueue taskQueue;
    private final SplitterStrategyRegistry splitterStrategyRegistry;
    /** 本地内存分片副本：入库/删除时同步，供 BM25 关键词召回、窗口扩容直接读取（不依赖 Milvus 客户端） */
    private final InMemoryChunkStore chunkStore;

    @Value("${lion.upload.path:upload/}")
    private String uploadPath;

    /** 知识库向量 collection：与检索侧默认 VectorStore 指向同一集合（dev 默认 lion_base_docs、prod 默认 lion_agent_knowledge） */
    @Value("${lion.knowledge.collection-name:lion_agent_knowledge}")
    private String collectionName;

    /** 向量维度，与 spring.ai.openai.embedding.model（text-embedding-v3 输出 1024 维）一致 */
    @Value("${lion.knowledge.embedding-dimension:1024}")
    private int embeddingDimension;

    /** 知识库向量存储持有器：懒加载建库 + 失败自愈（非 Spring Bean，避免顶掉检索侧默认 VectorStore） */
    private volatile LazyMilvusVectorStore knowledgeStore;

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
            // 1. 解析 + 2. 分片（文件已在上传阶段落盘并提供路径）
            //    解析型策略（如 PDF 按页切分）直接读原始文件；其余策略先用 Tika 抽成纯文本再切分
            if (savedPath == null || !Files.exists(savedPath)) {
                throw new BusinessException("文件不存在或已被清理：" + filePath);
            }
            List<Document> chunks = splitDocument(splitter, savedPath);

            // 3. 写入向量库（metadata 携带知识库/文档标识/分片序号，便于按知识库过滤检索、按文档删除，
            //    以及检索后做 small-to-big 窗口扩容时定位相邻分片）
            List<Document> vectorDocs = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                Map<String, Object> metadata = buildMetadata(knowledgeId, docId, doc.getFileName(), i);
                // 解析型策略（PDF 按页切分）会给块打上页码，透传给检索侧用于标注来源页
                chunk.getMetadata();
                Object page = chunk.getMetadata().get(DocumentSplitterStrategy.PAGE_METADATA_KEY);
                if (page != null) {
                    metadata.put(DocumentSplitterStrategy.PAGE_METADATA_KEY, page);
                }
                vectorDocs.add(Document.builder()
                        .text(chunk.getText())
                        .metadata(metadata)
                        .build());
            }
            // MilvusVectorStore.add 内部会对整批做 embedding，DashScope 单次上限 10 条，分批写入
            // （向量库走 LazyMilvusVectorStore 懒加载实例；不可用则抛异常 → 文档标记失败，下次入队重试自愈）
            MilvusVectorStore kbStore = store();
            final int addBatchSize = 10;
            for (int i = 0; i < vectorDocs.size(); i += addBatchSize) {
                kbStore.add(vectorDocs.subList(i, Math.min(i + addBatchSize, vectorDocs.size())));
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
     * 懒加载获取知识库向量库：首次调用（或上次失败后）才构建并建表。
     *
     * <p>为什么写入不直接用默认 VectorStore Bean：默认 Bean 由 Spring AI 自动配置管理，
     * 与检索侧共享同一 collection；而文档入库属于业务主链路——向量库不可用时应尽快失败
     * 并把文档留在可重试状态（FAIL → 重新入队），而不是静默跳过导致「DB 标记成功但向量缺失」。
     * 这里刻意不注册成 Bean，而是通过 {@link LazyMilvusVectorStore} 懒加载持有独立实例
     * （懒加载 + 建表 + 失败自愈收敛在持有器内），初始化失败会直接抛出，由调用方统一兜底。</p>
     */
    private MilvusVectorStore store() {
        LazyMilvusVectorStore holder = knowledgeStore;
        if (holder == null) {
            synchronized (this) {
                holder = knowledgeStore;
                if (holder == null) {
                    holder = new LazyMilvusVectorStore(milvusClient, embeddingModel,
                            collectionName, embeddingDimension, "知识库");
                    knowledgeStore = holder;
                }
            }
        }
        return holder.get();
    }

    /**
     * 按切分方式解析并分片：策略由 {@link SplitterStrategyRegistry} 按 type 分发，
     * 新增「文本切分型」策略只需新增一个实现类并声明 type()，无需改动本方法。
     *
     * <p>两类策略走不同分支：解析型策略（{@link DocumentSplitterStrategy#parseFromSource()} 为 true，
     * 如 PDF 按页切分）的分块依据在原始文件结构里，直接读落盘文件；
     * 文本切分型策略先由 Tika 抽取纯文本，再交给策略切分。</p>
     */
    private List<Document> splitDocument(String splitter, Path savedPath) {
        DocumentSplitterStrategy strategy = splitterStrategyRegistry.get(
                StringUtils.hasText(splitter) ? splitter : SplitterType.TOKEN.getValue());
        // 以 FileSystemResource 读取，避免 toUri() 对中文文件名的百分号编码在 Windows 上解析失败
        FileSystemResource resource = new FileSystemResource(savedPath);
        if (strategy.parseFromSource()) {
            return strategy.parseAndSplit(resource);
        }

        List<Document> parsedDocs = new TikaDocumentReader(resource).get();
        if (parsedDocs == null || parsedDocs.isEmpty()) {
            throw new BusinessException("文件内容为空，无法解析");
        }
        return strategy.split(parsedDocs);
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
        store().delete("documentId == " + docId);
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

        Path path = Paths.get(doc.getFilePath());
        if (!Files.exists(path)) {
            return "文件已丢失，无法预览";
        }

        boolean plainText = isPlainTextPreviewable(doc);
        boolean tikaParsable = isTikaPreviewable(doc);
        if (!plainText && !tikaParsable) {
            return String.format("当前文件类型「%s」不支持预览，仅支持 txt / md / pdf / doc / docx", doc.getFileType());
        }

        try {
            // 纯文本按 UTF-8 直读；PDF/Word 交给 Tika 抽取正文（与入库解析同一套能力，预览即实际切分内容）
            String text = plainText
                    ? new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                    : parseWithTika(path);
            return truncatePreview(text);
        } catch (Exception e) {
            log.warn("预览文档失败，docId={}", docId, e);
            return "读取文件失败：" + e.getMessage();
        }
    }

    /** 预览截断上限：超出仅展示前 5000 字符，避免大文档把响应撑爆 */
    private static final int PREVIEW_MAX_LEN = 5000;

    private String truncatePreview(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > PREVIEW_MAX_LEN) {
            return text.substring(0, PREVIEW_MAX_LEN) + "\n\n……（已截断，仅展示前 " + PREVIEW_MAX_LEN + " 字符）";
        }
        return text;
    }

    /**
     * 纯文本预览：按扩展名判断，兼容 fileType 存 MIME（text/plain、text/markdown）或扩展名的历史数据
     */
    private boolean isPlainTextPreviewable(KnowledgeDocument doc) {
        String ext = fileExtension(doc);
        if ("txt".equals(ext) || "md".equals(ext) || "markdown".equals(ext)) {
            return true;
        }
        String fileType = doc.getFileType();
        return "text/plain".equalsIgnoreCase(fileType) || "text/markdown".equalsIgnoreCase(fileType);
    }

    /**
     * PDF / Word 预览：交由 Tika 抽取文本，同样兼容扩展名与 MIME 两种存量数据
     */
    private boolean isTikaPreviewable(KnowledgeDocument doc) {
        String ext = fileExtension(doc);
        if ("pdf".equals(ext) || "doc".equals(ext) || "docx".equals(ext)) {
            return true;
        }
        if (!StringUtils.hasText(doc.getFileType())) {
            return false;
        }
        String fileType = doc.getFileType().toLowerCase(Locale.ROOT);
        return fileType.startsWith("application/pdf")
                || fileType.startsWith("application/msword")
                || fileType.startsWith("application/vnd.openxmlformats-officedocument.wordprocessingml");
    }

    /**
     * 取文件扩展名（小写、不含点）；取不到返回空串
     */
    private String fileExtension(KnowledgeDocument doc) {
        String fileName = doc.getFileName();
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 用 Tika 解析文档正文（PDF / doc / docx 等），返回抽取到的纯文本
     */
    private String parseWithTika(Path path) {
        List<Document> docs = new TikaDocumentReader(new FileSystemResource(path)).get();
        if (docs == null || docs.isEmpty()) {
            return "";
        }
        String text = docs.stream()
                .map(Document::getText)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n"));
        return StringUtils.hasText(text)
                ? text
                : "未提取到文本内容（可能是扫描件/图片型 PDF，无法做文本预览）";
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
