import { useUiStore } from '@/stores/uiStore'

import type { PetAction } from '@/types/pet'

/**
 * 8-bit 音效（PRD 2.9、TECH_DESIGN 8.2）。
 *
 * 几条硬约束：
 *
 * - **不引入任何音频素材**，全部用 Web Audio 现场合成方波、三角波和噪声。
 * - 每个音效总时长控制在 **80–250ms**，长了就不像掌机提示音了。
 * - AudioContext **在第一次用户点击时才创建**。浏览器禁止没有交互就出声，
 *   而 `play()` 只会从点击处理里调用，所以这条天然满足。
 * - 静音开关存在 localStorage，由 `uiStore` 管。
 * - 合成失败（浏览器不支持、被策略拦截）就**静默降级**，绝不能影响操作本身。
 */

export type SoundName = 'FEED' | 'PLAY' | 'CLEAN' | 'LEVEL_UP' | 'SICK' | 'EVOLVE'

/** 一个音符。`at` 和 `duration` 都是相对本次音效起点的毫秒数。 */
export interface Tone {
  at: number
  duration: number
  freq: number
  type: OscillatorType
  gain: number
}

/** 一段噪声，用来做"咬""冲刷"这类非乐音。 */
export interface NoiseBurst {
  at: number
  duration: number
  gain: number
  /** 带通中心频率，决定沙沙声是闷还是脆。 */
  center: number
}

export interface SoundRecipe {
  tones: Tone[]
  noise: NoiseBurst[]
}

/**
 * 六个音效的配方。
 *
 * 喂食是两口"咬"（噪声 + 两个上行方波），玩耍是轻快的三角波琶音，
 * 清洁是两段冲刷噪声，升级是上行大三和弦，生病是往下掉的两个音，
 * 进化最长，是一小段号角 —— 但也没超过 250ms。
 */
export const SOUND_RECIPES: Record<SoundName, SoundRecipe> = {
  FEED: {
    tones: [
      { at: 0, duration: 55, freq: 420, type: 'square', gain: 0.16 },
      { at: 60, duration: 80, freq: 560, type: 'square', gain: 0.16 },
    ],
    noise: [
      { at: 0, duration: 45, gain: 0.18, center: 1200 },
      { at: 60, duration: 45, gain: 0.14, center: 1500 },
    ],
  },
  PLAY: {
    tones: [
      { at: 0, duration: 55, freq: 523, type: 'triangle', gain: 0.2 },
      { at: 55, duration: 55, freq: 659, type: 'triangle', gain: 0.2 },
      { at: 110, duration: 80, freq: 880, type: 'triangle', gain: 0.2 },
    ],
    noise: [],
  },
  CLEAN: {
    tones: [{ at: 150, duration: 60, freq: 1046, type: 'triangle', gain: 0.12 }],
    noise: [
      { at: 0, duration: 70, gain: 0.14, center: 2600 },
      { at: 80, duration: 70, gain: 0.12, center: 3400 },
    ],
  },
  LEVEL_UP: {
    tones: [
      { at: 0, duration: 55, freq: 523, type: 'square', gain: 0.15 },
      { at: 55, duration: 55, freq: 659, type: 'square', gain: 0.15 },
      { at: 110, duration: 55, freq: 784, type: 'square', gain: 0.15 },
      { at: 165, duration: 85, freq: 1047, type: 'square', gain: 0.17 },
    ],
    noise: [],
  },
  SICK: {
    tones: [
      { at: 0, duration: 110, freq: 330, type: 'triangle', gain: 0.18 },
      { at: 110, duration: 120, freq: 247, type: 'triangle', gain: 0.18 },
    ],
    noise: [],
  },
  EVOLVE: {
    tones: [
      { at: 0, duration: 45, freq: 392, type: 'square', gain: 0.14 },
      { at: 45, duration: 45, freq: 523, type: 'square', gain: 0.14 },
      { at: 90, duration: 45, freq: 659, type: 'square', gain: 0.14 },
      { at: 135, duration: 45, freq: 784, type: 'square', gain: 0.14 },
      { at: 180, duration: 70, freq: 1047, type: 'square', gain: 0.18 },
    ],
    noise: [],
  },
}

/** 音效总时长（毫秒）。 */
export function soundDuration(name: SoundName): number {
  const recipe = SOUND_RECIPES[name]
  const ends = [
    ...recipe.tones.map((tone) => tone.at + tone.duration),
    ...recipe.noise.map((burst) => burst.at + burst.duration),
  ]
  return ends.length === 0 ? 0 : Math.max(...ends)
}

