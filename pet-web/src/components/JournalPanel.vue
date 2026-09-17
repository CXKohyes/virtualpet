<script setup lang="ts">
import { ACTION_LABELS } from '@/content/messages'

import type { JournalEntry } from '@/stores/petStore'

/**
 * 照护日志（PRD 4.2）。
 *
 * 批次 3 只记录本次打开页面之后做过的操作；跨会话的历史和离线变化摘要
 * 需要后端补一个日志查询接口，留到后续批次。
 */
defineProps<{
  entries: JournalEntry[]
}>()

function formatTime(timestamp: number): string {
  return new Date(timestamp).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
  })
}
</script>

<template>
  <section class="journal-panel" aria-label="照护日志">
    <h2 class="journal-title">照护日志</h2>

    <p v-if="entries.length === 0" class="journal-empty">还没有记录，去照护一下它吧。</p>

    <ol v-else class="journal-list">
      <li v-for="entry in entries" :key="entry.id" class="journal-item">
        <span class="journal-time">{{ formatTime(entry.at) }}</span>
        <span class="journal-action">{{ ACTION_LABELS[entry.action] ?? entry.action }}</span>
        <span class="journal-message">{{ entry.message }}</span>
        <span v-if="entry.xpGained > 0" class="journal-xp">+{{ entry.xpGained }} 经验</span>
        <span v-if="entry.levelUp" class="journal-tag">升级！</span>
        <span v-if="entry.evolved" class="journal-tag journal-tag--evolve">进化！</span>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.journal-panel {
  padding: var(--space-md);
  background: var(--color-screen);
  border: var(--border-pixel);
  box-shadow: var(--shadow-pixel);
  color: var(--color-ink-green);
}

.journal-title {
  margin: 0 0 var(--space-sm);
  font-size: 1rem;
  letter-spacing: 1px;
}

.journal-empty {
  margin: 0;
  color: #4a5c50;
  font-size: 0.875rem;
}

.journal-list {
  max-height: 220px;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}

.journal-item {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs) var(--space-sm);
  align-items: baseline;
  padding: var(--space-xs) 0;
  border-bottom: 1px dashed rgba(20, 38, 26, 0.25);
  font-size: 0.8125rem;
}

.journal-item:last-child {
  border-bottom: none;
}

.journal-time {
  font-family: var(--font-mono);
  color: #4a5c50;
}

.journal-action {
  font-weight: 700;
}

.journal-xp {
  margin-left: auto;
  font-family: var(--font-mono);
}

.journal-tag {
  padding: 0 var(--space-xs);
  background: var(--color-warm-orange);
  font-weight: 700;
}

.journal-tag--evolve {
  background: var(--color-sky-blue);
}
</style>
