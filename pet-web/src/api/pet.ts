import { request } from './client'

import type { ActionOutcome, Pet, PetAction, Species } from '@/types/pet'

/** 领养宠物（docs/api.md 2.2）。 */
export function createPet(species: Species, name: string): Promise<Pet> {
  return request<Pet>({
    method: 'POST',
    url: '/pets',
    data: { species, name },
  })
}

/** 查询宠物，服务端会先做离线结算（docs/api.md 2.3）。 */
export function fetchPet(): Promise<Pet> {
  return request<Pet>({ method: 'GET', url: '/pets/me' })
}

/**
 * 执行一次操作（docs/api.md 2.4）。
 *
 * `clientRequestId` 是幂等键，每次点击都要生成新的随机值；
 * 重复提交同一个值只会拿到第一次的结果，不会重复加经验。
 */
export function performAction(action: PetAction, clientRequestId: string): Promise<ActionOutcome> {
  return request<ActionOutcome>({
    method: 'POST',
    url: '/pets/me/actions',
    data: { action, clientRequestId },
  })
}

/** 重置存档：删除宠物和日志，玩家记录保留（docs/api.md 2.6）。 */
export function resetPet(): Promise<void> {
  return request<void>({ method: 'DELETE', url: '/pets/me' })
}
