import { request } from './client'

import type { Battle, BattleSummary, BattleTopic } from '@/types/battle'

/**
 * 对战接口（PRD 2.11）。
 *
 * 和养成一样，**组件不直接调这里**，统一走 `battleStore`。
 */

/** 取我的好友码；服务端第一次会现发一个，之后是幂等的。 */
export function fetchFriendCode(): Promise<{ friendCode: string; length: number }> {
  return request<{ friendCode: string; length: number }>({
    method: 'GET',
    url: '/players/me/friend-code',
  })
}

/** 用好友码发起挑战，服务端算完直接返回战报。 */
export function challenge(friendCode: string): Promise<Battle> {
  return request<Battle>({
    method: 'POST',
    url: '/battles',
    data: { friendCode },
  })
}

/** 我的最近对战，新的在前。服务端把 limit 钳制在 1–50。 */
export function fetchBattles(limit = 20): Promise<BattleSummary[]> {
  return request<BattleSummary[]>({
    method: 'GET',
    url: '/battles',
    params: { limit },
  })
}

/** 某一场的完整战报。只有参战双方能查。 */
export function fetchBattle(battleId: number): Promise<Battle> {
  return request<Battle>({ method: 'GET', url: `/battles/${battleId}` })
}

/** 战报通知的订阅主题，向服务端要，避免前端把路径写死。 */
export function fetchBattleTopic(): Promise<BattleTopic> {
  return request<BattleTopic>({ method: 'GET', url: '/battles/topic' })
}
