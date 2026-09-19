package com.virtualpet.game;

import java.util.List;

/**
 * 游戏配置（TECH_DESIGN 5.5）。
 *
 * <p>前端只缓存这些数据用于展示，<b>不得据此自己计算衰减、经验或进化</b>；
 * 所有判定以服务端为准。{@code maxSlots} 也一样：前端拿它决定要不要显示
 * 「再养一只」，但领养能不能成功仍然由服务端说了算。</p>
 */
public record GameConfigResponse(
        int offlineCapHours,
        int maxLevel,
        int maxSlots,
        List<Integer> expThresholds,
        List<SpeciesConfig> species,
        List<ActionConfig> actions,
        List<EvolutionConfig> evolution) {

    /** 物种及其特性修正。 */
    public record SpeciesConfig(String code, ModifierConfig modifier) {

        public static SpeciesConfig from(Species species) {
            Species.Modifier modifier = species.modifier();
            return new SpeciesConfig(species.name(), new ModifierConfig(
                    modifier.playMoodBonus(),
                    modifier.careGainBonus(),
                    modifier.feedSatietyBonus(),
                    modifier.hygieneDecayScale(),
                    modifier.energyDecayScale(),
                    modifier.healthRecoveryBonus()));
        }
    }

    public record ModifierConfig(
            double playMoodBonus,
            double careGainBonus,
            double feedSatietyBonus,
            double hygieneDecayScale,
            double energyDecayScale,
            int healthRecoveryBonus) {
    }

    /**
     * 操作的基础效果与门槛。
     *
     * @param satiety / {@code mood} / {@code hygiene} / {@code energy} 基础变化，未叠加物种修正
     * @param requirement 使用条件的文字说明，仅用于展示
     */
    public record ActionConfig(
            String code,
            int exp,
            int cooldownSeconds,
            int satiety,
            int mood,
            int hygiene,
            int energy,
            String requirement) {

        public static ActionConfig from(PetAction action) {
            return switch (action) {
                case FEED -> new ActionConfig(action.name(), GameRules.FEED_EXP,
                        PetActionRules.cooldownSeconds(action),
                        GameRules.FEED_SATIETY_GAIN, GameRules.FEED_MOOD_GAIN,
                        -GameRules.FEED_HYGIENE_LOSS, 0,
                        "饱食低于 " + GameRules.FEED_MAX_SATIETY);
                case PLAY -> new ActionConfig(action.name(), GameRules.PLAY_EXP,
                        PetActionRules.cooldownSeconds(action),
                        -GameRules.PLAY_SATIETY_LOSS, GameRules.PLAY_MOOD_GAIN,
                        -GameRules.PLAY_HYGIENE_LOSS, -GameRules.PLAY_ENERGY_LOSS,
                        "清醒且精力不低于 " + GameRules.PLAY_MIN_ENERGY);
                case CLEAN -> new ActionConfig(action.name(), GameRules.CLEAN_EXP,
                        PetActionRules.cooldownSeconds(action),
                        0, GameRules.CLEAN_MOOD_GAIN,
                        GameRules.CLEAN_HYGIENE_GAIN, 0,
                        "清洁低于 " + GameRules.CLEAN_MAX_HYGIENE);
                case SLEEP -> new ActionConfig(action.name(), 0, 0, 0, 0, 0, 0,
                        "清醒且精力低于 " + GameRules.SLEEP_REQUIRED_MAX_ENERGY);
                case WAKE -> new ActionConfig(action.name(), 0, 0, 0, 0, 0, 0, "正在睡觉");
            };
        }
    }

    /**
     * 进化条件。
     *
     * @param minCoreAttribute 四项核心属性的最低值，不适用时为 {@code null}
     */
    public record EvolutionConfig(int stage, String name, int minLevel, int minHealth, Integer minCoreAttribute) {

        public static List<EvolutionConfig> all() {
            return List.of(
                    new EvolutionConfig(EvolutionStage.JUVENILE.code(), EvolutionStage.JUVENILE.name(), 1, 0, null),
                    new EvolutionConfig(EvolutionStage.GROWTH.code(), EvolutionStage.GROWTH.name(),
                            GameRules.GROWTH_LEVEL, GameRules.GROWTH_HEALTH, null),
                    new EvolutionConfig(EvolutionStage.FINAL.code(), EvolutionStage.FINAL.name(),
                            GameRules.FINAL_LEVEL, GameRules.FINAL_HEALTH, GameRules.FINAL_MIN_CORE_ATTRIBUTE));
        }
    }
}
