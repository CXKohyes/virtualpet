package com.virtualpet.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.virtualpet.common.BusinessException;
import com.virtualpet.common.ErrorCode;
import com.virtualpet.player.Player;
import com.virtualpet.player.PlayerMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 校验 {@code Authorization: Bearer <token>}（TECH_DESIGN 6.6）。
 *
 * <p>用令牌哈希查玩家，查不到一律返回 401，不区分「设备不存在」和「令牌错误」，
 * 避免泄露设备是否存在。</p>
 *
 * <p>这里只读不写：{@code players.last_seen_at} 由会话接口更新，因为拦截器里
 * 不方便开启事务（AGENTS.md 5.2 要求数据库更新必须在事务中完成）。</p>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final PlayerMapper playerMapper;
    private final TokenService tokenService;
    private final PlayerContext playerContext;

    public AuthInterceptor(PlayerMapper playerMapper, TokenService tokenService, PlayerContext playerContext) {
        this.playerMapper = playerMapper;
        this.tokenService = tokenService;
        this.playerContext = playerContext;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 跨域预检不带 Authorization，直接放行
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        Player player = playerMapper.selectOne(
                Wrappers.lambdaQuery(Player.class).eq(Player::getTokenHash, tokenService.hash(token)));

        if (player == null) {
            log.debug("无效令牌，拒绝访问 {}", request.getRequestURI());
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        playerContext.setPlayerId(player.getId());
        return true;
    }
}
