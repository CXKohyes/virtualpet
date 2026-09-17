package com.virtualpet.pet;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.virtualpet.common.BusinessException;
import com.virtualpet.common.ErrorCode;
import com.virtualpet.game.PetAction;
import com.virtualpet.game.PetActionRules;
import com.virtualpet.game.PetAttributes;
import com.virtualpet.game.PetStatus;
import com.virtualpet.game.Species;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * 日常操作：喂食、玩耍、清洁、睡觉、唤醒（TECH_DESIGN 5.4、6.2）。
 *
 * <p>处理顺序严格按 TECH_DESIGN 6.2：</p>
 * <pre>
 * 开启事务
 *   结算宠物
 *   按 clientRequestId 查操作日志，已存在就直接返回保存的结果
 *   校验操作是否可用、是否在冷却中
 *   应用物种修正
 *   钳制属性到 0–100
 *   计算经验、等级、进化
 *   按乐观锁保存宠物
 *   写入操作日志
 * 提交
 * </pre>
 *
 * <p>所有游戏规则都来自 {@code game} 包的领域类，这里只做编排。</p>
 */
@Service
public class PetActionService {

    private static final Logger log = LoggerFactory.getLogger(PetActionService.class);

    private final PetService petService;
    private final PetActionLogMapper actionLogMapper;
    private final PetWriter writer;
    private final PetProgressService progressService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PetActionService(PetService petService,
                            PetActionLogMapper actionLogMapper,
                            PetWriter writer,
                            PetProgressService progressService,
                            ObjectMapper objectMapper,
                            Clock clock) {
        this.petService = petService;
        this.actionLogMapper = actionLogMapper;
        this.writer = writer;
        this.progressService = progressService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 执行一次操作。
     *
     * @param rawAction       操作名，大小写不敏感
     * @param clientRequestId 幂等键，同一个值重复提交只会执行一次
     * @throws BusinessException INVALID_ACTION / ACTION_NO_EFFECT / ACTION_COOLDOWN / PET_NOT_FOUND
     */
    public ActionResponse perform(Long playerId, String rawAction, String clientRequestId) {
        PetAction action = parseAction(rawAction);
        return writer.runWithRetry(() -> execute(playerId, action, clientRequestId));
    }

    private ActionResponse execute(Long playerId, PetAction action, String clientRequestId) {
        Pet pet = petService.requirePet(playerId);
        Instant now = Instant.now(clock);

        // 1. 先结算经过的时间。重复请求也要结算，因为时间确实过去了。
        SettlementResult settlement = writer.settle(pet);
        if (settlement.changed()) {
            writer.applySettlement(pet, settlement);
        }
        // 如果这次结算让它自己醒了，睡觉经验照发，免得丢了
        int sleepExp = settlement.wokeUp() ? PetActionRules.sleepExp(settlement.sleptHours()) : 0;

        // 2. 幂等：同一个 clientRequestId 只执行一次
        PetActionLog existing = findLog(clientRequestId);
        if (existing != null) {
            return replay(existing, pet, settlement, sleepExp);
        }

        // 3. 冷却与使用条件
        Instant cooldownUntil = cooldownUntil(pet.getId(), action, now);
        if (cooldownUntil != null) {
            throw new BusinessException(ErrorCode.ACTION_COOLDOWN, "它还在缓一缓，稍等一下");
        }

        PetAttributes before = attributesOf(pet);
        boolean wasSleeping = pet.getSleepingSince() != null;
        Optional<String> blocked = PetActionRules.blockReason(action, before, wasSleeping);
        if (blocked.isPresent()) {
            throw new BusinessException(ErrorCode.ACTION_NO_EFFECT, blocked.get());
        }

        // 4. 应用效果
        Species species = PetConverter.toState(pet).species();
        PetAttributes after = PetActionRules.applyEffect(before, action, species);
        writeAttributes(pet, after);

        int xpGained = PetActionRules.expFor(action) + sleepExp;
        xpGained += applySleepTransition(pet, action, settlement);

        // 属性变了，冗余的 status 列要跟着重算
        refreshStatus(pet);

        PetProgressService.Progress progress = progressService.apply(pet, xpGained);
        writer.touch(pet);
        writer.updateOrConflict(pet);

        // 5. 先写操作日志，再拼响应：日志落库后 toResponse 算出的冷却里才会带上本次操作
        ActionOutcome outcome = new ActionOutcome(
                deltasBetween(before, after),
                progress.xpGained(),
                progress.levelUp(),
                progress.evolved(),
                messageKey(action),
                nextCooldownUntil(action, now));
        saveLog(pet, action, clientRequestId, outcome, now);

        log.info("宠物 {} 执行 {}，获得 {} 点经验，等级 {}", pet.getId(), action, progress.xpGained(), pet.getLevel());
        return toResponse(outcome, pet);
    }

    private ActionResponse toResponse(ActionOutcome outcome, Pet pet) {
        return new ActionResponse(
                petService.toResponse(pet),
                outcome.deltas(),
                outcome.xpGained(),
                outcome.levelUp(),
                outcome.evolved(),
                outcome.messageKey(),
                outcome.cooldownUntil());
    }

    /**
     * 处理睡觉与唤醒的状态切换，返回因此获得的睡觉经验。
     *
     * <p>睡下时把 {@code sleepingSince} 对齐到结算游标，这样不足一小时的余数不会被丢掉。</p>
     */
    private int applySleepTransition(Pet pet, PetAction action, SettlementResult settlement) {
        return switch (action) {
            case SLEEP -> {
                // 对齐到结算游标，不足一小时的余数不会被丢掉
                pet.setSleepingSince(pet.getLastSettledAt());
                yield 0;
            }
            case WAKE -> {
                long sleptHours = settlement.wokeUp()
                        ? 0
                        : Duration.between(pet.getSleepingSince(), pet.getLastSettledAt()).toMinutes() / 60;
                pet.setSleepingSince(null);
                yield PetActionRules.sleepExp(sleptHours);
            }
            default -> 0;
        };
    }

    /** 属性变化后重算冗余的状态列。 */
    private static void refreshStatus(Pet pet) {
        pet.setStatus(PetStatus.resolve(pet.getSleepingSince() != null, pet.getSick(), attributesOf(pet)).name());
    }

    /** 重复请求：返回第一次保存的结果，但宠物状态用本次结算后的最新值。 */
    private ActionResponse replay(PetActionLog saved, Pet pet,
                                  SettlementResult settlement, int sleepExp) {
        ActionOutcome stored = readSaved(saved);
        if (settlement.changed() || sleepExp > 0) {
            if (sleepExp > 0) {
                progressService.apply(pet, sleepExp);
            }
            writer.touch(pet);
            writer.updateOrConflict(pet);
        }
        return toResponse(stored, pet);
    }

    private PetActionLog findLog(String clientRequestId) {
        return actionLogMapper.selectOne(
                Wrappers.lambdaQuery(PetActionLog.class)
                        .eq(PetActionLog::getClientRequestId, clientRequestId));
    }

    private void saveLog(Pet pet, PetAction action, String clientRequestId,
                         ActionOutcome outcome, Instant now) {
        PetActionLog entry = new PetActionLog();
        entry.setPetId(pet.getId());
        entry.setAction(action.name());
        entry.setClientRequestId(clientRequestId);
        entry.setResultJson(writeJson(outcome));
        entry.setCreatedAt(now);
        actionLogMapper.insert(entry);
    }

    private ActionOutcome readSaved(PetActionLog saved) {
        try {
            return objectMapper.readValue(saved.getResultJson(), ActionOutcome.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("操作日志 " + saved.getId() + " 的结果无法解析", exception);
        }
    }

    private String writeJson(ActionOutcome outcome) {
        try {
            return objectMapper.writeValueAsString(outcome);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("操作结果无法序列化", exception);
        }
    }

    /** 该操作是否还在冷却中；不在冷却或没有冷却则返回 {@code null}。 */
    private Instant cooldownUntil(Long petId, PetAction action, Instant now) {
        int seconds = PetActionRules.cooldownSeconds(action);
        if (seconds <= 0) {
            return null;
        }
        for (ActionUsage usage : actionLogMapper.selectLastUsedByAction(petId)) {
            if (!action.name().equals(usage.getAction()) || usage.getLastUsedAt() == null) {
                continue;
            }
            Instant until = usage.getLastUsedAt().plusSeconds(seconds);
            return until.isAfter(now) ? until : null;
        }
        return null;
    }

    private static Instant nextCooldownUntil(PetAction action, Instant now) {
        int seconds = PetActionRules.cooldownSeconds(action);
        return seconds <= 0 ? null : now.plusSeconds(seconds);
    }

    private static PetAttributes attributesOf(Pet pet) {
        return new PetAttributes(
                pet.getSatiety(), pet.getMood(), pet.getHygiene(), pet.getEnergy(), pet.getHealth());
    }

    /** 把属性写回实体。不碰健康，健康只由结算改变。 */
    private static void writeAttributes(Pet pet, PetAttributes attributes) {
        pet.setSatiety(attributes.satiety());
        pet.setMood(attributes.mood());
        pet.setHygiene(attributes.hygiene());
        pet.setEnergy(attributes.energy());
    }

    /** 操作的实际变化量。属性有上下限，所以这里返回的是生效后的真实差值。 */
    private static AttributeDeltas deltasBetween(PetAttributes before, PetAttributes after) {
        return new AttributeDeltas(
                after.satiety() - before.satiety(),
                after.mood() - before.mood(),
                after.hygiene() - before.hygiene(),
                after.energy() - before.energy(),
                after.health() - before.health());
    }

    private static String messageKey(PetAction action) {
        return action.name() + "_OK";
    }

    private static PetAction parseAction(String rawAction) {
        if (rawAction == null) {
            throw new BusinessException(ErrorCode.INVALID_ACTION);
        }
        try {
            return PetAction.valueOf(rawAction.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_ACTION);
        }
    }
}
