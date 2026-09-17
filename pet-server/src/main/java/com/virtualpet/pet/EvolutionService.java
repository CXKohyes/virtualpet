package com.virtualpet.pet;

import com.virtualpet.game.EvolutionStage;
import com.virtualpet.game.GameRules;
import com.virtualpet.game.PetAttributes;
import org.springframework.stereotype.Service;

/**
 * 进化判定（PRD 2.7、TECH_DESIGN 6.4）。
 *
 * <ul>
 *   <li>阶段 1（成长）：{@code level >= 4 && health >= 60}</li>
 *   <li>阶段 2（最终）：{@code level >= 8 && health >= 70}，且四项核心属性全部 ≥50</li>
 *   <li>条件不满足时保持当前阶段，<b>只升不降</b></li>
 * </ul>
 */
@Service
public class EvolutionService {

    /**
     * 计算结算后的进化阶段。
     *
     * @param level      当前等级
     * @param attributes 结算后的属性
     * @param current    当前阶段
     * @return 新的阶段，永不低于 {@code current}
     */
    public EvolutionStage evaluate(int level, PetAttributes attributes, EvolutionStage current) {
        EvolutionStage target = resolveTarget(level, attributes);
        return target.code() > current.code() ? target : current;
    }

    private EvolutionStage resolveTarget(int level, PetAttributes attributes) {
        boolean finalReady = level >= GameRules.FINAL_LEVEL
                && attributes.health() >= GameRules.FINAL_HEALTH
                && attributes.allCoreAtLeast(GameRules.FINAL_MIN_CORE_ATTRIBUTE);
        if (finalReady) {
            return EvolutionStage.FINAL;
        }
        boolean growthReady = level >= GameRules.GROWTH_LEVEL
                && attributes.health() >= GameRules.GROWTH_HEALTH;
        if (growthReady) {
            return EvolutionStage.GROWTH;
        }
        return EvolutionStage.JUVENILE;
    }
}
