package com.virtualpet.pet;

import java.time.Instant;
import java.util.Map;

/**
 * 宠物的对外表示（TECH_DESIGN 5.3）。
 *
 * <p>时间一律是 UTC 的 {@link Instant}，由 Jackson 序列化成 ISO-8601，
 * 前端负责转换成本地时区显示。</p>
 *
 * @param slot 槽位号，同一玩家的宠物按它升序排列
 * @param active 是不是玩家当前的宠物。{@code /pets/me} 返回的那只恒为 {@code true}；
 *              名册 {@code GET /pets} 里只有一只是 {@code true}
 * @param cooldowns 仍在冷却中的操作 -> 冷却结束时刻，只包含还没结束的项
 * @param settlement 这次读取结算出来的变化摘要（PRD 2.5）；时间没有前进时为 {@code null}
 */
public record PetResponse(
        Long id,
        Integer slot,
        boolean active,
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
        Map<String, Instant> cooldowns,
        SettlementSummary settlement) {
}
