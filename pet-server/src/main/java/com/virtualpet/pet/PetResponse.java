package com.virtualpet.pet;

import java.time.Instant;
import java.util.Map;

/**
 * 宠物的对外表示（TECH_DESIGN 5.3）。
 *
 * <p>时间一律是 UTC 的 {@link Instant}，由 Jackson 序列化成 ISO-8601，
 * 前端负责转换成本地时区显示。</p>
 *
 * @param cooldowns 仍在冷却中的操作 -> 冷却结束时刻，只包含还没结束的项
 */
public record PetResponse(
        Long id,
        String species,
        String name,
        int satiety,
        int mood,
        int hygiene,
        int energy,
        int health,
        String status,
        int level,
        int exp,
        int evolutionStage,
        Instant sleepingSince,
        Instant lastSettledAt,
        Map<String, Instant> cooldowns) {
}
