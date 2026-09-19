import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import PetRoster from '@/components/PetRoster.vue'
import { STATUS_LABELS } from '@/content/messages'

import type { Pet } from '@/types/pet'

const NOW = new Date('2026-09-19T04:00:00Z')

function makePet(overrides: Partial<Pet> = {}): Pet {
  return {
    id: 1,
    slot: 0,
    active: false,
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

function mountRoster(pets: Pet[], maxSlots = 3, busy = false) {
  return mount(PetRoster, { props: { pets, maxSlots, busy } })
}

function cards(wrapper: ReturnType<typeof mountRoster>) {
  return wrapper.findAll('.roster-card')
}

describe('PetRoster', () => {
  it('列出全部宠物，并标出当前那只', () => {
    const wrapper = mountRoster([
      makePet({ id: 1, slot: 0, name: '咪咪' }),
      makePet({ id: 2, slot: 1, name: '旺财', active: true }),
    ])

    const all = cards(wrapper)
    expect(all).toHaveLength(3) // 两只 + 「再养一只」
    expect(all[0].text()).toContain('咪咪')
    expect(all[1].text()).toContain('旺财')
    expect(all[1].text()).toContain('当前')
    expect(all[0].text()).not.toContain('当前')
  })

  it('点非当前的那只发出 select，点当前那只不响应', async () => {
    const wrapper = mountRoster([
      makePet({ id: 1, slot: 0, name: '咪咪' }),
      makePet({ id: 2, slot: 1, name: '旺财', active: true }),
    ])

    const all = cards(wrapper)
    // 当前那只是禁用的：点它没有任何意义，也会白跑一次结算
    expect(all[1].attributes('disabled')).toBeDefined()

    await all[0].trigger('click')
    expect(wrapper.emitted('select')).toEqual([[1]])
  })

  it('状态不正常的宠物加 is-alert，并显示状态与最缺的那一项', () => {
    const wrapper = mountRoster([
      makePet({ id: 1, status: 'HUNGRY', satiety: 12, mood: 70, hygiene: 70, energy: 70 }),
    ])

    const card = cards(wrapper)[0]
    expect(card.classes()).toContain('is-alert')
    expect(card.text()).toContain(STATUS_LABELS.HUNGRY)
    expect(card.text()).toContain('饱食 12')
  })

  it('睡觉不算需要照顾，不标警告', () => {
    const wrapper = mountRoster([makePet({ status: 'SLEEPING', energy: 10 })])

    const card = cards(wrapper)[0]
    expect(card.classes()).not.toContain('is-alert')
  })

  it('最缺的那一项取五者最小，且有多种分布时都算对', () => {
    const wrapper = mountRoster([
      // 精力最低
      makePet({ id: 1, status: 'TIRED', satiety: 90, mood: 90, hygiene: 90, energy: 7, health: 90 }),
      // 健康最低
      makePet({ id: 2, status: 'SICK', satiety: 60, mood: 60, hygiene: 60, energy: 60, health: 22 }),
    ])

    const all = cards(wrapper)
    expect(all[0].text()).toContain('精力 7')
    expect(all[1].text()).toContain('健康 22')
  })

  it('槽位没满时才有「再养一只」', () => {
    expect(cards(mountRoster([makePet()], 3))).toHaveLength(2)

    const full = mountRoster(
      [makePet({ id: 1 }), makePet({ id: 2, slot: 1 }), makePet({ id: 3, slot: 2 })],
      3,
    )
    expect(cards(full)).toHaveLength(3)
    expect(full.text()).not.toContain('再养一只')
  })

  it('配置拿不到（maxSlots 为 0）时不显示「再养一只」，不让玩家点了才发现不行', () => {
    const wrapper = mountRoster([makePet()], 0)
    expect(cards(wrapper)).toHaveLength(1)
    expect(wrapper.text()).not.toContain('再养一只')
  })

  it('「再养一只」发出 add', async () => {
    const wrapper = mountRoster([makePet()])
    const add = cards(wrapper).find((card) => card.text().includes('再养一只'))

    await add?.trigger('click')
    expect(wrapper.emitted('add')).toHaveLength(1)
  })

  it('busy 时整排按钮都禁用，避免连点', () => {
    const wrapper = mountRoster([makePet()], 3, true)
    for (const card of cards(wrapper)) {
      expect(card.attributes('disabled')).toBeDefined()
    }
  })

  it('没有宠物时只剩「再养一只」，不炸', () => {
    const wrapper = mountRoster([])
    expect(cards(wrapper)).toHaveLength(1)
    expect(wrapper.text()).toContain('0/3')
  })
})
