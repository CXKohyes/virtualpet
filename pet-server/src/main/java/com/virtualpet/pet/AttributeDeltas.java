package com.virtualpet.pet;

/**
 * 单次操作的属性变化量（TECH_DESIGN 5.4 的 {@code deltas}）。
 *
 * <p>只表示<b>操作本身</b>的效果（已叠加物种修正），不含离线衰减。宠物完整状态看响应里的
 * {@code pet} 字段。</p>
 */
public record AttributeDeltas(int satiety, int mood, int hygiene, int energy, int health) {

    public static final AttributeDeltas NONE = new AttributeDeltas(0, 0, 0, 0, 0);
}
