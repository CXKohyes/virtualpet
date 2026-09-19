import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/api/client'
import { fetchGameConfig } from '@/api/gameConfig'
import { activatePet, createPet, fetchJournal, fetchPets, fetchPet, performAction, releasePet } from '@/api/pet'
import { PET_SCENE_LINES, PET_STATUS_LINES } from '@/content/petLines'
import { usePetStore } from '@/stores/petStore'

import type { ActionOutcome, GameConfig, JournalEntry, Pet, SettlementSummary } from '@/types/pet'

vi.mock('@/api/pet', () => ({
  createPet: vi.fn(),
  fetchPet: vi.fn(),
  fetchJournal: vi.fn(),
  performAction: vi.fn(),
  fetchPets: vi.fn(),
  activatePet: vi.fn(),
  releasePet: vi.fn(),
}))

// 名册会顺手拉一次配置（要 maxSlots）。不 mock 的话它会去打真实网络请求 ——
// 被 loadRoster 的 catch 吞掉，测试看着是绿的，但每次都在等一个必然失败的请求。
vi.mock('@/api/gameConfig', () => ({
  fetchGameConfig: vi.fn(),
}))

const fetchPetMock = vi.mocked(fetchPet)
const fetchJournalMock = vi.mocked(fetchJournal)
const createPetMock = vi.mocked(createPet)
const performActionMock = vi.mocked(performAction)
const fetchPetsMock = vi.mocked(fetchPets)
const activatePetMock = vi.mocked(activatePet)
const releasePetMock = vi.mocked(releasePet)
const fetchGameConfigMock = vi.mocked(fetchGameConfig)

/** 固定"现在"，让冷却倒计时可预期。 */
const NOW = new Date('2026-09-17T12:00:00Z')

function makePet(overrides: Partial<Pet> = {}): Pet {
  return {
    id: 1,
    slot: 0,
    active: true,
    species: 'CAT',
    name: '咪咪',
    satiety: 80,
    mood: 80,
    hygiene: 80,
    energy: 80,
    health: 100,
    status: 'NORMAL',
    level: 1,
    exp: 0,
    evolutionStage: 0,
    sleepingSince: null,
    lastSettledAt: NOW.toISOString(),
    cooldowns: {},
    settlement: null,
    ...overrides,
  }
}

function makeConfig(overrides: Partial<GameConfig> = {}): GameConfig {
  return {
    offlineCapHours: 12,
    maxLevel: 10,
    maxSlots: 3,
    expThresholds: [40, 90],
    species: [],
    actions: [],
    evolution: [],
    ...overrides,
  }
}

function makeEntry(overrides: Partial<JournalEntry> = {}): JournalEntry {
  return {
    id: 1,
    action: 'FEED',
    at: NOW.toISOString(),
    deltas: { satiety: 20, mood: 3, hygiene: -2, energy: 0, health: 0 },
    xpGained: 6,
    levelUp: false,
    evolved: false,
    messageKey: 'FEED_OK',
    ...overrides,
  }
}

function makeOutcome(overrides: Partial<ActionOutcome> = {}): ActionOutcome {
  return {
    pet: makePet(),
    deltas: { satiety: 20, mood: 3, hygiene: -2, energy: 0, health: 0 },
    xpGained: 6,
    levelUp: false,
    evolved: false,
    messageKey: 'FEED_OK',
    cooldownUntil: '2026-09-17T12:01:00Z',
    journalEntry: makeEntry(),
    ...overrides,
  }
}

function makeSummary(overrides: Partial<SettlementSummary> = {}): SettlementSummary {
  return {
    settledHours: 8,
    deltas: { satiety: -40, mood: -32, hygiene: -18, energy: -32, health: 0 },
    statusBefore: 'NORMAL',
    statusAfter: 'NORMAL',
    wokeUp: false,
    sleptHours: 0,
    ...overrides,
  }
}

