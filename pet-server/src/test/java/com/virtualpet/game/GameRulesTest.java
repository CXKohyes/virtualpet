package com.virtualpet.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 等级规则测试（PRD 2.7：累计经验阈值 40、90、150、220、300、390、490、600、720，最高 10 级）。
 */
class GameRulesTest {

    @ParameterizedTest(name = "累计经验 {0} -> {1} 级")
    @CsvSource({
            // 低于第一个阈值
            "0, 1",
            "39, 1",
            // 每个阈值恰好跨级
            "40, 2",
            "89, 2",
            "90, 3",
            "149, 3",
            "150, 4",
            "219, 4",
            "220, 5",
            "299, 5",
            "300, 6",
            "389, 6",
            "390, 7",
            "489, 7",
            "490, 8",
            "599, 8",
            "600, 9",
            "719, 9",
            // 最高级
            "720, 10",
            "100000, 10",
    })
    void levelFollowsExpThresholds(int exp, int expectedLevel) {
        assertThat(GameRules.levelForExp(exp)).isEqualTo(expectedLevel);
    }

    @Test
    @DisplayName("负数经验按 1 级处理，不抛异常")
    void negativeExpFallsBackToLevelOne() {
        assertThat(GameRules.levelForExp(-1)).isEqualTo(GameRules.MIN_LEVEL);
        assertThat(GameRules.levelForExp(Integer.MIN_VALUE)).isEqualTo(GameRules.MIN_LEVEL);
    }

    @Test
    @DisplayName("等级永不超过 10 级")
    void levelNeverExceedsMaximum() {
        assertThat(GameRules.levelForExp(Integer.MAX_VALUE)).isEqualTo(GameRules.MAX_LEVEL);
    }
}
