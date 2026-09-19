import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/api/client'
import { fetchGameConfig } from '@/api/gameConfig'
import { createPet } from '@/api/pet'
import OnboardingView from '@/views/OnboardingView.vue'
import { SPECIES } from '@/types/pet'

import type { GameConfig, Pet } from '@/types/pet'

vi.mock('@/api/gameConfig', () => ({ fetchGameConfig: vi.fn() }))
vi.mock('@/api/pet', () => ({
  createPet: vi.fn(),
  fetchPet: vi.fn(),
  fetchJournal: vi.fn(),
  performAction: vi.fn(),
  resetPet: vi.fn(),
}))

const fetchGameConfigMock = vi.mocked(fetchGameConfig)
const createPetMock = vi.mocked(createPet)

const GAME_CONFIG: GameConfig = {
  offlineCapHours: 12,
  maxLevel: 10,
  maxSlots: 3,
  expThresholds: [40, 90, 150, 220, 300, 390, 490, 600, 720],
  species: [
    {
      code: 'CAT',
      modifier: {
        playMoodBonus: 1.25,
        careGainBonus: 1,
        feedSatietyBonus: 1,
        hygieneDecayScale: 0.75,
        energyDecayScale: 1,
        healthRecoveryBonus: 0,
      },
    },
    {
      code: 'DOG',
      modifier: {
        playMoodBonus: 1,
        careGainBonus: 1.1,
        feedSatietyBonus: 1,
        hygieneDecayScale: 1,
        energyDecayScale: 1,
        healthRecoveryBonus: 1,
      },
    },
    {
      code: 'DRAGON',
      modifier: {
        playMoodBonus: 1,
        careGainBonus: 1,
        feedSatietyBonus: 1.15,
        hygieneDecayScale: 1,
        energyDecayScale: 0.75,
        healthRecoveryBonus: 0,
      },
    },
    {
      code: 'RABBIT',
      modifier: {
        playMoodBonus: 1.15,
        careGainBonus: 1,
        feedSatietyBonus: 1,
        hygieneDecayScale: 0.7,
        energyDecayScale: 1,
        healthRecoveryBonus: 0,
      },
    },
  ],
  actions: [],
  evolution: [],
}

const CREATED_PET: Pet = {
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
  lastSettledAt: '2026-09-17T12:00:00Z',
  cooldowns: {},
  settlement: null,
}

async function mountView(): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<p>home</p>' } },
      { path: '/onboarding', name: 'onboarding', component: OnboardingView },
    ],
  })
  await router.push('/onboarding')
  await router.isReady()

  const wrapper = mount(OnboardingView, {
    global: { plugins: [createPinia(), router] },
  })
  // 等 onMounted 里的游戏配置请求落地
  await vi.waitFor(() => expect(fetchGameConfigMock).toHaveBeenCalled())

  return { wrapper, router }
}

describe('OnboardingView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    window.localStorage.clear()
    fetchGameConfigMock.mockResolvedValue(GAME_CONFIG)
  })

  it('展示全部候选宠物', async () => {
    const { wrapper } = await mountView()

    const cards = wrapper.findAll('.species-card')
    // 从 SPECIES 派生：写死 3 的话，加物种时这条会失败 —— 那还算好的；
    // 更糟的是它可能被顺手改成 4，然后永远停在 4 上不跟着长。
    expect(cards).toHaveLength(SPECIES.length)
    expect(wrapper.text()).toContain('猫')
    expect(wrapper.text()).toContain('狗')
    expect(wrapper.text()).toContain('像素龙')
    expect(wrapper.text()).toContain('兔子')
  })

  it('特性标签来自游戏配置，不是写死的文案', async () => {
    const { wrapper } = await mountView()

    // 猫：玩耍心情 +25%、清洁衰减 -25%
    expect(wrapper.text()).toContain('玩耍心情 +25%')
    expect(wrapper.text()).toContain('清洁衰减 -25%')
    // 狗：正向照护 +10%、健康恢复 +1/小时
    expect(wrapper.text()).toContain('正向照护 +10%')
    expect(wrapper.text()).toContain('健康恢复 +1/小时')
    // 龙：喂食饱食 +15%、精力衰减 -25%
    expect(wrapper.text()).toContain('喂食饱食 +15%')
    expect(wrapper.text()).toContain('精力衰减 -25%')
    // 兔子：玩耍心情 +15%、清洁衰减 -30%
    expect(wrapper.text()).toContain('玩耍心情 +15%')
    expect(wrapper.text()).toContain('清洁衰减 -30%')
  })

  it('游戏配置拿不到时也能正常渲染，只是不显示特性标签', async () => {
    fetchGameConfigMock.mockRejectedValue(new ApiError('NETWORK_ERROR', '连不上服务器', 0))

    const { wrapper } = await mountView()

    expect(wrapper.findAll('.species-card')).toHaveLength(SPECIES.length)
    expect(wrapper.findAll('.species-trait')).toHaveLength(0)
  })

  it('名字为空时不提交，并给出提示', async () => {
    const { wrapper } = await mountView()

    await wrapper.find('button').trigger('click')

    expect(wrapper.text()).toContain('先给它起个名字吧')
    expect(createPetMock).not.toHaveBeenCalled()
  })

  it('名字超过 8 个字时拒绝提交', async () => {
    const { wrapper } = await mountView()

    await wrapper.find('.name-input').setValue('一二三四五六七八九')
    await wrapper.find('button').trigger('click')

    expect(wrapper.text()).toContain('名字最多 8 个字')
    expect(createPetMock).not.toHaveBeenCalled()
  })

  it('创建成功后跳到主界面', async () => {
    createPetMock.mockResolvedValue(CREATED_PET)

    const { wrapper, router } = await mountView()
    await wrapper.find('.name-input').setValue('咪咪')
    await wrapper.find('button').trigger('click')

    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('home'))
    expect(createPetMock).toHaveBeenCalledWith('CAT', '咪咪')
  })

  it('创建失败时展示后端给的原因', async () => {
    createPetMock.mockRejectedValue(new ApiError('PET_SLOTS_FULL', '宠物已经满了，先送走一只再领养', 409))

    const { wrapper, router } = await mountView()
    await wrapper.find('.name-input').setValue('咪咪')
    await wrapper.find('button').trigger('click')

    await vi.waitFor(() => expect(wrapper.text()).toContain('宠物已经满了'))
    expect(router.currentRoute.value.name).toBe('onboarding')
  })

  it('切换物种后按选中的物种创建', async () => {
    createPetMock.mockResolvedValue({ ...CREATED_PET, species: 'DRAGON', name: '小焰' })

    const { wrapper } = await mountView()
    // 按 value 找，不按下标 —— 下标是位置耦合的，往 SPECIES_ORDER 里插一个
    // 物种就会让这条测试去点另一只，而且照样"通过"
    const dragon = wrapper
      .findAll('.species-radio')
      .find((radio) => (radio.element as HTMLInputElement).value === 'DRAGON')
    await dragon?.setValue()
    await wrapper.find('.name-input').setValue('小焰')
    await wrapper.find('button').trigger('click')

    await vi.waitFor(() => expect(createPetMock).toHaveBeenCalledWith('DRAGON', '小焰'))
  })
})
