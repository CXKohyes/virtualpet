package com.virtualpet.player;

import com.virtualpet.auth.PlayerContext;
import com.virtualpet.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 玩家资料接口（PRD 2.11 好友码）。
 *
 * <p>好友码是懒生成的：第一次来取才发。绝大多数匿名玩家不会去对战，
 * 没必要每个人一建号就占一个唯一码。</p>
 */
@RestController
@RequestMapping("/api/v1/players")
public class PlayerProfileController {

    private final PlayerService playerService;
    private final PlayerContext playerContext;

    public PlayerProfileController(PlayerService playerService, PlayerContext playerContext) {
        this.playerService = playerService;
        this.playerContext = playerContext;
    }

    /**
     * 取我的好友码，还没有就现发一个。
     *
     * <p>这个接口是幂等的：同一个人反复调用拿到的是同一个码。</p>
     */
    @GetMapping("/me/friend-code")
    public ApiResponse<Map<String, Object>> friendCode() {
        String code = playerService.ensureFriendCode(playerContext.playerId());
        return ApiResponse.ok(Map.of(
                "friendCode", code,
                "length", FriendCodeGenerator.length()));
    }
}
