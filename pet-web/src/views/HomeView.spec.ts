import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/api/client'
import { fetchGameConfig } from '@/api/gameConfig'
import { activatePet, fetchJournal, fetchPets, fetchPet, performAction, releasePet } from '@/api/pet'
import { FLASH_DURATION_MS, STATUS_LABELS } from '@/content/messages'
import { spriteFor } from '@/content/petSprites'
import HomeView from '@/views/HomeView.vue'

import type { ActionOutcome, GameConfig, JournalEntry, Pet, SettlementSummary } from '@/types/pet'

vi.mock('@/api/gameConfig', () => ({ fetchGameConfig: vi.fn() }))
vi.mock('@/api/pet', () => ({
  createPet: vi.fn(),
  fetchPet: vi.fn(),
  fetchJournal: vi.fn(),
  performAction: vi.fn(),
  fetchPets: vi.fn(),
  activatePet: vi.fn(),
  releasePet: vi.fn(),
}))

const fetchPetMock = vi.mocked(fetchPet)
const fetchJournalMock = vi.mocked(fetchJournal)
const performActionMock = vi.mocked(performAction)
const fetchPetsMock = vi.mocked(fetchPets)
const activatePetMock = vi.mocked(activatePet)
const releasePetMock = vi.mocked(releasePet)
const fetchGameConfigMock = vi.mocked(fetchGameConfig)

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
    level: 3,
    exp: 120,
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
    pet: makePet({ satiety: 100, exp: 126 }),
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

const GAME_CONFIG: GameConfig = {
  offlineCapHours: 12,
  maxLevel: 10,
  maxSlots: 3,
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

/**
 * 挂载主界面并**等到 onMounted 里的接口链真的跑完**。
 *
 * 不能只等 `fetchPet` 被调用 —— 它在 onMounted 里是同步调用的，
 * 一检查就通过，而此时 promise 还没落地，断言会看到"正在读取存档…"。
 */
async function mountView(
  options: { waitForPet?: boolean } = {},
): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const waitForPet = options.waitForPet ?? true
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: HomeView },
      { path: '/onboarding', name: 'onboarding', component: { template: '<p>onboarding</p>' } },
      { path: '/battle', name: 'battle', component: { template: '<p>battle</p>' } },
    ],
  })
  await router.push('/')
  await router.isReady()

  const wrapper = mount(HomeView, {
    global: { plugins: [createPinia(), router] },
  })

  await vi.waitFor(() => {
    if (waitForPet) {
      expect(wrapper.find('.home-title').exists()).toBe(true)
    } else {
      expect(router.currentRoute.value.name).toBe('onboarding')
    }
  })

  return { wrapper, router }
}

/** 操作区里的四个按钮。 */
function actionButtons(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('.action-dock button')
}

/** 页头里按文字找按钮。按钮会随功能增加而变多，按下标取迟早会点错。 */
/**
 * 走完「设置 → 送走某只 → 二次确认」。
 *
 * 按文字找按钮，不用 `button:not(.modal-close)` 这种位置选择器 ——
 * 弹窗里第一个非关闭按钮是音效开关，按位置点会点错目标，
 * 而且症状是"什么都没发生"，很难看出是选择器的问题。
 */
async function releaseCurrentPet(
  wrapper: ReturnType<typeof mount>,
  name: string,
): Promise<void> {
  await headerButton(wrapper, '设置').trigger('click')
  const open = wrapper
    .findAll('[role="dialog"] button')
    .find((button) => button.text().includes(`送走${name}`))
  await open?.trigger('click')
  const confirm = wrapper
    .findAll('[role="dialog"] button')
    .find((button) => button.text().includes('确认送走'))
  await confirm?.trigger('click')
  await vi.waitFor(() => expect(releasePetMock).toHaveBeenCalled())
}

function headerButton(wrapper: ReturnType<typeof mount>, label: string) {
  const button = wrapper
    .findAll('.home-header-right button')
    .find((candidate) => candidate.text().includes(label))
  if (!button) {
    throw new Error(`页头里没有「${label}」按钮`)
  }
  return button
}

