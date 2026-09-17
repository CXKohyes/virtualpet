package com.virtualpet.game;

import java.util.List;

/**
 * 战斗战报（PRD 2.11）。
 *
 * <p>完全由 {@link BattleSimulator} 从「两份快照 + 一个种子」算出来，
 * 是纯数据、可直接序列化存档。前端只负责把它画出来，不重算任何数值。</p>
 */
public record BattleReport(
        long seed,
        BattleSide winner,
        Outcome outcome,
        int totalRounds,
        int challengerMaxHp,
        int defenderMaxHp,
        List<BattleRound> timeline) {

    /** 胜负是怎么分的。 */
    public enum Outcome {
        /** 有一方生命归零。 */
        KO,
        /** 打满回合上限，按剩余生命比例判定。 */
        TIMEOUT,
        /** 打满回合且剩余生命比例完全相同。 */
        DRAW
    }

    /** 最后一个回合结束时的双方生命，供列表页直接展示。 */
    public int challengerHpAfter() {
        return lastHp(BattleSide.CHALLENGER);
    }

    public int defenderHpAfter() {
        return lastHp(BattleSide.DEFENDER);
    }

    private int lastHp(BattleSide side) {
        if (timeline.isEmpty()) {
            return side == BattleSide.CHALLENGER ? challengerMaxHp : defenderMaxHp;
        }
        BattleRound last = timeline.get(timeline.size() - 1);
        if (last.events().isEmpty()) {
            return side == BattleSide.CHALLENGER ? challengerMaxHp : defenderMaxHp;
        }
        BattleEvent tail = last.events().get(last.events().size() - 1);
        return side == BattleSide.CHALLENGER ? tail.challengerHpAfter() : tail.defenderHpAfter();
    }

    /** 一个回合。 */
    public record BattleRound(int round, List<BattleEvent> events) {
    }

    /**
     * 回合内的一个动作。
     *
     * <p>两个 HP 字段都是"这个动作做完之后"的值，前端照着画血条就行，
     * 不需要自己按顺序累加。</p>
     */
    public record BattleEvent(
            BattleSide actor,
            Type type,
            int value,
            boolean crit,
            int challengerHpAfter,
            int defenderHpAfter) {

        public enum Type {
            /** 普通攻击，{@code value} 是伤害。 */
            ATTACK,
            /** 回合结束时的恢复，{@code value} 是治疗量。 */
            HEAL
        }
    }
}
