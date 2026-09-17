package com.virtualpet.pet;

import com.virtualpet.game.PetAttributes;
import com.virtualpet.game.PetStatus;
import com.virtualpet.game.Species;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 懒结算测试（PRD 2.3–2.6、TECH_DESIGN 6.1）。
 *
 * <p>全部使用固定 {@link Clock}，不依赖真实时间。</p>
 */
class PetSettlementServiceTest {

    /** 所有场景的基准时刻。 */
    private static final Instant T = Instant.parse("2026-09-17T00:00:00Z");

    private static PetSettlementService serviceAt(Instant now) {
        return new PetSettlementService(Clock.fixed(now, ZoneOffset.UTC));
    }

    private static PetSettlementService serviceAfter(Duration elapsed) {
        return serviceAt(T.plus(elapsed));
    }

    /** 清醒状态的宠物：时间戳都在 T。 */
    private static PetState awake(PetAttributes attributes, Species species) {
        return new PetState(attributes, species, false, null, T);
    }

    /** 睡觉中的宠物：入睡时刻和游标都是 T（见 PetState 的不变量）。 */
    private static PetState sleeping(PetAttributes attributes, Species species) {
        return new PetState(attributes, species, false, T, T);
    }

    private static PetAttributes attrs(int satiety, int mood, int hygiene, int energy, int health) {
        return new PetAttributes(satiety, mood, hygiene, energy, health);
    }

    // ================================================================== 时间边界

    @Nested
    @DisplayName("时间边界与 12 小时上限")
    class TimeBoundaries {

        @Test
        @DisplayName("不足一小时不结算，游标保持不动")
        void lessThanOneHourIsNotSettled() {
            PetState state = awake(attrs(80, 80, 80, 80, 50), Species.CAT);

            SettlementResult result = serviceAfter(Duration.ofMinutes(59)).settle(state);

            assertThat(result.changed()).isFalse();
            assertThat(result.state()).isEqualTo(state);
            assertThat(result.state().lastSettledAt()).isEqualTo(T);
        }

        @Test
        @DisplayName("频繁刷新不会让属性永不衰减：余数累积满一小时照样结算")
        void subHourRemainderCarriesOver() {
            PetState state = awake(attrs(80, 80, 80, 80, 50), Species.CAT);

            // 第一次 40 分钟：不足一小时，不结算
            SettlementResult first = serviceAfter(Duration.ofMinutes(40)).settle(state);
            assertThat(first.changed()).isFalse();
            assertThat(first.state().lastSettledAt()).isEqualTo(T);

            // 再过 40 分钟（累计 80 分钟）：结算整 1 小时，游标推进到 T+1h，余下 20 分钟继续保留
            SettlementResult second = serviceAfter(Duration.ofMinutes(80)).settle(first.state());
            assertThat(second.settledHours()).isEqualTo(1);
            assertThat(second.after().satiety()).isEqualTo(75);
            assertThat(second.state().lastSettledAt()).isEqualTo(T.plus(Duration.ofHours(1)));
        }

        @Test
        @DisplayName("时间没有前进时不结算")
        void noElapsedTime() {
            PetState state = awake(attrs(80, 80, 80, 80, 50), Species.CAT);
            assertThat(serviceAt(T).settle(state).changed()).isFalse();
        }

        @Test
        @DisplayName("1 小时：清醒衰减 饱食-5 心情-4 清洁-3 精力-4，四项仍高于 60 时健康 +1")
        void oneHour() {
            PetState state = awake(attrs(80, 80, 80, 80, 50), Species.CAT);

            SettlementResult result = serviceAfter(Duration.ofHours(1)).settle(state);

            assertThat(result.settledHours()).isEqualTo(1);
            // 猫清洁衰减 -25%：round(3 * 0.75 * 1) = 2
            assertThat(result.after()).isEqualTo(attrs(75, 76, 78, 76, 51));
        }

