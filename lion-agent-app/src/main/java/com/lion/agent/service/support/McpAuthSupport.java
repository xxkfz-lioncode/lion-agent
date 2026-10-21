package com.lion.agent.service.support;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.RegisteredPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * MCP 调用凭据签发（Client 侧）。
 *
 * <p>工具调用的 {@code ToolContext} 会被 {@code ToolContextToMcpMetaConverter} 转成 MCP 请求的
 * {@code _meta} 发给远程 MCP Server，本类决定其中 {@code Authorization} 放什么。</p>
 *
 * <p>为什么用「短期 JWT」而不是固定字符串：</p>
 * <ul>
 *   <li><b>固定字符串</b>（如 {@code "lion-agent"}）：谁拿到都一样，等于没有认证，泄露后永久有效</li>
 *   <li><b>登录态 token</b>（Sa-Token）：随会话长期有效，泄露影响大；且独立进程的 MCP Server
 *       要共享同一套会话存储才能校验，耦合太重</li>
 *   <li><b>短期 JWT</b>：带用户身份、有过期时间、可验签，泄露后最多在 TTL 内有效，
 *       Server 侧只用一个共享密钥就能校验</li>
 * </ul>
 *
 * <p>⚠️ 重要前提：{@code _meta} 会发给<b>每一个</b>已接入的 MCP Server，转换器接口拿不到目标服务信息，
 * 无法按服务区分。接入不可信的第三方 MCP Server 时，应在 {@code AiConfig} 的转换器里去掉凭据透传。</p>
 */
@Slf4j
@Component
public class McpAuthSupport {

    /**
     * 凭据在 ToolContext 中的 key：写入方（ChatLlmSupport）与读取方（AiConfig 的
     * ToolContextToMcpMetaConverter）必须用同一个常量，否则会静默丢失。
     */
    public static final String AUTH_CONTEXT_KEY = "Authorization";

    /**
     * HTTP 语义的令牌前缀。
     *
     * <p>mcp-server 是独立项目，引用不到这里的常量，解析时会各自写一份——改这个值必须两边同步，
     * 否则会出现签名正确但前缀不匹配、被判为 MALFORMED 的情况。</p>
     */
    public static final String BEARER_PREFIX = "Bearer ";

    /** 签名密钥的最小长度：低于这个值启动时就告警，避免带着弱密钥上生产 */
    private static final int MIN_SECRET_LENGTH = 16;

    /**
     * application.yml 里的默认密钥，两处需同步。
     * 它长度够（25 位）却最危险——是写在文档里的公开值，用它等于没有签名。
     */
    private static final String DEFAULT_SECRET = "please-change-this-secret";

    private final byte[] secret;
    private final int ttlMinutes;

    public McpAuthSupport(@Value("${lion.mcp.auth.secret}") String secret,
                          @Value("${lion.mcp.auth.ttl-minutes:5}") int ttlMinutes) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlMinutes = ttlMinutes;
        boolean isDefault = DEFAULT_SECRET.equals(secret);
        if (isDefault || secret == null || secret.length() < MIN_SECRET_LENGTH) {
            // 用 warn：不安全的密钥是必须被看见的风险；只在启动时打一次，不会刷屏
            // 注意只描述问题、不打印密钥本身
            log.warn("[MCP 凭据] 签名密钥不安全（{}），"
                            + "生产环境请用环境变量 LION_MCP_AUTH_SECRET 设置足够长的随机密钥",
                    isDefault ? "仍在使用 yml 里的默认值，等同于公开"
                            : "长度不足，建议不少于 " + MIN_SECRET_LENGTH + " 字符");
        }
    }

    /**
     * 签发一个短期凭据
     *
     * @param userId 当前登录用户 ID（服务端取得，模型无法伪造）
     * @return JWT 字符串；按 HTTP 语义使用时写作 {@code "Bearer " + 本值}
     */
    public String buildToken(long userId) {
        return JWT.create()
                // 调用者身份：Server 侧据此做行级权限判断
                .setPayload("uid", userId)
                // 签发方：Server 侧可拒绝来源不符的凭据
                .setPayload(RegisteredPayload.ISSUER, "lion-agent")
                // 随机 ID：便于追踪单次调用，将来做重放检测也用得上
                .setPayload(RegisteredPayload.JWT_ID, IdUtil.fastSimpleUUID())
                // 过期时间：JWT 标准要求秒级时间戳
                .setPayload(RegisteredPayload.EXPIRES_AT, DateUtil.currentSeconds() + (long) ttlMinutes * 60)
                .setKey(secret)
                .sign();
    }

    /**
     * 生成可直接放进 {@link #AUTH_CONTEXT_KEY} 的完整值：{@code "Bearer <jwt>"}。
     *
     * <p>前缀拼接收在本类里，调用方不必知道 Bearer 这个细节，也就不会拼错。</p>
     */
    public String buildAuthHeader(long userId) {
        String token = buildToken(userId);
        // 只记元信息：完整 JWT 一旦写进日志就等于凭据泄露，任何级别都不打
        log.info("[MCP 凭据] 已签发 uid={}, token = {},有效期={}分钟, 长度={}", userId,token, ttlMinutes, token.length());
        return BEARER_PREFIX + token;
    }
}
