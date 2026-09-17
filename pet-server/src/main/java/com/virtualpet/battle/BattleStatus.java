package com.virtualpet.battle;

/**
 * 对战状态（TECH_DESIGN 4.5）。
 *
 * <p>当前实现里战斗是在发起请求内<b>同步</b>跑完的，所以落库的永远是
 * {@link #FINISHED}。保留 {@link #PENDING} 是为了将来真的要做后台异步执行时
 * 不用再改表结构和接口契约 —— 那时候 {@code PENDING} 会成为一个真实存在的中间态，
 * 客户端也已经在按 status 分支了。</p>
 */
public enum BattleStatus {
    /** 已发起、还没打完。 */
    PENDING,
    /** 战报已就绪，可以查询。 */
    FINISHED
}
