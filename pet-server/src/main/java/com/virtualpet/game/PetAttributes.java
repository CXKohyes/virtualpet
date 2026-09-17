package com.virtualpet.game;

/**
 * 宠物五项属性的不可变值对象。
 *
 * <p>构造时统一钳制到 {@link GameRules#ATTRIBUTE_MIN}–{@link GameRules#ATTRIBUTE_MAX}，
 * 因此任何时刻拿到的实例都是合法状态，调用方不需要重复校验。</p>
 *
 * @param satiety 饱食
 * @param mood    心情
 * @param hygiene 清洁
 * @param energy  精力
 * @param health  健康
 */
public record PetAttributes(int satiety, int mood, int hygiene, int energy, int health) {

    public PetAttributes {
        satiety = clamp(satiety);
        mood = clamp(mood);
        hygiene = clamp(hygiene);
        energy = clamp(energy);
        health = clamp(health);
    }

    /**
     * 在现有属性上叠加增量，结果自动钳制。
     *
     * @return 新的属性实例，原实例不变
     */
    public PetAttributes plus(int deltaSatiety, int deltaMood, int deltaHygiene, int deltaEnergy, int deltaHealth) {
        return new PetAttributes(
                satiety + deltaSatiety,
                mood + deltaMood,
                hygiene + deltaHygiene,
                energy + deltaEnergy,
                health + deltaHealth);
    }

    /** 是否四项核心属性（不含健康）全部高于给定阈值。 */
    public boolean allCoreAbove(int threshold) {
        return satiety > threshold && mood > threshold && hygiene > threshold && energy > threshold;
    }

    /** 是否四项核心属性（不含健康）全部不低于给定阈值。 */
    public boolean allCoreAtLeast(int threshold) {
        return satiety >= threshold && mood >= threshold && hygiene >= threshold && energy >= threshold;
    }

    private static int clamp(int value) {
        return Math.max(GameRules.ATTRIBUTE_MIN, Math.min(GameRules.ATTRIBUTE_MAX, value));
    }
}
