import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/api/client'
import { createPet, fetchPet, performAction, resetPet } from '@/api/pet'
import { usePetStore } from '@/stores/petStore'

import type { ActionOutcome, Pet } from '@/types/pet'

vi.mock('@/api/pet', () => ({
  createPet: vi.fn(),
  fetchPet: vi.fn(),
  performAction: vi.fn(),
  resetPet: vi.fn(),
}))

const fetchPetMock = vi.mocked(fetchPet)
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
    ...overrides,
  }
}

describe('petStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
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

  describe('act', () => {
    it('成功时用服务端状态整体覆盖，并写入日志', async () => {
      fetchPetMock.mockResolvedValue(makePet())
      const store = usePetStore()
      await store.load()

      const after = makePet({ satiety: 100, exp: 6, cooldowns: { FEED: '2026-09-17T12:01:00Z' } })
      performActionMock.mockResolvedValue({
        pet: after,
        deltas: { satiety: 20, mood: 3, hygiene: -2, energy: 0, health: 0 },
        xpGained: 6,
        levelUp: false,
        evolved: false,
        messageKey: 'FEED_OK',
        cooldownUntil: '2026-09-17T12:01:00Z',
      })

      await store.act('FEED')

      expect(store.pet).toEqual(after)
      expect(store.pet?.satiety).toBe(100)
      expect(store.journal).toHaveLength(1)
      expect(store.journal[0]?.xpGained).toBe(6)
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

      // 回滚：不会有半途的 pending 状态残留，宠物状态也没被动过
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

      releaseAction({
        pet: makePet(),
        deltas: { satiety: 0, mood: 0, hygiene: 0, energy: 0, health: 0 },
        xpGained: 0,
        levelUp: false,
        evolved: false,
        messageKey: 'FEED_OK',
        cooldownUntil: null,
      })
      await pending
      expect(store.acting).toBeNull()
    })

    it('升级和进化会置上一次性提示', async () => {
      fetchPetMock.mockResolvedValue(makePet())
      const store = usePetStore()
      await store.load()

      performActionMock.mockResolvedValue({
        pet: makePet({ level: 4, evolutionStage: 1 }),
        deltas: { satiety: 0, mood: 22, hygiene: -4, energy: -12, health: 0 },
        xpGained: 8,
        levelUp: true,
        evolved: true,
        messageKey: 'PLAY_OK',
        cooldownUntil: null,
      })

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
    it('领养成功后写入宠物和开场白', async () => {
      createPetMock.mockResolvedValue(makePet({ name: '旺财', species: 'DOG' }))

      const store = usePetStore()
      const ok = await store.adopt('DOG', '旺财')

      expect(ok).toBe(true)
      expect(store.pet?.name).toBe('旺财')
      expect(store.speech).toContain('旺财')
    })

    it('领养失败时返回 false 并带上原因', async () => {
      createPetMock.mockRejectedValue(new ApiError('PET_ALREADY_EXISTS', '已经领养过宠物了', 409))

      const store = usePetStore()
      const ok = await store.adopt('CAT', '咪咪')

      expect(ok).toBe(false)
      expect(store.error).toBe('已经领养过宠物了')
    })

    it('重置后清空宠物和日志', async () => {
      fetchPetMock.mockResolvedValue(makePet())
      resetPetMock.mockResolvedValue(undefined)

      const store = usePetStore()
      await store.load()
      const ok = await store.reset()

      expect(ok).toBe(true)
      expect(store.pet).toBeNull()
      expect(store.journal).toHaveLength(0)
    })
  })
})
