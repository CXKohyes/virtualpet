import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { ApiError, serverNow } from '@/api/client'
import { fetchGameConfig } from '@/api/gameConfig'
import { activatePet, createPet, fetchJournal, fetchPets, fetchPet, performAction, releasePet } from '@/api/pet'
import { soundForAction, useAudio } from '@/composables/useAudio'
import { createSpeechDirector } from '@/content/petLines'

import type { SpeechContext, SpeechScene } from '@/content/petLines'
import type { ActionOutcome, JournalEntry, Pet, PetAction, SettlementSummary, Species } from '@/types/pet'

/** 日志区最多显示多少条，和服务端默认值保持一致。 */
const JOURNAL_LIMIT = 20

/** 空闲时两次主动开口之间至少隔多久。 */
const IDLE_GAP_MS = 14000

/** 操作 → 场景台词。睡觉和唤醒也各有自己的场景。 */
const ACTION_SCENES: Record<PetAction, SpeechScene> = {
  FEED: 'FEED',
  PLAY: 'PLAY',
  CLEAN: 'CLEAN',
  SLEEP: 'SLEEP',
  WAKE: 'WAKE',
}

/**
 * 宠物状态、操作与照护日志。
 *
 * 两条铁律：
 *
 * 1. **服务端返回的宠物状态是唯一事实。** 每次操作成功后整体覆盖 `pet`，
 *    前端不做增量推算，也不复制衰减、经验、等级和进化规则。
 * 2. **乐观更新只用于即时反馈**（按钮的按压态、冷却计时），
 *    不猜属性数值 —— 猜数值就等于把规则抄了一遍。
 */
