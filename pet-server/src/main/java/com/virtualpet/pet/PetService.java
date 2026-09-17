package com.virtualpet.pet;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.virtualpet.common.BusinessException;
import com.virtualpet.common.ErrorCode;
import com.virtualpet.game.EvolutionStage;
import com.virtualpet.game.GameRules;
import com.virtualpet.game.PetAction;
import com.virtualpet.game.PetActionRules;
import com.virtualpet.game.PetAttributes;
import com.virtualpet.game.PetStatus;
import com.virtualpet.game.Species;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 宠物的读取、创建与重置。
 *
 * <p>所有对外读取都会先做懒结算（TECH_DESIGN 6.1：「所有服务端读取和写入路径都必须先结算」）。</p>
 */
@Service
public class PetService {

    private static final Logger log = LoggerFactory.getLogger(PetService.class);

    private final PetMapper petMapper;
    private final PetActionLogMapper actionLogMapper;
    private final PetWriter writer;
    private final PetProgressService progressService;
    private final Clock clock;

    public PetService(PetMapper petMapper,
                      PetActionLogMapper actionLogMapper,
                      PetWriter writer,
                      PetProgressService progressService,
                      Clock clock) {
        this.petMapper = petMapper;
        this.actionLogMapper = actionLogMapper;
        this.writer = writer;
        this.progressService = progressService;
        this.clock = clock;
    }

    /** 查询宠物，不存在返回 {@code null}。不结算也不开事务，供其他服务在事务内复用。 */
    public Pet findPet(Long playerId) {
        return petMapper.selectOne(Wrappers.lambdaQuery(Pet.class).eq(Pet::getPlayerId, playerId));
    }

    /**
     * 查询宠物，不存在则抛 {@link ErrorCode#PET_NOT_FOUND}。
     *
     * <p>故意不加事务注解：调用方通常在自己的事务里用它，加了会变成嵌套语义。</p>
     */
    public Pet requirePet(Long playerId) {
        Pet pet = findPet(playerId);
        if (pet == null) {
            throw new BusinessException(ErrorCode.PET_NOT_FOUND);
        }
        return pet;
    }

    /**
     * 领养宠物（TECH_DESIGN 5.2）。
     *
     * @throws BusinessException {@link ErrorCode#PET_ALREADY_EXISTS} 已有宠物；
     *                           {@link ErrorCode#INVALID_NAME} 名字不合法
     */
    @Transactional
    public Pet create(Long playerId, Species species, String rawName) {
        String name = normalizeName(rawName);
        if (findPet(playerId) != null) {
            throw new BusinessException(ErrorCode.PET_ALREADY_EXISTS);
        }

        Instant now = Instant.now(clock);
        PetAttributes attributes = new PetAttributes(
                GameRules.INITIAL_CORE_ATTRIBUTE,
                GameRules.INITIAL_CORE_ATTRIBUTE,
                GameRules.INITIAL_CORE_ATTRIBUTE,
                GameRules.INITIAL_CORE_ATTRIBUTE,
                GameRules.INITIAL_HEALTH);

        Pet pet = new Pet();
        pet.setPlayerId(playerId);
        pet.setSpecies(species.name());
        pet.setName(name);
        pet.setSatiety(attributes.satiety());
        pet.setMood(attributes.mood());
        pet.setHygiene(attributes.hygiene());
        pet.setEnergy(attributes.energy());
        pet.setHealth(attributes.health());
        pet.setSick(false);
        pet.setStatus(PetStatus.resolve(false, false, attributes).name());
        pet.setLevel(GameRules.MIN_LEVEL);
        pet.setExp(0);
        pet.setEvolutionStage(EvolutionStage.JUVENILE.code());
        pet.setSleepingSince(null);
        pet.setLastSettledAt(now);
        pet.setVersion(0);
        pet.setCreatedAt(now);
        pet.setUpdatedAt(now);
        petMapper.insert(pet);

        log.info("玩家 {} 领养了 {} 宠物 {}", playerId, species, pet.getId());
        return pet;
    }

