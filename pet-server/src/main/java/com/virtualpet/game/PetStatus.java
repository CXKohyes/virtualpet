package com.virtualpet.game;

/**
 * 宠物当前状态（PRD 2.3）。
 *
 * <p>状态由属性推导，不单独存储，避免属性和状态不一致。</p>
 */
public enum PetStatus {

    /** 正常。 */
    NORMAL,

    /** 饿：饱食低于 25。 */
    HUNGRY,

    /** 脏：清洁低于 25。 */
    DIRTY,

    /** 累：精力低于 20。 */
    TIRED,

    /** 低落：心情低于 30。 */
    SAD,

    /** 生病：见 {@link #resolve} 的生病滞回说明。 */
    SICK,

    /** 正在睡觉。 */
    SLEEPING;

    /**
     * 按优先级推导当前状态（TECH_DESIGN 6.3）。
     *
     * <p>优先级：{@code SLEEPING > SICK > HUNGRY > TIRED > DIRTY > SAD > NORMAL}。
     * 多项异常同时存在时只返回最高优先级的一个，前端状态条仍应展示全部异常属性。</p>
     *
     * <p>生病是<b>滞回</b>状态而不是纯阈值：{@code sick} 由调用方持有，
     * 健康低于 30 时置位，恢复到 50 以上才清除（PRD 2.3）。</p>
     *
     * @param sleeping   是否正在睡觉
     * @param sick       当前是否处于生病状态
     * @param attributes 结算后的属性
     */
    public static PetStatus resolve(boolean sleeping, boolean sick, PetAttributes attributes) {
        if (sleeping) {
            return SLEEPING;
        }
        if (sick) {
            return SICK;
        }
        if (attributes.satiety() < GameRules.HUNGRY_SATIETY_THRESHOLD) {
            return HUNGRY;
        }
        if (attributes.energy() < GameRules.TIRED_ENERGY_THRESHOLD) {
            return TIRED;
        }
        if (attributes.hygiene() < GameRules.DIRTY_HYGIENE_THRESHOLD) {
            return DIRTY;
        }
        if (attributes.mood() < GameRules.SAD_MOOD_THRESHOLD) {
            return SAD;
        }
        return NORMAL;
    }

    /**
     * 生病标志的滞回推进（PRD 2.3）。
     *
     * <p>健康低于 30 进入生病；已经生病时，要恢复到 50 以上才解除。
     * 30–49 之间保持原状态，避免在阈值附近反复进出。</p>
     */
    public static boolean sickAfter(boolean wasSick, int health) {
        return wasSick
                ? health < GameRules.HEALTH_RECOVER_THRESHOLD
                : health < GameRules.HEALTH_SICK_THRESHOLD;
    }
}
