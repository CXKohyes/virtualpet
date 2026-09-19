package com.virtualpet.pet;

import jakarta.validation.constraints.NotNull;

/**
 * 切换当前宠物请求（PRD 2.1，多宠物槽）。
 *
 * <p>只收一个 ID，归属判定全在服务端 —— 客户端不需要、也不应该自己去判断
 * 这个 ID 是不是自己的宠物。传别人的 ID 得到的是 404 {@code PET_NOT_FOUND}，
 * 和传一个不存在的 ID 无法区分（见 {@link PetService#requireOwnedPet}）。</p>
 */
public record ActivatePetRequest(
        @NotNull(message = "请指定要切换的宠物") Long petId) {
}
