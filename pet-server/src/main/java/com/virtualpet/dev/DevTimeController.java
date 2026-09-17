package com.virtualpet.dev;

import com.virtualpet.auth.PlayerContext;
import com.virtualpet.common.ApiResponse;
import com.virtualpet.pet.Pet;
import com.virtualpet.pet.PetResponse;
import com.virtualpet.pet.PetService;
import com.virtualpet.pet.PetSnapshot;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开发用时间推进接口（TECH_DESIGN 5.7）。
 *
 * <p>用于验证离线衰减、生病、睡觉和进化，<b>只在 {@code dev} profile 下存在</b>。
 * 生产环境（默认 profile）没有这个 Bean，接口会直接 404。</p>
 *
 * <p>需要令牌，和普通接口一样。</p>
 */
@RestController
@RequestMapping("/api/v1/dev")
@Profile("dev")
public class DevTimeController {

    private final DevTimeService devTimeService;
    private final PetService petService;
    private final PlayerContext playerContext;

    public DevTimeController(DevTimeService devTimeService, PetService petService, PlayerContext playerContext) {
        this.devTimeService = devTimeService;
        this.petService = petService;
        this.playerContext = playerContext;
    }

    /** 推进时间；默认推进后立即结算。 */
    @PostMapping("/advance-time")
    public ApiResponse<PetResponse> advanceTime(@Valid @RequestBody AdvanceTimeRequest request) {
        Long playerId = playerContext.playerId();
        Pet pet = devTimeService.rewind(playerId, request.hours());

        if (!request.shouldSettle()) {
            // 只挪游标，把结算留给下一次读取，这样才能看到回访提示
            return ApiResponse.ok(petService.toResponse(pet));
        }

        // 挪完游标后立刻按真实规则结算，调用方直接拿到结果（含结算摘要）
        PetSnapshot snapshot = petService.load(playerId);
        return ApiResponse.ok(petService.toResponse(snapshot.pet(), snapshot.settlement()));
    }
}
