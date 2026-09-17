import axios, { AxiosError, type AxiosRequestConfig } from 'axios'

import type { ApiEnvelope } from '@/types/api'

/** 本地存储键。清除这两个键等同于换一份新存档（PRD 2.1）。 */
export const DEVICE_ID_STORAGE_KEY = 'virtual-pet.device-id'
export const TOKEN_STORAGE_KEY = 'virtual-pet.token'

/**
 * 后端返回的业务错误。
 *
 * 组件不直接看 Axios 的异常，统一拿这个类型的 `code` 和 `message`：
 * `message` 是后端给的中文文案，可以直接展示给用户。
 */
export class ApiError extends Error {
  readonly code: string
  readonly status: number

  constructor(code: string, message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }

  /** 令牌失效，需要重新建立会话。 */
  get isUnauthorized(): boolean {
    return this.status === 401
  }
}

// ---------------------------------------------------------------- 本地存储

export function readStorage(key: string): string | null {
  try {
    return window.localStorage.getItem(key)
  } catch {
    // 隐私模式下 localStorage 可能直接抛错，当作没有存档处理
    return null
  }
}

export function writeStorage(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value)
  } catch {
    // 存不进去不影响本次会话，只是刷新后会丢
  }
}

export function removeStorage(key: string): void {
  try {
    window.localStorage.removeItem(key)
  } catch {
    // 同上
  }
}

// ---------------------------------------------------------------- 服务端时钟

let clockOffsetMs = 0

/**
 * 服务端当前时间（毫秒时间戳）。
 *
 * 每次收到响应都会用响应里的 `serverTime` 校准一次偏移量，所以冷却倒计时
 * 走的是服务端时间，客户端时钟不准也不会算错（TECH_DESIGN 6.5）。
 */
export function serverNow(): number {
  return Date.now() - clockOffsetMs
}

function syncClock(serverTime: string | undefined): void {
  if (!serverTime) {
    return
  }
  const parsed = Date.parse(serverTime)
  if (!Number.isNaN(parsed)) {
    clockOffsetMs = Date.now() - parsed
  }
}

// ---------------------------------------------------------------- 令牌失效回调

let unauthorizedHandler: (() => void) | null = null

/**
 * 注册令牌失效时的回调（由 sessionStore 在初始化时调用）。
 *
 * 用回调而不是让 client 直接 import store，避免 api 层反向依赖 store 造成循环。
 */
export function onUnauthorized(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

// ---------------------------------------------------------------- Axios 实例

const http = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
  headers: { Accept: 'application/json' },
})

http.interceptors.request.use((config) => {
  const token = readStorage(TOKEN_STORAGE_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    syncClock((response.data as ApiEnvelope<unknown> | undefined)?.serverTime)
    return response
  },
  (error: unknown) => {
    const apiError = toApiError(error)
    if (apiError.isUnauthorized) {
      // 令牌已经没用了，先清掉再通知上层重新建会话
      removeStorage(TOKEN_STORAGE_KEY)
      unauthorizedHandler?.()
    }
    return Promise.reject(apiError)
  },
)

function toApiError(error: unknown): ApiError {
  if (!(error instanceof AxiosError)) {
    return new ApiError('UNKNOWN_ERROR', '发生未知错误', 0)
  }

  const status = error.response?.status ?? 0
  const envelope = error.response?.data as ApiEnvelope<unknown> | undefined
  syncClock(envelope?.serverTime)

  if (envelope && typeof envelope.code === 'string' && typeof envelope.message === 'string') {
    return new ApiError(envelope.code, envelope.message, status)
  }
  if (status > 0) {
    return new ApiError('HTTP_ERROR', `请求失败（HTTP ${status}）`, status)
  }
  return new ApiError('NETWORK_ERROR', '连不上服务器，请确认后端已经启动', 0)
}

/**
 * 发起请求并拆掉响应信封，直接返回 `data`。
 *
 * 失败一律抛 {@link ApiError}，调用方按 `code` 判断，不用关心 HTTP 细节。
 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<ApiEnvelope<T>>(config)
  return response.data.data
}
