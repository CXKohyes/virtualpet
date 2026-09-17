package com.virtualpet.pet;

import com.virtualpet.auth.PlayerContext;
import com.virtualpet.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 宠物接口（TECH_DESIGN 5.2–5.4、5.6）。
 *
 * <p>控制器只做参数接收、调用服务和返回响应；所有游戏规则都在
 * {@code game} 包的领域类和 {@link PetService}/{@link PetActionService} 里。</p>
 */
@RestController
@RequestMapping("/api/v1/pets")
public class PetController {

    private final PetService petService;
    private final PetActionService petActionService;
    private final PlayerContext playerContext;

    public PetController(PetService petService, PetActionService petActionService, PlayerContext playerContext) {
        this.petService = petService;
        this.petActionService = petActionService;
        this.playerContext = playerContext;
    }

    /** 领养宠物。已有宠物时返回 409。 */
    @PostMapping
    public ApiResponse<PetResponse> create(@Valid @RequestBody CreatePetRequest request) {
        Pet pet = petService.create(playerContext.playerId(), request.species(), request.name());
        return ApiResponse.ok(petService.toResponse(pet));
    }

    /** 查询宠物，返回结算后的完整状态。 */
    @GetMapping("/me")
    public ApiResponse<PetResponse> me() {
        Pet pet = petService.load(playerContext.playerId());
        return ApiResponse.ok(petService.toResponse(pet));
    }

    /** 执行一次操作。 */
    @PostMapping("/me/actions")
    public ApiResponse<ActionResponse> act(@Valid @RequestBody ActionRequest request) {
        ActionResponse response = petActionService.perform(
                playerContext.playerId(), request.action(), request.clientRequestId());
        return ApiResponse.ok(response);
    }

    /** 重置存档：删除宠物和日志，玩家记录保留。 */
    @DeleteMapping("/me")
    public ApiResponse<Void> reset() {
        petService.reset(playerContext.playerId());
        return ApiResponse.<Void>ok(null);
    }
}
