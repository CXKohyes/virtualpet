/**
 * 与后端 DTO 一一对应的类型（docs/api.md 第 2 节）。
 *
 * 这些类型只描述服务端返回的结构，**不包含任何游戏规则**：
 * 衰减、经验、等级和进化的判定全部在服务端，前端不复制。
 */

export const SPECIES = ['CAT', 'DOG', 'DRAGON'] as const
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

/** 宠物完整状态。服务端返回的是唯一事实，前端不自行推算。 */
export interface Pet extends PetAttributes {
  id: number
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
}

export interface SessionData {
  playerId: number
  token: string
  pet: Pet | null
}

export interface GameConfig {
  offlineCapHours: number
  maxLevel: number
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