        @Test
        @DisplayName("8 小时结算")
        void eightHours() {
            PetState state = awake(attrs(80, 80, 80, 80, 50), Species.CAT);

            SettlementResult result = serviceAfter(Duration.ofHours(8)).settle(state);

            assertThat(result.settledHours()).isEqualTo(8);
            // 饱食 80-40=40 心情 80-32=48 清洁 80-18=62 精力 80-32=48
            // 结算后没有任何一项低于健康扣减阈值，也没有全部高于 60，健康不变
            assertThat(result.after()).isEqualTo(attrs(40, 48, 62, 48, 50));
        }

        @Test
        @DisplayName("12 小时恰好封顶")
        void twelveHours() {
            PetState state = awake(attrs(80, 80, 80, 80, 50), Species.CAT);

            SettlementResult result = serviceAfter(Duration.ofHours(12)).settle(state);

            assertThat(result.settledHours()).isEqualTo(12);
            // 饱食 80-60=20（<25，健康 -2/小时）心情 80-48=32 清洁 80-27=53 精力 80-48=32
            // 健康 50 - 2*12 = 26
            assertThat(result.after()).isEqualTo(attrs(20, 32, 53, 32, 26));
            assertThat(result.state().lastSettledAt()).isEqualTo(T.plus(Duration.ofHours(12)));
        }

        @Test
        @DisplayName("超过 12 小时只结算 12 小时，超出部分直接丢弃且不累计")
        void beyondCapDiscardsExcess() {
            PetState state = awake(attrs(80, 80, 80, 80, 50), Species.CAT);

            SettlementResult result = serviceAfter(Duration.ofHours(24)).settle(state);

            assertThat(result.settledHours()).isEqualTo(12);
            assertThat(result.after()).isEqualTo(attrs(20, 32, 53, 32, 26));
            assertThat(result.state().lastSettledAt()).isEqualTo(T.plus(Duration.ofHours(24)));

            // 紧接着再结算一次：超出的 12 小时没有攒着，这次无事发生
            SettlementResult again = serviceAfter(Duration.ofHours(24)).settle(result.state());
            assertThat(again.changed()).isFalse();
        }

        @Test
        @DisplayName("离线上限对睡觉同样生效")
        void capAppliesWhileSleeping() {
            PetState state = sleeping(attrs(100, 100, 100, 0, 100), Species.DOG);

            SettlementResult result = serviceAfter(Duration.ofHours(30)).settle(state);

            assertThat(result.settledHours()).isEqualTo(12);
            assertThat(result.state().lastSettledAt()).isEqualTo(T.plus(Duration.ofHours(30)));
        }
    }

    // ================================================================== 降温与物种修正

    @Nested
    @DisplayName("物种修正（PRD 2.6）")
    class SpeciesModifiers {

        @Test
        @DisplayName("猫：清洁衰减 -25%")
        void catHygieneDecaysSlower() {
            PetAttributes base = attrs(100, 100, 80, 100, 100);

            PetAttributes cat = serviceAfter(Duration.ofHours(8))
                    .settle(awake(base, Species.CAT)).after();
            PetAttributes dragon = serviceAfter(Duration.ofHours(8))
                    .settle(awake(base, Species.DRAGON)).after();

            // 猫 round(3*0.75*8)=18 -> 62；龙 3*8=24 -> 56
            assertThat(cat.hygiene()).isEqualTo(62);
            assertThat(dragon.hygiene()).isEqualTo(56);
        }

        @Test
        @DisplayName("龙：精力衰减 -25%")
        void dragonEnergyDecaysSlower() {
            PetAttributes base = attrs(100, 100, 100, 80, 100);

            PetAttributes dragon = serviceAfter(Duration.ofHours(8))
                    .settle(awake(base, Species.DRAGON)).after();
            PetAttributes cat = serviceAfter(Duration.ofHours(8))
                    .settle(awake(base, Species.CAT)).after();

            // 龙 round(4*0.75*8)=24 -> 56；猫 4*8=32 -> 48
            assertThat(dragon.energy()).isEqualTo(56);
            assertThat(cat.energy()).isEqualTo(48);
        }

