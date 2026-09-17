package com.virtualpet.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时间精度测试。
 *
 * <p>钉住的是"应用算出来的时间不会比数据库存的更精细"这条不变量。
 * 一旦有人把时钟换回 {@code Clock.systemUTC()}，MySQL 会开始对小数秒四舍五入，
 * 离线结算就可能整整少算一个小时 —— 详见 {@link ClockConfig} 上的说明。</p>
 *
 * <p>注意这条限制：H2 的 MySQL 兼容模式会原样保存小数秒，所以"少结算一小时"
 * 这个 bug 本身在测试里复现不出来，只有在真机 MySQL 上才会发生。
 * 这里能守住的是它的<b>成因</b>（时钟精度），真机行为靠 README 里的冒烟步骤确认。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ClockConfigTest {

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("注入的时钟精度是整秒，小数秒恒为 0")
    void clockHasSecondPrecision() {
        assertThat(clock.instant().getNano()).isZero();
    }

    @Test
    @DisplayName("时钟是截断不是四舍五入：小数部分不会被进位到下一秒")
    void clockTruncatesInsteadOfRounding() {
        // 用固定时钟直接验语义：四舍五入的话 .6 秒会被推进到下一秒，
        // 而截断必须停在原地
        Clock fixed = Clock.fixed(Instant.parse("2026-09-17T10:20:02.600Z"), ZoneOffset.UTC);
        Clock ticked = Clock.tick(fixed, Duration.ofSeconds(1));

        assertThat(ticked.instant()).isEqualTo(Instant.parse("2026-09-17T10:20:02Z"));
    }

    @Test
    @DisplayName("时钟走的是 UTC 时区")
    void clockUsesUtc() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