/** 主音量。8-bit 波形本来就刺耳，压低一点。 */
const MASTER_GAIN = 0.6

/** 起音和收尾各留几毫秒，不然波形被硬切会有"啪"的爆音。 */
const ATTACK_SECONDS = 0.005
const RELEASE_SECONDS = 0.01

declare global {
  interface Window {
    /** Safari 老版本的前缀写法。 */
    webkitAudioContext?: typeof AudioContext
  }
}

let context: AudioContext | null = null
let unavailable = false

/**
 * 取 AudioContext，没有就建一个。
 *
 * 浏览器不支持时只记一次失败，之后所有调用直接返回 null ——
 * 音效是锦上添花，不能因为它让整个操作报错。
 */
function audioContext(): AudioContext | null {
  if (context !== null || unavailable) {
    return context
  }
  try {
    const Ctor = window.AudioContext ?? window.webkitAudioContext
    if (!Ctor) {
      unavailable = true
      return null
    }
    context = new Ctor()
  } catch {
    unavailable = true
    context = null
  }
  return context
}

/** 一段白噪声缓冲，噪声类音效都用它。 */
function noiseBuffer(ctx: AudioContext, seconds: number): AudioBuffer {
  const length = Math.max(1, Math.floor(ctx.sampleRate * seconds))
  const buffer = ctx.createBuffer(1, length, ctx.sampleRate)
  const data = buffer.getChannelData(0)
  for (let i = 0; i < length; i += 1) {
    data[i] = Math.random() * 2 - 1
  }
  return buffer
}

function scheduleRecipe(ctx: AudioContext, recipe: SoundRecipe): void {
  const start = ctx.currentTime
  const master = ctx.createGain()
  master.gain.value = MASTER_GAIN
  master.connect(ctx.destination)

  for (const tone of recipe.tones) {
    const from = start + tone.at / 1000
    const to = from + tone.duration / 1000

    const oscillator = ctx.createOscillator()
    oscillator.type = tone.type
    oscillator.frequency.setValueAtTime(tone.freq, from)

    const envelope = ctx.createGain()
    envelope.gain.setValueAtTime(0, from)
    envelope.gain.linearRampToValueAtTime(tone.gain, from + ATTACK_SECONDS)
    envelope.gain.setValueAtTime(tone.gain, to - RELEASE_SECONDS)
    envelope.gain.linearRampToValueAtTime(0, to)

    oscillator.connect(envelope).connect(master)
    oscillator.start(from)
    oscillator.stop(to)
  }

  for (const burst of recipe.noise) {
    const from = start + burst.at / 1000
    const to = from + burst.duration / 1000

    const source = ctx.createBufferSource()
    source.buffer = noiseBuffer(ctx, burst.duration / 1000)

    const filter = ctx.createBiquadFilter()
    filter.type = 'bandpass'
    filter.frequency.setValueAtTime(burst.center, from)
    filter.Q.value = 1.2

    const envelope = ctx.createGain()
    envelope.gain.setValueAtTime(0, from)
    envelope.gain.linearRampToValueAtTime(burst.gain, from + ATTACK_SECONDS)
    envelope.gain.linearRampToValueAtTime(0, to)

    source.connect(filter).connect(envelope).connect(master)
    source.start(from)
    source.stop(to)
  }
}

/** 操作 → 音效。睡觉和唤醒没有提示音，PRD 2.9 只列了六种。 */
const ACTION_SOUNDS: Partial<Record<PetAction, SoundName>> = {
  FEED: 'FEED',
  PLAY: 'PLAY',
  CLEAN: 'CLEAN',
}

export function soundForAction(action: PetAction): SoundName | null {
  return ACTION_SOUNDS[action] ?? null
}

export interface Audio {
  play(name: SoundName): void
}

export function useAudio(): Audio {
  const ui = useUiStore()

  function play(name: SoundName): void {
    if (ui.soundMuted) {
      return
    }
    try {
      const ctx = audioContext()
      if (ctx === null) {
        return
      }
      // 首次点击时上下文可能是 suspended，恢复它；失败也不影响操作
      if (ctx.state === 'suspended') {
        void ctx.resume().catch(() => undefined)
      }
      scheduleRecipe(ctx, SOUND_RECIPES[name])
    } catch {
      // 静默降级：音效坏了也不能让玩家点不动按钮
    }
  }

  return { play }
}

/** 只给测试用：忘掉已经建好的上下文，让每个用例从干净状态开始。 */
export function resetAudioContextForTest(): void {
  context = null
  unavailable = false
}
