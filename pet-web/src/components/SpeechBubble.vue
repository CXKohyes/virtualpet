<script setup lang="ts">
/**
 * 宠物上方的性格气泡。
 *
 * 批次 3 只展示操作反馈的短句；按物种和状态区分的完整台词库在批次 4。
 */
defineProps<{
  text: string
}>()
</script>

<template>
  <Transition name="bubble">
    <p v-if="text" class="speech-bubble" role="status">{{ text }}</p>
  </Transition>
</template>

<style scoped>
.speech-bubble {
  position: relative;
  max-width: 100%;
  margin: 0 auto var(--space-sm);
  padding: var(--space-sm) var(--space-md);
  border: 3px solid var(--border-pixel-color);
  background: var(--color-screen);
  box-shadow: var(--shadow-pixel);
  color: var(--color-ink-green);
  font-size: 0.9375rem;
  text-align: center;
}

/* 气泡下方的小尖角 */
.speech-bubble::after {
  content: '';
  position: absolute;
  bottom: -12px;
  left: 50%;
  width: 0;
  height: 0;
  margin-left: -6px;
  border-left: 6px solid transparent;
  border-right: 6px solid transparent;
  border-top: 12px solid var(--border-pixel-color);
}

.bubble-enter-active,
.bubble-leave-active {
  transition: opacity 120ms linear;
}

.bubble-enter-from,
.bubble-leave-to {
  opacity: 0;
}
</style>
