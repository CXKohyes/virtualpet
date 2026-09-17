import type { PetAction, PetStatus } from '@/types/pet'

/**
 * 界面文案映射。
 *
 * 批次 3 只做**最小可用**的反馈文案：操作成功后的短句和状态说明。
 * 完整的按物种、按状态区分的性格台词库属于批次 4（`content/petLines.ts`），
 * 到时候这里会被替换成更丰富的版本。
 */

/** 操作成功后的短句，键来自服务端返回的 messageKey。 */
const ACTION_MESSAGES: Record<string, string> = {
  FEED_OK: '吃得真香！',
  PLAY_OK: '玩得好开心！',
  CLEAN_OK: '干净多了，舒服～',
  SLEEP_OK: '它睡着了……',
  WAKE_OK: '睡醒啦！',
}

/** 兜底文案，服务端加了新 messageKey 而前端还没跟上时用。 */
const FALLBACK_MESSAGE = '感觉不错！'

export function messageForAction(messageKey: string): string {
  return ACTION_MESSAGES[messageKey] ?? FALLBACK_MESSAGE
}

/** 操作按钮的中文名。 */
export const ACTION_LABELS: Record<PetAction, string> = {
  FEED: '喂食',
  PLAY: '玩耍',
  CLEAN: '清洁',
  SLEEP: '睡觉',
  WAKE: '唤醒',
}

/** 状态的中文名。 */
export const STATUS_LABELS: Record<PetStatus, string> = {
  NORMAL: '正常',
  HUNGRY: '饿了',
  DIRTY: '脏了',
  TIRED: '累了',
  SAD: '不开心',
  SICK: '生病了',
  SLEEPING: '睡着了',
}

/** 状态对应的语气提示，展示在状态条旁边。 */
export const STATUS_HINTS: Record<PetStatus, string> = {
  NORMAL: '状态不错',
  HUNGRY: '该喂点东西了',
  DIRTY: '需要清洁一下',
  TIRED: '精力见底了',
  SAD: '心情有点低落',
  SICK: '健康亮红灯，快照顾它',
  SLEEPING: '正在休息',
}

/** 五项属性的中文名与顺序，状态区按这个顺序渲染。 */
export const ATTRIBUTE_META = [
  { key: 'satiety', label: '饱食' },
  { key: 'mood', label: '心情' },
  { key: 'hygiene', label: '清洁' },
  { key: 'energy', label: '精力' },
  { key: 'health', label: '健康' },
] as const
