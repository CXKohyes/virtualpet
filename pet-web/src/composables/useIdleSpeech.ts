import { onUnmounted } from 'vue'

import { usePetStore } from '@/stores/petStore'

/** 多久看一眼该不该开口。真正的间隔由 store 里的 IDLE_GAP_MS 把关。 */
const POLL_MS = 5000

/**
 * 空闲台词（PRD 2.8）。
 *
 * 定时唤醒 `petStore.speakIdle()`，由它决定这次要不要真的说话 ——
 * 间隔判断放在 store 里，测试就不用跟计时器较劲。
 *
 * 只在组件里调用；组件卸载时自动停表。
 */
export function useIdleSpeech(pollMs: number = POLL_MS): void {
  const petStore = usePetStore()
  const timer = setInterval(() => petStore.speakIdle(), pollMs)

  onUnmounted(() => clearInterval(timer))
}
