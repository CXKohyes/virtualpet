package com.virtualpet.auth;

import com.virtualpet.common.BusinessException;
import com.virtualpet.common.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * 当前请求所属的玩家。
 *
 * <p>请求作用域：由 {@link AuthInterceptor} 在通过鉴权后写入，Service 和 Controller
 * 通过它取玩家 ID，避免把 playerId 一路当参数传下去。</p>
 *
 * <p>写不进去就说明这个接口没经过鉴权拦截，取用时直接按未授权处理。</p>
 */
@Component
@RequestScope
public class PlayerContext {

    private Long playerId;

    public void setPlayerId(Long playerId) {
        this.playerId = playerId;
    }

    public Long playerId() {
        if (playerId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return playerId;
    }
}
