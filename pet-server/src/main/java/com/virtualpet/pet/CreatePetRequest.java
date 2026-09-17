package com.virtualpet.pet;

import com.virtualpet.game.Species;
import jakarta.validation.constraints.NotNull;

/**
 * 创建宠物请求（TECH_DESIGN 5.2）。
 *
 * <p>{@code name} 的长度和字符校验在 Service 里做，以便返回精确的
 * {@code INVALID_NAME} 错误码而不是笼统的参数错误。</p>
 */
public record CreatePetRequest(
        @NotNull(message = "请选择宠物种类") Species species,
        String name) {
}
