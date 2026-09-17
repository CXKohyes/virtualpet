package com.virtualpet.pet;

import com.virtualpet.game.GameRules;
import com.virtualpet.game.PetAttributes;
import com.virtualpet.game.PetStatus;
import com.virtualpet.game.Species;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 懒结算：按经过时间推进宠物属性，不使用定时任务（TECH_DESIGN 6.1）。
 *
 * <p>核心规则：</p>
 * <ul>
 *   <li>以 {@code lastSettledAt} 为游标，{@code elapsed = now - lastSettledAt}。</li>
 *   <li>单次最多结算 {@link GameRules#OFFLINE_CAP_HOURS} 小时，超出部分<b>直接丢弃</b>，
 *       不累计到下次。</li>
 *   <li>不足一整小时的余数<b>保留</b>在游标里等下次结算。若每次都把游标推到 {@code now}，
 *       玩家频繁刷新页面就会永远不足一小时、属性永不衰减。</li>
 *   <li>健康按结算后的属性整段一次性计算，保持 O(1)，不逐小时循环。</li>
 * </ul>
 *
 * <p>时间来源统一由构造注入的 {@link Clock} 提供，业务代码不直接调用
 * {@code Instant.now()}。</p>
 */
@Service
public class PetSettlementService {

    private final Clock clock;

    public PetSettlementService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 结算宠物状态到当前时刻。
     *
     * @param state 结算前的状态
     * @return 结算结果；时间不足一小时时返回未变化的原状态
     */
    public SettlementResult settle(PetState state) {
        Instant now = Instant.now(clock);
        Instant cursor = state.lastSettledAt();

        long elapsedMinutes = Duration.between(cursor, now).toMinutes();
        if (elapsedMinutes <= 0) {
            return SettlementResult.unchanged(state);
        }

        long capMinutes = (long) GameRules.OFFLINE_CAP_HOURS * 60;
        boolean capped = elapsedMinutes > capMinutes;
        long settledHours = Math.min(elapsedMinutes, capMinutes) / 60;

        if (settledHours == 0) {
            // 不足一小时：完全不结算，游标不动，余数留到下次
            return SettlementResult.unchanged(state);
        }

        PetAttributes before = state.attributes();
        PetAttributes attributes = before;
        boolean wokeUp = false;
        long sleepHours = 0;
        long sleptHours = 0;

        if (state.sleeping()) {
            long alreadySleptHours = Math.max(0,
                    Duration.between(state.sleepingSince(), cursor).toMinutes() / 60);
            long remainingSleepBudget = Math.max(0, GameRules.SLEEP_MAX_HOURS - alreadySleptHours);
            long sleepLimit = Math.min(remainingSleepBudget, hoursUntilEnergyWake(attributes.energy()));

            sleepHours = Math.min(settledHours, sleepLimit);
            wokeUp = sleepHours >= sleepLimit;
            sleptHours = alreadySleptHours + sleepHours;
            attributes = applySleepDecay(attributes, state.species(), sleepHours);
        }

        long awakeHours = settledHours - sleepHours;
        if (awakeHours > 0) {
            attributes = applyAwakeDecay(attributes, state.species(), awakeHours);
        }

        attributes = attributes.plus(0, 0, 0, 0,
                healthDeltaPerHour(attributes, state.species()) * (int) settledHours);

        boolean sick = PetStatus.sickAfter(state.sick(), attributes.health());

        // 封顶时把超出的时长直接丢弃（游标推到 now）；否则只推进整小时，保留余数
        Instant nextLastSettledAt = capped
                ? now
                : cursor.plus(Duration.ofMinutes(settledHours * 60));
        Instant nextSleepingSince = wokeUp ? null : state.sleepingSince();

        PetState next = state.withSettlement(attributes, sick, nextSleepingSince, nextLastSettledAt);
        return new SettlementResult(next, settledHours, wokeUp, sleptHours, before, attributes);
    }

    // ------------------------------------------------------------------ 衰减

    /**
     * 清醒状态每小时变化（PRD 2.5）。
     *
     * <p>物种的「衰减」修正同样作用于睡觉衰减表：倍率描述的是宠物属性本身的
     * 衰减速度，例如「猫清洁衰减 -25%」在睡觉时也成立，见
     * {@link #applySleepDecay}。</p>
     */
    private static PetAttributes applyAwakeDecay(PetAttributes attributes, Species species, long hours) {
        Species.Modifier modifier = species.modifier();
        return attributes.plus(
                -scaled(GameRules.AWAKE_SATIETY_DECAY_PER_HOUR, 1.0, hours),
                -scaled(GameRules.AWAKE_MOOD_DECAY_PER_HOUR, 1.0, hours),
                -scaled(GameRules.AWAKE_HYGIENE_DECAY_PER_HOUR, modifier.hygieneDecayScale(), hours),
                -scaled(GameRules.AWAKE_ENERGY_DECAY_PER_HOUR, modifier.energyDecayScale(), hours),
                0);
    }

    /**
     * 睡觉状态每小时变化（PRD 2.4、2.5）。
     *
     * <p>龙的精力衰减 -25% 在睡觉时不体现，因为睡觉是恢复精力而不是衰减精力。</p>
     */
    private static PetAttributes applySleepDecay(PetAttributes attributes, Species species, long hours) {
        if (hours <= 0) {
            return attributes;
        }
        double hygieneScale = species.modifier().hygieneDecayScale();
        return attributes.plus(
                -GameRules.SLEEP_SATIETY_DECAY_PER_HOUR * (int) hours,
                -GameRules.SLEEP_MOOD_DECAY_PER_HOUR * (int) hours,
                -scaled(GameRules.SLEEP_HYGIENE_DECAY_PER_HOUR, hygieneScale, hours),
                GameRules.SLEEP_ENERGY_GAIN_PER_HOUR * (int) hours,
                0);
    }

    /** 按物种倍率把每小时衰减换算成整段时长的整数衰减，四舍五入到最近的整数。 */
    private static int scaled(int perHour, double scale, long hours) {
        return (int) Math.round(perHour * scale * hours);
    }

    /** 还差几小时精力才能到自动醒来的阈值；已经达到则为 0。 */
    private static long hoursUntilEnergyWake(int energy) {
        if (energy >= GameRules.SLEEP_WAKE_ENERGY) {
            return 0;
        }
        int deficit = GameRules.SLEEP_WAKE_ENERGY - energy;
        return (deficit + GameRules.SLEEP_ENERGY_GAIN_PER_HOUR - 1) / GameRules.SLEEP_ENERGY_GAIN_PER_HOUR;
    }

    // ------------------------------------------------------------------ 健康

    /**
     * 每小时健康变化量（PRD 2.3）。
     *
     * <p>按结算<b>之后</b>的属性判定，整段时长一次性相乘。这是 O(1) 结算的近似：
     * 一段 12 小时里属性只会变差，所以用最终值判定等于按最差情况计费。</p>
     */
    private static int healthDeltaPerHour(PetAttributes attributes, Species species) {
        int delta = 0;
        if (attributes.satiety() < GameRules.HEALTH_LOSS_SATIETY_THRESHOLD) {
            delta -= GameRules.HEALTH_LOSS_SATIETY_PER_HOUR;
        }
        if (attributes.hygiene() < GameRules.HEALTH_LOSS_HYGIENE_THRESHOLD) {
            delta -= GameRules.HEALTH_LOSS_HYGIENE_PER_HOUR;
        }
        if (attributes.mood() < GameRules.HEALTH_LOSS_MOOD_THRESHOLD) {
            delta -= GameRules.HEALTH_LOSS_MOOD_PER_HOUR;
        }
        if (attributes.allCoreAbove(GameRules.HEALTH_GAIN_THRESHOLD)) {
            delta += GameRules.HEALTH_GAIN_PER_HOUR + species.modifier().healthRecoveryBonus();
        }
        return delta;
    }
}
