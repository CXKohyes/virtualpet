<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { fetchGameConfig } from '@/api/gameConfig'
import ActionDock from '@/components/ActionDock.vue'
import JournalPanel from '@/components/JournalPanel.vue'
import PetStage from '@/components/PetStage.vue'
import PixelButton from '@/components/PixelButton.vue'
import SettingsModal from '@/components/SettingsModal.vue'
import SpeechBubble from '@/components/SpeechBubble.vue'
import StatusPanel from '@/components/StatusPanel.vue'
import { useIdleSpeech } from '@/composables/useIdleSpeech'
import { ATTRIBUTE_META, FLASH_DURATION_MS, STATUS_LABELS, STATUS_HINTS } from '@/content/messages'
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

/**
 * 离线期间真正发生变化的那几项，变化为 0 的不列出来，免得刷屏。
 *
 * 这里只是把服务端给的差值排排版，不做任何计算。
 */
const offlineChanges = computed(() => {
  const summary = petStore.offlineSummary
  if (!summary) {
    return []
  }
  return ATTRIBUTE_META.map((meta) => ({ label: meta.label, delta: summary.deltas[meta.key] })).filter(
    (change) => change.delta !== 0,
  )
})

/**
 * 升级 / 进化的强调动画（PRD 4.3）。
 *
 * 存成一份自己的状态，而不是直接读 store 里的 levelUpFlash：
 * 那两个标志要等动画放完才由 consumeFlash 清掉，动画时长才有处可放。
 * 进化盖过升级 —— 两个同时发生时只演更隆重的那一个。
 */
const stageFlash = ref<'LEVEL_UP' | 'EVOLVE' | null>(null)
let flashTimer: ReturnType<typeof setTimeout> | null = null

watch(
  () => [petStore.evolvedFlash, petStore.levelUpFlash] as const,
  ([evolved, levelUp]) => {
    if (!evolved && !levelUp) {
      return
    }
    stageFlash.value = evolved ? 'EVOLVE' : 'LEVEL_UP'
    if (flashTimer !== null) {
      clearTimeout(flashTimer)
    }
    flashTimer = setTimeout(() => {
      flashTimer = null
      dismissFlash()
    }, FLASH_DURATION_MS)
  },
)

/** 升级 / 进化的醒目提示文字。 */
const flashText = computed(() => {
  if (stageFlash.value === 'EVOLVE') {
    return '进化了！'
  }
  if (stageFlash.value === 'LEVEL_UP') {
    return '升级了！'
  }
  return ''
})

useIdleSpeech()

onMounted(async () => {
  petStore.startClock()
  // 会话接口已经把宠物（含离线摘要）种进来了，ensureLoaded 此时是空操作；
  // 直接用页面地址进来（没走会话）时它才会真的去拉。
  await petStore.ensureLoaded()
  if (pet.value === null) {
    // 没有宠物就回领养页，避免停留在空界面上
    await router.push({ name: 'onboarding' })
    return
  }
  await petStore.loadJournal()
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
  if (flashTimer !== null) {
    clearTimeout(flashTimer)
    flashTimer = null
  }
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
  stageFlash.value = null
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

      <!-- 回访时优先展示「你不在时发生了什么」（PRD 2.5、4.3） -->
      <section v-if="petStore.offlineSummary" class="home-offline" aria-live="polite">
        <p class="home-offline-title">
          你不在的 {{ petStore.offlineSummary.settledHours }} 小时里：
        </p>
        <p class="home-offline-body">
          <span v-for="change in offlineChanges" :key="change.label" class="home-offline-item">
            {{ change.label }} {{ change.delta > 0 ? '+' : '' }}{{ change.delta }}
          </span>
          <span
            v-if="petStore.offlineSummary.statusAfter !== petStore.offlineSummary.statusBefore"
            class="home-offline-item"
          >
            状态「{{ STATUS_LABELS[petStore.offlineSummary.statusAfter] }}」·
            {{ STATUS_HINTS[petStore.offlineSummary.statusAfter] }}
          </span>
          <span v-if="petStore.offlineSummary.wokeUp" class="home-offline-item">
            睡了 {{ petStore.offlineSummary.sleptHours }} 小时后自己醒了
          </span>
        </p>
        <button
          type="button"
          class="home-offline-close"
          @click="petStore.dismissOfflineSummary()"
        >
          知道了
        </button>
      </section>

      <button
        v-if="flashText"
        type="button"
        class="home-flash"
        :class="stageFlash === 'EVOLVE' ? 'is-evolve' : 'is-level-up'"
        @click="dismissFlash"
      >
        {{ flashText }}（点击关闭）
      </button>

      <div class="home-layout">
        <section class="home-stage-area">
          <SpeechBubble :text="petStore.speech" />
          <PetStage :pet="pet" :acting="petStore.acting" :flash="stageFlash" />
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

/* 升级 / 进化的强调条。刻意留在正常文档流里、不覆盖舞台，
   免得挡住操作按钮（PRD 4.4：动画不遮挡关键操作按钮）。 */
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

.home-flash.is-level-up {
  animation: flash-pop 420ms steps(3, end) 3;
}

.home-flash.is-evolve {
  background: var(--color-warm-orange);
  animation: flash-pop 420ms steps(3, end) 4;
}

@keyframes flash-pop {
  0%,
  100% {
    transform: translate(0, 0);
  }
  50% {
    transform: translate(-3px, -3px);
    box-shadow: 6px 6px 0 var(--border-pixel-color);
  }
}

@media (prefers-reduced-motion: reduce) {
  .home-flash.is-level-up,
  .home-flash.is-evolve {
    animation: none;
  }
}

.home-offline {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs) var(--space-sm);
  align-items: baseline;
  margin-bottom: var(--space-sm);
  padding: var(--space-sm) var(--space-md);
  border: 3px solid var(--border-pixel-color);
  background: var(--color-cream);
  box-shadow: var(--shadow-pixel);
  color: var(--color-ink-green);
}

.home-offline-title {
  margin: 0;
  font-weight: 700;
}

.home-offline-body {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs) var(--space-sm);
  margin: 0;
  font-size: 0.875rem;
}

.home-offline-item {
  padding: 0 var(--space-xs);
  background: var(--color-screen);
}

.home-offline-close {
  margin-left: auto;
  min-height: 32px;
  padding: 0 var(--space-sm);
  border: 2px solid var(--border-pixel-color);
  background: var(--color-warm-orange);
  color: var(--color-ink-green-dark);
  font-family: var(--font-ui);
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