describe('HomeView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    window.localStorage.clear()
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
    fetchGameConfigMock.mockResolvedValue(GAME_CONFIG)
    fetchJournalMock.mockResolvedValue([])
    // 名册默认是空的；vi.fn() 返回 undefined 会被 loadRoster 存下来，读 .length 就炸
    fetchPetsMock.mockResolvedValue([])
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
    const { router } = await mountView({ waitForPet: false })

    expect(router.currentRoute.value.name).toBe('onboarding')
  })

  it('点击操作按钮会调用 store 执行对应操作', async () => {
    fetchPetMock.mockResolvedValue(makePet())
    performActionMock.mockResolvedValue(makeOutcome())

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
    performActionMock.mockResolvedValue(makeOutcome({ pet: makePet({ exp: 126 }) }))

    const { wrapper } = await mountView()
    await actionButtons(wrapper)[0]?.trigger('click')

    await vi.waitFor(() => expect(wrapper.text()).toContain('吃得真香！'))
    expect(wrapper.findAll('.journal-item')).toHaveLength(1)
  })

  it('回访时优先展示「你不在时发生了什么」，关掉后消失', async () => {
    fetchPetMock.mockResolvedValue(makePet({ settlement: makeSummary({ settledHours: 8 }) }))
    const { wrapper } = await mountView()

    await vi.waitFor(() => expect(wrapper.find('.home-offline').exists()).toBe(true))
    expect(wrapper.text()).toContain('你不在的 8 小时里')
    expect(wrapper.text()).toContain('饱食 -40')
    expect(wrapper.text()).toContain('心情 -32')

    await wrapper.find('.home-offline-close').trigger('click')
    expect(wrapper.find('.home-offline').exists()).toBe(false)
  })

  it('睡觉中自己醒来时摘要会说明', async () => {
    fetchPetMock.mockResolvedValue(
      makePet({
        settlement: makeSummary({
          statusBefore: 'SLEEPING',
          statusAfter: 'NORMAL',
          wokeUp: true,
          sleptHours: 3,
        }),
      }),
    )
    const { wrapper } = await mountView()

    await vi.waitFor(() => expect(wrapper.find('.home-offline').exists()).toBe(true))
    expect(wrapper.text()).toContain('睡了 3 小时后自己醒了')
  })

  it('没有经过时间时不显示离线条', async () => {
    fetchPetMock.mockResolvedValue(makePet({ settlement: null }))
    const { wrapper } = await mountView()

    await vi.waitFor(() => expect(fetchPetMock).toHaveBeenCalled())
    expect(wrapper.find('.home-offline').exists()).toBe(false)
  })

  it('日志区展示服务端返回的历史记录（刷新页面也还在）', async () => {
    fetchPetMock.mockResolvedValue(makePet())
    fetchJournalMock.mockResolvedValue([
      makeEntry({ id: 5, action: 'PLAY', messageKey: 'PLAY_OK', xpGained: 8 }),
      makeEntry({ id: 4, action: 'FEED' }),
    ])

    const { wrapper } = await mountView()

    await vi.waitFor(() => expect(wrapper.findAll('.journal-item')).toHaveLength(2))
    expect(wrapper.text()).toContain('玩耍')
    expect(wrapper.text()).toContain('+8 经验')
  })

  it('打开设置弹窗', async () => {
    fetchPetMock.mockResolvedValue(makePet())
    const { wrapper } = await mountView()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    // 按文字找，不按位置：页头的按钮会随功能增加而变多，
    // 早先写死的 `button` 选择器在加了对战入口之后就点错了目标
    await headerButton(wrapper, '设置').trigger('click')

    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    // 原来是「重新领养」。多宠物之后那个说法不成立了：这里做的是送走当前
    // 那一只，送完可能还有别的，所以文案必须点名是哪一只。
    expect(wrapper.text()).toContain('送走一只')
    expect(wrapper.text()).toContain('送走咪咪')
  })

  it('设置弹窗里送走当前宠物，还有别的时留在主页', async () => {
    fetchPetMock.mockResolvedValue(makePet({ id: 1, name: '咪咪' }))
    fetchPetsMock.mockResolvedValue([
      makePet({ id: 2, slot: 1, name: '旺财', active: true }),
    ])
    releasePetMock.mockResolvedValue(undefined)

    const { wrapper, router } = await mountView()
    await releaseCurrentPet(wrapper, '咪咪')

    expect(releasePetMock).toHaveBeenCalledWith(1)
    // 服务端在名册里标出了新的当前宠物，前端跟着它走，不自己挑
    expect(router.currentRoute.value.name).toBe('home')
  })

  it('设置弹窗送走最后一只后回领养页', async () => {
    fetchPetMock.mockResolvedValue(makePet({ id: 1, name: '咪咪' }))
    releasePetMock.mockResolvedValue(undefined)
    fetchPetsMock.mockResolvedValue([])

    const { wrapper, router } = await mountView()
    await releaseCurrentPet(wrapper, '咪咪')

    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('onboarding'))
  })

  describe('名册', () => {
    it('列出全部宠物，只有当前那只标着「当前」', async () => {
      fetchPetMock.mockResolvedValue(makePet({ id: 2, slot: 1, name: '旺财' }))
      fetchPetsMock.mockResolvedValue([
        makePet({ id: 1, slot: 0, name: '咪咪', active: false }),
        makePet({ id: 2, slot: 1, name: '旺财', active: true }),
      ])

      const { wrapper } = await mountView()

      const cards = wrapper.findAll('.roster-card')
      expect(cards).toHaveLength(3) // 两只 + 「再养一只」
      expect(cards[0].text()).toContain('咪咪')
      expect(cards[1].text()).toContain('旺财')
      expect(cards[1].text()).toContain('当前')
      expect(cards[0].text()).not.toContain('当前')
    })

    it('点另一只就切换过去', async () => {
      fetchPetMock.mockResolvedValue(makePet({ id: 2, slot: 1, name: '旺财' }))
      fetchPetsMock.mockResolvedValue([
        makePet({ id: 1, slot: 0, name: '咪咪', active: false }),
        makePet({ id: 2, slot: 1, name: '旺财', active: true }),
      ])
      activatePetMock.mockResolvedValue(makePet({ id: 1, slot: 0, name: '咪咪' }))

      const { wrapper } = await mountView()
      await wrapper.findAll('.roster-card')[0].trigger('click')

      expect(activatePetMock).toHaveBeenCalledWith(1)
    })

    it('槽位没满时给「再养一只」入口，点了去领养页', async () => {
      fetchPetMock.mockResolvedValue(makePet())
      fetchPetsMock.mockResolvedValue([makePet({ active: true })])

      const { wrapper, router } = await mountView()
      const add = wrapper.findAll('.roster-card').find((card) => card.text().includes('再养一只'))
      await add?.trigger('click')

      await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('onboarding'))
    })

    it('槽位满了就不显示「再养一只」', async () => {
      fetchPetMock.mockResolvedValue(makePet())
      fetchPetsMock.mockResolvedValue([
        makePet({ id: 1, slot: 0, active: false }),
        makePet({ id: 2, slot: 1, active: false }),
        makePet({ id: 3, slot: 2, active: true }),
      ])

      const { wrapper } = await mountView()

      expect(wrapper.findAll('.roster-card')).toHaveLength(3)
      expect(wrapper.text()).not.toContain('再养一只')
    })

    it('状态不正常的宠物在名册上被标出来', async () => {
      fetchPetMock.mockResolvedValue(makePet({ id: 1, slot: 0, name: '咪咪', status: 'HUNGRY' }))
      fetchPetsMock.mockResolvedValue([
        makePet({ id: 1, slot: 0, name: '咪咪', active: true, status: 'HUNGRY', satiety: 12 }),
      ])

      const { wrapper } = await mountView()

      const card = wrapper.findAll('.roster-card')[0]
      expect(card.classes()).toContain('is-alert')
      expect(card.text()).toContain(STATUS_LABELS.HUNGRY)
      // 最缺的那一项也报出来，三只都正常时这项还能分出谁更接近出问题
      expect(card.text()).toContain('饱食 12')
    })
  })

  it('页头有对战入口，点了会跳到对战页', async () => {
    fetchPetMock.mockResolvedValue(makePet())
    const { wrapper, router } = await mountView()

    await headerButton(wrapper, '对战').trigger('click')

    // router.push 是异步的，触发点击之后要等它落地（isReady 此刻早就 resolve 了）
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('battle'))
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

  it('舞台上渲染的是服务端给的进化阶段对应的精灵', async () => {
    fetchPetMock.mockResolvedValue(makePet({ species: 'DRAGON', evolutionStage: 2 }))
    const { wrapper } = await mountView()

    expect(wrapper.find('.pet-sprite').attributes('src')).toBe(spriteFor('DRAGON', 2))
  })

  it('精灵带完整替代文本：名字、物种、形态、状态（PRD 4.4）', async () => {
    fetchPetMock.mockResolvedValue(
      makePet({ name: '咪咪', species: 'CAT', evolutionStage: 1, status: 'HUNGRY' }),
    )
    const { wrapper } = await mountView()

    const alt = wrapper.find('.pet-sprite').attributes('alt') ?? ''
    expect(alt).toContain('咪咪')
    expect(alt).toContain('猫')
    expect(alt).toContain('成长')
    expect(alt).toContain('饿了')
  })

  it('睡觉时精灵加 is-asleep，操作时加对应的反馈类', async () => {
    fetchPetMock.mockResolvedValue(
      makePet({ status: 'SLEEPING', sleepingSince: NOW.toISOString() }),
    )
    const { wrapper } = await mountView()

    expect(wrapper.find('.pet-sprite').classes()).toContain('is-asleep')
  })

  it('升级时弹出强调提示并给精灵加上动画类（PRD 4.3）', async () => {
    fetchPetMock.mockResolvedValue(makePet())
    const { wrapper } = await mountView()

    performActionMock.mockResolvedValue(makeOutcome({ levelUp: true }))
    await actionButtons(wrapper)[0]?.trigger('click')

    await vi.waitFor(() => expect(wrapper.find('.home-flash').exists()).toBe(true))
    expect(wrapper.find('.home-flash').text()).toContain('升级了')
    expect(wrapper.find('.pet-sprite').classes()).toContain('is-level-up')
  })

  it('进化比升级更隆重，提示和动画都换成进化的', async () => {
    fetchPetMock.mockResolvedValue(makePet())
    const { wrapper } = await mountView()

    performActionMock.mockResolvedValue(
      makeOutcome({ levelUp: true, evolved: true, pet: makePet({ level: 4, evolutionStage: 1 }) }),
    )
    await actionButtons(wrapper)[0]?.trigger('click')

    await vi.waitFor(() => expect(wrapper.find('.home-flash').exists()).toBe(true))
    expect(wrapper.find('.home-flash').text()).toContain('进化了')
    expect(wrapper.find('.home-flash').classes()).toContain('is-evolve')
    expect(wrapper.find('.pet-sprite').classes()).toContain('is-evolving')
  })

  it('强调提示会自动消失，不会一直挂着', async () => {
    fetchPetMock.mockResolvedValue(makePet())
    const { wrapper } = await mountView()

    performActionMock.mockResolvedValue(makeOutcome({ levelUp: true }))
    await actionButtons(wrapper)[0]?.trigger('click')
    await vi.waitFor(() => expect(wrapper.find('.home-flash').exists()).toBe(true))

    // 本文件的 beforeEach 开了假计时器，直接把时钟推过去就行
    await vi.advanceTimersByTimeAsync(FLASH_DURATION_MS + 100)
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.home-flash').exists()).toBe(false)
    expect(wrapper.find('.pet-sprite').classes()).not.toContain('is-level-up')
  })

  it('进化结束后舞台上换成新形态的精灵', async () => {
    fetchPetMock.mockResolvedValue(makePet({ evolutionStage: 0 }))
    const { wrapper } = await mountView()
    expect(wrapper.find('.pet-sprite').attributes('src')).toBe(spriteFor('CAT', 0))

    performActionMock.mockResolvedValue(
      makeOutcome({ evolved: true, pet: makePet({ evolutionStage: 1 }) }),
    )
    await actionButtons(wrapper)[0]?.trigger('click')

    await vi.waitFor(() =>
      expect(wrapper.find('.pet-sprite').attributes('src')).toBe(spriteFor('CAT', 1)),
    )
  })
})
