package com.virtualpet.game;

import java.util.Arrays;
import java.util.List;

/**
 * 游戏数值规则常量与纯函数。
 *
 * <p>数值来源为 {@code PRD.md} 2.3–2.7。这里只放常量和无状态计算，
 * 不持有宠物状态，也不依赖 Spring。</p>
 *
 * <p>批次 1 先以常量形式固定；{@code TECH_DESIGN.md} 11.3 规划的
 * {@code app.game.*} 可配置化留到接入配置文件时再做。</p>
 */
public final class GameRules {

    private GameRules() {
    }

    /** 属性下限。健康最低为 0，宠物永不死亡。 */
    public static final int ATTRIBUTE_MIN = 0;

    /** 属性上限。所有属性在应用层钳制到 0–100。 */
    public static final int ATTRIBUTE_MAX = 100;

    /** 单次结算的离线时长上限（小时）。超过部分直接丢弃，不累计。 */
    public static final int OFFLINE_CAP_HOURS = 12;

    // ---------------------------------------------------------- 清醒状态每小时衰减

    public static final int AWAKE_SATIETY_DECAY_PER_HOUR = 5;
    public static final int AWAKE_MOOD_DECAY_PER_HOUR = 4;
    public static final int AWAKE_HYGIENE_DECAY_PER_HOUR = 3;
    public static final int AWAKE_ENERGY_DECAY_PER_HOUR = 4;

    // ---------------------------------------------------------- 睡觉每小时变化

    public static final int SLEEP_ENERGY_GAIN_PER_HOUR = 10;
    public static final int SLEEP_SATIETY_DECAY_PER_HOUR = 3;
    public static final int SLEEP_MOOD_DECAY_PER_HOUR = 1;
    public static final int SLEEP_HYGIENE_DECAY_PER_HOUR = 1;

    /** 睡满多少小时自动醒来。 */
    public static final int SLEEP_MAX_HOURS = 10;

    /** 精力达到该值自动醒来。 */
    public static final int SLEEP_WAKE_ENERGY = 95;

    /** 精力低于该值才能入睡（批次 2 的 SLEEP 操作使用）。 */
    public static final int SLEEP_REQUIRED_MAX_ENERGY = 70;

    // ---------------------------------------------------------- 健康与生病

    /** 健康低于该值进入生病状态。 */
    public static final int HEALTH_SICK_THRESHOLD = 30;

    /**
     * 生病后健康恢复到该值才解除。
     *
     * <p>与 {@link #HEALTH_SICK_THRESHOLD} 一起构成滞回：30 与 50 之间保持原状态，
     * 见 {@code PRD.md} 2.3「健康低于 30 进入生病状态，恢复到 50 以上解除」。</p>
     */
    public static final int HEALTH_RECOVER_THRESHOLD = 50;

    public static final int HEALTH_LOSS_SATIETY_THRESHOLD = 25;
    public static final int HEALTH_LOSS_SATIETY_PER_HOUR = 2;

    public static final int HEALTH_LOSS_HYGIENE_THRESHOLD = 25;
    public static final int HEALTH_LOSS_HYGIENE_PER_HOUR = 2;

    /** 注意：该阈值（20）低于 {@link #SAD_MOOD_THRESHOLD}（30），两者是不同规则。 */
    public static final int HEALTH_LOSS_MOOD_THRESHOLD = 20;
    public static final int HEALTH_LOSS_MOOD_PER_HOUR = 1;

    /** 四项核心属性（不含健康）全部高于该值时健康恢复。 */
    public static final int HEALTH_GAIN_THRESHOLD = 60;
    public static final int HEALTH_GAIN_PER_HOUR = 1;

    // ---------------------------------------------------------- 状态判定阈值

    public static final int HUNGRY_SATIETY_THRESHOLD = 25;
    public static final int DIRTY_HYGIENE_THRESHOLD = 25;
    public static final int TIRED_ENERGY_THRESHOLD = 20;
    public static final int SAD_MOOD_THRESHOLD = 30;

    // ---------------------------------------------------------- 日常操作（PRD 2.4）

