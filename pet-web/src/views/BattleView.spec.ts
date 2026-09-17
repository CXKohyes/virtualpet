import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { challenge, fetchBattle, fetchBattles, fetchFriendCode } from '@/api/battle'
import BattleView from '@/views/BattleView.vue'

import type { Battle, BattleSummary } from '@/types/battle'

vi.mock('@/api/battle', () => ({
  challenge: vi.fn(),
  fetchBattle: vi.fn(),
  fetchBattles: vi.fn(),
  fetchFriendCode: vi.fn(),
}))

// WebSocket 在单测里连不上也不该连；订阅逻辑本身由 composable 自己的测试覆盖
vi.mock('@/composables/useBattleNotifications', () => ({
  useBattleNotifications: () => ({
    connected: { value: false },
    watch: vi.fn().mockResolvedValue(undefined),
    disconnect: vi.fn(),
  }),
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
    rounds: 2,
    seed: 12345,
    timeline: [
      {
        round: 1,
        events: [
          { actor: 'CHALLENGER', type: 'ATTACK', value: 18, crit: false, challengerHpAfter: 101, defenderHpAfter: 77 },
          { actor: 'DEFENDER', type: 'ATTACK', value: 12, crit: true, challengerHpAfter: 89, defenderHpAfter: 77 },
          { actor: 'DEFENDER', type: 'HEAL', value: 3, crit: false, challengerHpAfter: 89, defenderHpAfter: 80 },
        ],
      },
      {
        round: 2,
        events: [
          { actor: 'CHALLENGER', type: 'ATTACK', value: 80, crit: true, challengerHpAfter: 89, defenderHpAfter: 0 },
        ],
      },
    ],
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

async function mountView(): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/battle', name: 'battle', component: BattleView },
      { path: '/', name: 'home', component: { template: '<p>home</p>' } },
    ],
  })
  await router.push('/battle')
  await router.isReady()

  const wrapper = mount(BattleView, { global: { plugins: [createPinia(), router] } })
  await vi.waitFor(() => expect(wrapper.find('.code-value').exists()).toBe(true))
  return { wrapper, router }
}

describe('BattleView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    fetchFriendCodeMock.mockResolvedValue({ friendCode: 'K7M2PQXF', length: 8 })
    fetchBattlesMock.mockResolvedValue([])
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('展示我的好友码', async () => {
    const { wrapper } = await mountView()

    expect(wrapper.find('.code-value').text()).toBe('K7M2PQXF')
  })

  it('还没有对战时给出引导文案', async () => {
    const { wrapper } = await mountView()

    expect(wrapper.text()).toContain('还没有对战记录')
  })

  it('填好友码点开打 -> 调接口并展示战报', async () => {
    challengeMock.mockResolvedValue(makeBattle())
    const { wrapper } = await mountView()

    await wrapper.find('.challenge-input').setValue('k7m2pqxf')
    await wrapper.find('.challenge-form button').trigger('click')

    await vi.waitFor(() => expect(wrapper.find('.battle-report').exists()).toBe(true))
    expect(challengeMock).toHaveBeenCalledWith('k7m2pqxf')
    expect(wrapper.text()).toContain('你赢了')
  })

  it('好友码为空时不打接口', async () => {
    const { wrapper } = await mountView()

    await wrapper.find('.challenge-form button').trigger('click')

    expect(challengeMock).not.toHaveBeenCalled()
  })

  it('战报里的数字全部来自服务端，界面不自己算', async () => {
    challengeMock.mockResolvedValue(makeBattle())
    const { wrapper } = await mountView()
    await wrapper.find('.challenge-input').setValue('K7M2PQXF')
    await wrapper.find('.challenge-form button').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('.battle-report').exists()).toBe(true))

    const text = wrapper.find('.battle-report').text()
    // 服务端给的伤害、暴击、回血、最终血量都要原样出现
    expect(text).toContain('造成 18 点伤害')
    expect(text).toContain('暴击')
    expect(text).toContain('回复 3')
    expect(text).toContain('第 1 回合')
    expect(text).toContain('第 2 回合')
  })

  it('展示双方在对战里的战斗属性', async () => {
    challengeMock.mockResolvedValue(makeBattle())
    const { wrapper } = await mountView()
    await wrapper.find('.challenge-input').setValue('K7M2PQXF')
    await wrapper.find('.challenge-form button').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('.battle-report').exists()).toBe(true))

    const text = wrapper.find('.battle-report').text()
    expect(text).toContain('小蓝')
    expect(text).toContain('咪咪')
    expect(text).toContain('生命 101')
    expect(text).toContain('速 16')
  })

  it('最近对战列出对手和胜负，点击能打开', async () => {
    fetchBattlesMock.mockResolvedValue([makeSummary({ id: 9, winner: 'DEFENDER' })])
    fetchBattleMock.mockResolvedValue(makeBattle({ id: 9, viewer: 'CHALLENGER', winner: 'DEFENDER' }))

    const { wrapper } = await mountView()

    expect(wrapper.text()).toContain('对手 咪咪')
    expect(wrapper.find('.recent-result').text()).toBe('负')

    await wrapper.find('.recent-button').trigger('click')
    await vi.waitFor(() => expect(fetchBattleMock).toHaveBeenCalledWith(9))
  })

  it('挑战失败时显示服务端给的原因', async () => {
    const { ApiError } = await import('@/api/client')
    challengeMock.mockRejectedValue(new ApiError('FRIEND_CODE_NOT_FOUND', '没有找到这个好友码', 404))

    const { wrapper } = await mountView()
    await wrapper.find('.challenge-input').setValue('ZZZZZZZZ')
    await wrapper.find('.challenge-form button').trigger('click')

    await vi.waitFor(() => expect(wrapper.find('.battle-alert').exists()).toBe(true))
    expect(wrapper.find('.battle-alert').text()).toBe('没有找到这个好友码')
  })
})
