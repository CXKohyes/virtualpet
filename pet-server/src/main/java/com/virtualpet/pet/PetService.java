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
import com.virtualpet.player.PlayerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 宠物的读取、创建、切换与送走（PRD 2.1、2.2）。
 *
 * <p>所有对外读取都会先做懒结算（TECH_DESIGN 6.1：「所有服务端读取和写入路径都必须先结算」）。</p>
 *
 * <h2>多宠物槽之后，「宠物」这个词默认指「当前宠物」</h2>
 *
 * <p>玩家的宠物最多 {@link GameRules#MAX_PET_SLOTS} 只，其中一只是<b>当前宠物</b>，
 * 记在 {@code players.active_pet_id}。{@link #findPet}/{@link #requirePet}/{@link #load}
 * 这些不带 ID 的方法全都指当前宠物 —— 四个操作、照护日志、对战因此都不用改签名。
 * 名册和切换是另外几条路径（{@link #roster}、{@link #activate}）。</p>
 */
@Service
public class PetService {

    private static final Logger log = LoggerFactory.getLogger(PetService.class);

    private final PetMapper petMapper;
    private final PetActionLogMapper actionLogMapper;
    private final PetWriter writer;
    private final PetProgressService progressService;
    private final PlayerService playerService;
    private final Clock clock;

    public PetService(PetMapper petMapper,
                      PetActionLogMapper actionLogMapper,
                      PetWriter writer,
                      PetProgressService progressService,
                      PlayerService playerService,
                      Clock clock) {
        this.petMapper = petMapper;
        this.actionLogMapper = actionLogMapper;
        this.writer = writer;
        this.progressService = progressService;
        this.playerService = playerService;
        this.clock = clock;
    }

    // ------------------------------------------------------------ 当前宠物

    /**
     * 解析玩家的当前宠物，没有宠物返回 {@code null}。不结算也不开事务，供其他服务在事务内复用。
     *
     * <p>优先按 {@code players.active_pet_id} 查主键。这个字段为空、或者指向一只已经
     * 不属于该玩家的宠物时，<b>回落到槽位最小的那只</b>。这条兜底不是可有可无的：
     * 迁移的回填漏跑、或者历史上手工改过库，都会让 {@code active_pet_id} 落空，
     * 而那时如果直接返回 {@code null}，玩家会被告知「还没有领养宠物」——
     * 宠物其实好好躺在库里，只是没人指得动它。</p>
     *
     * <p>兜底路径是只读的，不会顺手把 {@code active_pet_id} 补上：这个方法被约定为
     * 「不写库」，而它是在各种事务内外到处被调用的。修正在真正发生切换/领养/送走时才落库。</p>
     */
    public Pet findPet(Long playerId) {
        Long activeId = playerService.activePetId(playerId);
        if (activeId != null) {
            Pet active = petMapper.selectById(activeId);
            if (active != null && playerId.equals(active.getPlayerId())) {
                return active;
            }
        }
        return firstPet(playerId);
    }

    /**
     * 查询当前宠物，不存在则抛 {@link ErrorCode#PET_NOT_FOUND}。
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

    /** 该玩家槽位最小的那只，没有则 {@code null}。 */
    private Pet firstPet(Long playerId) {
        return petMapper.selectOne(Wrappers.lambdaQuery(Pet.class)
                .eq(Pet::getPlayerId, playerId)
                .orderByAsc(Pet::getSlot)
                // 加 LIMIT 是因为 selectOne 在两行以上会抛 TooManyResultsException，
                // 而这里的语义本来就是「取第一只」
                .last("LIMIT 1"));
    }

    /**
     * 取指定宠物并确认它属于这个玩家。
     *
     * <p>「不存在」和「不是你的」返回同一个 {@link ErrorCode#PET_NOT_FOUND} ——
     * 与好友码、对战记录的处理一致（TECH_DESIGN 6.6）：能区分就等于给了
     * 一个用别人的宠物 ID 探测存档是否存在的接口。</p>
     */
    public Pet requireOwnedPet(Long playerId, Long petId) {
        Pet pet = petId == null ? null : petMapper.selectById(petId);
        if (pet == null || !playerId.equals(pet.getPlayerId())) {
            throw new BusinessException(ErrorCode.PET_NOT_FOUND);
        }
        return pet;
    }

    /** 该玩家的全部宠物，按槽位升序。不结算、不开事务。 */
    public List<Pet> listPets(Long playerId) {
        return petMapper.selectList(Wrappers.lambdaQuery(Pet.class)
                .eq(Pet::getPlayerId, playerId)
                .orderByAsc(Pet::getSlot));
    }

    // ------------------------------------------------------------ 领养

    /**
     * 领养宠物（TECH_DESIGN 5.2、PRD 2.2）。
     *
     * <p>占用**最小的空闲槽**，而不是「当前数量 + 1」：送走中间某只之后，
     * 那个槽必须能被重新用上，否则三个槽位用满再送走一只就再也领养不了了。</p>
     *
     * <p>刚领养的这只同时成为当前宠物 —— 玩家刚给它起完名字，想看的当然是它。</p>
     *
     * @throws BusinessException {@link ErrorCode#PET_SLOTS_FULL} 槽位已满；
     *                           {@link ErrorCode#INVALID_NAME} 名字不合法
     */
    @Transactional
    public Pet create(Long playerId, Species species, String rawName) {
        String name = normalizeName(rawName);
        List<Pet> existing = listPets(playerId);
        if (existing.size() >= GameRules.MAX_PET_SLOTS) {
            throw new BusinessException(ErrorCode.PET_SLOTS_FULL);
        }
        int slot = lowestFreeSlot(existing);

        Instant now = Instant.now(clock);
        PetAttributes attributes = new PetAttributes(
                GameRules.INITIAL_CORE_ATTRIBUTE,
                GameRules.INITIAL_CORE_ATTRIBUTE,
                GameRules.INITIAL_CORE_ATTRIBUTE,
                GameRules.INITIAL_CORE_ATTRIBUTE,
                GameRules.INITIAL_HEALTH);

        Pet pet = new Pet();
        pet.setPlayerId(playerId);
        pet.setSlot(slot);
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

        playerService.setActivePet(playerId, pet.getId());

        log.info("玩家 {} 领养了 {} 宠物 {}（槽位 {}）", playerId, species, pet.getId(), slot);
        return pet;
    }

    /** {@code 0 .. MAX_PET_SLOTS-1} 里第一个没被占用的槽位。 */
    private int lowestFreeSlot(List<Pet> existing) {
        Set<Integer> used = new HashSet<>();
        for (Pet pet : existing) {
            if (pet.getSlot() != null) {
                used.add(pet.getSlot());
            }
        }
        for (int slot = 0; slot < GameRules.MAX_PET_SLOTS; slot += 1) {
            if (!used.contains(slot)) {
                return slot;
            }
        }
        // 容量在调用方已经判过，正常到不了这里。真到了说明库里的 slot 超了范围
        // （应用层管上限，数据库只管 (player_id, slot) 唯一）
        throw new BusinessException(ErrorCode.PET_SLOTS_FULL);
    }

    // ------------------------------------------------------------ 切换与送走

    /**
     * 切换当前宠物（PRD 2.1）。
     *
     * <p>返回切换后<b>已结算</b>的那只，于是「切过去」这个动作本身就带回了
     * 「你不在时它怎么样了」。刻意不做成一个大事务：写当前宠物是一次单行更新，
     * 结算走 {@link PetWriter#runWithRetry} 自己的事务边界（它靠「回滚重开事务」
     * 读最新版本，包进外层事务会让重试失效）。</p>
     */
    public PetSnapshot activate(Long playerId, Long petId) {
        requireOwnedPet(playerId, petId);
        playerService.setActivePet(playerId, petId);
        return load(playerId);
    }

    /**
     * 送走一只宠物：删除它和它的全部照护日志（PRD 2.1）。
     *
     * <p>送走的如果正是当前宠物，在<b>同一个事务里</b>把当前宠物改成剩下里槽位最小的那只；
     * 一只都不剩就置空，前端据此回到领养页。</p>
     *
     * <p>对战记录不受影响：{@code battles} 里存的是开战时的快照，
     * {@code *_pet_id} 也刻意没有外键（见 002-battles.sql），删宠物不会破坏历史战报。</p>
     */
    @Transactional
    public void release(Long playerId, Long petId) {
        Pet pet = requireOwnedPet(playerId, petId);

        // 先删日志再删宠物，满足外键约束
        actionLogMapper.delete(Wrappers.lambdaQuery(PetActionLog.class).eq(PetActionLog::getPetId, pet.getId()));
        petMapper.deleteById(pet.getId());

        if (pet.getId().equals(playerService.activePetId(playerId))) {
            Pet next = firstPet(playerId);
            playerService.setActivePet(playerId, next == null ? null : next.getId());
        }
        log.info("玩家 {} 送走了宠物 {}", playerId, pet.getId());
    }

    /**
     * 送走当前宠物，等价于对当前宠物调 {@link #release}。
     *
     * <p>保留它是因为 {@code DELETE /pets/me} 这条路由还在用；接口层改成按 ID 送走之后
     * 它就没有调用方了。</p>
     */
    @Transactional
    public void reset(Long playerId) {
        Pet pet = findPet(playerId);
        if (pet == null) {
            return;
        }
        release(playerId, pet.getId());
    }

    // ------------------------------------------------------------ 读取与结算

    /**
     * 读取当前宠物并结算到当前时刻。
     *
     * <p>睡觉期间如果自动醒来，这里同时补发睡觉经验。
     * 返回的 {@link PetSnapshot#settlement()} 是本次结算的变化摘要，
     * 供前端展示「你不在时发生了什么」（PRD 2.5、4.3）。</p>
     */
    public PetSnapshot load(Long playerId) {
        return loadIfPresent(playerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PET_NOT_FOUND));
    }

    /**
     * 读取当前宠物并结算，还没有领养时返回空。
     *
     * <p>会话接口用它，省去"先查一次在不在、再读一次"的两次查询。</p>
     */
    public Optional<PetSnapshot> loadIfPresent(Long playerId) {
        return writer.runWithRetry(() -> {
            Pet pet = findPet(playerId);
            if (pet == null) {
                return Optional.<PetSnapshot>empty();
            }
            return Optional.of(settleAndSnapshot(pet));
        });
    }

    /**
     * 名册：该玩家的全部宠物，按槽位升序，<b>每只都结算到当前时刻</b>。
     *
     * <p>为什么要全部结算：名册的用处就是一眼看出谁快不行了。只读不结算的话，
     * 列表显示的是上次读取时的旧值 —— 一只正在挨饿的宠物在名册上看着好好的，
     * 切过去才发现，那这个名册就白给了。</p>
     *
     * <p>代价是 N 次结算（N ≤ {@link GameRules#MAX_PET_SLOTS}，每次 O(1)），
     * 每只各自一个事务。**刻意不包成一个大事务**：一是 {@link PetWriter#runWithRetry}
     * 靠回滚重开事务来读最新版本，包进外层事务会让重试失效；二是其中一只发生乐观锁
     * 冲突时，不该让另外两只的结算白做一遍。</p>
     */
    public List<PetResponse> roster(Long playerId) {
        Long activeId = playerService.activePetId(playerId);
        return listPets(playerId).stream()
                .map(pet -> {
                    PetSnapshot snapshot = loadById(pet.getId());
                    return toResponse(snapshot.pet(), snapshot.settlement(), pet.getId().equals(activeId));
                })
                .toList();
    }

    /** 按 ID 读取并结算一只宠物。名册用，不走「当前宠物」那套解析。 */
    private PetSnapshot loadById(Long petId) {
        return writer.runWithRetry(() -> {
            Pet pet = petMapper.selectById(petId);
            if (pet == null) {
                throw new BusinessException(ErrorCode.PET_NOT_FOUND);
            }
            return settleAndSnapshot(pet);
        });
    }

    /**
     * 结算一只宠物并落库，返回快照。
     *
     * <p>必须在 {@link PetWriter#runWithRetry} 开出来的事务里调用。</p>
     */
    private PetSnapshot settleAndSnapshot(Pet pet) {
        SettlementResult settlement = writer.settle(pet);
        if (!settlement.changed()) {
            return PetSnapshot.withoutSettlement(pet);
        }

        // 结算前的状态要在写入之前抓，写完就看不到了
        PetStatus statusBefore = PetConverter.toState(pet).status();

        writer.applySettlement(pet, settlement);
        if (settlement.wokeUp()) {
            int sleepExp = PetActionRules.sleepExp(settlement.sleptHours());
            progressService.apply(pet, sleepExp);
            log.info("宠物 {} 自动醒来，睡了 {} 小时，获得 {} 点经验",
                    pet.getId(), settlement.sleptHours(), sleepExp);
        }
        writer.touch(pet);
        writer.updateOrConflict(pet);

        SettlementSummary summary = new SettlementSummary(
                settlement.settledHours(),
                AttributeDeltas.between(settlement.before(), settlement.after()),
                statusBefore.name(),
                pet.getStatus(),
                settlement.wokeUp(),
                settlement.sleptHours());
        return new PetSnapshot(pet, summary);
    }

    // ------------------------------------------------------------ 对外表示

    /** 不涉及懒结算的路径用它（领养、执行操作），{@code settlement} 为 null。 */
    public PetResponse toResponse(Pet pet) {
        return toResponse(pet, null);
    }

    /**
     * 实体转对外响应，同时算出还在冷却中的操作。
     *
     * <p>{@code active} 默认为 {@code true}：调用这个重载的地方（{@code /pets/me}、
     * 领养、执行操作、会话）拿到的本来就是当前宠物。名册是唯一的例外，
     * 它用三参数版本逐只标出是不是当前那只。</p>
     */
    public PetResponse toResponse(Pet pet, SettlementSummary settlement) {
        return toResponse(pet, settlement, true);
    }

    /** 实体转对外响应，显式指定是不是当前宠物。 */
    public PetResponse toResponse(Pet pet, SettlementSummary settlement, boolean active) {
        return new PetResponse(
                pet.getId(),
                pet.getSlot(),
                active,
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
                activeCooldowns(pet.getId()),
                settlement);
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
