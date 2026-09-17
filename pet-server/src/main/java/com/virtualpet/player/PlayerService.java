package com.virtualpet.player;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.virtualpet.auth.TokenService;
import com.virtualpet.common.BusinessException;
import com.virtualpet.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 匿名玩家会话（TECH_DESIGN 5.1）。
 *
 * <p>设备 ID 是玩家的唯一身份：同一台设备再次请求会话时不会新建玩家，
 * 但会换发新令牌，旧令牌立即失效（服务端只保存哈希，无法回显旧令牌）。</p>
 */
@Service
public class PlayerService {

    private static final Logger log = LoggerFactory.getLogger(PlayerService.class);

    /** 好友码撞码后的重试上限。 */
    private static final int MAX_FRIEND_CODE_ATTEMPTS = 10;

    private final PlayerMapper playerMapper;
    private final TokenService tokenService;
    private final FriendCodeGenerator friendCodeGenerator;
    private final Clock clock;

    public PlayerService(PlayerMapper playerMapper, TokenService tokenService,
                         FriendCodeGenerator friendCodeGenerator, Clock clock) {
        this.playerMapper = playerMapper;
        this.tokenService = tokenService;
        this.friendCodeGenerator = friendCodeGenerator;
        this.clock = clock;
    }

    /**
     * 建立或恢复匿名会话。
     *
     * @param deviceId 浏览器生成的随机设备 ID，必须是去空格后的值
     * @return 玩家 ID 和只在本次返回的明文令牌
     */
    @Transactional
    public IssuedSession establish(String deviceId) {
        Instant now = Instant.now(clock);
        String token = tokenService.newToken();
        String tokenHash = tokenService.hash(token);

        Player player = playerMapper.selectOne(
                Wrappers.lambdaQuery(Player.class).eq(Player::getDeviceId, deviceId));

        if (player == null) {
            player = new Player();
            player.setDeviceId(deviceId);
            player.setTokenHash(tokenHash);
            player.setCreatedAt(now);
            player.setLastSeenAt(now);
            playerMapper.insert(player);
            log.info("创建匿名玩家 {}", player.getId());
        } else {
            player.setTokenHash(tokenHash);
            player.setLastSeenAt(now);
            playerMapper.updateById(player);
        }

        return new IssuedSession(player.getId(), token);
    }

    /**
     * 取玩家的好友码，没有就现生成一个（PRD 2.11）。
     *
     * <p>懒生成而不是建号时就发：绝大多数玩家不会去对战，没必要给每个匿名玩家
     * 都占一个唯一码。第一次查看或第一次被挑战时才生成。</p>
     *
     * <p>撞码由数据库的唯一索引兜底 —— 概率极低，但真撞上了必须重试而不是返回 500。</p>
     *
     * @param playerId 玩家 ID
     * @return 该玩家的好友码，已存在则原样返回
     */
    @Transactional
    public String ensureFriendCode(Long playerId) {
        Player player = requirePlayer(playerId);
        if (player.getFriendCode() != null && !player.getFriendCode().isBlank()) {
            return player.getFriendCode();
        }

        // 重试上限给得宽松一点：撞码本身极罕见，真连环撞上说明有别的问题，
        // 与其无限循环不如抛出来
        for (int attempt = 0; attempt < MAX_FRIEND_CODE_ATTEMPTS; attempt += 1) {
            String candidate = friendCodeGenerator.next();
            if (findByFriendCode(candidate) != null) {
                continue;
            }
            player.setFriendCode(candidate);
            playerMapper.updateById(player);
            log.info("玩家 {} 领到好友码", playerId);
            return candidate;
        }
        throw new BusinessException(ErrorCode.CONFLICT, "好友码生成失败，请重试");
    }

    /**
     * 按好友码找玩家（PRD 2.11）。
     *
     * @return 对应玩家；不存在返回 {@code null}
     */
    public Player findByFriendCode(String friendCode) {
        return playerMapper.selectOne(
                Wrappers.lambdaQuery(Player.class).eq(Player::getFriendCode, friendCode));
    }

    /** 取玩家，不存在直接抛 401 —— 有令牌却查不到玩家说明令牌已经失效。 */
    public Player requirePlayer(Long playerId) {
        Player player = playerMapper.selectById(playerId);
        if (player == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return player;
    }

    /**
     * 会话结果。
     *
     * @param playerId 玩家 ID
     * @param token    明文令牌，只在这里出现一次
     */
    public record IssuedSession(Long playerId, String token) {
    }
}
