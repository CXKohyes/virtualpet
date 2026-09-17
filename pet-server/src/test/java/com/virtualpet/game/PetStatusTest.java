package com.virtualpet.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态判定测试（PRD 2.3 阈值、TECH_DESIGN 6.3 优先级）。
 */
class PetStatusTest {

    /** 全部属性处于正常区间的基准值。 */
    private static final PetAttributes NORMAL = new PetAttributes(80, 80, 80, 80, 80);

    @Nested
    @DisplayName("单项阈值边界")
    class Thresholds {

        @ParameterizedTest(name = "饱食 {0} -> {1}")
        @CsvSource({"24, HUNGRY", "25, NORMAL", "0, HUNGRY"})
        void satiety(int satiety, PetStatus expected) {
            PetAttributes attributes = new PetAttributes(satiety, 80, 80, 80, 80);
            assertThat(PetStatus.resolve(false, false, attributes)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "精力 {0} -> {1}")
        @CsvSource({"19, TIRED", "20, NORMAL", "0, TIRED"})
        void energy(int energy, PetStatus expected) {
            PetAttributes attributes = new PetAttributes(80, 80, 80, energy, 80);
            assertThat(PetStatus.resolve(false, false, attributes)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "清洁 {0} -> {1}")
        @CsvSource({"24, DIRTY", "25, NORMAL", "0, DIRTY"})
        void hygiene(int hygiene, PetStatus expected) {
            PetAttributes attributes = new PetAttributes(80, 80, hygiene, 80, 80);
            assertThat(PetStatus.resolve(false, false, attributes)).isEqualTo(expected);
        }

        /** 注意心情的 SAD 阈值是 30，与健康扣减用的 20 不是同一条规则。 */
        @ParameterizedTest(name = "心情 {0} -> {1}")
        @CsvSource({"29, SAD", "30, NORMAL", "20, SAD"})
        void mood(int mood, PetStatus expected) {
            PetAttributes attributes = new PetAttributes(80, mood, 80, 80, 80);
            assertThat(PetStatus.resolve(false, false, attributes)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("优先级（TECH_DESIGN 6.3）")
    class Priority {

        @Test
        @DisplayName("SLEEPING 高于一切")
        void sleepingWins() {
            PetAttributes worst = new PetAttributes(0, 0, 0, 0, 0);
            assertThat(PetStatus.resolve(true, true, worst)).isEqualTo(PetStatus.SLEEPING);
        }

        @Test
        @DisplayName("SICK 高于 HUNGRY / TIRED / DIRTY / SAD")
        void sickBeatsOtherWarnings() {
            PetAttributes allBad = new PetAttributes(0, 0, 0, 0, 50);
            assertThat(PetStatus.resolve(false, true, allBad)).isEqualTo(PetStatus.SICK);
        }

        @Test
        @DisplayName("HUNGRY > TIRED > DIRTY > SAD > NORMAL")
        void warningOrder() {
            assertThat(PetStatus.resolve(false, false, new PetAttributes(0, 0, 0, 0, 80)))
                    .isEqualTo(PetStatus.HUNGRY);
            assertThat(PetStatus.resolve(false, false, new PetAttributes(80, 0, 0, 0, 80)))
                    .isEqualTo(PetStatus.TIRED);
            assertThat(PetStatus.resolve(false, false, new PetAttributes(80, 0, 0, 80, 80)))
                    .isEqualTo(PetStatus.DIRTY);
            assertThat(PetStatus.resolve(false, false, new PetAttributes(80, 0, 80, 80, 80)))
                    .isEqualTo(PetStatus.SAD);
            assertThat(PetStatus.resolve(false, false, NORMAL)).isEqualTo(PetStatus.NORMAL);
        }

        @Test
        @DisplayName("健康低于 30 本身不触发 SICK，SICK 由滞回标志决定")
        void lowHealthAloneDoesNotImplySick() {
            PetAttributes lowHealth = new PetAttributes(80, 80, 80, 80, 10);
            assertThat(PetStatus.resolve(false, false, lowHealth)).isEqualTo(PetStatus.NORMAL);
            assertThat(PetStatus.resolve(false, true, lowHealth)).isEqualTo(PetStatus.SICK);
        }
    }
}
