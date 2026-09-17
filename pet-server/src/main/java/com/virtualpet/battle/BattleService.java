package com.virtualpet.battle;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.virtualpet.common.BusinessException;
import com.virtualpet.common.ErrorCode;
import com.virtualpet.game.BattleReport;
import com.virtualpet.game.BattleSide;
import com.virtualpet.game.BattleSimulator;
import com.virtualpet.game.BattleSnapshot;
import com.virtualpet.pet.Pet;
import com.virtualpet.pet.PetConverter;
import com.virtualpet.pet.PetService;
import com.virtualpet.pet.PetSnapshot;
import com.virtualpet.pet.PetState;
import com.virtualpet.player.Player;
import com.virtualpet.player.PlayerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 异步对战（PRD 2.11）。
 *
 * <p>发起挑战的整个流程：取挑战方宠物 → 按好友码找到对方 → 取对方宠物 →
 * 双方各存一份快照 → 用固定种子跑一场确定性战斗 → 落库 → 通知。
 * 战斗本身在 {@link BattleSimulator} 里，这个类只管把数据凑齐和存好。</p>
 *
 * <p><b>快照取的是双方宠物结算之后的状态。</b>借用的是 {@link PetService#loadIfPresent}
 * 这条现成的读取路径，所以懒结算那套规则一条都没有被复制 ——
 * 对战这边完全不需要知道属性是怎么随时间变化的。</p>
 */
@Service
public class BattleService {

    private static final Logger log = LoggerFactory.getLogger(BattleService.class);

    /** 最近对战列表一次最多给多少条。 */
    private static final int MAX_LIST_LIMIT = 50;
    private static final int DEFAULT_LIST_LIMIT = 20;

    private final BattleMapper battleMapper;
    private final BattleSnapshotCodec codec;
    private final BattleNotifier notifier;
    private final PetService petService;
    private final PlayerService playerService;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public BattleService(BattleMapper battleMapper, BattleSnapshotCodec codec, BattleNotifier notifier,
                         PetService petService, PlayerService playerService, Clock clock) {
        this.battleMapper = battleMapper;
        this.codec = codec;
        this.notifier = notifier;
        this.petService = petService;
        this.playerService = playerService;
        this.clock = clock;
    }

    /**
     * 发起一场挑战并立刻打完。
     *
     * <p>战斗是同步算完的，因为确定性模拟本身只要微秒级，做成后台任务只会
     * 凭空多出一个中间态和一堆竞态。WebSocket 只负责在战报就绪后推一条通知，
     * 结果本身仍然由 REST 查询（PRD 2.11）。</p>
     *
     * @param challengerPlayerId 发起方玩家 ID
     * @param rawFriendCode      对方好友码，大小写不敏感
     * @return 打完之后的对战详情
     */
    public BattleResponse challenge(Long challengerPlayerId, String rawFriendCode) {
        String friendCode = rawFriendCode == null ? "" : rawFriendCode.strip().toUpperCase();

        Player defender = playerService.findByFriendCode(friendCode);
        if (defender == null) {
            throw new BusinessException(ErrorCode.FRIEND_CODE_NOT_FOUND);
        }
        if (defender.getId().equals(challengerPlayerId)) {
            throw new BusinessException(ErrorCode.SELF_CHALLENGE);
        }

        Pet challengerPet = requirePet(challengerPlayerId, ErrorCode.PET_NOT_FOUND);
        Pet defenderPet = requirePet(defender.getId(), ErrorCode.OPPONENT_NO_PET);

        BattleSnapshot challengerSnapshot = toBattleSnapshot(challengerPet);
        BattleSnapshot defenderSnapshot = toBattleSnapshot(defenderPet);

        long seed = random.nextLong();
        BattleReport report = BattleSimulator.simulate(challengerSnapshot, defenderSnapshot, seed);

        Instant now = Instant.now(clock);
        Battle battle = new Battle();
        battle.setChallengerPlayerId(challengerPlayerId);
        battle.setDefenderPlayerId(defender.getId());
        battle.setChallengerPetId(challengerPet.getId());
        battle.setDefenderPetId(defenderPet.getId());
        battle.setChallengerSnapshot(codec.writeSnapshot(challengerSnapshot));
        battle.setDefenderSnapshot(codec.writeSnapshot(defenderSnapshot));
        battle.setSeed(seed);
        battle.setStatus(BattleStatus.FINISHED.name());
        battle.setResultJson(codec.writeReport(report));
        battle.setCreatedAt(now);
        battle.setFinishedAt(now);
        battleMapper.insert(battle);

        log.info("对战 {}：玩家 {} 挑战玩家 {}（种子 {}），{} 回合，胜方 {}",
                battle.getId(), challengerPlayerId, defender.getId(), seed,
                report.totalRounds(), report.winner());

        BattleResponse response = toResponse(battle, challengerPlayerId);
        // 通知放在落库之后：订阅方收到推送就会来查，那时候记录必须已经在了
        notifier.publishFinished(response);
        return response;
    }

    /**
     * 查一场对战。
     *
     * <p>只有参战的双方能查 —— 对战记录里有双方宠物的完整状态，
     * 拿到 battleId 就能看别人家宠物的底细，不该对外开放。</p>
     */
    public BattleResponse find(Long battleId, Long viewerPlayerId) {
        Battle battle = battleMapper.selectById(battleId);
        if (battle == null || !isParticipant(battle, viewerPlayerId)) {
            throw new BusinessException(ErrorCode.BATTLE_NOT_FOUND);
        }
        return toResponse(battle, viewerPlayerId);
    }

    /** 我的最近对战，新的在前。 */
    public List<BattleResponse.BattleSummary> recent(Long playerId, Integer limit) {
        int size = limit == null
                ? DEFAULT_LIST_LIMIT
                : Math.min(Math.max(limit, 1), MAX_LIST_LIMIT);

        List<Battle> battles = battleMapper.selectList(
                Wrappers.lambdaQuery(Battle.class)
                        .and(wrapper -> wrapper
                                .eq(Battle::getChallengerPlayerId, playerId)
                                .or()
                                .eq(Battle::getDefenderPlayerId, playerId))
                        .orderByDesc(Battle::getId)
                        .last("LIMIT " + size));

        return battles.stream().map(battle -> toSummary(battle, playerId)).toList();
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 取宠物并<b>走一遍懒结算</b>，和普通读取路径完全一致。
     *
     * <p>不直接用 {@code requirePet}：那样拿到的是上次结算后的陈旧属性，
     * 会让人"离线十天回来还能满血上场"。</p>
     */
    private Pet requirePet(Long playerId, ErrorCode missing) {
        Optional<PetSnapshot> snapshot = petService.loadIfPresent(playerId);
        return snapshot.map(PetSnapshot::pet)
                .orElseThrow(() -> new BusinessException(missing));
    }

    private BattleSnapshot toBattleSnapshot(Pet pet) {
        PetState state = PetConverter.toState(pet);
        return new BattleSnapshot(
                pet.getId(),
                pet.getName(),
                state.species(),
                pet.getLevel(),
                pet.getEvolutionStage(),
                state.attributes());
    }

    private boolean isParticipant(Battle battle, Long playerId) {
        return playerId.equals(battle.getChallengerPlayerId()) || playerId.equals(battle.getDefenderPlayerId());
    }

    private BattleSide viewerSide(Battle battle, Long playerId) {
        return playerId.equals(battle.getChallengerPlayerId()) ? BattleSide.CHALLENGER : BattleSide.DEFENDER;
    }

    private BattleResponse toResponse(Battle battle, Long viewerPlayerId) {
        BattleSnapshot challenger = codec.readSnapshot(battle.getChallengerSnapshot());
        BattleSnapshot defender = codec.readSnapshot(battle.getDefenderSnapshot());
        BattleReport report = battle.getResultJson() == null ? null : codec.readReport(battle.getResultJson());

        return new BattleResponse(
                battle.getId(),
                battle.getStatus(),
                viewerSide(battle, viewerPlayerId),
                toBattlePet(challenger),
                toBattlePet(defender),
                report == null ? null : report.winner(),
                report == null ? null : report.outcome(),
                report == null ? 0 : report.totalRounds(),
                battle.getSeed(),
                report == null ? List.of() : report.timeline(),
                battle.getCreatedAt(),
                battle.getFinishedAt());
    }

    private BattleResponse.BattlePet toBattlePet(BattleSnapshot snapshot) {
        return new BattleResponse.BattlePet(
                snapshot.petId(),
                snapshot.name(),
                snapshot.species().name(),
                snapshot.level(),
                snapshot.evolutionStage(),
                snapshot.maxHp(),
                snapshot.attack(),
                snapshot.defense(),
                snapshot.speed());
    }

    private BattleResponse.BattleSummary toSummary(Battle battle, Long viewerPlayerId) {
        BattleSide viewer = viewerSide(battle, viewerPlayerId);
        BattleSide opponentSide = viewer.opponent();
        BattleSnapshot opponent = codec.readSnapshot(
                opponentSide == BattleSide.CHALLENGER ? battle.getChallengerSnapshot() : battle.getDefenderSnapshot());
        BattleReport report = battle.getResultJson() == null ? null : codec.readReport(battle.getResultJson());

        return new BattleResponse.BattleSummary(
                battle.getId(),
                battle.getStatus(),
                viewer,
                report == null ? null : report.winner(),
                opponent.name(),
                opponent.species().name(),
                report == null ? 0 : report.totalRounds(),
                battle.getCreatedAt());
    }
}
