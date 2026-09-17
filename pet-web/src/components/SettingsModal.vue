<script setup lang="ts">
import { ref } from 'vue'

import PixelButton from './PixelButton.vue'

/**
 * 设置弹窗（PRD 2.9、4.2）。
 *
 * 「重新领养」删除当前存档且不可恢复，所以要求二次确认（PRD 2.1）。
 * 用页面内的确认条而不是 window.confirm，既好测也不打断像素风格。
 */
defineProps<{
  soundMuted: boolean
  /** 正在重置中，按钮进入按压态。 */
  resetting: boolean
}>()

const emit = defineEmits<{
  close: []
  toggleSound: []
  reset: []
}>()

const confirming = ref(false)

function onResetClick(): void {
  if (!confirming.value) {
    confirming.value = true
    return
  }
  confirming.value = false
  emit('reset')
}

function cancelConfirm(): void {
  confirming.value = false
}
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('close')">
    <section class="modal" role="dialog" aria-modal="true" aria-labelledby="settings-title">
      <header class="modal-header">
        <h2 id="settings-title" class="modal-title">设置</h2>
        <button type="button" class="modal-close" aria-label="关闭设置" @click="emit('close')">
          ×
        </button>
      </header>

      <div class="modal-row">
        <span class="modal-label">音效</span>
        <PixelButton :hint="soundMuted ? '当前静音' : '当前开启'" @press="emit('toggleSound')">
          {{ soundMuted ? '已静音' : '开启中' }}
        </PixelButton>
      </div>
      <p class="modal-note">音效开关会记在本机。合成音效在下一批次接入。</p>

      <div class="modal-row modal-row--danger">
        <span class="modal-label">重新领养</span>
        <PixelButton :pending="resetting" @press="onResetClick">
          {{ confirming ? '确认删除？' : '重新领养' }}
        </PixelButton>
      </div>
      <p v-if="confirming" class="modal-warning">
        确认后会删除当前宠物和它的全部照护记录，且无法恢复。
        <button type="button" class="modal-link" @click="cancelConfirm">取消</button>
      </p>
      <p v-else class="modal-note">删除当前宠物和日志，回到领养页。</p>

      <footer class="modal-footer">像素宠物屋 · v0.0.1</footer>
    </section>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-md);
  background: rgba(20, 38, 26, 0.72);
  z-index: 10;
}

.modal {
  width: 100%;
  max-width: 380px;
  padding: var(--space-md);
  background: var(--color-screen);
  border: var(--border-pixel);
  box-shadow: var(--shadow-pixel);
  color: var(--color-ink-green);
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-md);
}

.modal-title {
  margin: 0;
  font-size: 1.125rem;
  letter-spacing: 1px;
}

.modal-close {
  min-width: 32px;
  min-height: 32px;
  border: 2px solid var(--border-pixel-color);
  background: var(--color-cream);
  color: var(--color-ink-green);
  font-size: 1.125rem;
  line-height: 1;
  cursor: pointer;
}

.modal-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
  margin-bottom: var(--space-xs);
}

.modal-row--danger {
  margin-top: var(--space-md);
}

.modal-label {
  font-weight: 700;
}

.modal-note,
.modal-warning {
  margin: 0 0 var(--space-sm);
  font-size: 0.8125rem;
  color: #4a5c50;
}

.modal-warning {
  color: var(--color-coral);
  font-weight: 700;
}

.modal-link {
  margin-left: var(--space-sm);
  border: none;
  background: none;
  color: var(--color-sky-blue);
  text-decoration: underline;
  cursor: pointer;
}

.modal-footer {
  margin-top: var(--space-md);
  padding-top: var(--space-sm);
  border-top: 1px dashed rgba(20, 38, 26, 0.25);
  font-family: var(--font-mono);
  font-size: 0.75rem;
  color: #4a5c50;
  text-align: right;
}
</style>
