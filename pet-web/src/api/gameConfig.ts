import { request } from './client'

import type { GameConfig } from '@/types/pet'

/**
 * 读取游戏配置（docs/api.md 2.5）。免鉴权。
 *
 * 这些数值只用于展示，**不参与任何业务判断** —— 前端不复制衰减、经验、等级和进化规则。
 */
export function fetchGameConfig(): Promise<GameConfig> {
  return request<GameConfig>({ method: 'GET', url: '/game/config' })
}
