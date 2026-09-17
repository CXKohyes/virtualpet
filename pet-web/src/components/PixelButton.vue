<script setup lang="ts">
/**
 * 像素风格的按钮。
 *
 * 按压反馈是纯 CSS 的，点击瞬间就有反馈，不等网络返回（PRD 4.3）。
 * 触控区域不小于 44px（PRD 2.10）。
 */
withDefaults(
  defineProps<{
    /** 禁用（例如冷却中）。 */
    disabled?: boolean
    /** 请求进行中，按钮进入按压态。 */
    pending?: boolean
    /** 悬停提示，用来展示禁用原因或使用条件。 */
    hint?: string
  }>(),
  { disabled: false, pending: false, hint: '' },
)

const emit = defineEmits<{ press: [] }>()
</script>

<template>
  <button
    type="button"
    class="pixel-button"
    :class="{ 'is-pending': pending }"
    :disabled="disabled"
    :title="hint || undefined"
    @click="emit('press')"
  >
    <slot />
  </button>
</template>

<style scoped>
.pixel-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  padding: var(--space-sm) var(--space-md);
  border: 3px solid var(--border-pixel-color);
  background: var(--color-warm-orange);
  box-shadow: var(--shadow-pixel);
  color: var(--color-ink-green-dark);
  font-family: var(--font-ui);
  font-size: 1rem;
  font-weight: 700;
  letter-spacing: 1px;
  cursor: pointer;
  transition: transform 80ms steps(2), box-shadow 80ms steps(2), filter 80ms linear;
}

.pixel-button:disabled {
  cursor: not-allowed;
  filter: grayscale(0.7) brightness(0.85);
}

/* 请求进行中虽然也是 disabled（防止连点），但要读起来像"正在处理"而不是"不可用"，
   所以取消灰度，只保留塌陷的按压态 */
.pixel-button.is-pending:disabled {
  cursor: progress;
  filter: none;
}

/* 按压反馈：按下去就立刻塌陷，不需要等接口 */
.pixel-button:not(:disabled):active,
.pixel-button.is-pending {
  transform: translate(4px, 4px);
  box-shadow: 0 0 0 var(--border-pixel-color);
}

.pixel-button:not(:disabled):focus-visible {
  outline: 3px solid var(--color-sky-blue);
  outline-offset: 2px;
}

@media (prefers-reduced-motion: reduce) {
  .pixel-button {
    transition: none;
  }
}
</style>
