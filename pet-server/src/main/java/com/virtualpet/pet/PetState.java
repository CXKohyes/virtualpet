package com.virtualpet.pet;

import com.virtualpet.game.PetAttributes;
import com.virtualpet.game.PetStatus;
import com.virtualpet.game.Species;

import java.time.Instant;
import java.util.Objects;

/**
 * 宠物的领域状态快照，供结算使用。
 *
 * <p>与批次 2 的 MyBatis-Plus 实体 {@code Pet} 分离：实体负责持久化字段，
 * 这里只保留结算需要的规则相关字段。</p>
 *
 * <p><b>不变量</b>：入睡时必须把 {@code sleepingSince} 和 {@code lastSettledAt}
 * 设为同一时刻（由批次 2 的 SLEEP 操作负责）。结算以 {@code lastSettledAt}
 * 为统一游标，{@code sleepingSince} 只用于判断本次睡觉的剩余额度。</p>
 *
 * @param attributes   五项属性
 * @param species      物种
 * @param sick         是否处于生病状态（滞回标志，见 {@link PetStatus#resolve}）
 * @param sleepingSince 入睡时刻，未睡觉时为 {@code null}
 * @param lastSettledAt 已结算到的时间点，也是下次结算的起点
 */
public record PetState(
        PetAttributes attributes,
        Species species,
        boolean sick,
        Instant sleepingSince,
        Instant lastSettledAt) {

    public PetState {
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(lastSettledAt, "lastSettledAt");
    }

    public boolean sleeping() {
        return sleepingSince != null;
    }

    /** 按优先级推导当前状态。 */
    public PetStatus status() {
        return PetStatus.resolve(sleeping(), sick, attributes);
    }

    /**
     * 生成结算后的新状态。
     *
     * @param newAttributes    结算后的属性
     * @param newSick          结算后的生病标志
     * @param newSleepingSince 结算后的入睡时刻，已醒来则为 {@code null}
     * @param newLastSettledAt 结算后的游标
     */
    public PetState withSettlement(PetAttributes newAttributes, boolean newSick,
                                   Instant newSleepingSince, Instant newLastSettledAt) {
        return new PetState(newAttributes, species, newSick, newSleepingSince, newLastSettledAt);
    }
}
