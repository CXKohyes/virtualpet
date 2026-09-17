package com.virtualpet.pet;

import java.time.Instant;

/**
 * 照护日志的一条记录（PRD 4.2 日志区）。
 *
 * <p>数据来自 {@code pet_action_logs}，所以刷新页面、关掉浏览器之后仍然在。</p>
 *
 * @param id         日志 ID
 * @param action     操作名
 * @param at         操作发生时间（UTC）
 * @param deltas     这次操作本身的属性变化，不含离线衰减
 * @param xpGained   获得的经验
 * @param levelUp    是否升级
 * @param evolved    是否进化
 * @param messageKey 前端用来选台词的键
 */
public record JournalEntryResponse(
        Long id,
        String action,
        Instant at,
        AttributeDeltas deltas,
        int xpGained,
        boolean levelUp,
        boolean evolved,
        String messageKey) {
}
