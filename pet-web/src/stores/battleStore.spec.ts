import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/api/client'
import { challenge, fetchBattle, fetchBattles, fetchFriendCode } from '@/api/battle'
import { useBattleStore } from '@/stores/battleStore'

import type { Battle, BattleSummary } from '@/types/battle'

vi.mock('@/api/battle', () => ({
  challenge: vi.fn(),
  fetchBattle: vi.fn(),
  fetchBattles: vi.fn(),
  fetchFriendCode: vi.fn(),
}))

const challengeMock = vi.mocked(challenge)
const fetchBattleMock = vi.mocked(fetchBattle)
const fetchBattlesMock = vi.mocked(fetchBattles)
const fetchFriendCodeMock = vi.mocked(fetchFriendCode)

function makeBattle(overrides: Partial<Battle> = {}): Battle {
  return {
    id: 1,
    status: 'FINISHED',
    viewer: 'CHALLENGER',
    challenger: {
      petId: 1, name: '小蓝', species: 'DRAGON', level: 5, evolutionStage: 1,
      maxHp: 101, attack: 26, defense: 11, speed: 10,
    },
    defender: {
      petId: 2, name: '咪咪', species: 'CAT', level: 5, evolutionStage: 1,
      maxHp: 95, attack: 23, defense: 12, speed: 16,
    },
    winner: 'CHALLENGER',
    outcome: 'KO',
    rounds: 6,
    seed: 42,
    timeline: [],
    createdAt: '2026-09-17T12:00:00Z',
    finishedAt: '2026-09-17T12:00:00Z',
    ...overrides,
  }
}

function makeSummary(overrides: Partial<BattleSummary> = {}): BattleSummary {
  return {
    id: 1,
    status: 'FINISHED',
    viewer: 'CHALLENGER',
    winner: 'CHALLENGER',
    opponentName: '咪咪',
    opponentSpecies: 'CAT',
    rounds: 6,
    createdAt: '2026-09-17T12:00:00Z',
    ...overrides,
  }
}

describe('battleStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    fetchBattlesMock.mockResolvedValue([])
  })

  describe('好友码', () => {
    it('拉到之后存下来', async () => {
      fetchFriendCodeMock.mockResolvedValue({ friendCode: 'K7M2PQXF', length: 8 })

      const store = useBattleStore()
      await store.loadFriendCode()

      expect(store.friendCode).toBe('K7M2PQXF')
      expect(store.error).toBeNull()
    })

    it('失败时记下原因，不影响页面其它部分', async () => {
      fetchFriendCodeMock.mockRejectedValue(new ApiError('INTERNAL_ERROR', '服务器开小差了', 500))

      const store = useBattleStore()
      await store.loadFriendCode()

      expect(store.friendCode).toBe('')
      expect(store.error).toBe('服务器开小差了')
    })
  })

  describe('发起挑战', () => {
    it('成功时把战报存下来并刷新列表', async () => {
      challengeMock.mockResolvedValue(makeBattle())
      fetchBattlesMock.mockResolvedValue([makeSummary()])

      const store = useBattleStore()
      const ok = await store.challenge('K7M2PQXF')

      expect(ok).toBe(true)
      expect(store.current?.challenger.name).toBe('小蓝')
      expect(store.recent).toHaveLength(1)
      expect(challengeMock).toHaveBeenCalledWith('K7M2PQXF')
    })

    it('失败时把服务端给的原因显示出来', async () => {
      challengeMock.mockRejectedValue(new ApiError('FRIEND_CODE_NOT_FOUND', '没有找到这个好友码', 404))

      const store = useBattleStore()
      const ok = await store.challenge('ZZZZZZZZ')

      expect(ok).toBe(false)
      expect(store.error).toBe('没有找到这个好友码')
      expect(store.current).toBeNull()
    })

    it('请求进行中会挡住重复点击', async () => {
      let release!: (battle: Battle) => void
      challengeMock.mockImplementation(
        () => new Promise<Battle>((resolve) => { release = resolve }),
      )

      const store = useBattleStore()
      const first = store.challenge('K7M2PQXF')
      await Promise.resolve()
      expect(store.challenging).toBe(true)

      // 第二次不该打接口
      expect(await store.challenge('K7M2PQXF')).toBe(false)
      expect(challengeMock).toHaveBeenCalledTimes(1)

      release(makeBattle())
      await first
      expect(store.challenging).toBe(false)
    })
  })

  describe('视角', () => {
    it('挑战方视角下，mine 是挑战方、opponent 是被挑战方', async () => {
      challengeMock.mockResolvedValue(makeBattle({ viewer: 'CHALLENGER' }))

      const store = useBattleStore()
      await store.challenge('K7M2PQXF')

      expect(store.mine?.name).toBe('小蓝')
      expect(store.opponent?.name).toBe('咪咪')
      expect(store.viewerWon).toBe(true)
    })

    it('被挑战方视角下正好相反', async () => {
      // 同一场对战，对方来看：胜利方 CHALLENGER 就变成了"对手赢了"
      challengeMock.mockResolvedValue(makeBattle({ viewer: 'DEFENDER' }))

      const store = useBattleStore()
      await store.challenge('K7M2PQXF')

      expect(store.mine?.name).toBe('咪咪')
      expect(store.opponent?.name).toBe('小蓝')
      expect(store.viewerWon).toBe(false)
    })

    it('平局时 viewerWon 是 false', async () => {
      challengeMock.mockResolvedValue(makeBattle({ winner: null, outcome: 'DRAW' }))

      const store = useBattleStore()
      await store.challenge('K7M2PQXF')

      expect(store.viewerWon).toBe(false)
    })
  })

  describe('战报通知', () => {
    it('通知说的那场在列表里出现时就拉完整战报', async () => {
      fetchBattlesMock.mockResolvedValue([makeSummary({ id: 77 })])
      fetchBattleMock.mockResolvedValue(makeBattle({ id: 77 }))

      const store = useBattleStore()
      await store.onBattleFinished(77)

      expect(fetchBattleMock).toHaveBeenCalledWith(77)
      expect(store.current?.id).toBe(77)
    })

    it('通知是广播：说的那场不在我的列表里就什么都不做', async () => {
      // 通配订阅会收到别人的对战通知，不能拿它的内容去渲染
      fetchBattlesMock.mockResolvedValue([makeSummary({ id: 1 })])

      const store = useBattleStore()
      await store.onBattleFinished(999)

      expect(fetchBattleMock).not.toHaveBeenCalled()
      expect(store.current).toBeNull()
    })

    it('拉列表失败时不炸，只是没有更新', async () => {
      fetchBattlesMock.mockRejectedValue(new ApiError('INTERNAL_ERROR', '服务器开小差了', 500))

      const store = useBattleStore()

      await expect(store.onBattleFinished(1)).resolves.toBeUndefined()
      expect(fetchBattleMock).not.toHaveBeenCalled()
    })
  })

  describe('打开战报', () => {
    it('按 id 拉完整战报', async () => {
      fetchBattleMock.mockResolvedValue(makeBattle({ id: 5 }))

      const store = useBattleStore()
      await store.open(5)

      expect(store.current?.id).toBe(5)
      expect(store.loading).toBe(false)
    })

    it('失败时清空当前战报并给出原因', async () => {
      fetchBattleMock.mockRejectedValue(new ApiError('BATTLE_NOT_FOUND', '没有这场对战的记录', 404))

      const store = useBattleStore()
      await store.open(5)

      expect(store.current).toBeNull()
      expect(store.error).toBe('没有这场对战的记录')
    })
  })
})
