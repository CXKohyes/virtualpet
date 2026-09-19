package com.virtualpet.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确定性自动战斗的单元测试（PRD 2.11）。
 *
 * <p>这里的头等大事是<b>可复现</b>：同样的快照加同样的种子必须产出逐字节相同的战报。
 * 战报要落库存档、要能被前端回放、要能在排查问题时重跑，任何一条都建立在这一点上。</p>
 */
class BattleSimulatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static BattleSnapshot pet(String name, Species species, int level, int stage, int health) {
        return new BattleSnapshot(
                1L,
                name,
                species,
                level,
                stage,
                new PetAttributes(80, 80, 80, 80, health));
    }

    private static BattleSnapshot cat(int level, int stage) {
        return pet("咪咪", Species.CAT, level, stage, 100);
    }

    private static BattleSnapshot dog(int level, int stage) {
        return pet("旺财", Species.DOG, level, stage, 100);
    }

    private static BattleSnapshot dragon(int level, int stage) {
        return pet("小蓝", Species.DRAGON, level, stage, 100);
    }

    @Nested
    @DisplayName("确定性")
    class Determinism {

        @Test
        @DisplayName("同一对快照 + 同一个种子 -> 连跑 20 次战报完全相同")
        void sameSeedProducesIdenticalReports() throws Exception {
            BattleSnapshot challenger = dragon(5, 1);
            BattleSnapshot defender = cat(5, 1);

            String expected = MAPPER.writeValueAsString(BattleSimulator.simulate(challenger, defender, 20260917L));

            for (int i = 0; i < 20; i += 1) {
                String again = MAPPER.writeValueAsString(BattleSimulator.simulate(challenger, defender, 20260917L));
                assertThat(again).as("第 %d 次重跑", i + 1).isEqualTo(expected);
            }
        }

        @Test
        @DisplayName("不同种子会打出不同的战报（否则种子就是摆设）")
        void differentSeedsProduceDifferentReports() throws Exception {
            BattleSnapshot challenger = dragon(5, 1);
            BattleSnapshot defender = cat(5, 1);

            Set<String> reports = IntStream.range(0, 30)
                    .mapToObj(seed -> {
                        try {
                            return MAPPER.writeValueAsString(BattleSimulator.simulate(challenger, defender, seed));
                        } catch (Exception cause) {
                            throw new IllegalStateException(cause);
                        }
                    })
                    .collect(Collectors.toSet());

            // 30 个种子至少打出 10 种不同的过程，说明随机确实在起作用
            assertThat(reports).hasSizeGreaterThan(10);
        }

        @Test
        @DisplayName("双方互换位置后，种子相同也不该是同一场（先手方变了）")
        void swappingSidesChangesTheBattle() throws Exception {
            BattleSnapshot cat = cat(5, 1);
            BattleSnapshot dragon = dragon(5, 1);

            String forward = MAPPER.writeValueAsString(BattleSimulator.simulate(cat, dragon, 7L));
            String backward = MAPPER.writeValueAsString(BattleSimulator.simulate(dragon, cat, 7L));

            assertThat(forward).isNotEqualTo(backward);
        }

        @Test
        @DisplayName("随机数取用顺序固定：逐回合画出来的血量和战报记录的一致")
        void hpInTimelineIsConsistent() {
            BattleReport report = BattleSimulator.simulate(dragon(6, 2), dog(6, 2), 42L);

            int challengerHp = dragon(6, 2).startingHp();
            int defenderHp = dog(6, 2).startingHp();

            for (BattleReport.BattleRound round : report.timeline()) {
                for (BattleReport.BattleEvent event : round.events()) {
                    boolean actorIsChallenger = event.actor() == BattleSide.CHALLENGER;
                    if (event.type() == BattleReport.BattleEvent.Type.ATTACK) {
                        if (actorIsChallenger) {
                            defenderHp = Math.max(0, defenderHp - event.value());
                        } else {
                            challengerHp = Math.max(0, challengerHp - event.value());
                        }
                    } else {
                        if (actorIsChallenger) {
                            challengerHp += event.value();
                        } else {
                            defenderHp += event.value();
                        }
                    }
                    // 事件里带的是"做完之后"的血量，照着回放必须能对上
                    assertThat(event.challengerHpAfter()).isEqualTo(challengerHp);
                    assertThat(event.defenderHpAfter()).isEqualTo(defenderHp);
                }
            }
        }
    }

    @Nested
    @DisplayName("战斗过程")
    class Fight {

        @Test
        @DisplayName("战斗一定会终止，且不会超过回合上限")
        void battleAlwaysTerminates() {
            for (int seed = 0; seed < 50; seed += 1) {
                BattleReport report = BattleSimulator.simulate(cat(1, 0), dragon(1, 0), seed);
                assertThat(report.totalRounds()).isBetween(1, BattleRules.MAX_ROUNDS);
            }
        }

        @Test
        @DisplayName("同等级同阶段下双方势均力敌，胜负不该一边倒")
        void evenlyMatchedPetsBothWinSometimes() {
            int challengerWins = 0;
            int total = 200;
            for (int seed = 0; seed < total; seed += 1) {
                BattleReport report = BattleSimulator.simulate(cat(5, 1), dragon(5, 1), seed);
                if (report.winner() == BattleSide.CHALLENGER) {
                    challengerWins += 1;
                }
            }

            // 猫靠速度、龙靠攻击，两个方向应该都能赢；留出宽裕的区间，只挡住"完全打不过"
            assertThat(challengerWins).isBetween(total / 5, total * 4 / 5);
        }

        @Test
        @DisplayName("等级差得远时高等级一方几乎必胜")
        void higherLevelUsuallyWins() {
            int highLevelWins = 0;
            int total = 50;
            for (int seed = 0; seed < total; seed += 1) {
                // 10 级最终形态打 1 级幼年
                BattleReport report = BattleSimulator.simulate(dragon(10, 2), cat(1, 0), seed);
                if (report.winner() == BattleSide.CHALLENGER) {
                    highLevelWins += 1;
                }
            }

            assertThat(highLevelWins).isEqualTo(total);
        }

        @Test
        @DisplayName("生命归零才判 KO，且战报里不会出现负血量")
        void knockoutEndsTheBattle() {
            BattleReport report = BattleSimulator.simulate(dragon(10, 2), cat(1, 0), 1L);

            assertThat(report.outcome()).isEqualTo(BattleReport.Outcome.KO);
            assertThat(report.winner()).isEqualTo(BattleSide.CHALLENGER);
            assertThat(report.defenderHpAfter()).isZero();

            report.timeline().forEach(round -> round.events().forEach(event -> {
                assertThat(event.challengerHpAfter()).isNotNegative();
                assertThat(event.defenderHpAfter()).isNotNegative();
            }));
        }

        @Test
        @DisplayName("健康折算成开场生命：生病的宠物上场就是残血")
        void healthScalesStartingHp() {
            BattleSnapshot healthy = pet("满血", Species.CAT, 5, 1, 100);
            BattleSnapshot sick = pet("病号", Species.CAT, 5, 1, 20);

            assertThat(sick.startingHp()).isEqualTo(healthy.startingHp() * 20 / 100);
            assertThat(sick.startingHp()).isLessThan(healthy.startingHp());
        }

        @Test
        @DisplayName("健康为 0 也至少留 1 点生命，不会开场即败")
        void zeroHealthStillFights() {
            BattleSnapshot dying = pet("奄奄一息", Species.CAT, 3, 0, 0);

            assertThat(dying.startingHp()).isEqualTo(1);
            assertThat(BattleSimulator.simulate(dying, cat(3, 0), 1L).totalRounds()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("速度决定先手，速度相同时挑战者先动")
        void fasterSideActsFirst() {
            // 猫的速度加成最高，同等级下应当先手
            assertThat(BattleRules.speed(5, Species.CAT))
                    .isGreaterThan(BattleRules.speed(5, Species.DRAGON));

            BattleReport report = BattleSimulator.simulate(cat(5, 1), dragon(5, 1), 3L);
            assertThat(report.timeline().get(0).events().get(0).actor()).isEqualTo(BattleSide.CHALLENGER);

            // 反过来挑战，先手方跟着换
            BattleReport reversed = BattleSimulator.simulate(dragon(5, 1), cat(5, 1), 3L);
            assertThat(reversed.timeline().get(0).events().get(0).actor()).isEqualTo(BattleSide.DEFENDER);
        }
    }

    @Nested
    @DisplayName("物种倾向")
    class SpeciesTendency {

        @Test
        @DisplayName("猫偏速度：速度最高，攻击和生命不占优")
        void catIsFastest() {
            assertThat(BattleRules.speed(5, Species.CAT)).isGreaterThan(BattleRules.speed(5, Species.DOG));
            assertThat(BattleRules.speed(5, Species.CAT)).isGreaterThan(BattleRules.speed(5, Species.DRAGON));
        }

        @Test
        @DisplayName("龙偏攻击：攻击力最高")
        void dragonHitsHardest() {
            assertThat(BattleRules.attack(5, 1, Species.DRAGON)).isGreaterThan(BattleRules.attack(5, 1, Species.CAT));
            assertThat(BattleRules.attack(5, 1, Species.DRAGON)).isGreaterThan(BattleRules.attack(5, 1, Species.DOG));
        }

        @Test
        @DisplayName("狗偏均衡和恢复：三项都不垫底，而且是唯一会回血的")
        void dogIsBalancedWithRecovery() {
            assertThat(BattleRules.regenPerRound(BattleRules.maxHp(5, 1, Species.DOG), 5, Species.DOG)).isPositive();
            assertThat(BattleRules.regenPerRound(BattleRules.maxHp(5, 1, Species.CAT), 5, Species.CAT)).isZero();
            assertThat(BattleRules.regenPerRound(BattleRules.maxHp(5, 1, Species.DRAGON), 5, Species.DRAGON)).isZero();

            // 注意：狗的生命<b>不是</b>最高的 —— 龙的生命加成更高，只是没有回血。
            // 狗的定位是"哪一项都不垫底 + 唯一会回血"，不是"血最厚"。
            assertThat(BattleRules.maxHp(5, 1, Species.DOG)).isGreaterThan(BattleRules.maxHp(5, 1, Species.CAT));
            assertThat(BattleRules.defense(5, 1, Species.DOG)).isGreaterThan(BattleRules.defense(5, 1, Species.CAT));
            assertThat(BattleRules.defense(5, 1, Species.DOG)).isGreaterThan(BattleRules.defense(5, 1, Species.DRAGON));
            assertThat(BattleRules.attack(5, 1, Species.DOG)).isGreaterThan(0);
        }

        @Test
        @DisplayName("狗真的会在战报里回血")
        void dogHealsDuringBattle() {
            BattleReport report = BattleSimulator.simulate(dragon(4, 1), dog(4, 1), 11L);

            boolean healed = report.timeline().stream()
                    .flatMap(round -> round.events().stream())
                    .anyMatch(event -> event.type() == BattleReport.BattleEvent.Type.HEAL
                            && event.actor() == BattleSide.DEFENDER);

            assertThat(healed).as("狗每回合回血，战报里应当看得到").isTrue();
        }

        /** 跑 200 场，返回先手方的胜率（百分比）。 */
        private int winRate(Species attacker, Species defender, int level, int stage) {
            int wins = 0;
            int total = 200;
            for (int seed = 0; seed < total; seed += 1) {
                BattleReport report = BattleSimulator.simulate(
                        pet("A", attacker, level, stage, 100), pet("B", defender, level, stage, 100), seed);
                if (report.winner() == BattleSide.CHALLENGER) {
                    wins += 1;
                }
            }
            return wins * 100 / total;
        }

        @Test
        @DisplayName("所有物种在同一个等级下互有胜负，没有碾压一切的那个")
        void everySpeciesWinsSomeMatchups() {
            // 从枚举派生而不是写字面量：写字面量的话，新加物种时这条测试会**照常通过**，
            // 只是悄悄把新物种漏掉 —— 那比失败更糟。
            List<Species> all = List.of(Species.values());

            for (Species attacker : all) {
                for (Species defender : all) {
                    if (attacker == defender) {
                        continue;
                    }
                    assertThat(winRate(attacker, defender, 5, 1))
                            .as("%s 打 %s", attacker, defender)
                            .isBetween(20, 80);
                }
            }
        }

        /**
         * 中期（5 级成长形态）是玩家停留最久的一段，平衡要求也最高。
         *
         * <p>数值是调参时实测出来的，不是拍脑袋定的。写进测试是为了让后续任何一次
         * 改数值都会在这里被拦下来 —— 战斗平衡靠肉眼看公式是看不出来的，
         * 上面那几轮"改一个常数、跑两百场"才是唯一靠谱的办法。</p>
         */
        @Test
        @DisplayName("中期所有物种两两之间都在 40–60%，没有明显强弱")
        void midGameIsBalanced() {
            // 同样是枚举派生：物种从 3 个加到 4 个，配对从 3 对变成 6 对，
            // 硬编码的三行不会失败，只会漏掉新增的 3 对。
            Species[] all = Species.values();
            for (int i = 0; i < all.length; i++) {
                for (int j = i + 1; j < all.length; j++) {
                    assertThat(winRate(all[i], all[j], 5, 1))
                            .as("中期 %s 打 %s", all[i], all[j])
                            .isBetween(40, 60);
                }
            }
        }

        @Test
        @DisplayName("同一个对局换边打，胜率互补（不存在位置优势）")
        void noPositionalAdvantage() {
            int forward = winRate(Species.CAT, Species.DOG, 5, 1);
            int backward = winRate(Species.DOG, Species.CAT, 5, 1);

            assertThat(forward + backward)
                    .as("先手方和后手方的胜率应当加起来约等于 100%，差得多说明有隐藏的先手/后手优势")
                    .isCloseTo(100, org.assertj.core.data.Offset.offset(10));
        }
    }
}
