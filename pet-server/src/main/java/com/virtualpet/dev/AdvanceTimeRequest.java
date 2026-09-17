package com.virtualpet.dev;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 开发用时间推进请求（TECH_DESIGN 5.7）。
 *
 * @param hours  向前推进的小时数，范围 1–240（10 天），够验证 12 小时封顶和连续进化
 * @param settle 是否在推进后立即结算。默认 {@code true}，和原来的行为一致。
 *               传 {@code false} 时只把时间游标往前挪、不结算，
 *               这样下一次读取（刷新页面或 {@code GET /pets/me}）才会产生结算，
 *               才能看到「你不在时发生了什么」的回访提示 —— 这个提示恰恰只能由
 *               读取路径产生，推进接口一旦顺手结算掉就再也看不到它了。
 */
public record AdvanceTimeRequest(
        @NotNull(message = "缺少 hours")
        @Min(value = 1, message = "hours 至少为 1")
        @Max(value = 240, message = "hours 最多为 240") Integer hours,
        Boolean settle) {

    public boolean shouldSettle() {
        return settle == null || settle;
    }
}
