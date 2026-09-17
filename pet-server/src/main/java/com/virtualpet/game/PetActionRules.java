package com.virtualpet.game;

import java.util.Optional;

/**
 * 日常操作的使用条件与属性收益（PRD 2.4、2.6）。
 *
 * <p>纯函数，不持有状态、不依赖 Spring。所有数值来自 {@link GameRules}，
 * 物种修正来自 {@link Species.Modifier}。</p>
 *
 * <p><b>物种修正的叠加方式</b>：只有<b>正向</b>收益才吃倍率（"正向照护收益"）。
 * 狗的 {@code careGainBonus} 作用于每个操作的所有正向分量；猫的
 * {@code playMoodBonus} 额外作用于玩耍的心情；龙的 {@code feedSatietyBonus}
 * 额外作用于喂食的饱食。三者互斥，不会重复叠乘。</p>
 */
public final class PetActionRules {

    private PetActionRules() {
    }

    /** 收益分量的种类，用来判断某项是否享受物种特有加成。 */
    private enum Gain {
        SATIETY, MOOD, HYGIENE, ENERGY
    }

    /**
     * 检查操作是否可用。
     *
     * @param action     操作
     * @param attributes 当前属性
     * @param sleeping   是否正在睡觉
     * @return 不可用时返回给用户看的原因，可用时返回空
     */
    public static Optional<String> blockReason(PetAction action, PetAttributes attributes, boolean sleeping) {
        return switch (action) {
            case FEED -> attributes.satiety() >= GameRules.FEED_MAX_SATIETY
                    ? Optional.of("它现在不饿")
                    : Optional.empty();
            case PLAY -> {
                if (sleeping) {
                    yield Optional.of("它正在睡觉");
                }
                if (attributes.energy() < GameRules.PLAY_MIN_ENERGY) {
                    yield Optional.of("它太累了，先让它睡一会儿");
                }
                yield Optional.empty();
            }
            case CLEAN -> attributes.hygiene() >= GameRules.CLEAN_MAX_HYGIENE
                    ? Optional.of("它现在很干净")
                    : Optional.empty();
            case SLEEP -> {
                if (sleeping) {
                    yield Optional.of("它已经睡着了");
                }
                if (attributes.energy() >= GameRules.SLEEP_REQUIRED_MAX_ENERGY) {
                    yield Optional.of("它还不困");
                }
                yield Optional.empty();
            }
            case WAKE -> sleeping ? Optional.empty() : Optional.of("它并没有在睡觉");
        };
    }

    /**
     * 计算操作对四项属性的影响。健康不参与，睡觉和唤醒也不在这里处理。
     *
     * @return 叠加了物种修正后的新属性
     */
    public static PetAttributes applyEffect(PetAttributes attributes, PetAction action, Species species) {
        return switch (action) {
            case FEED -> attributes.plus(
                    gain(action, Gain.SATIETY, GameRules.FEED_SATIETY_GAIN, species),
                    gain(action, Gain.MOOD, GameRules.FEED_MOOD_GAIN, species),
                    -GameRules.FEED_HYGIENE_LOSS,
                    0,
                    0);
            case PLAY -> attributes.plus(
                    -GameRules.PLAY_SATIETY_LOSS,
                    gain(action, Gain.MOOD, GameRules.PLAY_MOOD_GAIN, species),
                    -GameRules.PLAY_HYGIENE_LOSS,
                    -GameRules.PLAY_ENERGY_LOSS,
                    0);
            case CLEAN -> attributes.plus(
                    0,
                    gain(action, Gain.MOOD, GameRules.CLEAN_MOOD_GAIN, species),
                    gain(action, Gain.HYGIENE, GameRules.CLEAN_HYGIENE_GAIN, species),
                    0,
                    0);
            case SLEEP, WAKE -> attributes;
        };
    }

    /** 该操作带来的经验。睡觉的经验按实际睡眠时长在醒来时结算，这里返回 0。 */
    public static int expFor(PetAction action) {
        return switch (action) {
            case FEED -> GameRules.FEED_EXP;
            case PLAY -> GameRules.PLAY_EXP;
            case CLEAN -> GameRules.CLEAN_EXP;
            case SLEEP, WAKE -> 0;
        };
    }

    /** 该操作的冷却秒数，返回 0 表示无冷却。 */
    public static int cooldownSeconds(PetAction action) {
        return switch (action) {
            case FEED, PLAY, CLEAN -> GameRules.ACTION_COOLDOWN_SECONDS;
            case SLEEP, WAKE -> 0;
        };
    }

    /** 一次睡觉总共能获得多少经验：每小时 2 点，单次封顶 12 点。 */
    public static int sleepExp(long sleptHours) {
        if (sleptHours <= 0) {
            return 0;
        }
        long exp = sleptHours * GameRules.SLEEP_EXP_PER_HOUR;
        return (int) Math.min(exp, GameRules.SLEEP_EXP_MAX_PER_SESSION);
    }

    /** 正向收益统一走这里，保证只有收益吃倍率、代价不吃。 */
    private static int gain(PetAction action, Gain kind, int base, Species species) {
        Species.Modifier modifier = species.modifier();
        double multiplier = modifier.careGainBonus();
        if (action == PetAction.PLAY && kind == Gain.MOOD) {
            multiplier *= modifier.playMoodBonus();
        }
        if (action == PetAction.FEED && kind == Gain.SATIETY) {
            multiplier *= modifier.feedSatietyBonus();
        }
        return (int) Math.round(base * multiplier);
    }
}
