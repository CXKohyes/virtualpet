package com.virtualpet.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * 统一时间来源。
 *
 * <p>所有业务代码通过注入的 {@link Clock} 取时间，禁止直接调用 {@code Instant.now()}，
 * 这样测试可以换成固定时钟（TECH_DESIGN 6.5）。</p>
 */
@Configuration
public class ClockConfig {

    /**
     * 时间精度：整秒。
     *
     * <p>这不是随手取的，是为了和数据库对齐。{@code pets.last_settled_at} 是
     * 秒精度的 {@code TIMESTAMP}，MySQL 写入时会把小数秒<b>四舍五入</b>
     * （不是截断）。于是"创建于 10:20:02.871"会存成 10:20:03，游标比分秒不差地
     * 向前跳了 0.129 秒。紧接着结算 8 小时，实际经过时间是 7 小时 59.87 秒，
     * 取整成分钟就是 479 —— <b>整整少结算一个小时</b>。小数部分越大越容易中招，
     * 实测以 0.5 为界：小于 0.5 的存进去是截断，等于或大于 0.5 的会被进位。</p>
     *
     * <p>把时钟截断到整秒，应用算出来的时间和数据库存下来的就是同一个值，
     * 再没有"算得比存得精细"这回事。游戏按小时结算，损失不到一秒的精度没有影响。</p>
     */
    private static final Duration TICK = Duration.ofSeconds(1);

    @Bean
    public Clock clock() {
        // 数据库存 UTC，服务器本地时区不参与计算；
        // 再截断到整秒，避免 MySQL 对小数秒四舍五入导致少结算一小时
        return Clock.tick(Clock.systemUTC(), TICK);
    }
}
