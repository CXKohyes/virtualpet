package com.virtualpet.player;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 玩家实体（TECH_DESIGN 4.2）。
 *
 * <p>只有匿名设备身份，没有账号体系。{@code tokenHash} 是访问令牌的 SHA-256，
 * 服务端不保存明文令牌。</p>
 */
@TableName("players")
public class Player {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String deviceId;

    private String tokenHash;

    /** P1 好友码，MVP 不使用。 */
    private String friendCode;

    /**
     * 当前宠物 ID（PRD 2.1，多宠物槽）。没有宠物时为 {@code null}。
     *
     * <p>{@code /pets/me} 指的就是这一只，所以四个操作、照护日志、对战这些
     * 既有接口在多宠物下都不用改签名。</p>
     *
     * <p><b>刻意不加外键。</b>送走宠物要物理删除 {@code pets} 行，加了外键会让
     * 删除直接失败。这和 {@code battles.*_pet_id} 不加外键是同一个理由
     * （见 002-battles.sql），一致性由 {@code PetService} 在同一个事务里保证。</p>
     *
     * <p><b>⚠️ 把它置回 null 时不能只靠 {@code updateById}。</b>MyBatis-Plus 默认的
     * {@code NOT_NULL} 策略会把 null 字段从 UPDATE 里剔掉，于是"送走最后一只宠物"
     * 这一步会静默失效，玩家行上留着指向已删除宠物的 {@code active_pet_id}。
     * 这和 {@link Pet#getSleepingSince()} 踩过的是同一个坑（那里用了
     * {@code updateStrategy = ALWAYS}，代价是任何局部更新都会覆盖这一列）。
     * 这里选择不开这个口子，改用显式的 {@code LambdaUpdateWrapper} 去写 null，
     * 并用一个**直接查库**的回归测试钉住它 —— 只断言响应体是发现不了这类问题的。</p>
     */
    private Long activePetId;

    private Instant createdAt;

    private Instant lastSeenAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getFriendCode() {
        return friendCode;
    }

    public void setFriendCode(String friendCode) {
        this.friendCode = friendCode;
    }

    public Long getActivePetId() {
        return activePetId;
    }

    public void setActivePetId(Long activePetId) {
        this.activePetId = activePetId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
}
