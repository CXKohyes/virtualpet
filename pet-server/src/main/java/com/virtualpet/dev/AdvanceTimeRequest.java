package com.virtualpet.dev;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 开发用时间推进请求（TECH_DESIGN 5.7）。
 *
 * @param hours 向前推进的小时数，范围 1–240（10 天），够验证 12 小时封顶和连续进化
 */
public record AdvanceTimeRequest(
        @NotNull(message = "缺少 hours")
        @Min(value = 1, message = "hours 至少为 1")
        @Max(value = 240, message = "hours 最多为 240") Integer hours) {
}
