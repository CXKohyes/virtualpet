package com.virtualpet.pet;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.Instant;

/**
 * 宠物实体（TECH_DESIGN 4.3）。
 *
 * <p>枚举字段以字符串保存（AGENTS.md 5.4），所以实体里用 {@code String} 而不是枚举，
 * 由 {@link PetConverter} 负责和 {@link PetState} 之间转换。这样 {@code game} 包的枚举
 * 不需要依赖持久化框架，数据库里也直接可读。</p>
 */
@TableName("pets")
public class Pet {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long playerId;

    private String species;

    private String name;

    private Integer satiety;

    private Integer mood;

    private Integer hygiene;

    private Integer energy;

    private Integer health;

    /** 生病滞回标志（PRD 2.3）：健康低于 30 置位，恢复到 50 以上清除。 */
    private Boolean sick;

    /** 冗余存储的当前状态，方便按状态查询。由 PetState 推导后写回。 */
    private String status;

    private Integer level;

    private Integer exp;

    private Integer evolutionStage;

    private Instant sleepingSince;

    private Instant lastSettledAt;

    /** 乐观锁，冲突时更新影响行数为 0。 */
    @Version
    private Integer version;

    private Instant createdAt;

    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPlayerId() {
        return playerId;
    }

    public void setPlayerId(Long playerId) {
        this.playerId = playerId;
    }

    public String getSpecies() {
        return species;
    }

    public void setSpecies(String species) {
        this.species = species;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getSatiety() {
        return satiety;
    }

    public void setSatiety(Integer satiety) {
        this.satiety = satiety;
    }

    public Integer getMood() {
        return mood;
    }

    public void setMood(Integer mood) {
        this.mood = mood;
    }

    public Integer getHygiene() {
        return hygiene;
    }

    public void setHygiene(Integer hygiene) {
        this.hygiene = hygiene;
    }

    public Integer getEnergy() {
        return energy;
    }

    public void setEnergy(Integer energy) {
        this.energy = energy;
    }

    public Integer getHealth() {
        return health;
    }

    public void setHealth(Integer health) {
        this.health = health;
    }

    public Boolean getSick() {
        return sick;
    }

    public void setSick(Boolean sick) {
        this.sick = sick;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public Integer getExp() {
        return exp;
    }

    public void setExp(Integer exp) {
        this.exp = exp;
    }

    public Integer getEvolutionStage() {
        return evolutionStage;
    }

    public void setEvolutionStage(Integer evolutionStage) {
        this.evolutionStage = evolutionStage;
    }

    public Instant getSleepingSince() {
        return sleepingSince;
    }

    public void setSleepingSince(Instant sleepingSince) {
        this.sleepingSince = sleepingSince;
    }

    public Instant getLastSettledAt() {
        return lastSettledAt;
    }

    public void setLastSettledAt(Instant lastSettledAt) {
        this.lastSettledAt = lastSettledAt;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
