import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/api/client'
import { fetchGameConfig } from '@/api/gameConfig'
import { fetchPet, performAction } from '@/api/pet'
import HomeView from '@/views/HomeView.vue'

import type { GameConfig, Pet } from '@/types/pet'

vi.mock('@/api/gameConfig', () => ({ fetchGameConfig: vi.fn() }))
vi.mock('@/api/pet', () => ({
  createPet: vi.fn(),
  fetchPet: vi.fn(),
  performAction: vi.fn(),
  resetPet: vi.fn(),
}))

const fetchPetMock = vi.mocked(fetchPet)
const performActionMock = vi.mocked(performAction)
const fetchGameConfigMock = vi.mocked(fetchGameConfig)

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
    level: 3,
    exp: 120,
    evolutionStage: 0,
    sleepingSince: null,
    lastSettledAt: NOW.toISOString(),
    cooldowns: {},
    ...overrides,
  }
}

const GAME_CONFIG: GameConfig = {
  offlineCapHours: 12,
  maxLevel: 10,
  expThresholds: [40],
  species: [],
  actions: [
    {
      code: 'FEED',
      exp: 6,
      cooldownSeconds: 60,
      satiety: 30,
      mood: 3,
      hygiene: -2,
      energy: 0,
      requirement: '饱食低于 95',
    },
  ],
  evolution: [],
}

async function mountView(): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: HomeView },
      { path: '/onboarding', name: 'onboarding', component: { template: '<p>onboarding</p>' } },
    ],
  })
  await router.push('/')
  await router.isReady()

  const wrapper = mount(HomeView, {
    global: { plugins: [createPinia(), router] },
  })
  await vi.waitFor(() => expect(fetchPetMock).toHaveBeenCalled())

  return { wrapper, router }
}

/** 操作区里的四个按钮。 */
function actionButtons(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('.action-dock button')
}

