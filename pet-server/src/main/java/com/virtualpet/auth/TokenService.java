package com.virtualpet.auth;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 匿名访问令牌的生成与哈希（TECH_DESIGN 6.6）。
 *
 * <pre>
 * token      = secureRandom(32 bytes) -> URL-safe 字符串
 * token_hash = SHA-256(token) 的十六进制
 * </pre>
 *
 * <p>明文令牌只在创建会话时返回一次，服务端只保存哈希。日志里不得输出令牌。</p>
 */
@Component
public class TokenService {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 生成一个新的明文令牌，长度 43 个字符（32 字节 Base64URL 无填充）。 */
    public String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 计算令牌哈希，长度 64 个十六进制字符。 */
    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            // 所有 JVM 都自带 SHA-256，走到这里说明运行环境不正常
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", exception);
        }
    }
}
