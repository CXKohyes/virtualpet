package com.virtualpet.pet;

import java.time.Instant;

/**
 * 操作日志里保存的结果（{@code pet_action_logs.result_json}）。
 *
 * <p>故意<b>不包含宠物状态</b>：宠物状态每次请求都从数据库重新读并结算，存下来只会是过期数据。
 * 重复请求命中幂等时，用这里的结果配上当前宠物拼出完整响应。</p>
 */
public record ActionOutcome(
        AttributeDeltas deltas,
        int xpGained,
        boolean levelUp,
        boolean evolved,
        String messageKey,
        Instant cooldownUntil) {
}
