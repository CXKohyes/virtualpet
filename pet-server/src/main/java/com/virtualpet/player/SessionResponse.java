package com.virtualpet.player;

import com.virtualpet.pet.PetResponse;

/**
 * 建立匿名会话的响应（TECH_DESIGN 5.1）。
 *
 * @param playerId 玩家 ID
 * @param token    明文访问令牌，<b>只在这里返回一次</b>，服务端只保存哈希
 * @param pet      已有宠物时返回它，没有领养过则为 {@code null}
 */
public record SessionResponse(Long playerId, String token, PetResponse pet) {
}
