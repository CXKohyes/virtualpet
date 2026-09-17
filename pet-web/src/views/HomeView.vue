<script setup lang="ts">
/**
 * 主界面占位页。
 * 批次 0 只验证路由、Pinia 和构建链路可用，状态区、舞台、操作区和日志区在批次 3 实现。
 */
const appName = '像素宠物屋'
const stageLabel = '批次 0 · 工程骨架'
</script>

<template>
  <main class="home-view">
    <section class="home-panel">
      <h1 class="home-title">{{ appName }}</h1>
      <p class="home-stage">{{ stageLabel }}</p>
      <p class="home-hint">前端骨架已就绪，宠物养成功能将在后续批次实现。</p>
    </section>
  </main>
</template>

<style scoped>
.home-view {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: var(--space-md);
}

.home-panel {
  width: 100%;
  max-width: 480px;
  padding: var(--space-lg);
  background: var(--color-screen);
  border: var(--border-pixel);
  box-shadow: var(--shadow-pixel);
  text-align: center;
}

.home-title {
  margin: 0 0 var(--space-sm);
  color: var(--color-ink-green);
  font-size: 1.5rem;
  letter-spacing: 2px;
}

.home-stage {
  margin: 0 0 var(--space-md);
  color: var(--color-warm-orange);
  font-size: 0.875rem;
}

.home-hint {
  margin: 0;
  color: var(--color-ink-green);
  font-size: 0.875rem;
  line-height: 1.6;
}
</style>
