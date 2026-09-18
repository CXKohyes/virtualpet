import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import PetParticles from '@/components/PetParticles.vue'
import { PARTICLE_BURSTS, PARTICLE_RECIPES, particleSeeds } from '@/content/particles'

import type { ParticleBurst } from '@/content/particles'

function mountBurst(burst: ParticleBurst | null, burstId = 1) {
  return mount(PetParticles, { props: { burst, burstId } })
}

describe('粒子配方表', () => {
  it('每种爆发都有配方，且颗数、时长都是正数', () => {
    for (const burst of PARTICLE_BURSTS) {
      const recipe = PARTICLE_RECIPES[burst]

      expect(recipe.count, burst).toBeGreaterThan(0)
      expect(recipe.durationMs, burst).toBeGreaterThan(0)
      // 调色板至少一种颜色，否则取色会拿到 undefined。
      expect(recipe.palette.length, burst).toBeGreaterThan(0)
      expect(recipe.staggerMs, burst).toBeGreaterThanOrEqual(0)
      expect(recipe.size, burst).toBeGreaterThan(0)
    }
  })

  it('位移距离区间合法，且扇形角度有跨度', () => {
    for (const burst of PARTICLE_BURSTS) {
      const recipe = PARTICLE_RECIPES[burst]

      expect(recipe.minDistance, burst).toBeLessThanOrEqual(recipe.maxDistance)
      expect(recipe.minDistance, burst).toBeGreaterThan(0)
      // 跨度为零的话所有粒子会重叠成一颗。
      expect(recipe.toDeg - recipe.fromDeg, burst).toBeGreaterThan(0)
      expect(
        recipe.palette.every((tint) => tint.length > 0),
        burst,
      ).toBe(true)
    }
  })
})

describe('particleSeeds', () => {
  it('颗数与配方一致，首颗不延迟', () => {
    for (const burst of PARTICLE_BURSTS) {
      const seeds = particleSeeds(burst)

      expect(seeds, burst).toHaveLength(PARTICLE_RECIPES[burst].count)
      expect(seeds[0]?.delayMs, burst).toBe(0)
    }
  })

  it('位移落在配方给的区间内', () => {
    for (const burst of PARTICLE_BURSTS) {
      const recipe = PARTICLE_RECIPES[burst]

      for (const seed of particleSeeds(burst)) {
        const distance = Math.hypot(seed.dx, seed.dy)
        // 坐标取过整，所以允许 1px 的误差。
        expect(distance, burst).toBeGreaterThanOrEqual(recipe.minDistance - 1)
        expect(distance, burst).toBeLessThanOrEqual(recipe.maxDistance + 1)
      }
    }
  })

  it('取色只来自配方调色板', () => {
    for (const burst of PARTICLE_BURSTS) {
      const palette: readonly string[] = PARTICLE_RECIPES[burst].palette

      for (const seed of particleSeeds(burst)) {
        expect(palette, burst).toContain(seed.tint)
      }
    }
  })

  it('延迟非递减，形成依次进发', () => {
    const delays = particleSeeds('EVOLVE').map((seed) => seed.delayMs)
    const ascending = [...delays].sort((a, b) => a - b)

    expect(delays).toEqual(ascending)
    expect(Math.max(...delays)).toBeGreaterThan(0)
  })

  it('是确定性的：同一个爆发每次算出同一组参数', () => {
    for (const burst of PARTICLE_BURSTS) {
      // 用 Math.random 的话这条会挂 —— 形状飘忽正是它要防住的问题。
      expect(particleSeeds(burst), burst).toEqual(particleSeeds(burst))
    }
  })
})

describe('PetParticles 渲染', () => {
  it('burst 为 null 时什么都不渲染', () => {
    const wrapper = mountBurst(null)

    expect(wrapper.find('.pet-particles').exists()).toBe(false)
    expect(wrapper.findAll('.particle')).toHaveLength(0)
  })

  it('渲染出配方规定的颗数，并标出是哪一种爆发', () => {
    for (const burst of PARTICLE_BURSTS) {
      const wrapper = mountBurst(burst)

      expect(wrapper.get('.pet-particles').attributes('data-burst'), burst).toBe(burst)
      expect(wrapper.findAll('.particle'), burst).toHaveLength(PARTICLE_RECIPES[burst].count)
    }
  })

  it('每颗粒子都带上了位移和颜色的样式变量', () => {
    const wrapper = mountBurst('FEED')
    const first = wrapper.get('.particle')
    const style = first.attributes('style') ?? ''

    expect(style).toContain('--dx:')
    expect(style).toContain('--dy:')
    expect(style).toContain('--tint:')
    // 形状类名跟着配方走。
    expect(first.classes()).toContain(`is-${PARTICLE_RECIPES.FEED.shape}`)
  })

  it('burstId 变化时容器被整体替换，动画才会重播', async () => {
    const wrapper = mountBurst('PLAY', 1)
    const before = wrapper.get('.pet-particles').element

    expect(wrapper.get('.pet-particles').attributes('data-burst-id')).toBe('1')

    await wrapper.setProps({ burstId: 2 })

    expect(wrapper.get('.pet-particles').attributes('data-burst-id')).toBe('2')
    // 关键断言：不是改了属性，而是换了一个 DOM 节点 —— 靠 `:key` 重挂载。
    expect(wrapper.get('.pet-particles').element).not.toBe(before)
  })

  it('整层对读屏隐藏且不含可聚焦元素（PRD 4.4）', () => {
    const wrapper = mountBurst('EVOLVE')

    expect(wrapper.get('.pet-particles').attributes('aria-hidden')).toBe('true')
    expect(wrapper.findAll('button')).toHaveLength(0)
    expect(wrapper.findAll('[tabindex]')).toHaveLength(0)
  })
})
