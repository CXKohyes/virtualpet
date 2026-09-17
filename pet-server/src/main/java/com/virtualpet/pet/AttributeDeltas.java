package com.virtualpet.pet;

import com.virtualpet.game.PetAttributes;

/**
 * 属性变化量（TECH_DESIGN 5.4 的 {@code deltas}）。
 *
 * <p>用在两处：操作响应里表示<b>操作本身</b>的效果（已叠加物种修正，不含离线衰减），
 * 结算摘要里表示<b>离线期间</b>的净变化。</p>
 */
public record AttributeDeltas(int satiety, int mood, int hygiene, int energy, int health) {

    public static final AttributeDeltas NONE = new AttributeDeltas(0, 0, 0, 0, 0);

    /** 求两次属性快照的差。属性经过钳制，所以得到的是生效后的真实变化。 */
    public static AttributeDeltas between(PetAttributes before, PetAttributes after) {
        return new AttributeDeltas(
                after.satiety() - before.satiety(),
                after.mood() - before.mood(),
                after.hygiene() - before.hygiene(),
                after.energy() - before.energy(),
                after.health() - before.health());
    }
}

