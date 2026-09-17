package com.virtualpet.player;

import com.virtualpet.common.ApiResponse;
import com.virtualpet.pet.PetResponse;
import com.virtualpet.pet.PetService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 匿名会话接口（TECH_DESIGN 5.1）。这是唯一不需要令牌的写接口。
 */
@RestController
@RequestMapping("/api/v1/session")
public class PlayerController {

    private final PlayerService playerService;
    private final PetService petService;

    public PlayerController(PlayerService playerService, PetService petService) {
        this.playerService = playerService;
        this.petService = petService;
    }

    /**
     * 创建或恢复会话。
     *
     * <p>先提交会话（换发令牌），再结算宠物，两步分开是因为结算需要独立的事务
     * 才能做乐观锁重试。</p>
     */
    @PostMapping
    public ApiResponse<SessionResponse> establish(@Valid @RequestBody SessionRequest request) {
        PlayerService.IssuedSession session = playerService.establish(request.deviceId().strip());

        PetResponse petResponse = petService.loadIfPresent(session.playerId())
                .map(snapshot -> petService.toResponse(snapshot.pet(), snapshot.settlement()))
                .orElse(null);

        return ApiResponse.ok(new SessionResponse(session.playerId(), session.token(), petResponse));
    }
}
