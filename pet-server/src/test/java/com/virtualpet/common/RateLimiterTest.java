package com.virtualpet.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 令牌桶限流器（PRD 6.2）。
 *
 * <p>时间由参数传入，所以这些用例是确定性的，不依赖真实时钟也不 sleep。</p>
 */
class RateLimiterTest {

    /** 清扫阈值给小值，好让回收逻辑能被触发；默认 4096 在生产里也够用。 */
    private static final int SWEEP_THRESHOLD = 4;

    @Nested
    @DisplayName("令牌桶")
    class TokenBucket {

        @Test
        @DisplayName("容量内放行，超出的被拒")
        void allowsUpToCapacity() {
            RateLimiter limiter = new RateLimiter(3, Duration.ofMinutes(1), SWEEP_THRESHOLD);
            long now = 0;

            assertThat(limiter.tryAcquire("ip", now)).isTrue();
            assertThat(limiter.tryAcquire("ip", now)).isTrue();
            assertThat(limiter.tryAcquire("ip", now)).isTrue();
            assertThat(limiter.tryAcquire("ip", now)).as("第 4 次应当超限").isFalse();
        }

        @Test
        @DisplayName("按时间匀速补充令牌")
        void refillsOverTime() {
            // 1 分钟补 4 个 = 每 15 秒一个
            RateLimiter limiter = new RateLimiter(4, Duration.ofMinutes(1), SWEEP_THRESHOLD);
            long now = 0;

            for (int i = 0; i < 4; i++) {
                limiter.tryAcquire("ip", now);
            }
            assertThat(limiter.tryAcquire("ip", now)).isFalse();

            // 过 15 秒正好补回一个
            now += Duration.ofSeconds(15).toNanos();
            assertThat(limiter.tryAcquire("ip", now)).as("补回一个令牌后应当放行").isTrue();
            assertThat(limiter.tryAcquire("ip", now)).isFalse();
        }

        @Test
        @DisplayName("补充不会超过容量 —— 闲置很久也只攒满一桶")
        void refillIsCappedAtCapacity() {
            RateLimiter limiter = new RateLimiter(2, Duration.ofMinutes(1), SWEEP_THRESHOLD);
            long now = 0;

            assertThat(limiter.tryAcquire("ip", now)).isTrue();
            assertThat(limiter.tryAcquire("ip", now)).isTrue();

            // 闲置 10 分钟，本来能补 20 个，但容量只有 2
            now += Duration.ofMinutes(10).toNanos();
            assertThat(limiter.tryAcquire("ip", now)).isTrue();
            assertThat(limiter.tryAcquire("ip", now)).isTrue();
            assertThat(limiter.tryAcquire("ip", now)).as("只该攒满 2 个").isFalse();
        }

        @Test
        @DisplayName("不同 key 各算各的")
        void keysAreIndependent() {
            RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1), SWEEP_THRESHOLD);
            long now = 0;

            assertThat(limiter.tryAcquire("a", now)).isTrue();
            assertThat(limiter.tryAcquire("a", now)).isFalse();
            assertThat(limiter.tryAcquire("b", now)).as("别的 key 不该被 a 拖累").isTrue();
        }

        @Test
        @DisplayName("时钟没前进时不会算出负令牌")
        void handlesNonAdvancingClock() {
            RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1), SWEEP_THRESHOLD);

            assertThat(limiter.tryAcquire("ip", 0)).isTrue();
            assertThat(limiter.tryAcquire("ip", 0)).isFalse();
            // 时间倒流也不该放行（elapsed 为负时不补令牌）
            assertThat(limiter.tryAcquire("ip", -1_000_000_000L)).isFalse();
        }
    }

    @Nested
    @DisplayName("Retry-After")
    class RetryAfterHint {

        @Test
        @DisplayName("有令牌时不需要等待")
        void zeroWhenAvailable() {
            RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1), SWEEP_THRESHOLD);

            assertThat(limiter.retryAfter("ip", 0)).isZero();
        }

        @Test
        @DisplayName("被拒后给出接近真实等待时间的秒数")
        void reflectsRealWait() {
            // 1 分钟 4 个 = 每 15 秒一个
            RateLimiter limiter = new RateLimiter(4, Duration.ofMinutes(1), SWEEP_THRESHOLD);
            long now = 0;

            for (int i = 0; i < 4; i++) {
                limiter.tryAcquire("ip", now);
            }

            // 令牌为 0，补一个要 15 秒
            assertThat(limiter.retryAfter("ip", now).toSeconds()).isBetween(14L, 16L);
        }

        @Test
        @DisplayName("等待时间至少 1 秒 —— 返回 0 会让客户端立刻重试")
        void neverZeroWhileLimited() {
            // 每秒补 1000 个：真实等待不到 1 毫秒，但也不能返回 0
            RateLimiter limiter = new RateLimiter(1, Duration.ofMillis(1), SWEEP_THRESHOLD);
            long now = 0;

            limiter.tryAcquire("ip", now);

            assertThat(limiter.retryAfter("ip", now).toSeconds()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("没见过的 key 不等待")
        void unknownKeyIsZero() {
            RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1), SWEEP_THRESHOLD);

            assertThat(limiter.retryAfter("never-seen", 0)).isZero();
        }
    }

    @Nested
    @DisplayName("空闲桶回收")
    class Sweeping {

        @Test
        @DisplayName("空闲且已满的桶会被删掉 —— 否则 key 空间无界增长会吃光内存")
        void removesIdleFullBuckets() {
            // 1 分钟窗口 → 空闲阈值 2 分钟
            RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(1), SWEEP_THRESHOLD);
            long now = 0;

            for (int i = 0; i < SWEEP_THRESHOLD + 1; i++) {
                limiter.tryAcquire("key-" + i, now);
            }
            assertThat(limiter.size()).isEqualTo(SWEEP_THRESHOLD + 1);

            // 推进到远超空闲阈值，再发一个请求把清扫带起来
            now += Duration.ofMinutes(5).toNanos();
            limiter.tryAcquire("fresh", now);

            assertThat(limiter.size())
                    .as("空闲满桶应当被回收；还是 6 就说明清扫根本没生效")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("还欠着令牌的桶不会被回收 —— 删了等于把限流清零")
        void keepsBucketsThatAreStillLimited() {
            // 4 分钟补 4 个 = 每 60 秒一个；空闲阈值 8 分钟
            RateLimiter limiter = new RateLimiter(4, Duration.ofMinutes(4), 2);
            long now = 0;

            for (int i = 0; i < 4; i++) {
                limiter.tryAcquire("victim", now);
            }
            assertThat(limiter.tryAcquire("victim", now)).isFalse();

            // 90 秒：够触发清扫（间隔 60 秒），但远不到 8 分钟的空闲阈值
            now += Duration.ofSeconds(90).toNanos();
            limiter.tryAcquire("other", now);

            // victim 只补到 1.5 个令牌：放行一次，随后必须还是被拒。
            // 桶要是被误删重建（满桶 4 个），这里连放两次都不会失败。
            assertThat(limiter.tryAcquire("victim", now)).isTrue();
            assertThat(limiter.tryAcquire("victim", now))
                    .as("桶被误回收了 —— 限流被清零，攻击者只要拖长间隔就能绕过")
                    .isFalse();
        }
    }
}
