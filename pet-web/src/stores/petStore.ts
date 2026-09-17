import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { ApiError, serverNow } from '@/api/client'
import { createPet, fetchPet, performAction, resetPet } from '@/api/pet'
import { messageForAction } from '@/content/messages'

import type { ActionOutcome, Pet, PetAction, Species } from '@/types/pet'

/** 照护日志的一条记录。 */
export interface JournalEntry {
  id: number
  at: number
  action: PetAction
  message: string
  xpGained: number
  levelUp: boolean
  evolved: boolean
}

/** 日志区最多保留多少条，避免长时间挂着页面无限增长。 */
const JOURNAL_LIMIT = 20

/**
 * 宠物状态与操作。
 *
 * 两条铁律：
 *
 * 1. **服务端返回的宠物状态是唯一事实。** 每次操作成功后整体覆盖 `pet`，
 *    前端不做增量推算，也不复制衰减、经验、等级和进化规则。
 * 2. **乐观更新只用于即时反馈**（按钮的按压态、冷却计时），
 *    不猜属性数值 —— 猜数值就等于把规则抄了一遍。
 */
export const usePetStore = defineStore('pet', () => {
  const pet = ref<Pet | null>(null)
  const loading = ref(false)
  /** 正在执行的操作，按钮据此进入按压态。 */
  const acting = ref<PetAction | null>(null)
  const error = ref<string | null>(null)
  const journal = ref<JournalEntry[]>([])
  /** 性格气泡当前展示的短句。 */
  const speech = ref('')
  /** 升级、进化的一次性提示，由界面消费后清掉。 */
  const levelUpFlash = ref(false)
  const evolvedFlash = ref(false)

  // 冷却倒计时用服务端时间推算（client.ts 的 serverNow），不用本地时钟减
  const now = ref(serverNow())
  let timer: ReturnType<typeof setInterval> | null = null

  /** 各操作剩余冷却秒数，只包含仍在冷却中的。 */
  const cooldownSeconds = computed<Partial<Record<PetAction, number>>>(() => {
    const result: Partial<Record<PetAction, number>> = {}
    const untils = pet.value?.cooldowns
    if (!untils) {
      return result
    }
    for (const action of Object.keys(untils) as PetAction[]) {
      const until = untils[action]
      if (!until) {
        continue
      }
      const remaining = Math.ceil((Date.parse(until) - now.value) / 1000)
      if (remaining > 0) {
        result[action] = remaining
      }
    }
    return result
  })

  /** 是否正在睡觉，决定操作区第四个按钮是「睡觉」还是「唤醒」。 */
  const sleeping = computed(() => pet.value?.sleepingSince !== null && pet.value?.sleepingSince !== undefined)

  let journalSequence = 0

  function startClock(): void {
    if (timer !== null) {
      return
    }
    now.value = serverNow()
    timer = setInterval(() => {
      now.value = serverNow()
    }, 1000)
  }

  function stopClock(): void {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  /** 是否已经向后端确认过有没有宠物。 */
  const loadedOnce = ref(false)

  /** 拉取宠物状态。还没领养不算错误，只是把 pet 置空。 */
  async function load(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      pet.value = await fetchPet()
      loadedOnce.value = true
    } catch (cause) {
      if (cause instanceof ApiError && cause.code === 'PET_NOT_FOUND') {
        pet.value = null
        loadedOnce.value = true
      } else {
        error.value = describe(cause)
      }
    } finally {
      loading.value = false
    }
  }

  /**
   * 只在还没确认过的时候拉一次。
   *
   * 路由守卫每次导航都会调用它，用它避免反复打接口。
   */
  async function ensureLoaded(): Promise<void> {
    if (loadedOnce.value) {
      return
    }
    await load()
  }

  /** 领养。成功返回 true。 */
  async function adopt(species: Species, name: string): Promise<boolean> {
    loading.value = true
    error.value = null
    try {
      pet.value = await createPet(species, name)
      loadedOnce.value = true
      journal.value = []
      speech.value = `我是${name}，请多关照！`
      return true
    } catch (cause) {
      error.value = describe(cause)
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * 执行一次操作。
   *
   * 按 TECH_DESIGN 7.3 的流程：按钮先进入按压态 → 调接口 →
   * 成功用服务端状态整体覆盖，失败回滚按压态并把后端给的原因显示出来。
   */
  async function act(action: PetAction): Promise<void> {
    if (!pet.value || acting.value !== null) {
      return
    }
    error.value = null
    acting.value = action

    try {
      const outcome = await performAction(action, createRequestId())
      applyOutcome(outcome)
    } catch (cause) {
      // 失败回滚：按键的按压态在 finally 里清掉，这里只负责把原因呈现出来
      error.value = describe(cause)
    } finally {
      acting.value = null
    }
  }

  function applyOutcome(outcome: ActionOutcome): void {
    pet.value = outcome.pet
    speech.value = messageForAction(outcome.messageKey)
    levelUpFlash.value = outcome.levelUp
    evolvedFlash.value = outcome.evolved

    journal.value = [
      {
        id: (journalSequence += 1),
        at: Date.now(),
        action: outcome.messageKey.replace(/_OK$/, '') as PetAction,
        message: messageForAction(outcome.messageKey),
        xpGained: outcome.xpGained,
        levelUp: outcome.levelUp,
        evolved: outcome.evolved,
      },
      ...journal.value,
    ].slice(0, JOURNAL_LIMIT)
  }

  /** 重置存档，回到领养页。成功返回 true。 */
  async function reset(): Promise<boolean> {
    loading.value = true
    error.value = null
    try {
      await resetPet()
      pet.value = null
      loadedOnce.value = true
      journal.value = []
      speech.value = ''
      return true
    } catch (cause) {
      error.value = describe(cause)
      return false
    } finally {
      loading.value = false
    }
  }

  function consumeFlash(): void {
    levelUpFlash.value = false
    evolvedFlash.value = false
  }

  return {
    pet,
    loading,
    acting,
    error,
    journal,
    speech,
    levelUpFlash,
    evolvedFlash,
    cooldownSeconds,
    sleeping,
    /** 服务端当前时间（毫秒），由 startClock 每秒刷新，用于顶栏展示。 */
    serverNowMs: now,
    startClock,
    stopClock,
    load,
    ensureLoaded,
    adopt,
    act,
    reset,
    consumeFlash,
  }
})

function createRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `req-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

function describe(cause: unknown): string {
  return cause instanceof Error ? cause.message : '操作失败了，请再试一次'
}
