package com.virtualpet.pet;

import com.virtualpet.game.EvolutionStage;
import com.virtualpet.game.PetAttributes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进化判定测试（PRD 2.7、TECH_DESIGN 6.4）。
 *
 * <p>条件：成长形态需要 4 级 + 健康 ≥60；最终形态需要 8 级 + 健康 ≥70
 * 且四项核心属性全部 ≥50。只升不降。</p>
 */
class EvolutionServiceTest {

    private final EvolutionService service = new EvolutionService();

    /** 全部达标的属性。 */
    private static final PetAttributes GOOD = new PetAttributes(80, 80, 80, 80, 100);

    private static PetAttributes withHealth(int health) {
        return new PetAttributes(80, 80, 80, 80, health);
    }

    @Test
    @DisplayName("等级不够时保持幼年")
    void staysJuvenileBelowGrowthLevel() {
        assertThat(service.evaluate(3, withHealth(100), EvolutionStage.JUVENILE))
                .isEqualTo(EvolutionStage.JUVENILE);
    }

    @ParameterizedTest(name = "等级 {0} / 健康 {1} -> {2}")
    @CsvSource({
            // 成长形态：4 级 + 健康 60
            "4, 60, GROWTH",
            "4, 59, JUVENILE",
            "3, 100, JUVENILE",
            "7, 100, GROWTH",
            // 最终形态：8 级 + 健康 70
            "8, 70, FINAL",
            "8, 69, GROWTH",
            "7, 70, GROWTH",
            "10, 100, FINAL",
    })
    void levelAndHealthThresholds(int level, int health, EvolutionStage expected) {
        assertThat(service.evaluate(level, withHealth(health), EvolutionStage.JUVENILE)).isEqualTo(expected);
    }

    @Test
    @DisplayName("最终形态还要求四项核心属性全部 ≥50")
    void finalStageRequiresCoreAttributes() {
        PetAttributes justEnough = new PetAttributes(50, 50, 50, 50, 80);
        assertThat(service.evaluate(8, justEnough, EvolutionStage.JUVENILE)).isEqualTo(EvolutionStage.FINAL);

        PetAttributes oneShort = new PetAttributes(49, 50, 50, 50, 80);
        assertThat(service.evaluate(8, oneShort, EvolutionStage.JUVENILE)).isEqualTo(EvolutionStage.GROWTH);
    }

    @Test
    @DisplayName("四项核心属性不足时连成长形态都够不上（健康够但等级够、属性差）")
    void growthStageIgnoresCoreAttributes() {
        PetAttributes poor = new PetAttributes(10, 10, 10, 10, 80);
        assertThat(service.evaluate(4, poor, EvolutionStage.JUVENILE)).isEqualTo(EvolutionStage.GROWTH);
    }

    @Test
    @DisplayName("进化不可逆：条件变差时保持已达成的阶段")
    void neverGoesBackwards() {
        assertThat(service.evaluate(1, withHealth(10), EvolutionStage.FINAL)).isEqualTo(EvolutionStage.FINAL);
        assertThat(service.evaluate(1, withHealth(10), EvolutionStage.GROWTH)).isEqualTo(EvolutionStage.GROWTH);
    }

    @Test
    @DisplayName("阶段编号按 0/1/2 保存")
    void stageCodes() {
        assertThat(EvolutionStage.JUVENILE.code()).isZero();
        assertThat(EvolutionStage.GROWTH.code()).isEqualTo(1);
        assertThat(EvolutionStage.FINAL.code()).isEqualTo(2);
        assertThat(EvolutionStage.fromCode(2)).isEqualTo(EvolutionStage.FINAL);
    }

    @Test
    @DisplayName("满状态直接到最终形态")
    void healthyAdultIsFinal() {
        assertThat(service.evaluate(10, GOOD, EvolutionStage.JUVENILE)).isEqualTo(EvolutionStage.FINAL);
    }
}
