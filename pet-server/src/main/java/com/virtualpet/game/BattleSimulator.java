package com.virtualpet.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 确定性自动战斗（PRD 2.11）。
 *
 * <p><b>同一个种子 + 同一对快照，永远产出逐字节相同的战报。</b>这是整个模块的地基：
 * 战报要落库存档，前端要照着回放，测试要能复现，任何一条都要求它不能有真随机。</p>
 *
 * <p>随机源用的是 {@link Random}（{@code new Random(seed)}）。它虽然是伪随机，
 * 但算法在 Javadoc 里被写死成线性同余，<b>跨 JVM、跨平台、跨版本都逐位一致</b> ——
 * 这正是这里需要它、而不是需要"更好的随机"的原因。{@code ThreadLocalRandom}
 * 或者 {@code SecureRandom} 反而做不到这一点。</p>
 *
 * <p>模拟器是纯函数：不读数据库、不看时钟、不碰 Spring。两份快照进去，一份战报出来。</p>
 */
public final class BattleSimulator {

    private BattleSimulator() {
    }

    /**
     * 跑完一整场战斗。
     *
     * @param challenger 挑战方快照
     * @param defender   被挑战方快照
     * @param seed       固定随机种子，存档后可用于复现整场战斗
     * @return 逐回合的战报
     */
    public static BattleReport simulate(BattleSnapshot challenger, BattleSnapshot defender, long seed) {
        Random random = new Random(seed);

        int challengerMaxHp = challenger.maxHp();
        int defenderMaxHp = defender.maxHp();
        int challengerHp = challenger.startingHp();
        int defenderHp = defender.startingHp();

        List<BattleReport.BattleRound> timeline = new ArrayList<>();
        // 速度快的一方先手；相同则挑战者先手，保证同一对快照的出手顺序也是确定的
        BattleSide first = challenger.speed() >= defender.speed()
                ? BattleSide.CHALLENGER
                : BattleSide.DEFENDER;

        BattleSide winner = null;
        BattleReport.Outcome outcome = null;

        for (int round = 1; round <= BattleRules.MAX_ROUNDS; round += 1) {
            List<BattleReport.BattleEvent> events = new ArrayList<>();

            for (BattleSide actor : List.of(first, first.opponent())) {
                BattleSnapshot attacker = actor == BattleSide.CHALLENGER ? challenger : defender;
                BattleSnapshot target = actor == BattleSide.CHALLENGER ? defender : challenger;

                // 判的是**自己**还能不能动，不是对手还活着没有。
                // 写成看对手血量的话，先手方把对方打死了，对方还能回手一刀 ——
                // 实测这会让"后手"白赚约 25 个百分点的胜率，被挑战者因此全面占优。
                int actorHp = actor == BattleSide.CHALLENGER ? challengerHp : defenderHp;
                if (actorHp <= 0) {
                    break;
                }

                boolean crit = random.nextInt(100) < BattleRules.CRIT_CHANCE_PERCENT;
                int damage = rollDamage(random, attacker, target, crit);

                if (actor == BattleSide.CHALLENGER) {
                    defenderHp = Math.max(0, defenderHp - damage);
                } else {
                    challengerHp = Math.max(0, challengerHp - damage);
                }

                events.add(new BattleReport.BattleEvent(
                        actor,
                        BattleReport.BattleEvent.Type.ATTACK,
                        damage,
                        crit,
                        challengerHp,
                        defenderHp));

                // 速度优势换来一次追加攻击。判定放在普攻之后、伤害结算完之后，
                // 所以"这一下会不会补刀"取决于对手当前还剩多少血。
                int remaining = actor == BattleSide.CHALLENGER ? defenderHp : challengerHp;
                if (remaining > 0
                        && random.nextInt(100) < BattleRules.extraStrikeChance(attacker.speed(), target.speed())) {
                    boolean extraCrit = random.nextInt(100) < BattleRules.CRIT_CHANCE_PERCENT;
                    int extraDamage = rollDamage(random, attacker, target, extraCrit);
                    if (actor == BattleSide.CHALLENGER) {
                        defenderHp = Math.max(0, defenderHp - extraDamage);
                    } else {
                        challengerHp = Math.max(0, challengerHp - extraDamage);
                    }
                    events.add(new BattleReport.BattleEvent(
                            actor,
                            BattleReport.BattleEvent.Type.ATTACK,
                            extraDamage,
                            extraCrit,
                            challengerHp,
                            defenderHp));
                }
            }

            // 回合结束的恢复，放在双方都动完之后。
            // 两边都要过一遍（两只狗对战就都会回血），固定「先挑战者后被挑战者」的顺序。
            for (BattleSide side : List.of(BattleSide.CHALLENGER, BattleSide.DEFENDER)) {
                int current = side == BattleSide.CHALLENGER ? challengerHp : defenderHp;
                if (current <= 0) {
                    continue; // 已经倒下的不再回血，免得出现"打死了又站起来"
                }
                BattleSnapshot self = side == BattleSide.CHALLENGER ? challenger : defender;
                int healed = Math.min(self.regenPerRound(), self.maxHp() - current);
                if (healed <= 0) {
                    continue;
                }
                if (side == BattleSide.CHALLENGER) {
                    challengerHp = current + healed;
                } else {
                    defenderHp = current + healed;
                }
                events.add(new BattleReport.BattleEvent(
                        side,
                        BattleReport.BattleEvent.Type.HEAL,
                        healed,
                        false,
                        challengerHp,
                        defenderHp));
            }

            timeline.add(new BattleReport.BattleRound(round, events));

            if (challengerHp <= 0 || defenderHp <= 0) {
                winner = challengerHp <= 0 ? BattleSide.DEFENDER : BattleSide.CHALLENGER;
                outcome = BattleReport.Outcome.KO;
                break;
            }
        }

        if (winner == null) {
            // 打满回合还没分出胜负：按剩余生命比例判，比例也一样就算平局
            winner = judgeByRemainingHp(challengerHp, challengerMaxHp, defenderHp, defenderMaxHp);
            outcome = winner == null ? BattleReport.Outcome.DRAW : BattleReport.Outcome.TIMEOUT;
        }

        return new BattleReport(
                seed,
                winner,
                outcome,
                timeline.size(),
                challengerMaxHp,
                defenderMaxHp,
                List.copyOf(timeline));
    }