    /** FEED / PLAY / CLEAN 各自的冷却时间。睡觉和唤醒没有冷却。 */
    public static final int ACTION_COOLDOWN_SECONDS = 60;

    public static final int FEED_SATIETY_GAIN = 30;
    public static final int FEED_MOOD_GAIN = 3;
    public static final int FEED_HYGIENE_LOSS = 2;
    /** 饱食到达该值后不能再喂（"它现在不饿"）。 */
    public static final int FEED_MAX_SATIETY = 95;
    public static final int FEED_EXP = 6;

    public static final int PLAY_MOOD_GAIN = 22;
    public static final int PLAY_SATIETY_LOSS = 5;
    public static final int PLAY_HYGIENE_LOSS = 4;
    public static final int PLAY_ENERGY_LOSS = 12;
    /** 玩耍要求的最低精力（"它太累了"）。 */
    public static final int PLAY_MIN_ENERGY = 15;
    public static final int PLAY_EXP = 8;

    public static final int CLEAN_HYGIENE_GAIN = 35;
    public static final int CLEAN_MOOD_GAIN = 5;
    /** 清洁到达该值后不能再清洁。 */
    public static final int CLEAN_MAX_HYGIENE = 95;
    public static final int CLEAN_EXP = 6;

    public static final int SLEEP_EXP_PER_HOUR = 2;
    /** 单次睡觉最多获得多少经验（PRD 2.4）。 */
    public static final int SLEEP_EXP_MAX_PER_SESSION = 12;

    // ---------------------------------------------------------- 初始状态

    /**
     * 新领养宠物的四项核心属性初始值。
     *
     * <p>PRD 2.2 只规定「创建成功后自动进入宠物主界面」，没有规定初始数值。
     * 取 80 是为了让玩家一进游戏就能立刻喂食和清洁（两者的门槛都是 95），
     * 同时精力 80 高于 70，刚开始不能马上让它睡觉，符合「它还不困」的设定。</p>
     */
    public static final int INITIAL_CORE_ATTRIBUTE = 80;

    /** 新领养宠物的初始健康。 */
    public static final int INITIAL_HEALTH = 100;

    // ---------------------------------------------------------- 宠物名字（PRD 2.2）

    public static final int PET_NAME_MIN_LENGTH = 1;
    public static final int PET_NAME_MAX_LENGTH = 8;

    // ---------------------------------------------------------- 进化（PRD 2.7）

    /** 成长形态：至少 4 级。 */
    public static final int GROWTH_LEVEL = 4;
    /** 成长形态：健康至少 60。 */
    public static final int GROWTH_HEALTH = 60;

    /** 最终形态：至少 8 级。 */
    public static final int FINAL_LEVEL = 8;
    /** 最终形态：健康至少 70。 */
    public static final int FINAL_HEALTH = 70;
    /** 最终形态：四项核心属性全部至少 50。 */
    public static final int FINAL_MIN_CORE_ATTRIBUTE = 50;

    // ---------------------------------------------------------- 等级

    public static final int MAX_LEVEL = 10;
    public static final int MIN_LEVEL = 1;

    /** 升到 2–10 级所需的累计经验，共 9 个阈值（PRD 2.7）。 */
    private static final int[] EXP_THRESHOLDS = {40, 90, 150, 220, 300, 390, 490, 600, 720};

    /**
     * 升到 2–10 级所需的累计经验阈值，供 {@code GET /api/v1/game/config} 返回给前端展示。
     *
     * @return 长度为 9 的不可变列表
     */
    public static List<Integer> expThresholds() {
        return Arrays.stream(EXP_THRESHOLDS).boxed().toList();
    }

    /**
     * 由累计经验计算等级。
     *
     * <p>经验为累计值，等级由阈值表决定，最高 {@link #MAX_LEVEL} 级。
     * 负数或未知经验按 1 级处理。</p>
     *
     * @param exp 累计经验，允许为任意整数
     * @return 1–10 级
     */
    public static int levelForExp(int exp) {
        int level = MIN_LEVEL;
        for (int threshold : EXP_THRESHOLDS) {
            if (exp < threshold) {
                break;
            }
            level++;
        }
        return Math.min(level, MAX_LEVEL);
    }
}
