import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { challenge as challengeApi, fetchBattle, fetchBattles, fetchFriendCode } from '@/api/battle'

import type { Battle, BattleSummary } from '@/types/battle'

/** 列表一次拉多少条，和服务端默认值保持一致。 */
const LIST_LIMIT = 20

/**
 * 对战状态（PRD 2.11）。
 *
 * 两条铁律，和养成那边一样：
 *
 * 1. **战报完全由服务端算好**，前端只负责画。伤害、剩余血量、谁先手、
 *    胜负判定，一个都不在这里推。连"这一下是不是暴击"都是服务端给好的布尔值。
 * 2. **WebSocket 只用来知道"该刷新了"**，不传数据。收到通知之后照常走 REST
 *    拉战报 —— 这样通道断了顶多是不提示，数据永远是对的。
 */
export const useBattleStore = defineStore('battle', () => {
  /** 我的好友码，进对战页时懒加载。 */
  const friendCode = ref('')
  /** 最近对战，新的在前。 */
  const recent = ref<BattleSummary[]>([])
  /** 当前正在看的战报。 */
  const current = ref<Battle | null>(null)
  const loading = ref(false)
  const challenging = ref(false)
  const error = ref<string | null>(null)

  /** 当前战报里我是不是赢家。平局或还没打完时为 false。 */
  const viewerWon = computed(
    () => current.value !== null && current.value.winner === current.value.viewer,
  )

  /** 对手的宠物（相对于 viewer）。 */
  const opponent = computed(() => {
    const battle = current.value
    if (!battle) {
      return null
    }
    return battle.viewer === 'CHALLENGER' ? battle.defender : battle.challenger
  })

  /** 我的宠物（相对于 viewer）。 */
  const mine = computed(() => {
    const battle = current.value
    if (!battle) {
      return null
    }
    return battle.viewer === 'CHALLENGER' ? battle.challenger : battle.defender
  })

  /** 拉好友码。失败不算致命，界面显示占位即可。 */
  async function loadFriendCode(): Promise<void> {
    try {
      friendCode.value = (await fetchFriendCode()).friendCode
    } catch (cause) {
      error.value = describe(cause)
    }
  }

  /** 拉最近对战列表。 */
  async function loadRecent(): Promise<void> {
    try {
      recent.value = await fetchBattles(LIST_LIMIT)
    } catch (cause) {
      error.value = describe(cause)
    }
  }

  /** 打开一场战报。 */
  async function open(battleId: number): Promise<void> {
    loading.value = true
    error.value = null
    try {
      current.value = await fetchBattle(battleId)
    } catch (cause) {
      error.value = describe(cause)
      current.value = null
    } finally {
      loading.value = false
    }
  }

  /**
   * 发起挑战。
   *
   * 服务端是同步算完的，所以这个请求回来时战报已经在手里了 ——
   * 不需要再等 WebSocket 通知。（通知是给**被挑战方**看的：
   * 对方可能正开着页面，需要知道"有人打了你"。）
   */
  async function challenge(code: string): Promise<boolean> {
    if (challenging.value) {
      return false
    }
    challenging.value = true
    error.value = null
    try {
      current.value = await challengeApi(code)
      await loadRecent()
      return true
    } catch (cause) {
      error.value = describe(cause)
      return false
    } finally {
      challenging.value = false
    }
  }

  /**
   * 收到"战报就绪"通知。
   *
   * 通知是**广播**（见 `useBattleNotifications`），所以它可能说的是别人的对战。
   * 判断归属的唯一办法是查自己的列表 —— 那条路径有令牌校验，返回的必然是自己的。
   * 刷新之后如果列表里出现了这个 ID，就说明这一场跟我有关。
   *
   * 不直接用通知里的内容渲染：里面本来就没有战报本体。
   */
  async function onBattleFinished(battleId: number): Promise<void> {
    await loadRecent()

    const mine = recent.value.some((battle) => battle.id === battleId)
    if (!mine) {
      return
    }
    current.value = await fetchBattle(battleId).catch(() => current.value)
  }

  function clearError(): void {
    error.value = null
  }

  function reset(): void {
    friendCode.value = ''
    recent.value = []
    current.value = null
    error.value = null
  }

  return {
    friendCode,
    recent,
    current,
    loading,
    challenging,
    error,
    viewerWon,
    opponent,
    mine,
    loadFriendCode,
    loadRecent,
    open,
    challenge,
    onBattleFinished,
    clearError,
    reset,
  }
})

function describe(cause: unknown): string {
  return cause instanceof Error ? cause.message : '请求失败了，请再试一次'
}