    /**
     * 掷一次伤害。
     *
     * <p>随机数消耗的顺序是固定的（先暴击判定、后浮动），所以只要种子一样，
     * 第几次取数、取到什么都完全可复现。改动取数顺序会让所有历史战报的复现失效。</p>
     */
    private static int rollDamage(Random random, BattleSnapshot attacker, BattleSnapshot target, boolean crit) {
        int base = BattleRules.baseDamage(attacker.attack(), target.defense());

        // 在 [-variance, +variance] 区间内浮动
        int span = BattleRules.DAMAGE_VARIANCE_PERCENT;
        int offset = random.nextInt(span * 2 + 1) - span;
        int damage = base * (100 + offset) / 100;

        if (crit) {
            damage = damage * BattleRules.CRIT_MULTIPLIER_PERCENT / 100;
        }
        return Math.max(BattleRules.MIN_DAMAGE, damage);
    }

    /**
     * 打满回合时按剩余生命比例判定。
     *
     * @return 胜方；两边比例完全相同时返回 {@code null}（平局）
     */
    private static BattleSide judgeByRemainingHp(int challengerHp, int challengerMaxHp,
                                                 int defenderHp, int defenderMaxHp) {
        // 交叉相乘比较，避免浮点误差破坏确定性
        long challengerScore = (long) challengerHp * defenderMaxHp;
        long defenderScore = (long) defenderHp * challengerMaxHp;

        if (challengerScore == defenderScore) {
            return null;
        }
        return challengerScore > defenderScore ? BattleSide.CHALLENGER : BattleSide.DEFENDER;
    }
}
