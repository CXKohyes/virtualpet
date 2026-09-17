<script setup lang="ts">
import { computed } from 'vue'

import PixelButton from './PixelButton.vue'

import { ACTION_LABELS } from '@/content/messages'

import type { PetAction } from '@/types/pet'

/**
 * 四个主操作按钮（PRD 4.2）。
 *
 * 第四个按钮在「睡觉」和「唤醒」之间切换，由服务端返回的 sleepingSince 决定。
 *
 * **这里只根据冷却和请求中状态禁用按钮**，不判断"饿不饿""脏不脏" ——
 * 那些门槛在服务端，前端重复一份就等于把规则抄了两遍。
 * 点下去之后服务端会给出明确原因（例如「它现在不饿」），由上方提示区展示。
 */
const props = defineProps<{
  /** 正在执行的操作，非 null 时所有按钮都禁用。 */
  acting: PetAction | null
  sleeping: boolean
  /** 各操作剩余冷却秒数。 */
  cooldowns: Partial<Record<PetAction, number>>
  /** 来自游戏配置的使用条件文字，只用于悬停提示。 */
  requirements?: Partial<Record<PetAction, string>>
}>()

const emit = defineEmits<{ act: [action: PetAction] }>()

const buttons = computed<PetAction[]>(() => [
  'FEED',
  'PLAY',
  'CLEAN',
  props.sleeping ? 'WAKE' : 'SLEEP',
])

function labelFor(action: PetAction): string {
  const remaining = props.cooldowns[action] ?? 0
  return remaining > 0 ? `${ACTION_LABELS[action]}（${remaining}s）` : ACTION_LABELS[action]
}

function isDisabled(action: PetAction): boolean {
  return props.acting !== null || (props.cooldowns[action] ?? 0) > 0
}

function hintFor(action: PetAction): string {
  if (props.acting !== null) {
    return '正在处理上一次操作…'
  }
  if ((props.cooldowns[action] ?? 0) > 0) {
    return `${ACTION_LABELS[action]}还在冷却中`
  }
  return props.requirements?.[action] ?? ''
}
</script>

<template>
  <section class="action-dock" aria-label="照护操作">
    <PixelButton
      v-for="action in buttons"
      :key="action"
      :disabled="isDisabled(action)"
      :pending="acting === action"
      :hint="hintFor(action)"
      @press="emit('act', action)"
    >
      {{ labelFor(action) }}
    </PixelButton>
  </section>
</template>

<style scoped>
.action-dock {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-sm);
}

@media (min-width: 480px) {
  .action-dock {
    grid-template-columns: repeat(4, 1fr);
  }
}
</style>
