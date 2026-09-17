import { request } from './client'

import type { SessionData } from '@/types/pet'

/** 建立或恢复匿名会话（docs/api.md 2.1）。 */
export function establishSession(deviceId: string): Promise<SessionData> {
  return request<SessionData>({
    method: 'POST',
    url: '/session',
    data: { deviceId },
  })
}
