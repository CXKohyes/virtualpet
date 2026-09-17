import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'

import { fetchBattleTopic } from '@/api/battle'
import { useBattleNotifications } from '@/composables/useBattleNotifications'

import type { BattleNotification } from '@/types/battle'

vi.mock('@/api/battle', () => ({ fetchBattleTopic: vi.fn() }))

/**
 * 假 STOMP 客户端，**刻意照抄真库那个会咬人的行为**：
 * 没连上就 subscribe 直接抛 "There is no underlying STOMP connection"。
 *
 * 复刻这一点是这条测试的全部意义 —— 早先就是因为不知道它不排队，
 * 页面刚打开就订阅，异常被吞掉，表面上连接状态显示得好好的，
 * 实际上一次都没订上。
 *
 * 必须写在 `vi.hoisted` 里：`vi.mock` 会被提升到文件顶部，
 * 工厂函数执行时普通的类声明还没初始化。
 */
const { FakeClient } = vi.hoisted(() => {
  class FakeClientImpl {
    static instances: FakeClientImpl[] = []
    static readonly NOT_CONNECTED = 'There is no underlying STOMP connection'

    connected = false
    subscriptions: string[] = []
    activated = false
    deactivated = false
    handlers: Record<string, (message: unknown) => void> = {}

    onConnect?: () => void
    onWebSocketClose?: () => void
    onStompError?: () => void

    constructor(config: {
      onConnect?: () => void
      onWebSocketClose?: () => void
      onStompError?: () => void
    }) {
      this.onConnect = config.onConnect
      this.onWebSocketClose = config.onWebSocketClose
      this.onStompError = config.onStompError
      FakeClientImpl.instances.push(this)
    }

    activate(): void {
      this.activated = true
    }

    deactivate(): Promise<void> {
      this.deactivated = true
      return Promise.resolve()
    }

    subscribe(topic: string, handler: (message: unknown) => void): void {
      if (!this.connected) {
        throw new Error(FakeClientImpl.NOT_CONNECTED)
      }
      this.subscriptions.push(topic)
      this.handlers[topic] = handler
    }

    /** 测试里手动模拟连上。 */
    connectForTest(): void {
      this.connected = true
      this.onConnect?.()
    }

    /** 测试里手动模拟推送。 */
    push(topic: string, payload: unknown): void {
      this.handlers[topic]?.({ body: JSON.stringify(payload) })
    }
  }
  return { FakeClient: FakeClientImpl }
})

vi.mock('@stomp/stompjs', () => ({ Client: FakeClient }))

const fetchBattleTopicMock = vi.mocked(fetchBattleTopic)

describe('useBattleNotifications', () => {
  let received: BattleNotification[]

  const Host = defineComponent({
    setup() {
      const { connected, watch } = useBattleNotifications((notification) => {
        received.push(notification)
      })
      return { connected, watch }
    },
    render: () => h('div'),
  })

  beforeEach(() => {
    FakeClient.instances = []
    received = []
    vi.clearAllMocks()
    fetchBattleTopicMock.mockResolvedValue({ prefix: '/topic/battles/', pattern: '/topic/battles/{battleId}' })
  })

  it('还没连上就订阅不会抛错，也不会假装订上了', async () => {
    const wrapper = mount(Host)

    await expect(wrapper.vm.watch()).resolves.toBeUndefined()
    expect(FakeClient.instances[0]?.subscriptions).toEqual([])

    wrapper.unmount()
  })

  it('连上之后才真的订阅，主题是通配的那条', async () => {
    const wrapper = mount(Host)
    await wrapper.vm.watch()

    const client = FakeClient.instances[0]
    expect(client?.activated).toBe(true)

    client?.connectForTest()

    expect(client?.subscriptions).toEqual(['/topic/battles/*'])
    wrapper.unmount()
  })

  it('收到通知会回调出来', async () => {
    const wrapper = mount(Host)
    await wrapper.vm.watch()
    const client = FakeClient.instances[0]
    client?.connectForTest()

    client?.push('/topic/battles/*', { type: 'BATTLE_FINISHED', battleId: 7, status: 'FINISHED' })

    expect(received).toHaveLength(1)
    expect(received[0]?.battleId).toBe(7)
    wrapper.unmount()
  })

  it('断线重连之后会重新订阅', async () => {
    const wrapper = mount(Host)
    await wrapper.vm.watch()
    const client = FakeClient.instances[0]
    client?.connectForTest()
    expect(client?.subscriptions).toHaveLength(1)

    // 断线：旧连接上的订阅跟着没了
    client!.connected = false
    client?.onWebSocketClose?.()
    expect(wrapper.vm.connected).toBe(false)

    // 重连
    client?.connectForTest()

    expect(client?.subscriptions).toHaveLength(2)
    wrapper.unmount()
  })

  it('报文坏了不会把整个回调链打断', async () => {
    const wrapper = mount(Host)
    await wrapper.vm.watch()
    const client = FakeClient.instances[0]
    client?.connectForTest()

    expect(() => client?.handlers['/topic/battles/*']?.({ body: '不是 JSON' })).not.toThrow()
    expect(received).toHaveLength(0)
    wrapper.unmount()
  })

  it('拿不到主题前缀时静默放弃，不抛错', async () => {
    fetchBattleTopicMock.mockRejectedValue(new Error('后端没起'))
    const wrapper = mount(Host)

    await expect(wrapper.vm.watch()).resolves.toBeUndefined()
    expect(FakeClient.instances).toHaveLength(0)
    wrapper.unmount()
  })

  it('组件卸载时断开连接', async () => {
    const wrapper = mount(Host)
    await wrapper.vm.watch()
    const client = FakeClient.instances[0]

    wrapper.unmount()

    expect(client?.deactivated).toBe(true)
    expect(client?.connected).toBe(false)
  })
})
