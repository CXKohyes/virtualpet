package com.virtualpet.player;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 好友码生成（PRD 2.11）。
 *
 * <p>几个刻意的选择：</p>
 * <ul>
 *   <li>用 {@link SecureRandom} 而不是 {@code Random}：好友码是"知道就能挑战"的凭据，
 *       可猜的码等于任何人都能随便打别人。</li>
 *   <li>字母表剔掉了 {@code 0/O} 和 {@code 1/I/L}：这是要念给朋友听、
 *       或者在手机上手输的东西，形近字符带来的麻烦远大于多出来的那点熵。</li>
 *   <li>长度 8。32 个字符的 8 次方约 1.1 万亿，撞码概率可以忽略；
 *       真的撞上由数据库唯一索引兜底，服务层重试即可。</li>
 * </ul>
 */
@Component
public class FriendCodeGenerator {

    /** 去掉形近字符后的字母表，共 32 个。 */
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    private static final int LENGTH = 8;

    private final SecureRandom random = new SecureRandom();

    public String next() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i += 1) {
            code.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return code.toString();
    }

    /** 长度，供校验和文档使用。 */
    public static int length() {
        return LENGTH;
    }
}
