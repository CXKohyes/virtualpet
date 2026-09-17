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
