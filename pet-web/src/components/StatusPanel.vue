<script setup lang="ts">
import { computed } from 'vue'

import { ATTRIBUTE_META, STATUS_HINTS, STATUS_LABELS } from '@/content/messages'

import type { Pet } from '@/types/pet'

/**
 * 五项状态条。
 *
 * 低值除了变色还会显示文字警告：状态不能只靠颜色区分（PRD 4.4）。
 * 数值全部来自服务端，前端不做任何推算。
 */
const props = defineProps<{
  pet: Pet
}>()

/** 低于该值算"需要注意"，只用于加视觉警告，不参与任何判定。 */
const WARN_AT = 25

const bars = computed(() =>
  ATTRIBUTE_META.map((meta) => {
    const value = props.pet[meta.key]
    return {
      key: meta.key,
      label: meta.label,
      value,
      warning: value < WARN_AT,
    }
  }),
)
</script>

<template>
  <section class="status-panel" aria-label="宠物状态">
    <header class="status-header">
      <h2 class="status-title">{{ pet.name }}</h2>
      <p class="status-summary">
        <span class="status-badge">{{ STATUS_LABELS[pet.status] }}</span>
        <span class="status-hint">{{ STATUS_HINTS[pet.status] }}</span>
      </p>
      <p class="status-level">Lv.{{ pet.level }} · 经验 {{ pet.exp }}</p>
    </header>

    <ul class="status-list">
      <li v-for="bar in bars" :key="bar.key" class="status-row" :class="{ 'is-low': bar.warning }">
        <span class="status-label">{{ bar.label }}</span>
        <span
          class="status-track"
          role="meter"
          :aria-label="bar.label"
          :aria-valuenow="bar.value"
          aria-valuemin="0"
          aria-valuemax="100"
        >
          <span class="status-fill" :style="{ width: `${bar.value}%` }" />
        </span>
        <span class="status-value">{{ bar.value }}</span>
        <span v-if="bar.warning" class="status-warning">低</span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.status-panel {
  padding: var(--space-md);
  background: var(--color-screen);
  border: var(--border-pixel);
  box-shadow: var(--shadow-pixel);
  color: var(--color-ink-green);
}

.status-header {
  margin-bottom: var(--space-sm);
}

.status-title {
  margin: 0;
  font-size: 1.25rem;
  letter-spacing: 1px;
}

.status-summary {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  align-items: center;
  margin: var(--space-xs) 0;
  font-size: 0.875rem;
}

.status-badge {
  padding: 2px var(--space-sm);
  border: 2px solid var(--border-pixel-color);
  background: var(--color-warm-orange);
  color: var(--color-ink-green-dark);
  font-weight: 700;
}

.status-hint {
  color: #4a5c50;
}

.status-level {
  margin: 0;
  font-family: var(--font-mono);
  font-size: 0.8125rem;
}

.status-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.status-row {
  display: grid;
  grid-template-columns: 3.5em 1fr 2.5em 1.5em;
  gap: var(--space-sm);
  align-items: center;
  margin-bottom: var(--space-xs);
  font-size: 0.875rem;
}

.status-label {
  white-space: nowrap;
}

.status-track {
  height: 14px;
  border: 2px solid var(--border-pixel-color);
  background: #d9cfb4;
}

.status-fill {
  display: block;
  height: 100%;
  background: var(--color-ink-green);
  transition: width 240ms steps(6, end);
}

.status-row.is-low .status-fill {
  background: var(--color-coral);
}

.status-value {
  font-family: var(--font-mono);
  text-align: right;
}

.status-warning {
  color: var(--color-coral);
  font-size: 0.75rem;
  font-weight: 700;
}

@media (prefers-reduced-motion: reduce) {
  .status-fill {
    transition: none;
  }
}
</style>
