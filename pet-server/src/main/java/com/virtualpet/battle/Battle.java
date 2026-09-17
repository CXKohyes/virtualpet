package com.virtualpet.battle;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 一场异步对战（TECH_DESIGN 4.5）。
 *
 * <p>双方宠物的状态以 JSON 快照存在这里，战斗过程只读快照、不碰实时数据。</p>
 */
@TableName("battles")
public class Battle {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long challengerPlayerId;

    private Long defenderPlayerId;

    private Long challengerPetId;

    private Long defenderPetId;

    /** 挑战方快照的 JSON，见 {@link BattleSnapshotCodec}。 */
    private String challengerSnapshot;

    private String defenderSnapshot;

    /** 固定随机种子。同一对快照加同一个种子必然复现出同一份战报。 */
    private Long seed;

    private String status;

    /** 战报 JSON，打完才有。 */
    private String resultJson;

    private Instant createdAt;

    private Instant finishedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getChallengerPlayerId() {
        return challengerPlayerId;
    }

    public void setChallengerPlayerId(Long challengerPlayerId) {
        this.challengerPlayerId = challengerPlayerId;
    }

    public Long getDefenderPlayerId() {
        return defenderPlayerId;
    }

    public void setDefenderPlayerId(Long defenderPlayerId) {
        this.defenderPlayerId = defenderPlayerId;
    }

    public Long getChallengerPetId() {
        return challengerPetId;
    }

    public void setChallengerPetId(Long challengerPetId) {
        this.challengerPetId = challengerPetId;
    }

    public Long getDefenderPetId() {
        return defenderPetId;
    }

    public void setDefenderPetId(Long defenderPetId) {
        this.defenderPetId = defenderPetId;
    }

    public String getChallengerSnapshot() {
        return challengerSnapshot;
    }

    public void setChallengerSnapshot(String challengerSnapshot) {
        this.challengerSnapshot = challengerSnapshot;
    }

    public String getDefenderSnapshot() {
        return defenderSnapshot;
    }

    public void setDefenderSnapshot(String defenderSnapshot) {
        this.defenderSnapshot = defenderSnapshot;
    }

    public Long getSeed() {
        return seed;
    }

    public void setSeed(Long seed) {
        this.seed = seed;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }
}
