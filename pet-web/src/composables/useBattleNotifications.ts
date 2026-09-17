import { Client } from '@stomp/stompjs'
import { onUnmounted, ref } from 'vue'

import { fetchBattleTopic } from '@/api/battle'

import type { BattleNotification } from '@/types/battle'
import type { IMessage } from '@stomp/stompjs'

/**
 * 订阅"战报就绪"通知（PRD 2.11）。
 *
 * 几条刻意的设计：
 *
 * - **订阅的是通配主题 {@code /topic/battles/*}，不是某一场。** 被挑战方在打完之前
 *   根本不知道 battleId，订阅具体主题的话他永远收不到通知。通配是个真广播，
 *   所以服务端那边的报文里只有对战 ID 和状态，**不含任何参战方的信息**
 *   （见 `BattleNotifier` 的注释）。
 * - **通知只是提示，不是数据。** 收到之后走 REST 查自己的对战列表 ——
 *   那条路径有令牌校验，能查出"这一场到底是不是我的"。通道没连上、连上后断了、
 *   订阅晚了，都只影响"及不及时提示"，不影响"数据对不对"。
 * - **主题前缀向服务端要**，不写死 `/topic/battles/`，路径改了前端不用跟着发版。
 * - 连不上就静默放弃：这是锦上添花的功能，不该让对战页报错。
 */
export function useBattleNotifications(onFinished: (notification: BattleNotification) => void) {
  const connected = ref(false)

  let client: Client | null = null
  /** 想订阅的主题，连上之后按它去订。 */
  let desiredTopic: string | null = null
  /** 当前这条连接是否已经订过了。断线要清零，重连后才会重新订。 */
  let subscribed = false

  /**
   * 真正发出订阅。
   *
   * <p><b>必须等连接建立之后再调。</b>{@code @stomp/stompjs} 的 {@code subscribe}
   * 在未连接时会直接抛 "There is no underlying STOMP connection"，它<b>不会</b>
   * 帮忙排队。早先就是在这里踩了坑：页面刚打开就订阅，异常被吞掉，
   * 表面上连接状态一路显示"已连接"，实际上一次都没订上，通知永远收不到。
   * 这个 bug 单测发现不了（组合式函数被 mock 掉了），只有真的开浏览器才看得见。</p>
   */
  function subscribeIfReady(): void {
    if (client === null || !client.connected || desiredTopic === null || subscribed) {
      return
    }
    client.subscribe(desiredTopic, handleMessage)
    subscribed = true
  }

  function handleMessage(message: IMessage): void {
    try {
      onFinished(JSON.parse(message.body) as BattleNotification)
    } catch {
      // 报文坏了就当没收到，下一次刷新照样能看到战报
    }
  }

  function ensureClient(): Client {
    if (client) {
      return client
    }
    // 用当前页面地址推 WebSocket 地址：开发期走 Vite 代理，部署后同源
    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const url = `${protocol}://${window.location.host}/ws`

    client = new Client({
      brokerURL: url,
      // 断线后自动重连，间隔从 2 秒起翻倍，最长 30 秒
      reconnectDelay: 2000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        connected.value = true
        // 每次（重）连上都要重新订：旧连接上的订阅跟着连接一起没了
        subscribed = false
        subscribeIfReady()
      },
      onWebSocketClose: () => {
        connected.value = false
        subscribed = false
      },
      // 连不上就算了，控制台不要刷屏；对战页本来就有 REST 兜底
      onStompError: () => {
        connected.value = false
        subscribed = false
      },
    })
    client.activate()
    return client
  }

  /** 订阅战报通知。整个页面只需要订一次。 */
  async function watch(): Promise<void> {
    if (desiredTopic !== null) {
      return
    }

    try {
      const { prefix } = await fetchBattleTopic()
      desiredTopic = `${prefix}*`
    } catch {
      return
    }

    ensureClient()
    // 连上之前这次调用什么都不会做，交给 onConnect 补上
    subscribeIfReady()
  }

  function disconnect(): void {
    desiredTopic = null
    subscribed = false
    void client?.deactivate()
    client = null
    connected.value = false
  }

  onUnmounted(disconnect)

  return { connected, watch, disconnect }
}
