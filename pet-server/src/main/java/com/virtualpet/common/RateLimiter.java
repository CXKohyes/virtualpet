package com.virtualpet.common;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按 key 限流：令牌桶（PRD 6.2 的「速率限制」）。
 *
 * <p>单实例、内存态。不引 Redis —— 那是 `TECH_DESIGN.md` 第 13 节的冻结决策，
 * 而且只有多实例部署时才需要（届时每个实例各自限流会把总量放大成 N 倍，
 * 那份文档里已经写了要先引入 Redis relay）。</p>
 *
 * <p><b>时间基准是 {@code System.nanoTime()}，不是注入的 {@link java.time.Clock}。</b>
 * 业务时间必须用注入的 Clock（可测、可冻结），但限流计量的是「间隔」而非「时刻」，
 * 需要的是单调时钟：系统时间被 NTP 往回校一分钟，挂钟会算出负的间隔，
 * 而单调时钟不会。所以这里刻意不用 Clock。为了可测，{@code nowNanos} 由调用方传入。</p>
 *
 * <p>桶是懒创建的：只有真的收到过请求的 key 才占内存。空闲且已满的桶会被清理掉
 * —— 删掉它和留着它在语义上完全等价（下次来又是满桶），所以清理是无损的。</p>
 */
public class RateLimiter {

    /** 桶的容量，也就是允许的瞬时突发量。 */
    private final double capacity;

    /** 每纳秒补充的令牌数。 */
    private final double tokensPerNano;

    /** 桶满且这么久没被碰过，就可以回收。 */
    private final long idleTtlNanos;

    /** 桶数超过这个值才考虑清理，避免小流量下白跑。 */
    private final int sweepThreshold;

    /** 两次清理之间至少隔这么久，否则桶一多就会变成每个请求都全表扫描。 */
    private final long sweepIntervalNanos;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private final AtomicLong lastSweepNanos = new AtomicLong();

    /**
     * @param capacity       桶大小（突发上限）
     * @param per            补充满一桶所需的时间，例如 1 分钟
     * @param sweepThreshold 桶数超过它才触发清理
     */
    public RateLimiter(int capacity, Duration per, int sweepThreshold) {
        this.capacity = capacity;
        this.tokensPerNano = capacity / (double) per.toNanos();
        // 空闲判定给足两倍窗口：宁可多留一会儿，也不要把还在用的桶误删，
        // 误删等于把限流器清零，攻击者只要拖长间隔就能绕过。
        this.idleTtlNanos = per.toNanos() * 2;
        this.sweepThreshold = sweepThreshold;
        this.sweepIntervalNanos = Duration.ofSeconds(60).toNanos();
    }

    /**
     * 尝试取走一个令牌。
     *
     * @return true 表示放行；false 表示该 key 已超限，调用方应返回 429
     */
    public boolean tryAcquire(String key, long nowNanos) {
        sweepIfNeeded(nowNanos);

        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(capacity, nowNanos));
        return bucket.tryAcquire(nowNanos, tokensPerNano, capacity);
    }

    /**
     * 还要等多久才可能有令牌。
     *
     * <p>用于填 {@code Retry-After} 响应头 —— 让调用方知道等多久重试，
     * 比只给一个 429 友好，也能减少客户端盲目重试。</p>
     */
    public Duration retryAfter(String key, long nowNanos) {
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            return Duration.ZERO;
        }
        return bucket.retryAfter(nowNanos, tokensPerNano, capacity);
    }

    /** 当前跟踪的 key 数量。测试用。 */
    public int size() {
        return buckets.size();
    }

    /**
     * 回收空闲桶，避免 key 空间无界增长把内存吃光。
     *
     * <p>只有当桶数超过阈值、且距上次清理超过一个间隔时才真的扫一遍，
     * 否则每次请求都遍历就是 O(n²)。</p>
     */
    private void sweepIfNeeded(long nowNanos) {
        if (buckets.size() < sweepThreshold) {
            return;
        }
        long last = lastSweepNanos.get();
        if (nowNanos - last < sweepIntervalNanos) {
            return;
        }
        // 单个清理线程即可：拿不到就直接跳过，下次请求再试
        if (!lastSweepNanos.compareAndSet(last, nowNanos)) {
            return;
        }
        buckets.forEach((key, bucket) -> {
            if (bucket.isIdleAndFull(nowNanos, idleTtlNanos, tokensPerNano, capacity)) {
                // 带值删除：万一这一刻正好有请求把桶取走并用了，就不会误删新的
                buckets.remove(key, bucket);
            }
        });
    }

    /** 单个 key 的令牌桶。同步粒度是桶，不同 key 之间不互相阻塞。 */
    private static final class Bucket {

        private double tokens;
        private long lastRefillNanos;

        Bucket(double tokens, long nowNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = nowNanos;
        }

        synchronized boolean tryAcquire(long nowNanos, double tokensPerNano, double capacity) {
            refill(nowNanos, tokensPerNano, capacity);
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }

        synchronized Duration retryAfter(long nowNanos, double tokensPerNano, double capacity) {
            refill(nowNanos, tokensPerNano, capacity);
            if (tokens >= 1.0) {
                return Duration.ZERO;
            }
            double missing = 1.0 - tokens;
            long waitNanos = (long) Math.ceil(missing / tokensPerNano);
            // 至少 1 秒，免得返回 Retry-After: 0 让客户端立刻重试
            return Duration.ofNanos(Math.max(waitNanos, Duration.ofSeconds(1).toNanos()));
        }

        /** 空闲且已满 = 和没有记录等价，可以安全回收。 */
        synchronized boolean isIdleAndFull(long nowNanos, long idleTtlNanos,
                                           double tokensPerNano, double capacity) {
            // 必须先把「上次活动时刻」取出来：refill 会把 lastRefillNanos 改写成
            // nowNanos，之后再比就恒等于 0，永远够不到空闲阈值 —— 那样清扫会
            // 静默地一个都删不掉，看着有回收逻辑，实际一直在漏。
            long lastTouchedNanos = lastRefillNanos;
            refill(nowNanos, tokensPerNano, capacity);
            return tokens >= capacity && nowNanos - lastTouchedNanos >= idleTtlNanos;
        }

        /** 按经过的时间补令牌，补不超过容量。 */
        private void refill(long nowNanos, double tokensPerNano, double capacity) {
            long elapsed = nowNanos - lastRefillNanos;
            if (elapsed <= 0) {
                // 时钟没前进（或理论上倒退）就不补，避免算出负令牌
                return;
            }
            tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
            lastRefillNanos = nowNanos;
        }
    }
}
