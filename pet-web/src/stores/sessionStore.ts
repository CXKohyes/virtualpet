import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import {
  DEVICE_ID_STORAGE_KEY,
  TOKEN_STORAGE_KEY,
  onUnauthorized,
  readStorage,
  removeStorage,
  writeStorage,
} from '@/api/client'
import { establishSession } from '@/api/session'

import type { SessionData } from '@/types/pet'

/**
 * 设备 ID 是匿名存档的唯一身份（PRD 2.1），存在 localStorage 里。
 *
 * 后端限制 64 字符，`crypto.randomUUID()` 是 36 字符，够用。
 */
function createDeviceId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `device-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

/**
 * 匿名会话。
 *
 * 负责：生成并保存 deviceId、向后端换取令牌、把令牌存到本地、
 * 在令牌失效时清掉本地状态。
 */
export const useSessionStore = defineStore('session', () => {
  const deviceId = ref('')
  const token = ref('')
  const playerId = ref<number | null>(null)
  /** 会话是否已经建立成功。 */
  const ready = ref(false)
  const initializing = ref(false)
  const error = ref<string | null>(null)

  const hasToken = computed(() => token.value !== '')

  /** 建立（或恢复）会话。重复调用是安全的，并发调用只会真正跑一次。 */
  async function initialize(): Promise<void> {
    if (initializing.value) {
      return
    }
    initializing.value = true
    error.value = null

    try {
      // deviceId 一旦生成就一直复用，这就是"同一份存档"
      const stored = readStorage(DEVICE_ID_STORAGE_KEY)
      deviceId.value = stored && stored.trim() !== '' ? stored : createDeviceId()
      writeStorage(DEVICE_ID_STORAGE_KEY, deviceId.value)

      apply(await establishSession(deviceId.value))
      ready.value = true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '建立会话失败'
      ready.value = false
    } finally {
      initializing.value = false
    }
  }

  function apply(session: SessionData): void {
    token.value = session.token
    playerId.value = session.playerId
    writeStorage(TOKEN_STORAGE_KEY, session.token)
  }

  /**
   * 清空本地会话状态。
   *
   * 令牌失效时由 {@link onUnauthorized} 自动调用；deviceId 故意<b>不删</b>，
   * 这样重新建会话后还是同一份存档。
   */
  function clear(): void {
    token.value = ''
    playerId.value = null
    ready.value = false
    removeStorage(TOKEN_STORAGE_KEY)
  }

  onUnauthorized(clear)

  return {
    deviceId,
    token,
    playerId,
    ready,
    initializing,
    error,
    hasToken,
    initialize,
    clear,
  }
})
