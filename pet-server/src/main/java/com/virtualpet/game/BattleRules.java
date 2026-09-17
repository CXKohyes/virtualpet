package com.virtualpet.game;

/**
 * 对战数值规则（PRD 2.11）。
 *
 * <p><b>战斗属性是从宠物现有状态推出来的，不是另存一套。</b>等级、进化阶段、
 * 物种倾向和当前健康都取自开战时冻结的快照，用的是 {@link GameRules} 里已有的
 * 等级与进化常量 —— 这里只负责把它们换算成生命、攻击、防御和速度。</p>
 *
 * <p>换算取的是"够用就好"的线性公式，不引入新的成长曲线：宠物养成那边的等级和进化
 * 才是唯一事实，战斗只是给它换一种读法。</p>
 */
public final class BattleRules {

    private BattleRules() {
    }

    // ---------------------------------------------------------- 基础值

    public static final int BASE_HP = 50;
    public static final int HP_PER_LEVEL = 6;
    public static final int HP_PER_STAGE = 15;

    public static final int BASE_ATTACK = 8;
    public static final int ATTACK_PER_LEVEL = 2;
    public static final int ATTACK_PER_STAGE = 3;

    public static final int BASE_DEFENSE = 4;
    public static final int DEFENSE_PER_LEVEL = 1;
    public static final int DEFENSE_PER_STAGE = 2;

    public static final int BASE_SPEED = 5;
    public static final int SPEED_PER_LEVEL = 1;

    // ---------------------------------------------------------- 伤害

    /** 单次伤害至少这么多，避免高防御方把对方完全锁死。 */
    public static final int MIN_DAMAGE = 1;

    /** 伤害浮动幅度（百分比）。同一套种子必须复现，所以浮动来自种子而不是真随机。 */
    public static final int DAMAGE_VARIANCE_PERCENT = 15;

    /** 暴击率（百分比）与暴击倍率（百分比）。 */
    public static final int CRIT_CHANCE_PERCENT = 10;
    public static final int CRIT_MULTIPLIER_PERCENT = 160;

    /**
     * 速度优势换算成额外出手概率。
     *
     * <p>这一条是调平衡时补上的，不是一开始就有的。最初的模型里速度只决定"谁先手"，
     * 而先手一次大约只值半次攻击；龙的攻击加成却是每回合都兑现的。实测下来猫对谁都是
     * <b>0% 胜率</b> —— 三个物种直接塌成一条线。给速度一个能换算成伤害的出口之后，
     * 猫"靠连击数压人"的定位才立得住。</p>
     *
     * <p>算的是<b>相对</b>速度优势而不是绝对差值。绝对差值会随等级一起变大
     * （速度本身随等级涨），于是同一个 +6 速度加成在高等级换来更多连击，
     * 猫在 10 级就滚成 70% 胜率。按比例算，连击率在各个等级段才是同一条曲线。</p>
     */
    public static final int EXTRA_STRIKE_PER_PERCENT_ADVANTAGE = 4;
    public static final int MAX_EXTRA_STRIKE_PERCENT = 35;

    /** 速度优势带来的额外出手概率（百分比）。速度不占优就是 0。 */
    public static int extraStrikeChance(int attackerSpeed, int targetSpeed) {
        int gap = attackerSpeed - targetSpeed;
        if (gap <= 0 || targetSpeed <= 0) {
            return 0;
        }
        int advantagePercent = gap * 100 / targetSpeed;
        return Math.min(MAX_EXTRA_STRIKE_PERCENT,
                advantagePercent * EXTRA_STRIKE_PER_PERCENT_ADVANTAGE / 10);
    }

    // ---------------------------------------------------------- 回合

    /** 回合上限。超过就按剩余生命比例判定，保证战斗一定终止。 */
    public static final int MAX_ROUNDS = 30;

    // ---------------------------------------------------------- 属性换算

    /**
     * 物种加成的缩放基准等级。
     *
     * <p>物种倾向是<b>固定数值</b>，而基础属性随等级线性增长，于是同一个 +5 攻击在
     * 1 级占基础值的 50%、在 10 级只占 14%。实测下来龙在 1 级碾压（82% 胜率）、
     * 在 10 级被狗压着打（20%）—— 同一条倾向在两端给出相反的结论。</p>
     *
     * <p>所以加成要跟着等级一起长。基准取 5 级，也就是调平衡时的那一档：
     * 5 级保持原样，1 级按比例缩小，10 级按比例放大，
     * 让"猫快、狗韧、龙猛"这条定位在每个等级段的分量都差不多。</p>
     */
    private static final int TENDENCY_REFERENCE_LEVEL = 5;

