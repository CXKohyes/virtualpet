package com.virtualpet.pet;

import com.virtualpet.game.PetAttributes;

/**
 * 一次懒结算的结果，包含变化摘要（PRD 2.5：用于回访弹窗和操作日志）。
 *
 * @param state        结算后的宠物状态
 * @param settledHours 本次实际结算的小时数，0 表示没有结算
 * @param wokeUp       本次结算中是否从睡觉中醒来
 * @param sleptHours   该次睡觉累计已睡的小时数（跨越多次结算会继续累加），不在睡觉时为 0
 * @param before       结算前的属性
 * @param after        结算后的属性
 */
public record SettlementResult(
        PetState state,
        long settledHours,
        boolean wokeUp,
        long sleptHours,
        PetAttributes before,
        PetAttributes after) {

    /** 未发生结算（时间不足一小时，或时间倒流）时返回原状态。 */
    public static SettlementResult unchanged(PetState state) {
        return new SettlementResult(state, 0, false, 0, state.attributes(), state.attributes());
    }

    /** 本次是否真的结算了时间。 */
    public boolean changed() {
        return settledHours > 0;
    }
}