    /**
     * 读取宠物并结算到当前时刻。
     *
     * <p>睡觉期间如果自动醒来，这里同时补发睡觉经验。</p>
     */
    public Pet load(Long playerId) {
        return writer.runWithRetry(() -> {
            Pet pet = requirePet(playerId);

            SettlementResult settlement = writer.settle(pet);
            if (!settlement.changed()) {
                return pet;
            }

            writer.applySettlement(pet, settlement);
            if (settlement.wokeUp()) {
                int sleepExp = PetActionRules.sleepExp(settlement.sleptHours());
                progressService.apply(pet, sleepExp);
                log.info("宠物 {} 自动醒来，睡了 {} 小时，获得 {} 点经验",
                        pet.getId(), settlement.sleptHours(), sleepExp);
            }
            writer.touch(pet);
            writer.updateOrConflict(pet);
            return pet;
        });
    }

    /**
     * 重置存档：删除宠物及其操作日志，保留玩家记录以便重新领养（TECH_DESIGN 5.6）。
     *
     * <p>没有宠物时直接返回，重复调用是安全的。</p>
     */
    @Transactional
    public void reset(Long playerId) {
        Pet pet = findPet(playerId);
        if (pet == null) {
            return;
        }
        // 先删日志再删宠物，满足外键约束
        actionLogMapper.delete(Wrappers.lambdaQuery(PetActionLog.class).eq(PetActionLog::getPetId, pet.getId()));
        petMapper.deleteById(pet.getId());
        log.info("玩家 {} 重置了存档，删除宠物 {}", playerId, pet.getId());
    }

    /** 实体转对外响应，同时算出还在冷却中的操作。 */
    public PetResponse toResponse(Pet pet) {
        return new PetResponse(
                pet.getId(),
                pet.getSpecies(),
                pet.getName(),
                pet.getSatiety(),
                pet.getMood(),
                pet.getHygiene(),
                pet.getEnergy(),
                pet.getHealth(),
                pet.getStatus(),
                pet.getLevel(),
                pet.getExp(),
                pet.getEvolutionStage(),
                pet.getSleepingSince(),
                pet.getLastSettledAt(),
                activeCooldowns(pet.getId()));
    }

    /** 仍在冷却中的操作 -> 冷却结束时刻。无冷却的操作不会出现在结果里。 */
    private Map<String, Instant> activeCooldowns(Long petId) {
        Instant now = Instant.now(clock);
        Map<String, Instant> cooldowns = new LinkedHashMap<>();
        for (ActionUsage usage : actionLogMapper.selectLastUsedByAction(petId)) {
            PetAction action = parseStoredAction(usage.getAction());
            if (action == null) {
                continue;
            }
            int seconds = PetActionRules.cooldownSeconds(action);
            if (seconds <= 0 || usage.getLastUsedAt() == null) {
                continue;
            }
            Instant until = usage.getLastUsedAt().plusSeconds(seconds);
            if (until.isAfter(now)) {
                cooldowns.put(action.name(), until);
            }
        }
        return cooldowns;
    }

    private static PetAction parseStoredAction(String raw) {
        try {
            return PetAction.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            // 历史日志里出现了当前版本不认识的操作，跳过而不是让整个接口失败
            return null;
        }
    }

    /** 去首尾空格、校验长度与字符集（PRD 2.2、6.2）。 */
    private String normalizeName(String rawName) {
        if (rawName == null) {
            throw new BusinessException(ErrorCode.INVALID_NAME);
        }
        String trimmed = rawName.strip();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_NAME, "名字不能为空");
        }
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < GameRules.PET_NAME_MIN_LENGTH || length > GameRules.PET_NAME_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_NAME,
                    "名字需要 " + GameRules.PET_NAME_MIN_LENGTH + "–" + GameRules.PET_NAME_MAX_LENGTH + " 个字符");
        }
        // 只允许字母和数字（涵盖中文、英文和数字），空白、标点和 HTML 特殊字符一律拒绝
        if (trimmed.codePoints().anyMatch(codePoint -> !Character.isLetterOrDigit(codePoint))) {
            throw new BusinessException(ErrorCode.INVALID_NAME, "名字只能包含中文、英文和数字");
        }
        return trimmed;
    }
}
