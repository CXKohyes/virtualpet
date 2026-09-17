/**
 * 后端统一响应信封（docs/api.md 1.1）。
 *
 * 所有接口成功和失败都用这个结构，`code` 为 `'OK'` 表示成功。
 */
export interface ApiEnvelope<T> {
  code: string
  message: string
  data: T
  serverTime: string
}