        @Test
        @DisplayName("狗：健康恢复额外 +1/小时（+1 -> +2）")
        void dogRecoversHealthFaster() {
            PetAttributes base = attrs(100, 100, 100, 100, 10);

            PetAttributes dog = serviceAfter(Duration.ofHours(1))
                    .settle(awake(base, Species.DOG)).after();
            PetAttributes cat = serviceAfter(Duration.ofHours(1))
                    .settle(awake(base, Species.CAT)).after();

            assertThat(cat.health()).isEqualTo(11);
            assertThat(dog.health()).isEqualTo(12);
        }

        @Test
        @DisplayName("物种的清洁衰减倍率同样作用于睡觉衰减表")
        void hygieneScaleAlsoAppliesWhileSleeping() {
            PetAttributes base = attrs(100, 100, 100, 0, 100);

            PetAttributes cat = serviceAfter(Duration.ofHours(10))
                    .settle(sleeping(base, Species.CAT)).after();

            // 睡觉清洁 -1/小时，猫 -25%：round(1*0.75*10)=8 -> 92
            assertThat(cat.hygiene()).isEqualTo(92);
        }
    }

    // ================================================================== 健康

    @Nested
    @DisplayName("健康变化（PRD 2.3）")
    class Health {

        @Test
        @DisplayName("饱食低于 25：健康 -2/小时")
        void lowSatietyCostsHealth() {
            SettlementResult result = serviceAfter(Duration.ofHours(8))
                    .settle(awake(attrs(50, 100, 100, 100, 100), Species.DOG));

            // 饱食 50-40=10 心情 68 清洁 76 精力 68；健康 100 - 2*8 = 84
            assertThat(result.after()).isEqualTo(attrs(10, 68, 76, 68, 84));
        }

        @Test
        @DisplayName("清洁低于 25：健康 -2/小时")
        void lowHygieneCostsHealth() {
            SettlementResult result = serviceAfter(Duration.ofHours(8))
                    .settle(awake(attrs(100, 100, 40, 100, 100), Species.DOG));

            // 饱食 60 心情 68 清洁 16 精力 68；健康 100 - 2*8 = 84
            assertThat(result.after()).isEqualTo(attrs(60, 68, 16, 68, 84));
        }

        @Test
        @DisplayName("心情低于 20：健康 -1/小时（阈值不是 30）")
        void lowMoodCostsHealth() {
            SettlementResult result = serviceAfter(Duration.ofHours(8))
                    .settle(awake(attrs(100, 50, 100, 100, 100), Species.DOG));

            // 心情 50-32=18 < 20；健康 100 - 1*8 = 92
            assertThat(result.after()).isEqualTo(attrs(60, 18, 76, 68, 92));
        }

        @Test
        @DisplayName("心情 20–29 之间扣状态（SAD）但不扣健康")
        void moodBetweenSickAndSadThresholdsDoesNotCostHealth() {
            SettlementResult result = serviceAfter(Duration.ofHours(1))
                    .settle(awake(attrs(100, 28, 100, 100, 100), Species.DOG));

            // 心情 28-4=24，低于 SAD 的 30 但不低于健康扣减的 20
            assertThat(result.after().mood()).isEqualTo(24);
            assertThat(result.after().health()).isEqualTo(100);
            assertThat(result.state().status()).isEqualTo(PetStatus.SAD);
        }

        @Test
        @DisplayName("多项异常同时存在时健康扣减叠加")
        void penaltiesStack() {
            SettlementResult result = serviceAfter(Duration.ofHours(1))
                    .settle(awake(attrs(20, 10, 20, 100, 100), Species.DOG));

            // 饱食 15(-2) 心情 6(-1) 清洁 17(-2) -> -5/小时；精力 96
            assertThat(result.after()).isEqualTo(attrs(15, 6, 17, 96, 95));
        }

