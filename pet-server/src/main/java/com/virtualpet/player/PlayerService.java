package com.virtualpet.player;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.virtualpet.auth.TokenService;
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

    private final PlayerMapper playerMapper;
    private final TokenService tokenService;
    private final Clock clock;

    public PlayerService(PlayerMapper playerMapper, TokenService tokenService, Clock clock) {
        this.playerMapper = playerMapper;
        this.tokenService = tokenService;
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
     * 会话结果。
     *
     * @param playerId 玩家 ID
     * @param token    明文令牌，只在这里出现一次
     */
    public record IssuedSession(Long playerId, String token) {
    }
}
