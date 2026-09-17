package com.virtualpet.game;

/**
 * 进化阶段（PRD 2.7、TECH_DESIGN 4.1）。
 *
 * <p>批次 1 只定义枚举与阶段编号的映射。进化的判定条件与触发时机
 * 属于批次 2 的 {@code EvolutionService}，本批不实现。</p>
 */
public enum EvolutionStage {

    /** 幼年形态：1–3 级。 */
    JUVENILE(0),

    /** 成长形态：4 级且健康 ≥60。 */
    GROWTH(1),

    /** 最终形态：8 级、健康 ≥70，且四项核心属性全部 ≥50。 */
    FINAL(2);

    private final int code;

    EvolutionStage(int code) {
        this.code = code;
    }

    /** 数据库中保存的阶段编号（0/1/2），不使用 ordinal 以免重排常量时出错。 */
    public int code() {
        return code;
    }

    /** 由数据库中的阶段编号还原枚举。 */
    public static EvolutionStage fromCode(int code) {
        for (EvolutionStage stage : values()) {
            if (stage.code == code) {
                return stage;
            }
        }
        throw new IllegalArgumentException("未知的进化阶段编号: " + code);
    }
}
