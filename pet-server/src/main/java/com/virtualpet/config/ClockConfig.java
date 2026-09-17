package com.virtualpet.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 统一时间来源。
 *
 * <p>所有业务代码通过注入的 {@link Clock} 取时间，禁止直接调用 {@code Instant.now()}，
 * 这样测试可以换成固定时钟（TECH_DESIGN 6.5）。</p>
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        // 数据库存 UTC，服务器本地时区不参与计算
        return Clock.systemUTC();
    }
}