        @Test
        @DisplayName("健康下限为 0，宠物永不死亡")
        void healthNeverGoesBelowZero() {
            SettlementResult result = serviceAfter(Duration.ofHours(12))
                    .settle(awake(attrs(0, 0, 0, 0, 10), Species.DOG));

            // 理论扣减 5*12=60，健康 10-60 被钳制到 0
            assertThat(result.after().health()).isZero();
            assertThat(result.state().status()).isEqualTo(PetStatus.SICK);
        }
    }

    // ================================================================== 生病与恢复

    @Nested
    @DisplayName("生病与恢复的滞回（PRD 2.3）")
    class Sickness {

        @Test
        @DisplayName("健康 30 不算生病（阈值是「低于 30」）")
        void healthThirtyIsNotSick() {
            SettlementResult result = serviceAfter(Duration.ofHours(1))
                    .settle(awake(attrs(100, 100, 100, 100, 29), Species.CAT));

            assertThat(result.after().health()).isEqualTo(30);
            assertThat(result.state().sick()).isFalse();
        }

        @Test
        @DisplayName("健康跌破 30 进入生病")
        void dropsIntoSickness() {
            SettlementResult result = serviceAfter(Duration.ofHours(1))
                    .settle(awake(attrs(100, 100, 100, 100, 28), Species.CAT));

            assertThat(result.after().health()).isEqualTo(29);
            assertThat(result.state().sick()).isTrue();
            assertThat(result.state().status()).isEqualTo(PetStatus.SICK);
        }

        @Test
        @DisplayName("生病后健康回到 30–49 之间不解除，仍显示生病")
        void sickStaysSickBelowRecoverThreshold() {
            PetState sick = new PetState(attrs(100, 100, 100, 100, 40), Species.CAT, true, null, T);

            SettlementResult result = serviceAfter(Duration.ofHours(1)).settle(sick);

            assertThat(result.after().health()).isEqualTo(41);
            assertThat(result.state().sick()).isTrue();
            assertThat(result.state().status()).isEqualTo(PetStatus.SICK);
        }

        @Test
        @DisplayName("健康恢复到 50 才解除生病")
        void recoversAtFifty() {
            PetState sick = new PetState(attrs(100, 100, 100, 100, 49), Species.CAT, true, null, T);

            SettlementResult result = serviceAfter(Duration.ofHours(1)).settle(sick);

            assertThat(result.after().health()).isEqualTo(50);
            assertThat(result.state().sick()).isFalse();
            assertThat(result.state().status()).isEqualTo(PetStatus.NORMAL);
        }

        @Test
        @DisplayName("没生病的宠物健康落在 30–49 之间不会被动变成生病")
        void healthyPetInMiddleRangeStaysHealthy() {
            SettlementResult result = serviceAfter(Duration.ofHours(1))
                    .settle(awake(attrs(100, 100, 100, 100, 40), Species.CAT));

            assertThat(result.after().health()).isEqualTo(41);
            assertThat(result.state().sick()).isFalse();
        }
    }

    // ================================================================== 睡觉

    @Nested
    @DisplayName("睡觉（PRD 2.4、2.5）")
    class Sleeping {

        @Test
        @DisplayName("睡觉不足一小时不结算，入睡时刻保持不动")
        void subHourSleepIsNotSettled() {
            PetState state = sleeping(attrs(80, 80, 80, 50, 100), Species.CAT);

            SettlementResult result = serviceAfter(Duration.ofMinutes(30)).settle(state);

            assertThat(result.changed()).isFalse();
            assertThat(result.state().sleepingSince()).isEqualTo(T);
            assertThat(result.state().lastSettledAt()).isEqualTo(T);
        }

