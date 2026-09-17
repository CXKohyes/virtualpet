package com.virtualpet.battle;

import com.virtualpet.auth.PlayerContext;
import com.virtualpet.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 异步对战接口（PRD 2.11）。
 *
 * <p>战斗结果一律通过 REST 查询；WebSocket 只推"战报就绪"这一条通知，
 * 不承担传数据。所有游戏数值都在服务端算，客户端不参与。</p>
 */
@RestController
@RequestMapping("/api/v1/battles")
public class BattleController {

    private final BattleService battleService;
    private final PlayerContext playerContext;

    public BattleController(BattleService battleService, PlayerContext playerContext) {
        this.battleService = battleService;
        this.playerContext = playerContext;
    }

    /** 用好友码发起挑战；服务端立刻算完并返回战报。 */
    @PostMapping
    public ApiResponse<BattleResponse> challenge(@Valid @RequestBody ChallengeRequest request) {
        return ApiResponse.ok(battleService.challenge(playerContext.playerId(), request.friendCode()));
    }

    /** 我的最近对战，新的在前。 */
    @GetMapping
    public ApiResponse<List<BattleResponse.BattleSummary>> recent(
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(battleService.recent(playerContext.playerId(), limit));
    }

    /** 某一场的完整战报。只有参战双方能查。 */
    @GetMapping("/{battleId}")
    public ApiResponse<BattleResponse> detail(@PathVariable Long battleId) {
        return ApiResponse.ok(battleService.find(battleId, playerContext.playerId()));
    }

    /** 战报通知的订阅主题，让前端不用把这个路径写死在代码里。 */
    @GetMapping("/topic")
    public ApiResponse<Map<String, String>> topic() {
        return ApiResponse.ok(Map.of(
                "prefix", BattleNotifier.topicPrefix(),
                "pattern", BattleNotifier.topicPrefix() + "{battleId}"));
    }
}
