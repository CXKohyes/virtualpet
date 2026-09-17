import { defineStore } from 'pinia'
import { ref } from 'vue'

import { readStorage, writeStorage } from '@/api/client'

const SOUND_STORAGE_KEY = 'virtual-pet.sound-muted'

/**
 * 界面状态：音效开关和弹窗（TECH_DESIGN 7.1）。
 *
 * 音效开关会持久化到本地。真正的音频合成在批次 4 的 `useAudio` 里实现，
 * 现在这个开关只是把用户的选择记下来。
 */
export const useUiStore = defineStore('ui', () => {
  const soundMuted = ref(readStorage(SOUND_STORAGE_KEY) === 'true')
  const settingsOpen = ref(false)

  function toggleSound(): void {
    soundMuted.value = !soundMuted.value
    writeStorage(SOUND_STORAGE_KEY, String(soundMuted.value))
  }

  function openSettings(): void {
    settingsOpen.value = true
  }

  function closeSettings(): void {
    settingsOpen.value = false
  }

  return { soundMuted, settingsOpen, toggleSound, openSettings, closeSettings }
})