        @Test
        @DisplayName("睡觉 1 小时：精力 +10，饱食 -3、心情 -1、清洁 -1")
        void oneHourOfSleep() {
            PetState state = sleeping(attrs(80, 80, 80, 50, 100), Species.CAT);

            SettlementResult result = serviceAfter(Duration.ofHours(1)).settle(state);

            assertThat(result.wokeUp()).isFalse();
            // 猫清洁 -25%：round(1*0.75*1) = 1
            assertThat(result.after()).isEqualTo(attrs(77, 79, 79, 60, 100));
            assertThat(result.state().sleepingSince()).isEqualTo(T);
            assertThat(result.state().status()).isEqualTo(PetStatus.SLEEPING);
        }

        @Test
        @DisplayName("精力提前到 95 自动醒来")
        void wakesWhenEnergyReachesThreshold() {
            PetState state = sleeping(attrs(80, 80, 80, 50, 100), Species.CAT);

            // 精力 50 需要 5 小时到 95
            SettlementResult result = serviceAfter(Duration.ofHours(5)).settle(state);

            assertThat(result.wokeUp()).isTrue();
            assertThat(result.state().sleepingSince()).isNull();
            assertThat(result.after().energy()).isEqualTo(100);
            assertThat(result.after()).isEqualTo(attrs(65, 75, 76, 100, 100));
        }

        @Test
        @DisplayName("最多睡 10 小时，醒来后的剩余时间按清醒衰减结算")
        void wakesAfterTenHoursAndSettlesRemainderAwake() {
            PetState state = sleeping(attrs(100, 100, 100, 0, 100), Species.DOG);

            // 精力 0 需要 10 小时到 95，正好等于睡觉上限；总离线 20 小时被封顶到 12
            SettlementResult result = serviceAfter(Duration.ofHours(20)).settle(state);

            assertThat(result.settledHours()).isEqualTo(12);
            assertThat(result.wokeUp()).isTrue();
            assertThat(result.state().sleepingSince()).isNull();
            // 睡 10 小时：饱食 100-30=70 心情 100-10=90 清洁 100-10=90 精力 100
            // 醒后 2 小时清醒：饱食 70-10=60 心情 90-8=82 清洁 90-6=84 精力 100-8=92
            assertThat(result.after()).isEqualTo(attrs(60, 82, 84, 92, 100));
        }

        @Test
        @DisplayName("醒来后的剩余时间按清醒结算")
        void remainingTimeAfterWakingUsesAwakeDecay() {
            PetState state = sleeping(attrs(80, 80, 80, 50, 100), Species.DOG);

            // 睡 5 小时到精力 95，剩下的 2 小时按清醒衰减
            SettlementResult result = serviceAfter(Duration.ofHours(7)).settle(state);

            assertThat(result.settledHours()).isEqualTo(7);
            assertThat(result.wokeUp()).isTrue();
            // 睡 5 小时：饱食 65 心情 75 清洁 75 精力 100
            // 醒后 2 小时：饱食 55 心情 67 清洁 69 精力 92
            assertThat(result.after()).isEqualTo(attrs(55, 67, 69, 92, 100));
            assertThat(result.state().lastSettledAt()).isEqualTo(T.plus(Duration.ofHours(7)));
        }

        @Test
        @DisplayName("分批结算也能累计到睡眠上限，不会因频繁请求而睡不完")
        void sleepAccumulatesAcrossMultipleSettlements() {
            PetState state = sleeping(attrs(100, 100, 100, 30, 100), Species.DOG);

            // 第一次：累计睡 6 小时，还没到精力 95
            SettlementResult first = serviceAfter(Duration.ofHours(6)).settle(state);
            assertThat(first.wokeUp()).isFalse();
            assertThat(first.after()).isEqualTo(attrs(82, 94, 94, 90, 100));
            assertThat(first.state().sleepingSince()).isEqualTo(T);

            // 第二次：再睡 1 小时就到精力 95，剩余 5 小时按清醒结算
            SettlementResult second = serviceAfter(Duration.ofHours(12)).settle(first.state());
            assertThat(second.wokeUp()).isTrue();
            assertThat(second.after()).isEqualTo(attrs(54, 73, 78, 80, 100));
        }
    }
}
