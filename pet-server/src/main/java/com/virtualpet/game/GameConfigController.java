package com.virtualpet.game;

import com.virtualpet.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

/**
 * 游戏配置接口（TECH_DESIGN 5.5）。
 *
 * <p>免鉴权。前端只缓存这些数值用于展示，<b>不得据此自己计算衰减、经验或进化</b>。</p>
 */
@RestController
@RequestMapping("/api/v1/game")
public class GameConfigController {

    @GetMapping("/config")
    public ApiResponse<GameConfigResponse> config() {
        GameConfigResponse response = new GameConfigResponse(
                GameRules.OFFLINE_CAP_HOURS,
                GameRules.MAX_LEVEL,
                GameRules.MAX_PET_SLOTS,
                GameRules.expThresholds(),
                Arrays.stream(Species.values()).map(GameConfigResponse.SpeciesConfig::from).toList(),
                Arrays.stream(PetAction.values()).map(GameConfigResponse.ActionConfig::from).toList(),
                GameConfigResponse.EvolutionConfig.all());
        return ApiResponse.ok(response);
    }
}
