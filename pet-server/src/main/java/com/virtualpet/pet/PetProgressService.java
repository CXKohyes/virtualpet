package com.virtualpet.pet;

import com.virtualpet.game.EvolutionStage;
import com.virtualpet.game.GameRules;
import com.virtualpet.game.PetAttributes;
import org.springframework.stereotype.Service;

/**
 * 经验、等级与进化的推进（PRD 2.7、TECH_DESIGN 6.4）。
 *
 * <p>经验是累计值，等级由阈值表算出，升级可以连续触发；进化只升不降。
 * 操作服务和懒结算都会用到，所以单独抽出来。</p>
 */
@Service
public class PetProgressService {

    private final EvolutionService evolutionService;

    public PetProgressService(EvolutionService evolutionService) {
        this.evolutionService = evolutionService;
    }

    /**
     * 给宠物加经验并重算等级与进化阶段，直接修改传入的实体（不落库）。
     *
     * @param pet       宠物实体
     * @param gainedExp 本次获得的经验，0 表示只重算等级和进化
     * @return 本次推进的结果
     */
    public Progress apply(Pet pet, int gainedExp) {
        int actualGain = Math.max(0, gainedExp);
        int totalExp = Math.max(0, pet.getExp() + actualGain);
        int newLevel = GameRules.levelForExp(totalExp);
        boolean levelUp = newLevel > pet.getLevel();

        PetAttributes attributes = new PetAttributes(
                pet.getSatiety(), pet.getMood(), pet.getHygiene(), pet.getEnergy(), pet.getHealth());
        EvolutionStage previousStage = EvolutionStage.fromCode(pet.getEvolutionStage());
        EvolutionStage stage = evolutionService.evaluate(newLevel, attributes, previousStage);
        boolean evolved = stage.code() > previousStage.code();

        pet.setExp(totalExp);
        pet.setLevel(newLevel);
        pet.setEvolutionStage(stage.code());

        return new Progress(actualGain, levelUp, evolved, previousStage, stage);
    }

    /**
     * 经验推进的结果。
     *
     * @param xpGained      本次实际获得的经验
     * @param levelUp       是否升级（连续升多级也只算一次 true）
     * @param evolved       是否进化
     * @param previousStage 推进前的阶段
     * @param stage         推进后的阶段
     */
    public record Progress(int xpGained, boolean levelUp, boolean evolved,
                           EvolutionStage previousStage, EvolutionStage stage) {

        /** 什么都没发生时用这个，避免各处 new。 */
        public static Progress none(EvolutionStage stage) {
            return new Progress(0, false, false, stage, stage);
        }
    }
}
