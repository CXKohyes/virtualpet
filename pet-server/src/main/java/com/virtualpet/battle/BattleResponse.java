package com.virtualpet.battle;

import com.virtualpet.game.BattleReport;
import com.virtualpet.game.BattleSide;

import java.time.Instant;

/**
 * 一场对战的对外表示（PRD 2.11）。
 *
 * <p>双方都能查同一场对战，所以响应里两边对称地给出快照和名字，
 * 由 {@code viewer} 告诉前端"你是谁"，让界面把己方标出来。</p>
 *
 * @param id          对战 ID
 * @param status      状态，见 {@link BattleStatus}
 * @param viewer      当前请求者站在哪一边
 * @param challenger  挑战方快照摘要
 * @param defender    被挑战方快照摘要
 * @param winner      胜方；平局为 {@code null}
 * @param outcome     胜负是怎么判的
 * @param rounds      总回合数
 * @param seed        固定种子，可用于复现
 * @param timeline    逐回合战报
 * @param createdAt   发起时间
 * @param finishedAt  结束时间
 */
public record BattleResponse(
        Long id,
        String status,
        BattleSide viewer,
        BattlePet challenger,
        BattlePet defender,
        BattleSide winner,
        BattleReport.Outcome outcome,
        int rounds,
        long seed,
        java.util.List<BattleReport.BattleRound> timeline,
        Instant createdAt,
        Instant finishedAt) {

    /** 参战宠物在对战里的样子，取自快照而不是实时数据。 */
    public record BattlePet(
            Long petId,
            String name,
            String species,
            int level,
            int evolutionStage,
            int maxHp,
            int attack,
            int defense,
            int speed) {
    }

    /**
     * 列表用的精简版：不带逐回合战报。
     *
     * <p>最近对战列表可能有几十条，每条都塞一份完整战报会让响应大出一个数量级，
     * 而列表页只显示"谁打谁、谁赢了、几个回合"。</p>
     */
    public record BattleSummary(
            Long id,
            String status,
            BattleSide viewer,
            BattleSide winner,
            String opponentName,
            String opponentSpecies,
            int rounds,
            Instant createdAt) {
    }
}
