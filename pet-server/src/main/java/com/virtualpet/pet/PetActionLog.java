package com.virtualpet.pet;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 操作日志实体（TECH_DESIGN 4.4）。
 *
 * <p>{@code clientRequestId} 上有唯一索引，是操作幂等的依据：重复提交只返回第一次
 * 保存的 {@code resultJson}，不会再次扣减或增加属性（TECH_DESIGN 6.2）。</p>
 */
@TableName("pet_action_logs")
public class PetActionLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long petId;

    private String action;

    private String clientRequestId;

    /** 第一次执行时保存的完整操作结果，重复请求原样返回。 */
    private String resultJson;

    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPetId() {
        return petId;
    }

    public void setPetId(Long petId) {
        this.petId = petId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
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
}
