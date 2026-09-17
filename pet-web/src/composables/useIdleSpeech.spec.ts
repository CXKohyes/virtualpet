import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'

import { useIdleSpeech } from '@/composables/useIdleSpeech'
import { usePetStore } from '@/stores/petStore'

/** 套一层组件来跑组合式函数，卸载时才能验证定时器有没有被清掉。 */
const Host = defineComponent({
  props: { pollMs: { type: Number, default: 1000 } },
  setup(props) {
    useIdleSpeech(props.pollMs)
    return () => h('div')
  },
})

describe('useIdleSpeech', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('按间隔唤醒 store 的空闲台词', async () => {
    const petStore = usePetStore()
    const speakIdle = vi.spyOn(petStore, 'speakIdle').mockImplementation(() => undefined)

    const wrapper = mount(Host, { props: { pollMs: 1000 } })
    await vi.advanceTimersByTimeAsync(3000)

    expect(speakIdle).toHaveBeenCalledTimes(3)
    wrapper.unmount()
  })

  it('组件卸载后不再触发', async () => {
    const petStore = usePetStore()
    const speakIdle = vi.spyOn(petStore, 'speakIdle').mockImplementation(() => undefined)

    const wrapper = mount(Host, { props: { pollMs: 1000 } })
    await vi.advanceTimersByTimeAsync(2000)
    expect(speakIdle).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(5000)

    expect(speakIdle).toHaveBeenCalledTimes(2)
  })
})
