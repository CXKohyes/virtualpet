package com.virtualpet.battle;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发起挑战（PRD 2.11）。
 *
 * @param friendCode 对方的好友码，大小写不敏感
 */
public record ChallengeRequest(
        @NotBlank(message = "缺少好友码")
        @Size(max = 12, message = "好友码格式不对") String friendCode) {
}
