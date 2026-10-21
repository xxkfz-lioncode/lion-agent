package com.lion.mcp.server.auth;

import cn.hutool.jwt.JWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 校验 lion-agent 签发过来的调用凭据（Server 侧）。
 *
 * <p>与 Client 侧的 {@code McpAuthSupport} 对称：那边用共享密钥签发短期 JWT，这边用同一把密钥验签。
 * 密钥分别配在 {@code lion.mcp.auth.secret}（Client）和 {@code mcp.auth.secret}（Server），必须一致。</p>
 *
 * <p>返回结构化的 {@link AuthResult}，而不是一段文案——业务代码可以直接
 * {@code if (!result.valid())} 拒绝执行；{@link AuthResult#message()} 只是顺带生成的中文描述，
 * 方便演示时直接回显给模型看。本类不抛异常，任何失败都体现为 {@code valid == false}。</p>
 */
@Component
public class McpAuthVerifier {

    private static final Logger log = LoggerFactory.getLogger(McpAuthVerifier.class);

    /**
     * 与 lion-agent 的 {@code McpAuthSupport.BEARER_PREFIX} 保持一致。
     * 独立项目互相引用不到对方的常量，只能各写一份——改动时务必同步，
     * 否则会出现签名正确却被判为格式错误的情况。
     */
    public static final String BEARER_PREFIX = "Bearer ";

    /** 客户端凭据在 {@code _meta} 中的 key，由 lion-agent 的 ToolContextToMcpMetaConverter 写入 */
    public static final String AUTH_META_KEY = "auth-token";

    /** 签名密钥的最小长度，低于此值启动时告警 */
    private static final int MIN_SECRET_LENGTH = 16;

    /**
     * application.yml 里的默认密钥，两处需同步。
     * 它长度够（25 位）却最危险——是写在文档里的公开值，用它等于没有签名。
     */
    private static final String DEFAULT_SECRET = "please-change-this-secret";

    private final byte[] secret;

    public McpAuthVerifier(@Value("${mcp.auth.secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        boolean isDefault = DEFAULT_SECRET.equals(secret);
        if (isDefault || secret.length() < MIN_SECRET_LENGTH) {
            // 只描述问题，不打印密钥本身
            log.warn("[MCP 凭据] 签名密钥不安全（{}），"
                            + "生产环境请用环境变量 LION_MCP_AUTH_SECRET 设置足够长的随机密钥",
                    isDefault ? "仍在使用 yml 里的默认值，等同于公开"
                            : "长度不足，建议不少于 " + MIN_SECRET_LENGTH + " 字符");
        }
    }

    /**
     * 校验客户端传来的 Authorization 值（{@code "Bearer <jwt>"}）。
     *
     * @param authHeader {@code _meta} 里的原始值，可能为 null
     * @return 校验结果，永不返回 null、不抛异常
     */
    public AuthResult verify(Object authHeader) {
        if (authHeader == null) {
            return AuthResult.fail("缺失（客户端未传凭据）");
        }
        String header = String.valueOf(authHeader);
        if (!header.startsWith(BEARER_PREFIX)) {
            return AuthResult.fail("格式错误（应为 '" + BEARER_PREFIX + "<jwt>'）");
        }
        try {
            JWT jwt = JWT.of(header.substring(BEARER_PREFIX.length())).setKey(secret);
            if (!jwt.validate(0)) {
                return AuthResult.fail("无效（签名不匹配或已过期）");
            }
            Long uid = toLong(jwt.getPayload("uid"));
            String issuer = String.valueOf(jwt.getPayload("iss"));
            Long expiresAt = toLong(jwt.getPayload("exp"));
            return new AuthResult(true, uid, issuer, expiresAt,
                    "有效，用户ID=" + uid + "，签发方=" + issuer + "，过期时间=" + expiresAt);
        } catch (Exception e) {
            return AuthResult.fail("无效（解析失败：" + e.getMessage() + "）");
        }
    }

    /** JWT payload 里数值字段的实际类型可能是 Integer 或 Long，统一转成 Long */
    private static Long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    /**
     * 凭据校验结果。
     *
     * @param valid     是否通过（签名正确且未过期）
     * @param uid       签名内的用户 ID——<b>可信</b>。业务鉴权应以它为准，
     *                  不要用 {@code _meta} 里的明文 user-id：那个字段客户端可以随便填
     * @param issuer    签发方
     * @param expiresAt 过期时间（秒级时间戳）
     * @param message   中文描述，可直接回显给模型
     */
    public record AuthResult(boolean valid, Long uid, String issuer, Long expiresAt, String message) {

        static AuthResult fail(String message) {
            return new AuthResult(false, null, null, null, message);
        }
    }
}
