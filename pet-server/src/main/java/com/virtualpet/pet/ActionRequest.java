package com.virtualpet.pet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 执行操作请求（TECH_DESIGN 5.4）。
 *
 * <p>{@code action} 故意用 {@code String} 而不是枚举、也不加 {@code @NotBlank}：
 * 这样空值和非法值都会走到 Service，统一返回精确的 {@code INVALID_ACTION} 错误码，
 * 而不是被 Jackson 或 Bean Validation 拦成笼统的参数错误。</p>
 */
public record ActionRequest(
        String action,
        @NotBlank(message = "缺少 clientRequestId")
        @Size(max = 64, message = "clientRequestId 过长") String clientRequestId) {
}
