import { describe, expect, it } from 'vitest'

import { PET_SCENE_LINES, PET_STATUS_LINES, createSpeechDirector } from '@/content/petLines'

import type { SpeechScene } from '@/content/petLines'
import type { PetStatus, Species } from '@/types/pet'

const SPECIES: Species[] = ['CAT', 'DOG', 'DRAGON']

const STATUSES: PetStatus[] = ['NORMAL', 'HUNGRY', 'DIRTY', 'TIRED', 'SAD', 'SICK', 'SLEEPING']

const SCENES: SpeechScene[] = [
  'FEED',
  'PLAY',
  'CLEAN',
  'SLEEP',
  'WAKE',
  'LEVEL_UP',
  'EVOLVE',
  'SICK',
  'RETURN',
]

/** 固定序列的假随机源：失败可以复现，不受 Math.random 影响。 */
function seededRandom(seed: number): () => number {
  let state = seed
  return () => {
    state = (state * 1664525 + 1013904223) % 4294967296
    return state / 4294967296
  }
}

/** 把两张表摊平成 (物种, 桶名, 句子数组) 的列表，方便逐个断言。 */
function everyBucket(): Array<[string, readonly string[]]> {
  const rows: Array<[string, readonly string[]]> = []
  for (const species of SPECIES) {
    for (const status of STATUSES) {
      rows.push([`${species}.${status}`, PET_STATUS_LINES[species][status]])
    }
    for (const scene of SCENES) {
      rows.push([`${species}.${scene}`, PET_SCENE_LINES[species][scene]])
    }
  }
  return rows
}

describe('台词库', () => {
  it('每个物种的每个状态和场景都至少有 3 句台词（PRD 2.8）', () => {
    for (const [name, lines] of everyBucket()) {
      expect(lines.length, name).toBeGreaterThanOrEqual(3)
    }
  })

  it('同一个桶里没有重复的句子', () => {
    for (const [name, lines] of everyBucket()) {
      expect(new Set(lines).size, name).toBe(lines.length)
    }
  })

  it('句子都非空，也没有留下占位符', () => {
    for (const [name, lines] of everyBucket()) {
      for (const line of lines) {
        expect(line.trim(), name).not.toBe('')
        expect(line, name).not.toMatch(/TODO|FIXME|XXX|\{\{/)
      }
    }
  })

  it('三个物种在同一个桶里的说法互不相同', () => {
    for (const status of STATUSES) {
      const all = SPECIES.flatMap((species) => [...PET_STATUS_LINES[species][status]])
      expect(new Set(all).size, status).toBe(all.length)
    }
    for (const scene of SCENES) {
      const all = SPECIES.flatMap((species) => [...PET_SCENE_LINES[species][scene]])
      expect(new Set(all).size, scene).toBe(all.length)
    }
  })

  it('状态表和场景表都覆盖了全部键', () => {
    for (const species of SPECIES) {
      expect(Object.keys(PET_STATUS_LINES[species]).sort()).toEqual([...STATUSES].sort())
      expect(Object.keys(PET_SCENE_LINES[species]).sort()).toEqual([...SCENES].sort())
    }
  })
})

describe('台词调度器', () => {
  it('连续取 200 次都不会说和上一次相同的句子', () => {
    const director = createSpeechDirector(seededRandom(20260917))

    for (const species of SPECIES) {
      for (const bucket of [...STATUSES, ...SCENES]) {
        const isScene = (SCENES as string[]).includes(bucket)
        let previous = ''
        for (let i = 0; i < 200; i += 1) {
          const line = director.next(
            isScene
              ? { species, scene: bucket as SpeechScene }
              : { species, status: bucket as PetStatus },
          )
          expect(line, `${species} 的 ${bucket} 第 ${i} 次`).not.toBe(previous)
          previous = line
        }
      }
    }
  })

  it('场景台词优先于状态台词', () => {
    const director = createSpeechDirector(seededRandom(7))
    const line = director.next({ species: 'CAT', scene: 'EVOLVE', status: 'HUNGRY' })

    expect(PET_SCENE_LINES.CAT.EVOLVE).toContain(line)
    expect(PET_STATUS_LINES.CAT.HUNGRY).not.toContain(line)
  })

  it('同名的状态桶和场景桶各记各的"上一条"', () => {
    // SICK 两张表里都有。共用一条记录的话，场景台词刚说完，
    // 紧接着的状态台词就会被迫跳过第一句
    const director = createSpeechDirector(() => 0)

    expect(director.next({ species: 'DOG', scene: 'SICK' })).toBe(PET_SCENE_LINES.DOG.SICK[0])
    expect(director.next({ species: 'DOG', status: 'SICK' })).toBe(PET_STATUS_LINES.DOG.SICK[0])
  })

  it('取够次数能把桶里的句子都说一遍，不会只在一两句之间打转', () => {
    const director = createSpeechDirector(seededRandom(99))
    const seen = new Set<string>()
    for (let i = 0; i < 60; i += 1) {
      seen.add(director.next({ species: 'DRAGON', status: 'NORMAL' }))
    }

    expect(seen.size).toBe(PET_STATUS_LINES.DRAGON.NORMAL.length)
  })

  it('reset 之后忘掉上一条，可以重新开始', () => {
    const director = createSpeechDirector(() => 0)
    const first = director.next({ species: 'CAT', status: 'NORMAL' })
    director.reset()

    expect(director.next({ species: 'CAT', status: 'NORMAL' })).toBe(first)
  })

  it('没有场景也没有状态时给兜底文案，不抛错', () => {
    const director = createSpeechDirector(seededRandom(1))

    expect(director.next({ species: 'CAT' })).toBeTruthy()
  })
})
