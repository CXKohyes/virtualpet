package com.virtualpet.dev;

import com.virtualpet.game.PetAttributes;
import com.virtualpet.game.PetStatus;
import com.virtualpet.pet.Pet;
import com.virtualpet.pet.PetProgressService;
import com.virtualpet.pet.PetService;
import com.virtualpet.pet.PetWriter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 开发用状态铺设（批次 5 验收），<b>只在 dev profile 下存在</b>。
 *
 * <p>铺设完不是简单写库了事，而是紧接着跑一遍<b>和真实操作完全相同</b>的后处理：
 * 重算状态、重算等级与进化。所以进化、生病这些判定走的还是
 * {@code PetProgressService} 和 {@code PetStatus.resolve}，dev 接口只是
 * 把"属性/经验是从哪来的"这一步换掉，规则一条都没绕过。</p>
 */
@Service
@Profile("dev")
public class DevStateService {

    private final PetService petService;
    private final PetWriter writer;
    private final PetProgressService progressService;

    public DevStateService(PetService petService, PetWriter writer, PetProgressService progressService) {
        this.petService = petService;
        this.writer = writer;
        this.progressService = progressService;
    }

    /**
     * 按请求铺设状态并返回铺设后的宠物。
     *
     * @param playerId 玩家 ID
     * @param request  要铺设的字段，{@code null} 的项保持原值
     * @return 重新算过状态和进化之后的宠物
     */
    public Pet setState(Long playerId, DevSetStateRequest request) {
        return writer.runWithRetry(() -> {
            Pet pet = petService.requirePet(playerId);

            if (request.satiety() != null) {
                pet.setSatiety(request.satiety());
            }
            if (request.mood() != null) {
                pet.setMood(request.mood());
            }
            if (request.hygiene() != null) {
                pet.setHygiene(request.hygiene());
            }
            if (request.energy() != null) {
                pet.setEnergy(request.energy());
            }
            if (request.health() != null) {
                pet.setHealth(request.health());
            }
            if (request.exp() != null) {
                // 直接覆盖累计经验，而不是"加"：验收要的是确定的状态
                pet.setExp(request.exp());
            }

            // 经验覆盖之后等级可能对不上，用 pass 0 经验走一遍真实推进，
            // 顺带把等级和进化一起重算（没有升到新等级就不会误报进化）
            progressService.apply(pet, 0);
            refreshDerived(pet);

            writer.touch(pet);
            writer.updateOrConflict(pet);
            return pet;
        });
    }

    /**
     * 重算冗余的 status 与 sick 两列。
     *
     * <p>顺序和 {@code PetActionService.refreshStatus} 一致：先按健康更新生病标志
     * （滞回），再由 sleeping / sick / 属性共同决定状态。反过来的话，
     * 把健康设回 80 之后状态还会停在"生病"。</p>
     */
    private void refreshDerived(Pet pet) {
        PetAttributes attributes = new PetAttributes(
                pet.getSatiety(), pet.getMood(), pet.getHygiene(), pet.getEnergy(), pet.getHealth());
        pet.setSick(PetStatus.sickAfter(pet.getSick(), attributes.health()));
        pet.setStatus(PetStatus.resolve(pet.getSleepingSince() != null, pet.getSick(), attributes).name());
    }
}
