import type { Species } from '@/types/pet'

/**
 * 对战相关类型（PRD 2.11）。
 *
 * 这些结构和服务端返回的 JSON 一一对应，**前端不做任何推导**：
 * 伤害、剩余血量、谁先手、胜负判定全部由服务端算好，
 * 这里只是把结果画出来。加字段时同步改 `pet-server` 的 `battle` 包。
 */

/** 对战双方。 */
export type BattleSide = 'CHALLENGER' | 'DEFENDER'

/** 胜负是怎么判出来的。 */
export type BattleOutcome = 'KO' | 'TIMEOUT' | 'DRAW'

/**
 * 对战状态。
 *
 * 当前实现里战斗是同步跑完的，所以拿到的永远是 `FINISHED`。
 * 保留 `PENDING` 是为了将来真的做后台执行时不用改接口契约。
 */
export type BattleStatus = 'PENDING' | 'FINISHED'

/** 参战宠物在对战里的样子，取自**开战时的快照**，不是实时状态。 */
export interface BattlePet {
  petId: number
  name: string
  species: Species
  level: number
  evolutionStage: number
  maxHp: number
  attack: number
  defense: number
  speed: number
}

/** 回合内的一个动作。两个血量字段都是"这个动作做完之后"的值。 */
export interface BattleEvent {
  actor: BattleSide
  type: 'ATTACK' | 'HEAL'
  value: number
  crit: boolean
  challengerHpAfter: number
  defenderHpAfter: number
}

export interface BattleRound {
  round: number
  events: BattleEvent[]
}

/** 一场完整对战，含逐回合战报。 */
export interface Battle {
  id: number
  status: BattleStatus
  /** 当前请求者站在哪一边，界面据此标出"我"。 */
  viewer: BattleSide
  challenger: BattlePet
  defender: BattlePet
  winner: BattleSide | null
  outcome: BattleOutcome | null
  rounds: number
  seed: number
  timeline: BattleRound[]
  createdAt: string
  finishedAt: string
}

/** 列表用的精简版，不带逐回合战报。 */
export interface BattleSummary {
  id: number
  status: BattleStatus
  viewer: BattleSide
  winner: BattleSide | null
  opponentName: string
  opponentSpecies: Species
  rounds: number
  createdAt: string
}

/** 战报通知的订阅主题，由服务端给出，前端不写死路径。 */
export interface BattleTopic {
  prefix: string
  pattern: string
}

/**
 * WebSocket 推过来的"战报就绪"通知。
 *
 * **只有对战 ID 和状态。** 前端订的是通配主题（被挑战方事先不知道 battleId，
 * 订不了具体的某一场），而通配是广播 —— 所以服务端刻意不往里放任何参战方信息。
 * 想知道这一场是不是自己的，拿着 ID 去查 `/battles` 列表，那条有令牌校验。
 */
export interface BattleNotification {
  type: 'BATTLE_FINISHED'
  battleId: number
  status: BattleStatus
  serverTime: string
}
