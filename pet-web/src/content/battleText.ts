import type { BattleOutcome, BattleSide } from '@/types/battle'
import type { Species } from '@/types/pet'

/**
 * 对战界面文案。
 *
 * 只放"服务端返回的枚举 → 中文"这一层映射，不含任何数值或规则 ——
 * 伤害、血量、胜负全都在战报里，前端只负责翻译。
 */

export const SPECIES_NAMES: Record<Species, string> = {
  CAT: '猫',
  DOG: '狗',
  DRAGON: '像素龙',
}

/** 双方在对战里的称呼。 */
export const SIDE_LABELS: Record<BattleSide, string> = {
  CHALLENGER: '挑战方',
  DEFENDER: '被挑战方',
}

/** 胜负是怎么判出来的。 */
export const OUTCOME_LABELS: Record<BattleOutcome, string> = {
  KO: '击倒',
  TIMEOUT: '打满回合，按剩余生命判定',
  DRAW: '打满回合，剩余生命相同',
}

/**
 * 三个物种的对战倾向，用来在选宠和对战页做说明。
 *
 * 数值本身不在这里 —— 它们属于游戏规则，由服务端持有
 * （`Species.BattleTendency`）。这里只是把它翻译成人话，
 * 改了数值不用动这段文字。
 */
export const SPECIES_TENDENCY_HINTS: Record<Species, string> = {
  CAT: '速度最快，靠追加攻击压制对手',
  DOG: '生命与防御均衡，每回合都能回血',
  DRAGON: '攻击最高，血也厚，但没有恢复手段',
}