describe('petStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
    fetchJournalMock.mockResolvedValue([])
    // 名册和配置给了默认值：不设的话 vi.fn() 返回 undefined，
    // loadRoster 会把它当成"名册就是这个值"存下来，后面读 .length 就炸了
    fetchPetsMock.mockResolvedValue([])
    fetchGameConfigMock.mockResolvedValue(makeConfig())
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  describe('load', () => {
    it('成功时把服务端返回的宠物整体存下来', async () => {
      fetchPetMock.mockResolvedValue(makePet({ satiety: 45 }))

      const store = usePetStore()
      await store.load()

      expect(store.pet?.satiety).toBe(45)
      expect(store.error).toBeNull()
      expect(store.loading).toBe(false)
    })

    it('还没领养时置空且不算错误', async () => {
      fetchPetMock.mockRejectedValue(new ApiError('PET_NOT_FOUND', '还没有领养宠物', 404))

      const store = usePetStore()
      await store.load()

      expect(store.pet).toBeNull()
      expect(store.error).toBeNull()
      expect(store.journal).toHaveLength(0)
    })

    it('其他错误会记录下来', async () => {
      fetchPetMock.mockRejectedValue(new ApiError('UNAUTHORIZED', '登录状态已失效', 401))

      const store = usePetStore()
      await store.load()

      expect(store.error).toBe('登录状态已失效')
    })

    it('ensureLoaded 只打一次接口', async () => {
      fetchPetMock.mockResolvedValue(makePet())

      const store = usePetStore()
      await store.ensureLoaded()
      await store.ensureLoaded()
      await store.ensureLoaded()

      expect(fetchPetMock).toHaveBeenCalledTimes(1)
    })
  })

  describe('照护日志', () => {
    it('日志来自服务端，顺序原样保留', async () => {
      fetchPetMock.mockResolvedValue(makePet())
      fetchJournalMock.mockResolvedValue([
        makeEntry({ id: 9, action: 'PLAY', messageKey: 'PLAY_OK' }),
        makeEntry({ id: 8, action: 'FEED' }),
      ])

      const store = usePetStore()
      await store.load()

      expect(store.journal).toHaveLength(2)
      expect(store.journal[0]?.id).toBe(9)
      expect(store.journal[0]?.action).toBe('PLAY')
    })

    it('日志接口失败时退化成空列表，不影响宠物状态', async () => {
      fetchPetMock.mockResolvedValue(makePet({ satiety: 55 }))
      fetchJournalMock.mockRejectedValue(new ApiError('INTERNAL_ERROR', '服务器开小差了', 500))

      const store = usePetStore()
      await store.load()

      expect(store.pet?.satiety).toBe(55)
      expect(store.journal).toHaveLength(0)
      expect(store.error).toBeNull()
    })

    it('操作成功后把服务端返回的那条日志插到最前', async () => {
      fetchPetMock.mockResolvedValue(makePet())
      fetchJournalMock.mockResolvedValue([makeEntry({ id: 1 })])
      const store = usePetStore()
      await store.load()

      performActionMock.mockResolvedValue(makeOutcome({ journalEntry: makeEntry({ id: 2, action: 'PLAY', messageKey: 'PLAY_OK' }) }))
      await store.act('PLAY')

      expect(store.journal.map((entry) => entry.id)).toEqual([2, 1])
    })

    it('幂等重放拿到同一条日志时不会重复记一笔', async () => {
      fetchPetMock.mockResolvedValue(makePet())
      const store = usePetStore()
      await store.load()

      // 服务端回放返回的是第一次那条日志，id 相同
      performActionMock.mockResolvedValue(makeOutcome({ journalEntry: makeEntry({ id: 7 }) }))
      await store.act('FEED')
      await store.act('FEED')

      expect(store.journal.filter((entry) => entry.id === 7)).toHaveLength(1)
    })
  })

  describe('离线结算摘要', () => {
    it('读取时把服务端的结算摘要存下来供界面展示', async () => {
      fetchPetMock.mockResolvedValue(makePet({ settlement: makeSummary() }))

      const store = usePetStore()
      await store.load()

      expect(store.offlineSummary?.settledHours).toBe(8)
      expect(store.offlineSummary?.deltas.satiety).toBe(-40)
    })

    it('用户关掉之后清空', async () => {
      fetchPetMock.mockResolvedValue(makePet({ settlement: makeSummary() }))
      const store = usePetStore()
      await store.load()

      store.dismissOfflineSummary()

      expect(store.offlineSummary).toBeNull()
    })

    it('没有经过时间时没有摘要', async () => {
      fetchPetMock.mockResolvedValue(makePet({ settlement: null }))

      const store = usePetStore()
      await store.load()

      expect(store.offlineSummary).toBeNull()
    })

    it('执行操作后摘要失效（这段时间已经被这次操作结算掉了）', async () => {
      fetchPetMock.mockResolvedValue(makePet({ settlement: makeSummary() }))
      const store = usePetStore()
      await store.load()
      expect(store.offlineSummary).not.toBeNull()

      performActionMock.mockResolvedValue(makeOutcome())
      await store.act('FEED')

      expect(store.offlineSummary).toBeNull()
    })
  })

  describe('seed（会话接口带回的宠物）', () => {
    it('把会话里的宠物和离线摘要一起种进来', () => {
      const store = usePetStore()

      store.seed(makePet({ settlement: makeSummary() }))

      expect(store.pet?.name).toBe('咪咪')
      expect(store.offlineSummary?.settledHours).toBe(8)
    })

    it('种过之后 ensureLoaded 不再打接口，否则摘要会被第二次读取读丢', async () => {
      // 后端的读路径都会先结算：第二次读时时间已经结算完，settlement 会变成 null
      fetchPetMock.mockResolvedValue(makePet({ settlement: null }))

      const store = usePetStore()
      store.seed(makePet({ settlement: makeSummary() }))
      await store.ensureLoaded()

      expect(fetchPetMock).not.toHaveBeenCalled()
      expect(store.offlineSummary?.settledHours).toBe(8)
    })

    it('会话说没有宠物时也不打接口，直接进领养页', async () => {
      const store = usePetStore()

      store.seed(null)
      await store.ensureLoaded()

      expect(fetchPetMock).not.toHaveBeenCalled()
      expect(store.pet).toBeNull()
    })

    it('重新种入时日志清空，避免拿到上一个存档的记录', () => {
      const store = usePetStore()
      store.seed(makePet({ settlement: null }))
      store.seed(makePet({ settlement: null }))

      expect(store.journal).toHaveLength(0)
    })
  })

  describe('loadJournal', () => {
    it('只拉日志，不动宠物状态', async () => {
      fetchPetMock.mockResolvedValue(makePet({ satiety: 33 }))
      fetchJournalMock.mockResolvedValue([makeEntry({ id: 3 })])

      const store = usePetStore()
      await store.loadJournal()

      expect(store.journal).toHaveLength(1)
      expect(store.pet).toBeNull()
      expect(fetchPetMock).not.toHaveBeenCalled()
    })

    it('失败时退化成空列表', async () => {
      fetchJournalMock.mockRejectedValue(new ApiError('INTERNAL_ERROR', '服务器开小差了', 500))

      const store = usePetStore()
      await store.loadJournal()

      expect(store.journal).toHaveLength(0)
      expect(store.error).toBeNull()
    })
  })

  describe('act', () => {
    it('成功时用服务端状态整体覆盖', async () => {
      fetchPetMock.mockResolvedValue(makePet())
      const store = usePetStore()
      await store.load()

      const after = makePet({ satiety: 100, exp: 6, cooldowns: { FEED: '2026-09-17T12:01:00Z' } })
      performActionMock.mockResolvedValue(makeOutcome({ pet: after }))

      await store.act('FEED')

      expect(store.pet).toEqual(after)
      expect(store.acting).toBeNull()
      // 台词改成按物种说话，猫喂食应该出自「猫 × 喂食」那个桶
      expect(PET_SCENE_LINES.CAT.FEED).toContain(store.speech)
    })

    it('失败时回滚按压态并展示后端给的原因', async () => {
      fetchPetMock.mockResolvedValue(makePet())
      const store = usePetStore()
      await store.load()
      const before = store.pet

      performActionMock.mockRejectedValue(new ApiError('ACTION_NO_EFFECT', '它现在不饿', 409))

      await store.act('FEED')

      expect(store.acting).toBeNull()
      expect(store.pet).toEqual(before)
      expect(store.error).toBe('它现在不饿')
      expect(store.journal).toHaveLength(0)
    })

    it('请求进行中会暴露 acting，供按钮显示按压态', async () => {
      fetchPetMock.mockResolvedValue(makePet())
      const store = usePetStore()
      await store.load()

      // 抓住 resolve，把请求挂住，观察中间态
      let releaseAction!: (outcome: ActionOutcome) => void
      performActionMock.mockImplementation(
        () =>
          new Promise<ActionOutcome>((resolve) => {
            releaseAction = resolve
          }),
      )

      const pending = store.act('FEED')
      await Promise.resolve()
      expect(store.acting).toBe('FEED')

      releaseAction(makeOutcome())
      await pending
      expect(store.acting).toBeNull()
    })

    it('升级和进化会置上一次性提示', async () => {
      fetchPetMock.mockResolvedValue(makePet())
      const store = usePetStore()
      await store.load()

      performActionMock.mockResolvedValue(
        makeOutcome({
          pet: makePet({ level: 4, evolutionStage: 1 }),
          levelUp: true,
          evolved: true,
          messageKey: 'PLAY_OK',
          journalEntry: makeEntry({ action: 'PLAY', levelUp: true, evolved: true }),
        }),
      )

      await store.act('PLAY')

      expect(store.levelUpFlash).toBe(true)
      expect(store.evolvedFlash).toBe(true)

      store.consumeFlash()
      expect(store.levelUpFlash).toBe(false)
      expect(store.evolvedFlash).toBe(false)
    })

    it('没有宠物时直接忽略', async () => {
      const store = usePetStore()
      await store.act('FEED')
      expect(performActionMock).not.toHaveBeenCalled()
    })
  })

  describe('性格台词（PRD 2.8）', () => {
    it('操作后说的话出自该物种该操作的场景桶', async () => {
      fetchPetMock.mockResolvedValue(makePet())
      const store = usePetStore()
      await store.load()

      performActionMock.mockResolvedValue(makeOutcome())
      await store.act('PLAY')

      expect(PET_SCENE_LINES.CAT.PLAY).toContain(store.speech)
    })

    it('换一个物种就说另一种话', async () => {
      fetchPetMock.mockResolvedValue(makePet({ species: 'DRAGON' }))
      const store = usePetStore()
      await store.load()

      performActionMock.mockResolvedValue(makeOutcome({ pet: makePet({ species: 'DRAGON' }) }))
      await store.act('CLEAN')

      expect(PET_SCENE_LINES.DRAGON.CLEAN).toContain(store.speech)
    })

    it('进化台词盖过升级台词，升级台词盖过操作台词', async () => {
      fetchPetMock.mockResolvedValue(makePet())
      const store = usePetStore()
      await store.load()

      performActionMock.mockResolvedValue(
        makeOutcome({
          pet: makePet({ level: 4, evolutionStage: 1 }),
          levelUp: true,
          evolved: true,
        }),
      )
      await store.act('PLAY')

      expect(PET_SCENE_LINES.CAT.EVOLVE).toContain(store.speech)
    })

    it('升级时说的是升级台词', async () => {
      fetchPetMock.mockResolvedValue(makePet())
      const store = usePetStore()
      await store.load()

      performActionMock.mockResolvedValue(makeOutcome({ levelUp: true }))
      await store.act('FEED')

      expect(PET_SCENE_LINES.CAT.LEVEL_UP).toContain(store.speech)
    })

    it('打开应用时带着离线摘要就说回访台词', async () => {
      const store = usePetStore()

      store.seed(makePet({ settlement: makeSummary() }))

      expect(PET_SCENE_LINES.CAT.RETURN).toContain(store.speech)
    })

    it('这次结算把宠物推进生病，优先说生病台词', async () => {
      const store = usePetStore()

      store.seed(
        makePet({
          status: 'SICK',
          settlement: makeSummary({ statusBefore: 'HUNGRY', statusAfter: 'SICK' }),
        }),
      )

      expect(PET_SCENE_LINES.CAT.SICK).toContain(store.speech)
    })

    it('刚打开没离开过就说当前状态的台词', async () => {
      const store = usePetStore()

      store.seed(makePet({ status: 'HUNGRY' }))

      expect(PET_STATUS_LINES.CAT.HUNGRY).toContain(store.speech)
    })

    it('说话不连续重复', async () => {
      fetchPetMock.mockResolvedValue(makePet())
      const store = usePetStore()
      await store.load()

      let previous = ''
      for (let i = 0; i < 30; i += 1) {
        performActionMock.mockResolvedValue(makeOutcome())
        await store.act('FEED')
        expect(store.speech).not.toBe(previous)
        previous = store.speech
      }
    })
  })

  describe('空闲台词', () => {
    it('刚操作完不会立刻插一句，免得顶掉操作反馈', async () => {
      fetchPetMock.mockResolvedValue(makePet())
      const store = usePetStore()
      await store.load()

      performActionMock.mockResolvedValue(makeOutcome())
      await store.act('FEED')
      const afterAction = store.speech

      store.speakIdle()

      expect(store.speech).toBe(afterAction)
    })

    it('隔得够久就按当前状态说一句', async () => {
      fetchPetMock.mockResolvedValue(makePet({ status: 'SAD' }))
      const store = usePetStore()
      await store.load()
      const afterLoad = store.speech

      // 把时间推过空闲间隔
      vi.setSystemTime(new Date(NOW.getTime() + 20_000))
      store.speakIdle()

      expect(store.speech).not.toBe(afterLoad)
      expect(PET_STATUS_LINES.CAT.SAD).toContain(store.speech)
    })

    it('还没有宠物时不说话', () => {
      const store = usePetStore()

      expect(() => store.speakIdle()).not.toThrow()
      expect(store.speech).toBe('')
    })
  })

  describe('冷却', () => {
    it('按服务端给的时间算剩余秒数', async () => {
      fetchPetMock.mockResolvedValue(
        makePet({
          cooldowns: {
            FEED: '2026-09-17T12:00:30Z', // 还有 30 秒
            PLAY: '2026-09-17T11:59:00Z', // 早就结束了，不该出现
          },
        }),
      )

      const store = usePetStore()
      await store.load()
      store.startClock()
      store.stopClock()

      expect(store.cooldownSeconds.FEED).toBe(30)
      expect(store.cooldownSeconds.PLAY).toBeUndefined()
    })

    it('sleeping 跟随服务端返回的 sleepingSince', async () => {
      fetchPetMock.mockResolvedValue(makePet({ sleepingSince: NOW.toISOString() }))
      const store = usePetStore()
      await store.load()

      expect(store.sleeping).toBe(true)
    })
  })

  describe('adopt / reset', () => {
    it('领养成功后写入宠物和开场白，日志清空', async () => {
      fetchPetMock.mockResolvedValue(makePet())
      fetchJournalMock.mockResolvedValue([makeEntry()])
      createPetMock.mockResolvedValue(makePet({ name: '旺财', species: 'DOG', id: 2 }))

      const store = usePetStore()
      await store.load()
      expect(store.journal).toHaveLength(1)

      const ok = await store.adopt('DOG', '旺财')

      expect(ok).toBe(true)
      expect(store.pet?.name).toBe('旺财')
      expect(store.speech).toContain('旺财')
      expect(store.journal).toHaveLength(0)
    })

    it('领养失败时返回 false 并带上原因', async () => {
      createPetMock.mockRejectedValue(new ApiError('PET_SLOTS_FULL', '宠物已经满了，先送走一只再领养', 409))

      const store = usePetStore()
      const ok = await store.adopt('CAT', '咪咪')

      expect(ok).toBe(false)
      expect(store.error).toBe('宠物已经满了，先送走一只再领养')
    })

    it('送走最后一只后清空宠物、日志和摘要', async () => {
      fetchPetMock.mockResolvedValue(makePet({ settlement: makeSummary() }))
      fetchJournalMock.mockResolvedValue([makeEntry()])
      releasePetMock.mockResolvedValue(undefined)
      fetchPetsMock.mockResolvedValue([])

      const store = usePetStore()
      await store.load()
      const ok = await store.releaseActive()

      expect(ok).toBe(true)
      expect(store.pet).toBeNull()
      expect(store.journal).toHaveLength(0)
      expect(store.offlineSummary).toBeNull()
    })
  })

  describe('多宠物槽', () => {
    it('loadRoster 存下名册和槽位上限', async () => {
      fetchPetsMock.mockResolvedValue([
        makePet({ id: 1, slot: 0, active: false }),
        makePet({ id: 2, slot: 1, active: true }),
      ])
      fetchGameConfigMock.mockResolvedValue(makeConfig({ maxSlots: 3 }))

      const store = usePetStore()
      await store.loadRoster()

      expect(store.roster).toHaveLength(2)
      expect(store.maxSlots).toBe(3)
      expect(store.slotsFull).toBe(false)
    })

    it('名册满了时 slotsFull 为真', async () => {
      fetchPetsMock.mockResolvedValue([
        makePet({ id: 1, slot: 0 }),
        makePet({ id: 2, slot: 1 }),
        makePet({ id: 3, slot: 2 }),
      ])
      fetchGameConfigMock.mockResolvedValue(makeConfig({ maxSlots: 3 }))

      const store = usePetStore()
      await store.loadRoster()

      expect(store.slotsFull).toBe(true)
    })

    it('配置拿不到时不把入口藏掉（宁可让服务端去拒绝）', async () => {
      fetchPetsMock.mockResolvedValue([makePet({ id: 1 })])
      fetchGameConfigMock.mockRejectedValue(new Error('连不上'))

      const store = usePetStore()
      await store.loadRoster()

      expect(store.maxSlots).toBe(0)
      expect(store.slotsFull).toBe(false)
      // 名册本身不该被配置的失败拖没
      expect(store.roster).toHaveLength(1)
    })

    it('切换当前宠物，并把服务端带回来的结算摘要接上', async () => {
      activatePetMock.mockResolvedValue(makePet({ id: 2, slot: 1, name: '旺财', settlement: makeSummary() }))
      fetchPetsMock.mockResolvedValue([])

      const store = usePetStore()
      const ok = await store.switchTo(2)

      expect(ok).toBe(true)
      expect(store.pet?.name).toBe('旺财')
      expect(store.offlineSummary).not.toBeNull()
    })

    it('点到当前那只时不发请求', async () => {
      fetchPetMock.mockResolvedValue(makePet({ id: 7 }))

      const store = usePetStore()
      await store.load()
      const ok = await store.switchTo(7)

      expect(ok).toBe(true)
      expect(activatePetMock).not.toHaveBeenCalled()
    })

    it('送走当前宠物但还有别的时，跟着服务端换成剩下那只', async () => {
      fetchPetMock.mockResolvedValue(makePet({ id: 1, slot: 0 }))
      releasePetMock.mockResolvedValue(undefined)
      fetchPetsMock.mockResolvedValue([
        // 服务端已经把当前宠物改到了剩下这只，前端照着 `active` 认人，
        // 不自己再定一套「该轮到谁」的规则
        makePet({ id: 2, slot: 1, name: '旺财', active: true }),
      ])

      const store = usePetStore()
      await store.load()
      const ok = await store.release(1)

      expect(ok).toBe(true)
      expect(store.pet?.id).toBe(2)
      expect(store.pet?.name).toBe('旺财')
    })
  })
})
