<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { fetchGameConfig } from '@/api/gameConfig'
import ActionDock from '@/components/ActionDock.vue'
import JournalPanel from '@/components/JournalPanel.vue'
import PetStage from '@/components/PetStage.vue'
import PixelButton from '@/components/PixelButton.vue'
import SettingsModal from '@/components/SettingsModal.vue'
import SpeechBubble from '@/components/SpeechBubble.vue'
import StatusPanel from '@/components/StatusPanel.vue'
import { STATUS_LABELS } from '@/content/messages'
import { usePetStore } from '@/stores/petStore'
import { useUiStore } from '@/stores/uiStore'

import type { PetAction } from '@/types/pet'

/**
 * 主界面（PRD 4.2）。
 *
 * 手机单列：状态区 → 舞台 → 操作区 → 日志区；
 * 桌面双栏：左侧舞台，右侧状态、日志和设置。
 */
const router = useRouter()
const petStore = usePetStore()
const uiStore = useUiStore()

/** 各操作的使用条件文字，来自游戏配置，只用于按钮悬停提示。 */
const requirements = ref<Partial<Record<PetAction, string>>>({})
const resetting = ref(false)

const pet = computed(() => petStore.pet)

const serverTimeText = computed(() =>
  new Date(petStore.serverNowMs).toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }),
)

/** 升级 / 进化的醒目提示（PRD 4.3）。 */
const flashText = computed(() => {
  if (petStore.evolvedFlash) {
    return '进化了！'
  }
  if (petStore.levelUpFlash) {
    return '升级了！'
  }
  return ''
})

onMounted(async () => {
  petStore.startClock()
  // 守卫通常已经拉过了，ensureLoaded 是幂等的
  await petStore.ensureLoaded()
  if (pet.value === null) {
    // 没有宠物就回领养页，避免停留在空界面上
    await router.push({ name: 'onboarding' })
    return
  }
  try {
    const config = await fetchGameConfig()
    requirements.value = Object.fromEntries(
      config.actions.map((action) => [action.code, action.requirement]),
    ) as Partial<Record<PetAction, string>>
  } catch {
    // 配置只是提示文案，拿不到就算了
    requirements.value = {}
  }
})

onUnmounted(() => {
  petStore.stopClock()
})

async function onAction(action: PetAction): Promise<void> {
  await petStore.act(action)
}

async function onReset(): Promise<void> {
  resetting.value = true
  const done = await petStore.reset()
  resetting.value = false
  if (done) {
    uiStore.closeSettings()
    await router.push({ name: 'onboarding' })
  }
}

function dismissFlash(): void {
  petStore.consumeFlash()
}
</script>

<template>
  <main class="home">
    <template v-if="pet">
      <header class="home-header">
        <div>
          <h1 class="home-title">{{ pet.name }}</h1>
          <p class="home-meta">
            Lv.{{ pet.level }} · {{ STATUS_LABELS[pet.status] }}
          </p>
        </div>
        <div class="home-header-right">
          <span class="home-clock">服务器时间 {{ serverTimeText }}</span>
          <PixelButton hint="打开设置" @press="uiStore.openSettings()">设置</PixelButton>
        </div>
      </header>

      <p v-if="petStore.error" class="home-alert" role="alert">{{ petStore.error }}</p>

      <button
        v-if="flashText"
        type="button"
        class="home-flash"
        @click="dismissFlash"
      >
        {{ flashText }}（点击关闭）
      </button>

      <div class="home-layout">
        <section class="home-stage-area">
          <SpeechBubble :text="petStore.speech" />
          <PetStage :pet="pet" :acting="petStore.acting" />
          <ActionDock
            :acting="petStore.acting"
            :sleeping="petStore.sleeping"
            :cooldowns="petStore.cooldownSeconds"
            :requirements="requirements"
            @act="onAction"
          />
        </section>

        <aside class="home-side-area">
          <StatusPanel :pet="pet" />
          <JournalPanel :entries="petStore.journal" />
        </aside>
      </div>

      <SettingsModal
        v-if="uiStore.settingsOpen"
        :sound-muted="uiStore.soundMuted"
        :resetting="resetting"
        @close="uiStore.closeSettings()"
        @toggle-sound="uiStore.toggleSound()"
        @reset="onReset"
      />
    </template>

    <p v-else-if="petStore.loading" class="home-loading">正在读取存档…</p>
  </main>
</template>

<style scoped>
.home {
  width: 100%;
  max-width: 1080px;
  margin: 0 auto;
  padding: var(--space-md);
}

.home-header {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-md);
  color: var(--color-cream);
}

.home-title {
  margin: 0;
  font-size: 1.375rem;
  letter-spacing: 2px;
}

.home-meta {
  margin: 0;
  font-size: 0.875rem;
}

.home-header-right {
  display: flex;
  gap: var(--space-sm);
  align-items: center;
}

.home-clock {
  font-family: var(--font-mono);
  font-size: 0.75rem;
  opacity: 0.85;
}

.home-alert {
  margin: 0 0 var(--space-sm);
  padding: var(--space-sm);
  border: 3px solid var(--color-coral);
  background: var(--color-screen);
  color: var(--color-coral);
  font-size: 0.875rem;
  font-weight: 700;
}

.home-flash {
  width: 100%;
  margin-bottom: var(--space-sm);
  padding: var(--space-sm);
  border: 3px solid var(--border-pixel-color);
  background: var(--color-sky-blue);
  box-shadow: var(--shadow-pixel);
  color: var(--color-ink-green-dark);
  font-family: var(--font-ui);
  font-size: 1rem;
  font-weight: 700;
  cursor: pointer;
}

/* 手机单列：舞台 + 操作在上，状态和日志在下 */
.home-layout {
  display: grid;
  gap: var(--space-md);
}

.home-stage-area,
.home-side-area {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

/* 桌面双栏：左边舞台和操作，右边状态和日志。
   断点取 960px 而不是 768px：768px 时两栏会把左栏压到 300px 左右，
   四个操作按钮每个只剩约 69px，装不下"喂食"两个字就会被折成竖排。
   960px 是实测出来的下限，不是随手取整。 */
@media (min-width: 960px) {
  .home-layout {
    grid-template-columns: minmax(0, 1fr) minmax(320px, 420px);
    align-items: start;
  }
}

.home-loading {
  color: var(--color-cream);
  text-align: center;
}
</style>
