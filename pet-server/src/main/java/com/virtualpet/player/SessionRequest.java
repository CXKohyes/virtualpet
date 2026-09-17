package com.virtualpet.player;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 建立匿名会话请求（TECH_DESIGN 5.1）。
 *
 * <p>{@code deviceId} 由浏览器生成并保存在本地，没有它就无法找回存档。</p>
 */
public record SessionRequest(
        @NotBlank(message = "缺少 deviceId")
        @Size(max = 64, message = "deviceId 过长") String deviceId) {
}
