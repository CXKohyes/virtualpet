package com.virtualpet.pet;

import java.time.Instant;

/**
 * 某个操作最近一次执行的时间，用于计算冷却。
 *
 * <p>用普通类而不是 record，是为了配合 MyBatis 的结果映射（setter 注入）。</p>
 */
public class ActionUsage {

    private String action;

    private Instant lastUsedAt;

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
}
