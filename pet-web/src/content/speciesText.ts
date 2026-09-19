import type { Species } from '@/types/pet'

/**
 * 物种的中文显示名。
 *
 * **全项目只有这一份。** 它原先有三份互相独立的副本 —— 领养页、对战文案、
 * 宠物舞台各写了一份字面量。加一个物种就要改三处，漏掉任何一处，
 * 对应界面就会显示空白或 `undefined`，而且类型检查也拦不住
 * （三份都是 `Record<Species, string>`，各自补一个键就能编译通过）。
 *
 * 只做「枚举 → 中文」这一层翻译，不含任何数值或规则。
 */
export const SPECIES_NAMES: Record<Species, string> = {
  CAT: '猫',
  DOG: '狗',
  DRAGON: '像素龙',
  RABBIT: '兔子',
}
