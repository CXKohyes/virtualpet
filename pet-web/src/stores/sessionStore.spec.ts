import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { DEVICE_ID_STORAGE_KEY, TOKEN_STORAGE_KEY, onUnauthorized } from '@/api/client'
import { establishSession } from '@/api/session'
import { useSessionStore } from '@/stores/sessionStore'

vi.mock('@/api/session', () => ({ establishSession: vi.fn() }))

// 只替换 onUnauthorized，其余（localStorage 读写、serverNow）保持真实实现
vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>()
  return { ...actual, onUnauthorized: vi.fn() }
})

const establishSessionMock = vi.mocked(establishSession)
const onUnauthorizedMock = vi.mocked(onUnauthorized)

describe('sessionStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    window.localStorage.clear()
    vi.clearAllMocks()
  })

  afterEach(() => {
    window.localStorage.clear()
  })

  it('首次初始化：生成 deviceId、建立会话、把令牌存到本地', async () => {
    establishSessionMock.mockResolvedValue({ playerId: 7, token: 'token-a', pet: null })

    const store = useSessionStore()
    await store.initialize()

    expect(store.ready).toBe(true)
    expect(store.playerId).toBe(7)
    expect(store.token).toBe('token-a')
    expect(store.hasToken).toBe(true)

    // deviceId 会落到本地，刷新页面后还是同一份存档
    const storedDeviceId = window.localStorage.getItem(DEVICE_ID_STORAGE_KEY)
    expect(storedDeviceId).toBeTruthy()
    expect(establishSessionMock).toHaveBeenCalledWith(storedDeviceId)
    expect(window.localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('token-a')
  })

  it('已有 deviceId 时复用，不重新生成', async () => {
    window.localStorage.setItem(DEVICE_ID_STORAGE_KEY, 'existing-device-id')
    establishSessionMock.mockResolvedValue({ playerId: 7, token: 'token-b', pet: null })

    const store = useSessionStore()
    await store.initialize()

    expect(store.deviceId).toBe('existing-device-id')
    expect(establishSessionMock).toHaveBeenCalledWith('existing-device-id')
  })

  it('建立会话失败时 ready 为 false 并带上原因', async () => {
    establishSessionMock.mockRejectedValue(new Error('连不上服务器，请确认后端已经启动'))

    const store = useSessionStore()
    await store.initialize()

    expect(store.ready).toBe(false)
    expect(store.error).toBe('连不上服务器，请确认后端已经启动')
  })

  it('并发调用 initialize 只会真正跑一次', async () => {
    establishSessionMock.mockResolvedValue({ playerId: 1, token: 'token-c', pet: null })

    const store = useSessionStore()
    await Promise.all([store.initialize(), store.initialize(), store.initialize()])

    expect(establishSessionMock).toHaveBeenCalledTimes(1)
  })

  it('clear 清掉令牌但保留 deviceId', async () => {
    establishSessionMock.mockResolvedValue({ playerId: 7, token: 'token-d', pet: null })
    const store = useSessionStore()
    await store.initialize()

    store.clear()

    expect(store.token).toBe('')
    expect(store.ready).toBe(false)
    expect(window.localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    // deviceId 保留，重新建会话后还是同一份存档
    expect(window.localStorage.getItem(DEVICE_ID_STORAGE_KEY)).toBeTruthy()
  })

  it('令牌失效（401）时自动清除本地会话', async () => {
    establishSessionMock.mockResolvedValue({ playerId: 7, token: 'token-e', pet: null })
    const store = useSessionStore()
    await store.initialize()
    expect(store.token).toBe('token-e')

    // store 在创建时把 clear 注册给了 client，模拟 client 收到 401 后回调
    const handler = onUnauthorizedMock.mock.calls.at(-1)?.[0]
    expect(handler).toBeTypeOf('function')
    handler?.()

    expect(store.token).toBe('')
    expect(store.ready).toBe(false)
    expect(window.localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
  })
})
