package com.virtualpet.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 操作规则测试（PRD 2.4 效果表、2.6 物种特性）。
 *
 * <p>基准属性取 50，避免撞上 0/100 的钳制，便于观察真实数值。</p>
 */
class PetActionRulesTest {

    private static final PetAttributes BASE = new PetAttributes(50, 50, 50, 50, 100);

    private static PetAttributes apply(PetAction action, Species species) {
        return PetActionRules.applyEffect(BASE, action, species);
    }

    @Nested
    @DisplayName("基础效果（PRD 2.4）")
    class BaseEffects {

        @Test
        @DisplayName("喂食：饱食 +30、心情 +3、清洁 -2")
        void feed() {
            assertThat(apply(PetAction.FEED, Species.CAT)).isEqualTo(new PetAttributes(80, 53, 48, 50, 100));
        }

        @Test
        @DisplayName("玩耍：心情 +22、精力 -12、饱食 -5、清洁 -4")
        void play() {
            assertThat(apply(PetAction.PLAY, Species.DRAGON)).isEqualTo(new PetAttributes(45, 72, 46, 38, 100));
        }

        @Test
        @DisplayName("清洁：清洁 +35、心情 +5")
        void clean() {
            assertThat(apply(PetAction.CLEAN, Species.CAT)).isEqualTo(new PetAttributes(50, 55, 85, 50, 100));
        }

        @Test
        @DisplayName("睡觉和唤醒不直接改属性，只改睡觉状态")
        void sleepAndWakeDoNotChangeAttributes() {
            assertThat(apply(PetAction.SLEEP, Species.CAT)).isEqualTo(BASE);
            assertThat(apply(PetAction.WAKE, Species.CAT)).isEqualTo(BASE);
        }

        @Test
        @DisplayName("属性在 0–100 处被钳制")
        void clampsAtBounds() {
            PetAttributes high = new PetAttributes(94, 99, 99, 99, 100);
            assertThat(PetActionRules.applyEffect(high, PetAction.FEED, Species.CAT))
                    .isEqualTo(new PetAttributes(100, 100, 97, 99, 100));

            // 玩耍：饱食 3-5 和清洁 2-4 都归零，心情 3+28 不封顶，精力 14-12
            PetAttributes low = new PetAttributes(3, 3, 2, 14, 100);
            assertThat(PetActionRules.applyEffect(low, PetAction.PLAY, Species.CAT))
                    .isEqualTo(new PetAttributes(0, 31, 0, 2, 100));
        }
    }

    @Nested
    @DisplayName("物种修正（PRD 2.6）")
    class SpeciesBonuses {

        @Test
        @DisplayName("猫：玩耍心情收益 +25%（22 -> 28）")
        void catPlayMoodBonus() {
            // 基准心情 50，收益分别是 28 / 24 / 22
            assertThat(apply(PetAction.PLAY, Species.CAT).mood()).isEqualTo(78);
            assertThat(apply(PetAction.PLAY, Species.DOG).mood()).isEqualTo(74);
            assertThat(apply(PetAction.PLAY, Species.DRAGON).mood()).isEqualTo(72);
        }

        @Test
        @DisplayName("龙：喂食饱食收益 +15%（30 -> 35）")
        void dragonFeedSatietyBonus() {
            assertThat(apply(PetAction.FEED, Species.DRAGON).satiety()).isEqualTo(85);
            assertThat(apply(PetAction.FEED, Species.CAT).satiety()).isEqualTo(80);
        }

        @Test
        @DisplayName("狗：所有正向收益 +10%，代价不受影响")
        void dogCareGainBonus() {
            PetAttributes fed = apply(PetAction.FEED, Species.DOG);
            // 饱食 50+33=83，心情 50+3=53，清洁照旧 -2
            assertThat(fed).isEqualTo(new PetAttributes(83, 53, 48, 50, 100));
        }

        @Test
        @DisplayName("狗的加成也作用于清洁的心情收益（5 -> 6）")
        void dogBonusAppliesToCleanMood() {
            assertThat(apply(PetAction.CLEAN, Species.DOG).mood()).isEqualTo(56);
            assertThat(apply(PetAction.CLEAN, Species.DOG).hygiene()).isEqualTo(89);
        }

        @Test
        @DisplayName("代价永远是负数，任何物种都不放大代价")
        void costsAreNeverAmplified() {
            // 玩耍的精力代价对任何物种都是 -12
            assertThat(apply(PetAction.PLAY, Species.CAT).energy()).isEqualTo(38);
            assertThat(apply(PetAction.PLAY, Species.DOG).energy()).isEqualTo(38);
            assertThat(apply(PetAction.PLAY, Species.DRAGON).energy()).isEqualTo(38);
        }
    }

