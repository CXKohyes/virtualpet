package com.virtualpet.pet;

import com.virtualpet.auth.PlayerContext;
import com.virtualpet.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    /** 领养宠物。槽位已满时返回 409。 */
    @PostMapping
    public ApiResponse<PetResponse> create(@Valid @RequestBody CreatePetRequest request) {
        Pet pet = petService.create(playerContext.playerId(), request.species(), request.name());
        return ApiResponse.ok(petService.toResponse(pet));
    }

    /**
     * 名册：该玩家的全部宠物，按槽位升序，<b>每只都已结算</b>（PRD 2.1）。
     *
     * <p>和 {@code /pets/me} 的区别是它返回全部 —— 名册要能一眼看出谁快不行了，
     * 所以每只都真结算过，不是拿旧值凑数。非当前宠物也在其中，用 {@code active}
     * 标出哪只是。</p>
     */
    @GetMapping
    public ApiResponse<List<PetResponse>> roster() {
        return ApiResponse.ok(petService.roster(playerContext.playerId()));
    }

    /**
     * 切换当前宠物（PRD 2.1）。
     *
     * <p>返回切换后那只<b>已结算</b>的状态，于是「切过去」这个动作本身就带回了
     * 「你不在时它怎么样了」—— 回访摘要是靠结算产生的，换个入口不该把它丢掉。</p>
     */
    @PostMapping("/me/active")
    public ApiResponse<PetResponse> activate(@Valid @RequestBody ActivatePetRequest request) {
        PetSnapshot snapshot = petService.activate(playerContext.playerId(), request.petId());
        return ApiResponse.ok(petService.toResponse(snapshot.pet(), snapshot.settlement()));
    }

    /**
     * 查询宠物，返回结算后的完整状态。
     *
     * <p>响应里的 {@code settlement} 是本次结算的变化摘要，前端据此展示
     * 「你不在时发生了什么」（PRD 2.5、4.3）。</p>
     */
    @GetMapping("/me")
    public ApiResponse<PetResponse> me() {
        PetSnapshot snapshot = petService.load(playerContext.playerId());
        return ApiResponse.ok(petService.toResponse(snapshot.pet(), snapshot.settlement()));
    }

    /** 最近的照护记录，新的在前（PRD 4.2）。 */
    @GetMapping("/me/journal")
    public ApiResponse<List<JournalEntryResponse>> journal(
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(petActionService.recentJournal(playerContext.playerId(), limit));
    }

    /** 执行一次操作。 */
    @PostMapping("/me/actions")
    public ApiResponse<ActionResponse> act(@Valid @RequestBody ActionRequest request) {
        ActionResponse response = petActionService.perform(
                playerContext.playerId(), request.action(), request.clientRequestId());
        return ApiResponse.ok(response);
    }

    /**
     * 送走一只宠物：删除它和它的全部照护日志，玩家记录保留（PRD 2.1）。
     *
     * <p><b>取代了原来的 {@code DELETE /pets/me}。</b>多宠物之下「me」是有歧义的，
     * 而且名册里送走非当前那只也必须能表达，所以统一按 ID 送走。
     * 送走当前宠物时，服务端会在同一个事务里把当前宠物改到剩下里槽位最小的那只。</p>
     *
     * <p>重复送走同一只返回 404 {@code PET_NOT_FOUND} —— 不是幂等的。
     * 原来的重置接口是幂等的，但那是单宠物时代的事：那时「重置」没有对象，
     * 现在「送走哪一只」是有明确对象的，对象已经没了就该说没了，
     * 客户端也能据此知道自己的名册是旧的。</p>
     */
    @DeleteMapping("/{petId}")
    public ApiResponse<Void> release(@PathVariable Long petId) {
        petService.release(playerContext.playerId(), petId);
        return ApiResponse.<Void>ok(null);
    }
}
