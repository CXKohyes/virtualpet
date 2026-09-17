import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  SOUND_RECIPES,
  resetAudioContextForTest,
  soundDuration,
  soundForAction,
  useAudio,
} from '@/composables/useAudio'
import { useUiStore } from '@/stores/uiStore'

import type { SoundName } from '@/composables/useAudio'

const SOUNDS = Object.keys(SOUND_RECIPES) as SoundName[]

/** 记录调用次数的假 AudioContext，够验证"该响的时候响了"就行。 */
class FakeAudioContext {
  static instances: FakeAudioContext[] = []
  /** 新建上下文时的初始状态，用来模拟"还没被用户手势解锁"。 */
  static startState: AudioContextState = 'running'

  state: AudioContextState = FakeAudioContext.startState
  currentTime = 0
  sampleRate = 44100
  destination = { name: 'destination' }
  started: number[] = []
  stopped: number[] = []
  resumed = 0

  constructor() {
    FakeAudioContext.instances.push(this)
  }

  createGain() {
    return {
      gain: {
        value: 0,
        setValueAtTime: vi.fn(),
        linearRampToValueAtTime: vi.fn(),
      },
      connect: vi.fn((node: unknown) => node),
    }
  }

  createOscillator() {
    return {
      type: 'square',
      frequency: { setValueAtTime: vi.fn() },
      connect: vi.fn((node: unknown) => node),
      start: vi.fn((at: number) => this.started.push(at)),
      stop: vi.fn((at: number) => this.stopped.push(at)),
    }
  }

  createBiquadFilter() {
    return {
      type: 'bandpass',
      frequency: { setValueAtTime: vi.fn() },
      Q: { value: 0 },
      connect: vi.fn((node: unknown) => node),
    }
  }

  createBufferSource() {
    return {
      buffer: null,
      connect: vi.fn((node: unknown) => node),
      start: vi.fn((at: number) => this.started.push(at)),
      stop: vi.fn((at: number) => this.stopped.push(at)),
    }
  }

  createBuffer(_channels: number, length: number) {
    return { getChannelData: () => new Float32Array(length) }
  }

  resume() {
    this.resumed += 1
    return Promise.resolve()
  }
}

function installAudioContext(): void {
  vi.stubGlobal('AudioContext', FakeAudioContext)
}

describe('音效配方', () => {
  it('六种音效齐全：喂食、玩耍、清洁、升级、生病、进化', () => {
    expect(SOUNDS.sort()).toEqual(['CLEAN', 'EVOLVE', 'FEED', 'LEVEL_UP', 'PLAY', 'SICK'])
  })

  it('每个音效时长都在 80–250ms（TECH_DESIGN 8.2）', () => {
    for (const name of SOUNDS) {
      const duration = soundDuration(name)
      expect(duration, name).toBeGreaterThanOrEqual(80)
      expect(duration, name).toBeLessThanOrEqual(250)
    }
  })

  it('六种音效各不相同', () => {
    const shapes = SOUNDS.map((name) => JSON.stringify(SOUND_RECIPES[name]))
    expect(new Set(shapes).size).toBe(SOUNDS.length)
  })

  it('没有负偏移，音符都真的在发声', () => {
    for (const name of SOUNDS) {
      const recipe = SOUND_RECIPES[name]
      expect(recipe.tones.length + recipe.noise.length, name).toBeGreaterThan(0)
      for (const part of [...recipe.tones, ...recipe.noise]) {
        expect(part.at, name).toBeGreaterThanOrEqual(0)
        expect(part.duration, name).toBeGreaterThan(0)
      }
    }
  })

  it('只有喂食、玩耍、清洁三个操作有音效', () => {
    expect(soundForAction('FEED')).toBe('FEED')
    expect(soundForAction('PLAY')).toBe('PLAY')
    expect(soundForAction('CLEAN')).toBe('CLEAN')
    expect(soundForAction('SLEEP')).toBeNull()
    expect(soundForAction('WAKE')).toBeNull()
  })
})

describe('useAudio', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    window.localStorage.clear()
    FakeAudioContext.instances = []
    FakeAudioContext.startState = 'running'
    resetAudioContextForTest()
    installAudioContext()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    resetAudioContextForTest()
    window.localStorage.clear()
  })

  it('播放时真的向 AudioContext 排了音', () => {
    useAudio().play('LEVEL_UP')

    const ctx = FakeAudioContext.instances[0]
    expect(ctx).toBeDefined()
    // LEVEL_UP 是四个方波音符
    expect(ctx?.started).toHaveLength(4)
    expect(ctx?.stopped).toHaveLength(4)
  })

  it('静音时完全不碰 AudioContext', () => {
    const ui = useUiStore()
    ui.toggleSound()
    expect(ui.soundMuted).toBe(true)

    useAudio().play('FEED')

    expect(FakeAudioContext.instances).toHaveLength(0)
  })

  it('恢复静音之后又能出声', () => {
    const ui = useUiStore()
    ui.toggleSound()
    useAudio().play('FEED')
    ui.toggleSound()

    useAudio().play('FEED')

    expect(FakeAudioContext.instances).toHaveLength(1)
  })

  it('AudioContext 只创建一次，之后复用', () => {
    const audio = useAudio()
    audio.play('FEED')
    audio.play('PLAY')
    audio.play('CLEAN')

    expect(FakeAudioContext.instances).toHaveLength(1)
  })

  it('浏览器不支持时静默降级，不抛错', () => {
    vi.stubGlobal('AudioContext', undefined)

    expect(() => useAudio().play('EVOLVE')).not.toThrow()
  })

  it('AudioContext 构造抛错时静默降级', () => {
    vi.stubGlobal(
      'AudioContext',
      class {
        constructor() {
          throw new Error('Autoplay blocked')
        }
      },
    )

    expect(() => useAudio().play('SICK')).not.toThrow()
  })

  it('上下文挂起时先恢复再发声', () => {
    // 浏览器在用户交互之前会把新建的 AudioContext 挂在 suspended
    FakeAudioContext.startState = 'suspended'

    useAudio().play('FEED')

    const ctx = FakeAudioContext.instances[0]
    expect(ctx?.resumed).toBe(1)
    expect(ctx?.started.length).toBeGreaterThan(0)
  })

  it('resume 被拒绝也不影响发声和操作', () => {
    FakeAudioContext.startState = 'suspended'
    vi.spyOn(FakeAudioContext.prototype, 'resume').mockReturnValue(
      Promise.reject(new Error('blocked')) as unknown as Promise<void>,
    )

    expect(() => useAudio().play('FEED')).not.toThrow()
    expect(FakeAudioContext.instances[0]?.started.length).toBeGreaterThan(0)
  })
})