export const usePetStore = defineStore('pet', () => {
  /**
   * 当前宠物。四个操作、照护日志、对战都作用在它身上。
   *
   * 多宠物槽之后这个字段的含义没变，只是不再是「唯一那只」了 ——
   * 全部宠物在 {@link roster} 里，切换用 {@link switchTo}。
   */
  const pet = ref<Pet | null>(null)
  /** 名册：全部宠物，按槽位升序。每只都带着服务端结算过的状态（PRD 2.1）。 */
  const roster = ref<Pet[]>([])
  /** 槽位上限，来自配置接口。拿不到时是 0，界面就不显示「再养一只」。 */
  const maxSlots = ref(0)
  const loading = ref(false)
  /** 正在执行的操作，按钮据此进入按压态。 */
  const acting = ref<PetAction | null>(null)
  const error = ref<string | null>(null)
  /** 照护日志，来自服务端。 */
  const journal = ref<JournalEntry[]>([])
  /** 本次读取结算出来的离线变化摘要，用户关掉后清空。 */
  const offlineSummary = ref<SettlementSummary | null>(null)
  /** 性格气泡当前展示的短句。 */
  const speech = ref('')
  /** 升级、进化的一次性提示，由界面消费后清掉。 */
  const levelUpFlash = ref(false)
  const evolvedFlash = ref(false)

  // 冷却倒计时用服务端时间推算（client.ts 的 serverNow），不用本地时钟减
  const now = ref(serverNow())
  let timer: ReturnType<typeof setInterval> | null = null

  // 台词和音效：台词不连续重复由 director 负责，音效静音由 uiStore 负责
  const director = createSpeechDirector()
  const audio = useAudio()
  let lastSpokeAt = serverNow()

  /** 说一句。场景优先，没有场景就按当前状态说（PRD 2.8）。 */
  function say(context: SpeechContext): void {
    speak(director.next(context))
  }

  /**
   * 直接给一句现成的话。
   *
   * 必须同时更新 `lastSpokeAt`：领养时的开场白不走台词库，
   * 忘了打时间戳的话，空闲台词会以为"很久没说话了"，
   * 刚说完自我介绍就立刻插一句状态台词。
   */
  function speak(text: string): void {
    speech.value = text
    lastSpokeAt = serverNow()
  }

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

  /**
   * 槽位是否已经满了。
   *
   * 配置没拿到时（`maxSlots === 0`）一律当作「没满」：宁可让玩家点进领养页
   * 被服务端拒绝并看到明确原因，也不要因为一次配置请求失败就把入口藏掉。
   */
  const slotsFull = computed(() => maxSlots.value > 0 && roster.value.length >= maxSlots.value)

  /** 是否已经向后端确认过有没有宠物。 */
  const loadedOnce = ref(false)
  /** 名册是否已经拉过一次。和 loadedOnce 分开：会话响应只带当前宠物，不带名册。 */
  const rosterLoaded = ref(false)

  /**
   * 用会话接口带回来的宠物初始化。
   *
   * 会话响应里的宠物已经经过结算，并且带着离线摘要 —— 那正是「回访」的那一刻。
   * 直接用掉它，**不要再发一次 `GET /pets/me`**：后端的读取路径都会先结算，
   * 第二次读时时间已经被第一次结算完了，摘要会变成 null 丢掉。
   */
  function seed(seeded: Pet | null): void {
    pet.value = seeded
    journal.value = []
    offlineSummary.value = seeded?.settlement ?? null
    loadedOnce.value = true
    if (seeded) {
      greet(seeded)
    }
  }

  /**
   * 打开应用时的第一句话。
   *
   * 有结算摘要说明确实离开过一段时间 → 回访台词；这次结算把宠物推进了生病状态 →
   * 生病台词（优先）。都没有就按当前状态说一句。
   */
  function greet(loadedPet: Pet): void {
    const settlement = loadedPet.settlement
    const justFellSick =
      settlement !== null && settlement.statusAfter === 'SICK' && settlement.statusBefore !== 'SICK'

    if (justFellSick) {
      say({ species: loadedPet.species, scene: 'SICK', status: loadedPet.status })
      audio.play('SICK')
      return
    }
    say({
      species: loadedPet.species,
      scene: settlement ? 'RETURN' : undefined,
      status: loadedPet.status,
    })
  }

  /**
   * 空闲时主动说一句当前状态的台词。
   *
   * 间隔由 `IDLE_GAP_MS` 控制：刚操作完就不要再插一句，
   * 免得反馈文案被立刻顶掉。
   */
  function speakIdle(): void {
    if (!pet.value || acting.value !== null) {
      return
    }
    if (serverNow() - lastSpokeAt < IDLE_GAP_MS) {
      return
    }
    say({ species: pet.value.species, status: pet.value.status })
  }

  /** 只拉照护日志。日志是次要信息，失败就保持空列表，不影响宠物状态。 */
  async function loadJournal(): Promise<void> {
    try {
      journal.value = await fetchJournal(JOURNAL_LIMIT)
    } catch {
      journal.value = []
    }
  }

  /** 拉取宠物状态和照护日志。还没领养不算错误，只是把状态清空。 */
  async function load(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const [loadedPet, entries] = await Promise.all([
        fetchPet(),
        fetchJournal(JOURNAL_LIMIT).catch(() => [] as JournalEntry[]),
      ])
      pet.value = loadedPet
      journal.value = entries
      offlineSummary.value = loadedPet.settlement
      loadedOnce.value = true
      greet(loadedPet)
    } catch (cause) {
      if (cause instanceof ApiError && cause.code === 'PET_NOT_FOUND') {
        clearPetState()
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
    if (!loadedOnce.value) {
      await load()
    }
    if (!rosterLoaded.value) {
      await loadRoster()
    }
  }

  // ---------------------------------------------------------------- 多宠物槽

  /**
   * 拉名册和槽位上限。
   *
   * 名册是次要信息：拉不到就保持现状，不影响宠物状态本身。
   * 配置单独 catch —— 它只是「再养一只」那个入口的开关，
   * 拿不到顶多不显示入口，不该把名册一起拖没。
   */
  async function loadRoster(): Promise<void> {
    try {
      const [list, config] = await Promise.all([
        fetchPets(),
        fetchGameConfig().catch(() => null),
      ])
      roster.value = list
      if (config !== null) {
        maxSlots.value = config.maxSlots
      }
      rosterLoaded.value = true
    } catch {
      // 名册失败不影响主流程，和 loadJournal 一个策略
    }
  }

  /**
   * 切换当前宠物（PRD 2.1）。
   *
   * 点到的是同一只就什么都不做 —— 否则每次点名册都白跑一次结算。
   */
  async function switchTo(petId: number): Promise<boolean> {
    if (pet.value?.id === petId) {
      return true
    }
    loading.value = true
    error.value = null
    try {
      enterPet(await activatePet(petId))
      await loadJournal()
      await loadRoster()
      return true
    } catch (cause) {
      error.value = describe(cause)
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * 送走一只宠物（PRD 2.1）。
   *
   * 送走的如果是当前宠物，服务端已经在同一个事务里把当前宠物改到了剩下那只
   * （一只都不剩就置空）。这里**跟着服务端的结论走**：重新拉一次名册，
   * 用返回的 `active` 标记挑下一只，而不是自己再定一套「该轮到谁」的规则。
   */
  async function release(petId: number): Promise<boolean> {
    loading.value = true
    error.value = null
    try {
      await releasePet(petId)
      await loadRoster()

      if (pet.value?.id === petId) {
        const next = roster.value.find((item) => item.active) ?? roster.value[0]
        if (next) {
          enterPet(next)
          await loadJournal()
        } else {
          clearPetState()
          loadedOnce.value = true
        }
      }
      return true
    } catch (cause) {
      error.value = describe(cause)
      return false
    } finally {
      loading.value = false
    }
  }

  /** 送走当前宠物。设置页的「送走这只」用它；名册里逐只送走用 {@link release}。 */
  async function releaseActive(): Promise<boolean> {
    return pet.value === null ? false : release(pet.value.id)
  }

  /**
   * 把一只宠物变成「当前宠物」。
   *
   * 切换和送走之后都走这里。换了一只要重新开始记「上一条台词」，
   * 并且把服务端在响应里带回来的结算摘要接上 —— 回访台词不该因为
   * 换了个入口就丢掉。
   */
  function enterPet(next: Pet): void {
    pet.value = next
    offlineSummary.value = next.settlement
    journal.value = []
    loadedOnce.value = true
    director.reset()
    greet(next)
  }

  /** 领养。成功返回 true。 */
  async function adopt(species: Species, name: string): Promise<boolean> {
    loading.value = true
    error.value = null
    try {
      const created = await createPet(species, name)
      pet.value = created
      loadedOnce.value = true
      journal.value = []
      offlineSummary.value = null
      // 换了一只宠物，重新开始记"上一条台词"
      director.reset()
      speak(`我是${name}，请多关照！`)
      // 新领养的占了一个槽位，名册和「还能不能再养」都要跟着变
      await loadRoster()
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
      applyOutcome(await performAction(action, createRequestId()), action)
    } catch (cause) {
      // 失败回滚：按键的按压态在 finally 里清掉，这里只负责把原因呈现出来
      error.value = describe(cause)
    } finally {
      acting.value = null
    }
  }

  function applyOutcome(outcome: ActionOutcome, action: PetAction): void {
    pet.value = outcome.pet
    // 操作前如果有离线结算，操作本身也顺带把它结算掉了，旧摘要就过期了
    offlineSummary.value = null
    levelUpFlash.value = outcome.levelUp
    evolvedFlash.value = outcome.evolved

    // 场景台词优先级：进化 > 升级 > 操作本身（PRD 2.8）
    say({
      species: outcome.pet.species,
      scene: outcome.evolved ? 'EVOLVE' : outcome.levelUp ? 'LEVEL_UP' : ACTION_SCENES[action],
      status: outcome.pet.status,
    })

    // 音效同一套优先级，只有喂食、玩耍、清洁有操作音（PRD 2.9）
    const sound = outcome.evolved ? 'EVOLVE' : outcome.levelUp ? 'LEVEL_UP' : soundForAction(action)
    if (sound) {
      audio.play(sound)
    }

    // 用服务端刚写下的那条日志更新列表，幂等重放时拿到的是同一条，不会重复记
    journal.value = [
      outcome.journalEntry,
      ...journal.value.filter((entry) => entry.id !== outcome.journalEntry.id),
    ].slice(0, JOURNAL_LIMIT)
  }

  function clearPetState(): void {
    pet.value = null
    roster.value = []
    journal.value = []
    offlineSummary.value = null
    speech.value = ''
    director.reset()
  }

  function dismissOfflineSummary(): void {
    offlineSummary.value = null
  }

  function consumeFlash(): void {
    levelUpFlash.value = false
    evolvedFlash.value = false
  }

  return {
    pet,
    roster,
    maxSlots,
    slotsFull,
    loading,
    acting,
    error,
    journal,
    offlineSummary,
    speech,
    levelUpFlash,
    evolvedFlash,
    cooldownSeconds,
    sleeping,
    /** 服务端当前时间（毫秒），由 startClock 每秒刷新，用于顶栏展示。 */
    serverNowMs: now,
    startClock,
    stopClock,
    seed,
    load,
    loadJournal,
    ensureLoaded,
    speakIdle,
    adopt,
    act,
    loadRoster,
    switchTo,
    release,
    releaseActive,
    dismissOfflineSummary,
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