describe('HomeView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    window.localStorage.clear()
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
    fetchGameConfigMock.mockResolvedValue(GAME_CONFIG)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('展示宠物名字、等级和状态', async () => {
    fetchPetMock.mockResolvedValue(makePet())
    const { wrapper } = await mountView()

    expect(wrapper.text()).toContain('咪咪')
    expect(wrapper.text()).toContain('Lv.3')
  })

  it('渲染五项状态条和四个操作按钮', async () => {
    fetchPetMock.mockResolvedValue(makePet())
    const { wrapper } = await mountView()

    expect(wrapper.findAll('.status-row')).toHaveLength(5)
    expect(actionButtons(wrapper)).toHaveLength(4)
    expect(wrapper.text()).toContain('饱食')
    expect(wrapper.text()).toContain('健康')
    expect(wrapper.text()).toContain('喂食')
    expect(wrapper.text()).toContain('睡觉')
  })

  it('低值状态条带文字警告，不只靠颜色区分', async () => {
    fetchPetMock.mockResolvedValue(makePet({ satiety: 10 }))
    const { wrapper } = await mountView()

    const lowRow = wrapper.findAll('.status-row').find((row) => row.classes().includes('is-low'))
    expect(lowRow?.text()).toContain('低')
  })

  it('没有宠物时跳回领养页', async () => {
    fetchPetMock.mockRejectedValue(new ApiError('PET_NOT_FOUND', '还没有领养宠物', 404))
    const { router } = await mountView()

    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('onboarding'))
  })

  it('点击操作按钮会调用 store 执行对应操作', async () => {
    fetchPetMock.mockResolvedValue(makePet())
    performActionMock.mockResolvedValue({
      pet: makePet({ satiety: 100, exp: 126 }),
      deltas: { satiety: 20, mood: 3, hygiene: -2, energy: 0, health: 0 },
      xpGained: 6,
      levelUp: false,
      evolved: false,
      messageKey: 'FEED_OK',
      cooldownUntil: '2026-09-17T12:01:00Z',
    })

    const { wrapper } = await mountView()
    await actionButtons(wrapper)[0]?.trigger('click')

    await vi.waitFor(() => expect(performActionMock).toHaveBeenCalled())
    expect(performActionMock.mock.calls[0]?.[0]).toBe('FEED')
    // 服务端返回的状态覆盖了本地：饱食条跟着涨到 100
    await vi.waitFor(() => expect(wrapper.text()).toContain('100'))
  })

  it('失败时展示后端给的原因，不静默失败', async () => {
    fetchPetMock.mockResolvedValue(makePet())
    performActionMock.mockRejectedValue(new ApiError('ACTION_NO_EFFECT', '它现在不饿', 409))

    const { wrapper } = await mountView()
    await actionButtons(wrapper)[0]?.trigger('click')

    await vi.waitFor(() => expect(wrapper.text()).toContain('它现在不饿'))
  })

  it('冷却中的按钮被禁用并显示剩余秒数', async () => {
    fetchPetMock.mockResolvedValue(
      makePet({ cooldowns: { FEED: '2026-09-17T12:00:45Z' } }),
    )
    const { wrapper } = await mountView()
    await vi.waitFor(() => expect(actionButtons(wrapper)).toHaveLength(4))

    const feedButton = actionButtons(wrapper)[0]
    expect(feedButton?.attributes('disabled')).toBeDefined()
    expect(feedButton?.text()).toContain('45s')
  })

  it('睡觉时第四个按钮变成唤醒', async () => {
    fetchPetMock.mockResolvedValue(
      makePet({ status: 'SLEEPING', sleepingSince: NOW.toISOString() }),
    )
    const { wrapper } = await mountView()

    const labels = actionButtons(wrapper).map((button) => button.text())
    expect(labels[3]).toContain('唤醒')
    expect(labels.join()).not.toContain('睡觉')
  })

  it('操作成功后气泡显示反馈短句并写入日志', async () => {
    fetchPetMock.mockResolvedValue(makePet())
    performActionMock.mockResolvedValue({
      pet: makePet({ exp: 126 }),
      deltas: { satiety: 20, mood: 3, hygiene: -2, energy: 0, health: 0 },
      xpGained: 6,
      levelUp: false,
      evolved: false,
      messageKey: 'FEED_OK',
      cooldownUntil: '2026-09-17T12:01:00Z',
    })

    const { wrapper } = await mountView()
    await actionButtons(wrapper)[0]?.trigger('click')

    await vi.waitFor(() => expect(wrapper.text()).toContain('吃得真香！'))
    expect(wrapper.findAll('.journal-item')).toHaveLength(1)
  })

  it('打开设置弹窗', async () => {
    fetchPetMock.mockResolvedValue(makePet())
    const { wrapper } = await mountView()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    await wrapper.find('.home-header-right button').trigger('click')

    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('重新领养')
  })

  it('操作按钮的悬停提示来自游戏配置', async () => {
    fetchPetMock.mockResolvedValue(makePet())
    const { wrapper } = await mountView()

    await vi.waitFor(() => expect(fetchGameConfigMock).toHaveBeenCalled())
    await vi.waitFor(() =>
      expect(actionButtons(wrapper)[0]?.attributes('title')).toBe('饱食低于 95'),
    )
  })

  it('把游戏配置里的使用条件挂到按钮上，但按钮本身不据此禁用', async () => {
    // 服务端会拒（饱食 95 不能再喂），但前端不去判断这件事，
    // 只把条件文字当提示，点下去由服务端给原因
    fetchPetMock.mockResolvedValue(makePet({ satiety: 99 }))
    const { wrapper } = await mountView()
    await vi.waitFor(() => expect(fetchGameConfigMock).toHaveBeenCalled())

    const feedButton = actionButtons(wrapper)[0]
    expect(feedButton?.attributes('disabled')).toBeUndefined()
    expect(feedButton?.attributes('title')).toBe('饱食低于 95')
  })
})
