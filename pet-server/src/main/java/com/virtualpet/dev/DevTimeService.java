package com.virtualpet.dev;

import com.virtualpet.pet.Pet;
import com.virtualpet.pet.PetService;
import com.virtualpet.pet.PetWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;

/**
 * 开发用时间推进（TECH_DESIGN 5.7）。
 *
 * <p>做法是把宠物的结算游标往前挪，让下一次懒结算「看到」已经过去了这么多时间。
 * 这样不需要伪造 {@code Clock}，走的完全是真实结算路径。</p>
 *
 * <p><b>只在 {@code dev} profile 下注册为 Bean。</b>生产环境绝对不能激活该 profile。</p>
 */
@Service
@Profile("dev")
public class DevTimeService {

    private static final Logger log = LoggerFactory.getLogger(DevTimeService.class);

    private final PetService petService;
    private final PetWriter writer;

    public DevTimeService(PetService petService, PetWriter writer) {
        this.petService = petService;
        this.writer = writer;
    }

    /**
     * 把宠物的时间游标往前推指定小时数。
     *
     * <p>睡觉中的宠物连 {@code sleepingSince} 一起挪，保持
     * 「{@code sleepingSince <= lastSettledAt}」这个不变量。</p>
     *
     * @return 时间被挪动但还没结算的宠物实体
     */
    public Pet rewind(Long playerId, int hours) {
        Pet pet = writer.runWithRetry(() -> {
            Pet loaded = petService.requirePet(playerId);
            loaded.setLastSettledAt(loaded.getLastSettledAt().minus(hours, ChronoUnit.HOURS));
            if (loaded.getSleepingSince() != null) {
                loaded.setSleepingSince(loaded.getSleepingSince().minus(hours, ChronoUnit.HOURS));
            }
            writer.touch(loaded);
            writer.updateOrConflict(loaded);
            return loaded;
        });
        log.warn("[dev] 玩家 {} 的时间推进了 {} 小时", playerId, hours);
        return pet;
    }
}
