import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

import { describe, expect, it } from 'vitest'

import { PET_SPRITES, STAGE_LABELS, spriteFor } from '@/content/petSprites'
import { SPECIES } from '@/types/pet'

const SPRITE_DIR = join(process.cwd(), 'src', 'assets', 'pets')

/**
 * 直接读 PNG 的 IHDR 拿宽高。
 *
 * 引一张 4KB 的图进项目就为了测尺寸不划算；PNG 的宽高就写在开头，
 * 8 字节签名 + 4 字节长度 + "IHDR" 之后就是宽和高，各 4 字节大端。
 */
function pngSize(path: string): { width: number; height: number } {
  const buffer = readFileSync(path)
  return { width: buffer.readUInt32BE(16), height: buffer.readUInt32BE(20) }
}

/**
 * 物种列表来自 `types/pet.ts` 的常量，**不在这里再抄一份字面量**。
 *
 * 抄一份的话，加新物种时这些用例会照常通过，只是悄悄漏测新物种 ——
 * 那比直接失败更糟。「精灵表覆盖了全部物种」这条另有 TypeScript 兜底：
 * `PET_SPRITES` 的类型是 `Record<Species, …>`，漏一个物种编译就过不去。
 */
const STAGE_COUNT = 3

describe('精灵图索引', () => {
  it('每个物种三个阶段的地址都不一样', () => {
    const urls = SPECIES.flatMap((species) => [...PET_SPRITES[species]])

    expect(urls).toHaveLength(SPECIES.length * STAGE_COUNT)
    expect(new Set(urls).size).toBe(SPECIES.length * STAGE_COUNT)
    expect(urls.every((url) => url.length > 0)).toBe(true)
  })

  it('spriteFor 取到对应阶段，越界时退回幼年形态', () => {
    for (const species of SPECIES) {
      expect(spriteFor(species, 0)).toBe(PET_SPRITES[species][0])
      expect(spriteFor(species, 1)).toBe(PET_SPRITES[species][1])
      expect(spriteFor(species, 2)).toBe(PET_SPRITES[species][2])
      expect(spriteFor(species, 9)).toBe(PET_SPRITES[species][0])
      expect(spriteFor(species, -1)).toBe(PET_SPRITES[species][0])
    }
  })

  it('三个阶段都有中文名', () => {
    expect(STAGE_LABELS).toHaveLength(STAGE_COUNT)
    expect(STAGE_LABELS[0]).toBe('幼年')
  })
})

describe('生成出来的精灵图文件', () => {
  it('每个物种的每个阶段文件都在，且都是 64×64 的 PNG（TECH_DESIGN 8.1）', () => {
    for (const species of SPECIES) {
      for (let stage = 0; stage < STAGE_COUNT; stage += 1) {
        const file = join(SPRITE_DIR, `${species.toLowerCase()}-stage${stage}.png`)
        const { width, height } = pngSize(file)

        expect({ file: `${species}-${stage}`, width, height }).toEqual({
          file: `${species}-${stage}`,
          width: 64,
          height: 64,
        })
      }
    }
  })

  it('目录里没有多余的图，联系表也在（用来肉眼核对一致性）', () => {
    const entries = readdirSync(SPRITE_DIR)

    expect(entries).toContain('contact-sheet.png')
    // 物种 × 阶段 张精灵 + 一张联系表。
    // 这条同时兜住另一件事：`generate_sprites.py` 的 SPECIES 和
    // `types/pet.ts` 的 SPECIES 必须一一对应，多一张少一张都会在这里炸。
    expect(entries.filter((name) => name.endsWith('.png'))).toHaveLength(
      SPECIES.length * STAGE_COUNT + 1,
    )
  })
})
