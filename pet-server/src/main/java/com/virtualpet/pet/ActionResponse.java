package com.virtualpet.pet;

import java.time.Instant;

/**
 * 执行操作的响应（TECH_DESIGN 5.4）。
 *
 * @param pet           结算并应用操作后的宠物完整状态
 * @param deltas        操作本身的属性变化，不含离线衰减
 * @param xpGained      本次获得的经验
 * @param levelUp       是否升级
 * @param evolved       是否进化
 * @param messageKey    给前端选台词用的键，例如 {@code FEED_OK}
 * @param cooldownUntil 该操作的冷却结束时刻；无冷却时为 {@code null}
 * @param journalEntry  这次操作刚写进日志的那一条，前端直接拿它更新日志区，
 *                      不用再发一次请求去拉日志列表
 */
public record ActionResponse(
        PetResponse pet,
        AttributeDeltas deltas,
        int xpGained,
        boolean levelUp,
        boolean evolved,
        String messageKey,
        Instant cooldownUntil,
        JournalEntryResponse journalEntry) {
}
