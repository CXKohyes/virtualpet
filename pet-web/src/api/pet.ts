import { request } from './client'

import type { ActionOutcome, JournalEntry, Pet, PetAction, Species } from '@/types/pet'

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

/**
 * 最近的照护记录，新的在前（docs/api.md 2.7）。
 *
 * 服务端会把 limit 钳制在 1–50。
 */
export function fetchJournal(limit = 20): Promise<JournalEntry[]> {
  return request<JournalEntry[]>({
    method: 'GET',
    url: '/pets/me/journal',
    params: { limit },
  })
}

/**
 * 名册：该玩家的全部宠物，按槽位升序（docs/api.md 2.13）。
 *
 * 服务端会**逐只结算**，所以每只带的 `settlement` 都是真的 ——
 * 名册能一眼看出谁快不行了，靠的就是这个。
 */
export function fetchPets(): Promise<Pet[]> {
  return request<Pet[]>({ method: 'GET', url: '/pets' })
}

/**
 * 切换当前宠物（docs/api.md 2.14）。
 *
 * 返回切换后那只已结算的状态，于是「切过去」这个动作本身就带回了
 * 「你不在时它怎么样了」。
 */
export function activatePet(petId: number): Promise<Pet> {
  return request<Pet>({
    method: 'POST',
    url: '/pets/me/active',
    data: { petId },
  })
}

/**
 * 送走一只宠物：删除它和它的全部照护日志（docs/api.md 2.6）。
 *
 * 不是幂等的：重复送走同一只返回 404，调用方据此知道名册已经旧了。
 */
export function releasePet(petId: number): Promise<void> {
  return request<void>({ method: 'DELETE', url: `/pets/${petId}` })
}
