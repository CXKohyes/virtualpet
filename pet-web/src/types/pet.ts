/**
 * 与后端 DTO 一一对应的类型（docs/api.md 第 2 节）。
 *
 * 这些类型只描述服务端返回的结构，**不包含任何游戏规则**：
 * 衰减、经验、等级和进化的判定全部在服务端，前端不复制。
 */

export const SPECIES = ['CAT', 'DOG', 'DRAGON', 'RABBIT'] as const
export type Species = (typeof SPECIES)[number]

export const PET_STATUSES = [
  'NORMAL',
  'HUNGRY',
  'DIRTY',
  'TIRED',
  'SAD',
  'SICK',
  'SLEEPING',
] as const
export type PetStatus = (typeof PET_STATUSES)[number]

export const PET_ACTIONS = ['FEED', 'PLAY', 'CLEAN', 'SLEEP', 'WAKE'] as const
export type PetAction = (typeof PET_ACTIONS)[number]

/** 五项属性。 */
export interface PetAttributes {
  satiety: number
  mood: number
  hygiene: number
  energy: number
  health: number
}

/**
 * 一次懒结算的变化摘要（PRD 2.5）。
 *
 * 只在这次读取真的结算了时间时才有值，用来展示「你不在时发生了什么」。
 */
export interface SettlementSummary {
  settledHours: number
  deltas: AttributeDeltas
  statusBefore: PetStatus
  statusAfter: PetStatus
  wokeUp: boolean
  sleptHours: number
}

/** 照护日志的一条记录。数据来自服务端的操作日志，刷新页面也还在。 */
export interface JournalEntry {
  id: number
  action: PetAction
  /** UTC ISO-8601。 */
  at: string
  deltas: AttributeDeltas
  xpGained: number
  levelUp: boolean
  evolved: boolean
  messageKey: string
}

/** 宠物完整状态。服务端返回的是唯一事实，前端不自行推算。 */
export interface Pet extends PetAttributes {
  id: number
  /** 槽位号，同一玩家的宠物按它升序排列（PRD 2.1，多宠物槽）。 */
  slot: number
  /** 是不是当前宠物。`/pets/me` 返回的那只恒为 true，名册里只有一只是。 */
  active: boolean
  species: Species
  name: string
  status: PetStatus
  level: number
  exp: number
  evolutionStage: number
  /** UTC ISO-8601，未睡觉时为 null。 */
  sleepingSince: string | null
  lastSettledAt: string
  /** 仍在冷却中的操作 -> 冷却结束时刻，只包含还没结束的项。 */
  cooldowns: Partial<Record<PetAction, string>>
  /** 本次读取结算出来的变化摘要；时间没有前进时为 null。 */
  settlement: SettlementSummary | null
}

/** 单次操作的属性变化量，是操作本身的效果，不含离线衰减。 */
export type AttributeDeltas = PetAttributes

export interface ActionOutcome {
  pet: Pet
  deltas: AttributeDeltas
  xpGained: number
  levelUp: boolean
  evolved: boolean
  messageKey: string
  cooldownUntil: string | null
  /** 服务端刚写下的那条日志，前端直接拿它更新日志区，不用再拉一次列表。 */
  journalEntry: JournalEntry
}

export interface SessionData {
  playerId: number
  token: string
  pet: Pet | null
}

export interface GameConfig {
  offlineCapHours: number
  maxLevel: number
  /** 最多能养几只（PRD 2.1）。界面据此决定要不要显示「再养一只」。 */
  maxSlots: number
  expThresholds: number[]
  species: GameConfigSpecies[]
  actions: GameConfigAction[]
  evolution: GameConfigEvolution[]
}

export interface GameConfigSpecies {
  code: Species
  modifier: {
    playMoodBonus: number
    careGainBonus: number
    feedSatietyBonus: number
    hygieneDecayScale: number
    energyDecayScale: number
    healthRecoveryBonus: number
  }
}

export interface GameConfigAction {
  code: PetAction
  exp: number
  cooldownSeconds: number
  satiety: number
  mood: number
  hygiene: number
  energy: number
  requirement: string
}

export interface GameConfigEvolution {
  stage: number
  name: string
  minLevel: number
  minHealth: number
  minCoreAttribute: number | null
}
