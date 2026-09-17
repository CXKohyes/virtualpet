import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/api/client'
import { createPet, fetchJournal, fetchPet, performAction, resetPet } from '@/api/pet'
import { usePetStore } from '@/stores/petStore'

import type { ActionOutcome, JournalEntry, Pet, SettlementSummary } from '@/types/pet'

vi.mock('@/api/pet', () => ({
  createPet: vi.fn(),
  fetchPet: vi.fn(),
  fetchJournal: vi.fn(),
  performAction: vi.fn(),
  resetPet: vi.fn(),
}))

const fetchPetMock = vi.mocked(fetchPet)
const fetchJournalMock = vi.mocked(fetchJournal)
const createPetMock = vi.mocked(createPet)
const performActionMock = vi.mocked(performAction)
const resetPetMock = vi.mocked(resetPet)

/** 固定"现在"，让冷却倒计时可预期。 */
const NOW = new Date('2026-09-17T12:00:00Z')

function makePet(overrides: Partial<Pet> = {}): Pet {
  return {
    id: 1,
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
      expect(store.speech).toBe('吃得真香！')
      expect(store.acting).toBeNull()
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
      createPetMock.mockRejectedValue(new ApiError('PET_ALREADY_EXISTS', '已经领养过宠物了', 409))

      const store = usePetStore()
      const ok = await store.adopt('CAT', '咪咪')

      expect(ok).toBe(false)
      expect(store.error).toBe('已经领养过宠物了')
    })

    it('重置后清空宠物、日志和摘要', async () => {
      fetchPetMock.mockResolvedValue(makePet({ settlement: makeSummary() }))
      fetchJournalMock.mockResolvedValue([makeEntry()])
      resetPetMock.mockResolvedValue(undefined)

      const store = usePetStore()
      await store.load()
      const ok = await store.reset()

      expect(ok).toBe(true)
      expect(store.pet).toBeNull()
      expect(store.journal).toHaveLength(0)
      expect(store.offlineSummary).toBeNull()
    })
  })
})
