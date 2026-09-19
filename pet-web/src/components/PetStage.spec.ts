import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import PetStage from '@/components/PetStage.vue'
import { PARTICLE_RECIPES } from '@/content/particles'
import { spriteFor } from '@/content/petSprites'

import type { Pet } from '@/types/pet'

const NOW = new Date('2026-09-18T04:00:00Z')

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

function mountStage(overrides: Partial<Pet> = {}, acting: string | null = null) {
  return mount(PetStage, {
    props: { pet: makePet(overrides), acting },
  })
}

/** 粒子层的 `data-burst`；没有粒子时返回 null。 */
function burstOf(wrapper: ReturnType<typeof mountStage>): string | null {
  const layer = wrapper.find('.pet-particles')
  return layer.exists() ? (layer.attributes('data-burst') ?? null) : null
}

describe('PetStage', () => {
  it('刚挂载时不放粒子', () => {
    expect(burstOf(mountStage())).toBeNull()
  })

  it('照护动作触发同名粒子', async () => {
    const wrapper = mountStage()

    for (const action of ['FEED', 'PLAY', 'CLEAN'] as const) {
      await wrapper.setProps({ acting: null })
      await wrapper.setProps({ acting: action })

      expect(burstOf(wrapper), action).toBe(action)
      expect(wrapper.findAll('.particle'), action).toHaveLength(PARTICLE_RECIPES[action].count)
    }
  })

  it('睡觉和唤醒不触发粒子：它们只是状态切换', async () => {
    const wrapper = mountStage()

    await wrapper.setProps({ acting: 'SLEEP' })
    expect(burstOf(wrapper)).toBeNull()

    await wrapper.setProps({ acting: null })
    await wrapper.setProps({ acting: 'WAKE' })
    expect(burstOf(wrapper)).toBeNull()
  })

  it('升级和进化用 flash 触发，且后发生的压过先前的', async () => {
    const wrapper = mountStage()

    await wrapper.setProps({ acting: 'FEED' })
    expect(burstOf(wrapper)).toBe('FEED')

    await wrapper.setProps({ flash: 'LEVEL_UP' })
    expect(burstOf(wrapper)).toBe('LEVEL_UP')

    await wrapper.setProps({ flash: 'EVOLVE' })
    expect(burstOf(wrapper)).toBe('EVOLVE')
  })

  it('连做两次同一操作会重放粒子（burstId 递增）', async () => {
    const wrapper = mountStage()

    await wrapper.setProps({ acting: 'FEED' })
    const first = wrapper.get('.pet-particles').attributes('data-burst-id')

    await wrapper.setProps({ acting: null })
    await wrapper.setProps({ acting: 'FEED' })
    const second = wrapper.get('.pet-particles').attributes('data-burst-id')

    expect(burstOf(wrapper)).toBe('FEED')
    // 值没变但序号变了，容器才会重挂载、动画才会从头播。
    expect(second).not.toBe(first)
  })

  it('刚生病的那一刻触发一次，一直病着不反复触发', async () => {
    const wrapper = mountStage()

    await wrapper.setProps({ pet: makePet({ status: 'SICK' }) })
    expect(burstOf(wrapper)).toBe('SICK')

    const idAfterOnset = wrapper.get('.pet-particles').attributes('data-burst-id')

    // 重新给一份仍处于生病状态、但属性值不同的数据，模拟后续刷新。
    await wrapper.setProps({ pet: makePet({ status: 'SICK', satiety: 40 }) })
    expect(wrapper.get('.pet-particles').attributes('data-burst-id')).toBe(idAfterOnset)
  })

  it('保留既有的精灵渲染与替代文本（PRD 4.4）', () => {
    const wrapper = mountStage({ species: 'DOG', evolutionStage: 1, status: 'HUNGRY' })
    const sprite = wrapper.get('.pet-sprite')

    expect(sprite.attributes('src')).toBe(spriteFor('DOG', 1))
    expect(sprite.attributes('alt')).toContain('咪咪')
    expect(sprite.attributes('alt')).toContain('成长')
    expect(sprite.attributes('alt')).toContain('饿了')
    // 地面阴影还在，粒子层不能把它挤掉。
    expect(wrapper.find('.stage-ground').exists()).toBe(true)
  })
})