    /**
     * 把物种的固定加成按等级缩放。
     *
     * <p>系数是 {@code (level + REF - 1) / (2 × REF - 1)}，也就是
     * 1 级 0.56 倍、5 级 1 倍、10 级 1.56 倍 —— 5 级正好保持调平衡时的原值。
     * 分母里那个 {@code -1} 是让曲线在最低等级也不至于缩到 0，
     * 否则新玩家等于带着没有物种特性的宠物上场。</p>
     */
    static int scaleTendency(int bonus, int level) {
        if (bonus == 0) {
            return 0;
        }
        int denominator = 2 * TENDENCY_REFERENCE_LEVEL - 1;
        int scaled = Math.round(bonus * (level + TENDENCY_REFERENCE_LEVEL - 1) / (float) denominator);
        // 有倾向就该看得出来，别被整数取整抹成 0
        return scaled == 0 ? Integer.signum(bonus) : scaled;
    }

    /** 最大生命。等级和进化阶段越高越耐打。 */
    public static int maxHp(int level, int evolutionStage, Species species) {
        return BASE_HP
                + level * HP_PER_LEVEL
                + evolutionStage * HP_PER_STAGE
                + scaleTendency(species.battle().hpBonus(), level);
    }

    /** 攻击力。 */
    public static int attack(int level, int evolutionStage, Species species) {
        return BASE_ATTACK
                + level * ATTACK_PER_LEVEL
                + evolutionStage * ATTACK_PER_STAGE
                + scaleTendency(species.battle().attackBonus(), level);
    }

    /** 防御力。 */
    public static int defense(int level, int evolutionStage, Species species) {
        return BASE_DEFENSE
                + level * DEFENSE_PER_LEVEL
                + evolutionStage * DEFENSE_PER_STAGE
                + scaleTendency(species.battle().defenseBonus(), level);
    }

    /** 速度。每回合速度快的一方先动手，相同则挑战者先手。 */
    public static int speed(int level, Species species) {
        return BASE_SPEED + level * SPEED_PER_LEVEL + scaleTendency(species.battle().speedBonus(), level);
    }

    /**
     * 每回合恢复的生命，只有狗有。
     *
     * <p>按<b>最大生命的百分比</b>算，不是一个固定值。固定值在低等级太强、高等级太弱：
     * 回血 3 对 1 级的 56 点生命是 5%，对 10 级的 140 点只剩 2%。实测固定值时
     * 1 级猫对狗只有 14% 胜率、10 级却有 66% —— 同一条规则在两个等级段给出相反结论。
     * 换成百分比，回血强度就和等级无关了。</p>
     */
    public static int regenPerRound(int maxHp, int level, Species species) {
        // 回血比例也要跟着等级缩放，和其它倾向保持一致。
        // 不缩的话，1 级时别人加成都缩到 0.56 倍、回血却还是满的，
        // 狗在低等级会一家独大（实测 84% 胜率）。
        int percent = scaleTendency(species.battle().regenPercent(), level);
        return percent <= 0 ? 0 : Math.max(1, maxHp * percent / 100);
    }

    /**
     * 开场生命。
     *
     * <p>按<b>当前健康</b>折算：健康 100 满血上场，健康 20 就只有两成血。
     * 这是战斗和养成之间唯一的接口 —— 生病、挨饿的宠物打不了架，
     * 也让"照顾好宠物"这件事真的有意义。</p>
     *
     * <p>健康本身不参与伤害计算，只有这一个折算入口，规则不会散落各处。</p>
     */
    public static int startingHp(int maxHp, int health) {
        int clampedHealth = Math.max(GameRules.ATTRIBUTE_MIN, Math.min(GameRules.ATTRIBUTE_MAX, health));
        return Math.max(1, maxHp * clampedHealth / GameRules.ATTRIBUTE_MAX);
    }

    /**
     * 一次攻击的伤害，不含浮动。
     *
     * <p>防御按一半减伤：这样防御有用但不会让伤害归零，高防方仍然会被磨死。</p>
     */
    public static int baseDamage(int attack, int defense) {
        return Math.max(MIN_DAMAGE, attack - defense / 2);
    }
}