    @Nested
    @DisplayName("使用条件（PRD 2.4）")
    class BlockReasons {

        @ParameterizedTest(name = "喂食 饱食={0} -> {1}")
        @CsvSource({"95, true", "94, false", "100, true"})
        void feedRequiresNotFull(int satiety, boolean blocked) {
            PetAttributes attributes = new PetAttributes(satiety, 50, 50, 50, 100);
            assertThat(PetActionRules.blockReason(PetAction.FEED, attributes, false).isPresent())
                    .isEqualTo(blocked);
        }

        @ParameterizedTest(name = "玩耍 精力={0} -> {1}")
        @CsvSource({"15, false", "14, true", "0, true"})
        void playRequiresEnergy(int energy, boolean blocked) {
            PetAttributes attributes = new PetAttributes(50, 50, 50, energy, 100);
            assertThat(PetActionRules.blockReason(PetAction.PLAY, attributes, false).isPresent())
                    .isEqualTo(blocked);
        }

        @Test
        @DisplayName("睡觉中不能玩耍，提示它正在睡觉")
        void playBlockedWhileSleeping() {
            assertThat(PetActionRules.blockReason(PetAction.PLAY, BASE, true))
                    .contains("它正在睡觉");
        }

        @ParameterizedTest(name = "清洁 清洁={0} -> {1}")
        @CsvSource({"95, true", "94, false"})
        void cleanRequiresNotClean(int hygiene, boolean blocked) {
            PetAttributes attributes = new PetAttributes(50, 50, hygiene, 50, 100);
            assertThat(PetActionRules.blockReason(PetAction.CLEAN, attributes, false).isPresent())
                    .isEqualTo(blocked);
        }

        @Test
        @DisplayName("睡觉门槛是精力低于 70")
        void sleepRequiresTiredness() {
            assertThat(PetActionRules.blockReason(PetAction.SLEEP, new PetAttributes(50, 50, 50, 70, 100), false))
                    .contains("它还不困");
            assertThat(PetActionRules.blockReason(PetAction.SLEEP, new PetAttributes(50, 50, 50, 69, 100), false))
                    .isEmpty();
            assertThat(PetActionRules.blockReason(PetAction.SLEEP, new PetAttributes(50, 50, 50, 10, 100), true))
                    .contains("它已经睡着了");
        }

        @Test
        @DisplayName("唤醒只在睡觉时可用")
        void wakeRequiresSleeping() {
            assertThat(PetActionRules.blockReason(PetAction.WAKE, BASE, true)).isEmpty();
            assertThat(PetActionRules.blockReason(PetAction.WAKE, BASE, false)).contains("它并没有在睡觉");
        }
    }

    @Nested
    @DisplayName("经验与冷却")
    class ExpAndCooldown {

        @Test
        @DisplayName("喂食 6 点、玩耍 8 点、清洁 6 点")
        void actionExp() {
            assertThat(PetActionRules.expFor(PetAction.FEED)).isEqualTo(6);
            assertThat(PetActionRules.expFor(PetAction.PLAY)).isEqualTo(8);
            assertThat(PetActionRules.expFor(PetAction.CLEAN)).isEqualTo(6);
            assertThat(PetActionRules.expFor(PetAction.SLEEP)).isZero();
            assertThat(PetActionRules.expFor(PetAction.WAKE)).isZero();
        }

        @Test
        @DisplayName("喂食、玩耍、清洁有 60 秒冷却；睡觉和唤醒没有")
        void cooldowns() {
            assertThat(PetActionRules.cooldownSeconds(PetAction.FEED)).isEqualTo(60);
            assertThat(PetActionRules.cooldownSeconds(PetAction.PLAY)).isEqualTo(60);
            assertThat(PetActionRules.cooldownSeconds(PetAction.CLEAN)).isEqualTo(60);
            assertThat(PetActionRules.cooldownSeconds(PetAction.SLEEP)).isZero();
            assertThat(PetActionRules.cooldownSeconds(PetAction.WAKE)).isZero();
        }

        @ParameterizedTest(name = "睡 {0} 小时 -> {1} 点经验")
        @CsvSource({"0, 0", "1, 2", "5, 10", "6, 12", "10, 12", "12, 12"})
        void sleepExpIsCappedAtTwelve(long hours, int expected) {
            assertThat(PetActionRules.sleepExp(hours)).isEqualTo(expected);
        }
    }
}
