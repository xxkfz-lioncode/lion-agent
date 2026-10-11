package com.lion.agent.tools.base;

import cn.hutool.core.io.FileTypeUtil;
import cn.hutool.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 资源下载工具：把网络上的文件（图片、文档、压缩包、数据集等）下载到本地并返回可访问地址。
 * <p>
 * 典型场景：用户给一个 URL 让助手"把这个文件/图片/报表保存下来"，或模型需要先取回远端素材再做处理。
 * <p>
 * 安全护栏（下载类工具最容易被滥用成 SSRF 探针或恶意文件投放，默认收紧）：
 * <ol>
 *   <li><b>协议白名单</b>：仅允许 http/https，拒绝 file、ftp、jar 等协议（防读本地文件）。</li>
 *   <li><b>内网地址拦截</b>：解析 host 后拒绝 loopback / link-local / 私有网段，
 *       避免被当成探测内网的跳板（SSRF）。</li>
 *   <li><b>后缀黑名单</b>：拒绝 .exe/.bat/.cmd/.sh/.msi 等可执行产物，只收"资源"不收"程序"。</li>
 *   <li><b>大小上限 + 超时</b>：单文件 20MB、下载超时 60s（由 Hutool {@link HttpUtil} 控制），
 *       先写 .part 临时文件再原子改名，失败不留半截文件。</li>
 * </ol>
 * <p>
 * 落盘目录默认 {@code upload/download/}：{@code WebConfig} 已把 {@code /upload/**} 静态映射出去，
 * 下载完即可通过 URL 访问，无需新增映射。
 */
@Slf4j
@Component
public class ResourceDownloadTools {

    /** 下载超时（毫秒，交给 Hutool HttpUtil.downloadFile 控制）：下载可能较慢，给得比普通接口宽 */
    private static final int DOWNLOAD_TIMEOUT_MS = 60_000;

    /** 单文件大小上限：20MB */
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

    /** URL 长度上限 */
    private static final int MAX_URL_LENGTH = 2000;

    /** 文件名非法字符（保留中英文、数字、点、下划线、中划线） */
    private static final Pattern ILLEGAL_CHARS = Pattern.compile("[^A-Za-z0-9_.\\-\\u4e00-\\u9fa5]");

    /** 禁止下载的可执行后缀 */
    private static final Set<String> DENIED_EXTENSIONS = Set.of("exe", "bat", "cmd", "com", "msi", "sh", "bash", "ps1");

    /** 默认文件名时间戳：download-20261011-153000 */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 下载根目录（绝对、已规范化） */
    private final Path saveDir;

    /** 浏览器访问前缀，与 WebConfig 的 /upload/** 静态映射对应 */
    private final String urlPrefix;

    public ResourceDownloadTools(@Value("${lion.tools.download.dir:upload/download/}") String dir,
                                 @Value("${lion.tools.download.url-prefix:/upload/download/}") String urlPrefix) {
        this.saveDir = Paths.get(dir).toAbsolutePath().normalize();
        this.urlPrefix = urlPrefix.endsWith("/") ? urlPrefix : urlPrefix + "/";
    }

    /**
     * 下载网络资源到本地。
     *
     * @param url       资源地址（http/https）
     * @param fileName  可选保存文件名，缺省从 URL 路径推断
     * @param overwrite 是否覆盖同名文件，缺省 false
     * @return 文件名 + 大小 + 类型 + 绝对路径 + 访问地址；失败时返回错误说明
     */
    @Tool(name = "downloadResource",
          description = "Download a file from a public http/https URL and save it to the server, then return the URL where it can be accessed. "
                  + "Use it when the user provides a link and asks to save, fetch or keep the file (image, PDF, document, archive, dataset, ...), "
                  + "or when a remote asset is needed for further processing. "
                  + "Returns the file name, size in KB, content type, absolute path and the accessible URL, "
                  + "or an explicit error message. Constraints: http/https only, max 20MB per file, "
                  + "private/loopback addresses and executable extensions (.exe/.bat/.sh/...) are rejected.")
    public String downloadResource(
            @ToolParam(description = "Public http/https URL of the resource to download, e.g. \"https://example.com/report.pdf\".") String url,
            @ToolParam(description = "Optional file name to save as, e.g. \"report.pdf\". Leave empty to derive it from the URL.", required = false) String fileName,
            @ToolParam(description = "Set true to overwrite an existing file with the same name. Defaults to false.", required = false) Boolean overwrite
    ) {
        if (!StringUtils.hasText(url) || url.length() > MAX_URL_LENGTH) {
            return "错误：URL 为空或过长（上限 " + MAX_URL_LENGTH + " 字符）。";
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return "错误：URL 格式不合法：" + e.getMessage();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "错误：只支持 http/https 协议下载，当前为「" + scheme + "」。";
        }
        String blockReason = checkHost(uri.getHost());
        if (blockReason != null) {
            log.warn("下载请求被拦截, url={}, reason={}", url, blockReason);
            return "错误：" + blockReason;
        }

        String name = resolveFileName(fileName, uri);
        if (DENIED_EXTENSIONS.contains(extensionOf(name))) {
            return "错误：禁止下载可执行文件（." + extensionOf(name) + "），仅支持图片、文档、压缩包等静态资源。";
        }

        Path target = saveDir.resolve(name).normalize();
        if (!target.startsWith(saveDir)) {
            return "错误：文件名不合法，禁止写入下载目录之外的路径。";
        }
        if (Files.exists(target) && !Boolean.TRUE.equals(overwrite)) {
            return "错误：文件已存在「" + name + "」。换一个文件名，或传 overwrite=true 覆盖。";
        }

        Path part = saveDir.resolve(name + ".part");
        long size;
        try {
            Files.createDirectories(saveDir);
            Files.deleteIfExists(part);
            // Hutool 封装的流式下载：内部按缓冲区边下边写盘，不会把整个文件读进内存
            size = HttpUtil.downloadFile(uri.toString(), part.toFile(), DOWNLOAD_TIMEOUT_MS);
            if (size > MAX_FILE_SIZE) {
                Files.deleteIfExists(part);
                return "错误：文件超过 20MB 上限，已放弃保存。";
            }
            // 先写 .part 再原子改名：避免下载中断留下半截文件被当成完整资源使用
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.error("资源下载失败, url={}", url, e);
            deleteQuietly(part);
            return "错误：下载失败：" + e.getMessage();
        }

        return String.join("\n",
                "资源已下载。",
                "文件名：" + name,
                "大小：" + Math.max(1, size / 1024) + " KB",
                "类型：" + fileType(target),
                "访问地址：" + urlPrefix + name,
                "绝对路径：" + target);
    }

    /** 用 Hutool 按文件头魔数识别真实类型（比 URL 后缀 / Content-Type 更可信）；识别不出时退回扩展名 */
    private String fileType(Path file) {
        String fallback = extensionOf(file.getFileName().toString());
        try {
            String type = FileTypeUtil.getType(file.toFile());
            return StringUtils.hasText(type) ? type : (StringUtils.hasText(fallback) ? fallback : "未知");
        } catch (Exception e) {
            return StringUtils.hasText(fallback) ? fallback : "未知";
        }
    }

    /** 主机校验：拒绝内网/本机地址（SSRF 防护），合法返回 null */
    private String checkHost(String host) {
        if (!StringUtils.hasText(host)) {
            return "URL 缺少主机名。";
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                return "禁止下载内网/本机地址的资源（" + host + "）。";
            }
            return null;
        } catch (UnknownHostException e) {
            return "无法解析主机：" + host;
        }
    }

    /** 文件名：优先用传入值，其次从 URL 路径推断，最后按时间戳兜底 */
    private String resolveFileName(String fileName, URI uri) {
        String raw = StringUtils.hasText(fileName) ? fileName.trim() : nameFromPath(uri.getPath());
        String cleaned = ILLEGAL_CHARS.matcher(raw).replaceAll("-").replaceAll("^-+", "");
        if (!StringUtils.hasText(cleaned) || ".".equals(cleaned)) {
            cleaned = "download-" + LocalDateTime.now().format(TIMESTAMP);
        }
        return cleaned;
    }

    /** 从 URL 路径取末段并做 URL 解码（处理 %20 之类转义） */
    private String nameFromPath(String path) {
        if (!StringUtils.hasText(path) || "/".equals(path)) {
            return "";
        }
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        return URLDecoder.decode(lastSegment, StandardCharsets.UTF_8);
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不影响主流程
        }
    }
}
