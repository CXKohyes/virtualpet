import catStage0 from '@/assets/pets/cat-stage0.png'
import catStage1 from '@/assets/pets/cat-stage1.png'
import catStage2 from '@/assets/pets/cat-stage2.png'
import dogStage0 from '@/assets/pets/dog-stage0.png'
import dogStage1 from '@/assets/pets/dog-stage1.png'
import dogStage2 from '@/assets/pets/dog-stage2.png'
import dragonStage0 from '@/assets/pets/dragon-stage0.png'
import dragonStage1 from '@/assets/pets/dragon-stage1.png'
import dragonStage2 from '@/assets/pets/dragon-stage2.png'

import type { Species } from '@/types/pet'

/**
 * 精灵图索引：物种 × 进化阶段 → 图片地址。
 *
 * 图片是 `scripts/generate_sprites.py` 程序化生成的，**不要手改**。
 * 用静态 import 而不是拼路径：Vite 会在构建时把它们算进产物哈希，
 * 拼字符串的话打包后路径对不上。
 */
export const PET_SPRITES: Record<Species, readonly string[]> = {
  CAT: [catStage0, catStage1, catStage2],
  DOG: [dogStage0, dogStage1, dogStage2],
  DRAGON: [dragonStage0, dragonStage1, dragonStage2],
}

/** 取某个物种某个阶段的精灵地址。阶段越界时退回幼年形态。 */
export function spriteFor(species: Species, stage: number): string {
  const stages = PET_SPRITES[species]
  return stages[stage] ?? stages[0] ?? ''
}

/** 进化阶段的中文名，用在替代文本和日志里。 */
export const STAGE_LABELS = ['幼年', '成长', '最终'] as const
