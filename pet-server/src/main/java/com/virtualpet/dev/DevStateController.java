package com.virtualpet.dev;

import com.virtualpet.auth.PlayerContext;
import com.virtualpet.common.ApiResponse;
import com.virtualpet.pet.Pet;
import com.virtualpet.pet.PetResponse;
import com.virtualpet.pet.PetService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开发用状态铺设接口（批次 5 验收）。
 *
 * <p>和 {@link DevTimeController} 一样<b>只在 {@code dev} profile 下存在</b>，
 * 生产环境没有这个 Bean，接口直接 404。</p>
 *
 * <p>存在的理由见 {@link DevSetStateRequest}：进化和健康恢复这两条验收路径
 * 在真实节奏下要几十分钟才能走到，没有铺设工具就没法验收。</p>
 */
@RestController
@RequestMapping("/api/v1/dev")
@Profile("dev")
public class DevStateController {

    private final DevStateService devStateService;
    private final PetService petService;
    private final PlayerContext playerContext;

    public DevStateController(DevStateService devStateService, PetService petService,
                              PlayerContext playerContext) {
        this.devStateService = devStateService;
        this.petService = petService;
        this.playerContext = playerContext;
    }

    /** 铺设属性或经验；铺完立刻按真实规则重算状态、等级和进化。 */
    @PostMapping("/set-state")
    public ApiResponse<PetResponse> setState(@Valid @RequestBody DevSetStateRequest request) {
        Pet pet = devStateService.setState(playerContext.playerId(), request);
        return ApiResponse.ok(petService.toResponse(pet));
    }
}
