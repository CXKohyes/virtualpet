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

    /** 猫：灵巧、爱玩。玩耍心情收益 +25%，清洁衰减 -25%。对战偏速度。 */
    CAT(new Modifier(1.25, 1.00, 1.00, 0.75, 1.00, 0), new BattleTendency(0, 1, 1, 6, 0)),

    /** 狗：均衡、亲人。所有正向照护收益 +10%，健康恢复额外 +1/小时。对战均衡且有恢复。 */
    DOG(new Modifier(1.00, 1.10, 1.00, 1.00, 1.00, 1), new BattleTendency(4, 1, 2, 1, 4)),

    /** 龙：贪吃、强壮。喂食饱食收益 +15%，精力衰减 -25%。对战偏攻击。 */
    DRAGON(new Modifier(1.00, 1.00, 1.15, 1.00, 0.75, 0), new BattleTendency(6, 5, 0, 0, 0)),

    /**
     * 兔子：爱干净、好动。玩耍心情收益 +15%，清洁衰减 -30%。对战偏速度。
     *
     * <p>兔子的两个照护修正<b>复用了猫的字段</b>（{@code playMoodBonus}、
     * {@code hygieneDecayScale}），只是强度不同：比猫更耐脏（-30% 对 -25%），
     * 但没那么爱玩（+15% 对 +25%）。{@link Modifier} 的六个字段已被前三个物种占满，
     * 第 4 个物种拿不到独占字段 —— 这是结构性的，不是疏漏。要真正区分开，
     * 得给 {@code Modifier} 加新字段，那是另一个改动。</p>
     *
     * <p>对战上兔子是<b>全物种速度最快的</b>（+7，猫是 +6），但攻击不加成：
     * 靠先手多打一轮，而不是靠单次伤害。生命也不加成，只有一层厚毛（防御 +1）。
     * 这组数值是实测调出来的 —— 最初给的是「猫的加强版」（生命 +2、速度 +7），
     * 结果把猫压到 26% 胜率。速度在战斗里的权重远高于生命和攻击，
     * 所以兔子的速度要最高，代价就必须从攻击上扣。</p>
     */
    RABBIT(new Modifier(1.15, 1.00, 1.00, 0.70, 1.00, 0), new BattleTendency(0, 0, 1, 7, 0));

    private final Modifier modifier;
    private final BattleTendency battle;

    Species(Modifier modifier, BattleTendency battle) {
        this.modifier = modifier;
        this.battle = battle;
    }

    public Modifier modifier() {
        return modifier;
    }

    /**
     * 对战倾向（PRD 2.11「物种特性影响速度、攻击、防御或恢复倾向」）。
     *
     * <p>和 {@link Modifier} 分开而不是塞进同一个 record：那个是<b>照护</b>修正表，
     * 这个是<b>战斗</b>修正表，两者的消费方（结算服务 / 战斗模拟器）毫无关系，
     * 混在一起只会让每次读代码都要先分辨哪个字段属于哪一边。</p>
     */
    public BattleTendency battle() {
        return battle;
    }

    /**
     * 战斗中的物种倾向。
     *
     * <p>四个物种各占一个位置，没有谁全面更强：猫靠先手多打一轮，狗靠回血拖持久战，
     * 龙靠单次伤害高，兔子靠速度压过猫。具体数值由 {@link BattleRules} 换算成实际属性，
     * 是否真的互不吃亏由 {@code BattleSimulatorTest} 的两两对战钉住。</p>
     *
     * @param hpBonus      生命加成
     * @param attackBonus  攻击加成
     * @param defenseBonus 防御加成
     * @param speedBonus   速度加成（决定每回合谁先动手，以及额外出手的概率）
     * @param regenPercent 每回合恢复的生命占最大生命的百分比，狗的特色
     */
    public record BattleTendency(
            int hpBonus,
            int attackBonus,
            int defenseBonus,
            int speedBonus,
            int regenPercent) {
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
