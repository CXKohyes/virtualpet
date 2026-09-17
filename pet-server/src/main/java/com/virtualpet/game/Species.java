package com.virtualpet.game;

/**
 * 宠物物种及其特性修正（PRD 2.6）。
 *
 * <p>修正分两类：影响<b>操作收益</b>的倍率（{@code playMoodBonus}、
 * {@code careGainBonus}、{@code feedSatietyBonus}）由批次 2 的操作服务使用；
 * 影响<b>时间结算</b>的（{@code hygieneDecayScale}、{@code energyDecayScale}、
 * {@code healthRecoveryBonus}）由 {@code PetSettlementService} 使用。</p>
 */
public enum Species {

    /** 猫：灵巧、爱玩。玩耍心情收益 +25%，清洁衰减 -25%。 */
    CAT(new Modifier(1.25, 1.00, 1.00, 0.75, 1.00, 0)),

    /** 狗：均衡、亲人。所有正向照护收益 +10%，健康恢复额外 +1/小时。 */
    DOG(new Modifier(1.00, 1.10, 1.00, 1.00, 1.00, 1)),

    /** 龙：贪吃、强壮。喂食饱食收益 +15%，精力衰减 -25%。 */
    DRAGON(new Modifier(1.00, 1.00, 1.15, 1.00, 0.75, 0));

    private final Modifier modifier;

    Species(Modifier modifier) {
        this.modifier = modifier;
    }

    public Modifier modifier() {
        return modifier;
    }

    /**
     * 物种特性修正表。
     *
     * @param playMoodBonus        玩耍心情收益倍率
     * @param careGainBonus        所有正向照护收益倍率
     * @param feedSatietyBonus     喂食饱食收益倍率
     * @param hygieneDecayScale    清洁衰减倍率
     * @param energyDecayScale     精力衰减倍率
     * @param healthRecoveryBonus  健康恢复的额外值（每小时）
     */
    public record Modifier(
            double playMoodBonus,
            double careGainBonus,
            double feedSatietyBonus,
            double hygieneDecayScale,
            double energyDecayScale,
            int healthRecoveryBonus) {
    }
}
