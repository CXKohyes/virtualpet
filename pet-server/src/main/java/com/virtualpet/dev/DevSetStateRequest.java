package com.virtualpet.dev;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 开发用状态铺设请求（批次 5 验收）。
 *
 * <p>每一项都可以不传，不传就保持原值。所有字段都按真实规则钳制到 0–100，
 * 经验上限放宽到 10000 只是为了能一次推到 10 级（阈值 720）。</p>
 *
 * <p><b>为什么需要它</b>：升级到 8 级要 600 点经验，而每次照护只有 6–8 点、
 * 还带 60 秒冷却 —— 正常节奏按 PRD 2.7 是"第 16 天到 8 级"。
 * 健康恢复同样要求四项属性连续多小时高于 60。这两条验收路径靠真实操作
 * 要几十分钟，只能提供铺设状态的工具。</p>
 *
 * @param satiety 饱食 0–100
 * @param mood    心情 0–100
 * @param hygiene 清洁 0–100
 * @param energy  精力 0–100
 * @param health  健康 0–100
 * @param exp     累计经验 0–10000
 */
public record DevSetStateRequest(
        @Min(value = 0, message = "satiety 不能小于 0")
        @Max(value = 100, message = "satiety 不能大于 100") Integer satiety,

        @Min(value = 0, message = "mood 不能小于 0")
        @Max(value = 100, message = "mood 不能大于 100") Integer mood,

        @Min(value = 0, message = "hygiene 不能小于 0")
        @Max(value = 100, message = "hygiene 不能大于 100") Integer hygiene,

        @Min(value = 0, message = "energy 不能小于 0")
        @Max(value = 100, message = "energy 不能大于 100") Integer energy,

        @Min(value = 0, message = "health 不能小于 0")
        @Max(value = 100, message = "health 不能大于 100") Integer health,

        @Min(value = 0, message = "exp 不能小于 0")
        @Max(value = 10000, message = "exp 不能大于 10000") Integer exp) {
}
