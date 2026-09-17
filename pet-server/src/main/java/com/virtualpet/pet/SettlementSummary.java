package com.virtualpet.pet;

/**
 * 一次懒结算的变化摘要（PRD 2.5：「结算结果包含状态变化摘要，用于回访弹窗和日志」）。
 *
 * <p>只在这次读取真的结算了时间（{@code settledHours > 0}）时才返回，
 * 没有经过时间时为 {@code null}。前端据此展示「你不在时发生了什么」。</p>
 *
 * @param settledHours    本次结算的小时数，已按 12 小时封顶
 * @param deltas          五项属性在这次结算里的净变化
 * @param statusBefore    结算前的状态
 * @param statusAfter     结算后的状态
 * @param wokeUp          是否在这次结算中从睡觉里醒来
 * @param sleptHours      该次睡觉累计睡了多久，没在睡时为 0
 */
public record SettlementSummary(
        long settledHours,
        AttributeDeltas deltas,
        String statusBefore,
        String statusAfter,
        boolean wokeUp,
        long sleptHours) {
}
