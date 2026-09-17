package com.virtualpet.game;

/** 对战双方。速度相同时挑战者先手，所以它不是一个纯粹的标签，也参与判定。 */
public enum BattleSide {
    /** 发起挑战的一方。 */
    CHALLENGER,
    /** 被挑战的一方，可能全程不在线。 */
    DEFENDER;

    public BattleSide opponent() {
        return this == CHALLENGER ? DEFENDER : CHALLENGER;
    }
}
