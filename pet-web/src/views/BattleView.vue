<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import BattleTimeline from '@/components/BattleTimeline.vue'
import PixelButton from '@/components/PixelButton.vue'
import { useBattleNotifications } from '@/composables/useBattleNotifications'
import { SPECIES_NAMES } from '@/content/battleText'
import { useBattleStore } from '@/stores/battleStore'

import type { BattleSide } from '@/types/battle'

/**
 * 对战页（PRD 2.11）。
 *
 * 三块：我的好友码、用好友码发起挑战、最近对战与战报。
 *
 * 战斗本身在服务端，这里不做任何数值计算和胜负判断 ——
 * 界面上的每一个数字都直接来自战报。
 */
const router = useRouter()
const battleStore = useBattleStore()

const friendCodeInput = ref('')
const copied = ref(false)

/**
 * 收到"战报就绪"通知时刷新。通知是广播，归属由 store 查自己的列表判断。
 *
 * 解构出来而不是留着 `notifications.connected` 这种写法：解构后 `connected`
 * 是顶层 ref，模板里会自动解包；挂在对象上就得写 `.value`，容易看漏。
 */
const { connected: notificationsConnected, watch: watchBattleNotifications } =
  useBattleNotifications((notification) => {
    void battleStore.onBattleFinished(notification.battleId)
  })

const mySideLabel = computed(() => sideLabel(battleStore.current?.viewer))

function sideLabel(side: BattleSide | undefined): string {
  if (!side) {
    return ''
  }
  return side === 'CHALLENGER' ? '你发起的挑战' : '别人挑战了你'
}

onMounted(async () => {
  // 先订阅再拉数据：订阅是异步的，早订上少漏通知
  void watchBattleNotifications()
  await Promise.all([battleStore.loadFriendCode(), battleStore.loadRecent()])
})

async function onChallenge(): Promise<void> {
  const code = friendCodeInput.value.trim()
  if (code === '') {
    return
  }
  const ok = await battleStore.challenge(code)
  if (ok) {
    friendCodeInput.value = ''
  }
}

async function copyFriendCode(): Promise<void> {
  try {
    await navigator.clipboard.writeText(battleStore.friendCode)
    copied.value = true
    setTimeout(() => {
      copied.value = false
    }, 1500)
  } catch {
    // 剪贴板权限被拒时用户还是能手动选中，不用报错打断
    copied.value = false
  }
}
</script>

<template>
  <main class="battle">
    <header class="battle-header">
      <h1 class="battle-title">宠物对战</h1>
      <div class="battle-header-right">
        <span class="battle-conn" :class="{ 'is-online': notificationsConnected }">
          {{ notificationsConnected ? '实时通知已连接' : '实时通知未连接' }}
        </span>
        <PixelButton hint="回到宠物" @press="router.push({ name: 'home' })">返回</PixelButton>
      </div>
    </header>

    <p v-if="battleStore.error" class="battle-alert" role="alert">{{ battleStore.error }}</p>

    <div class="battle-layout">
      <section class="battle-panel">
        <h2 class="panel-title">我的好友码</h2>
        <p class="panel-hint">把它发给朋友，对方就能来挑战你的宠物。</p>
        <div class="code-row">
          <output class="code-value">{{ battleStore.friendCode || '……' }}</output>
          <PixelButton :hint="copied ? '已复制' : '复制好友码'" @press="copyFriendCode">
            {{ copied ? '已复制' : '复制' }}
          </PixelButton>
        </div>
      </section>

      <section class="battle-panel">
        <h2 class="panel-title">发起挑战</h2>
        <p class="panel-hint">输入对方的好友码，服务端会立刻算完并给出战报。</p>
        <form class="challenge-form" @submit.prevent="onChallenge">
          <label class="challenge-field">
            <span class="visually-hidden">对方好友码</span>
            <input
              v-model="friendCodeInput"
              class="challenge-input"
              type="text"
              maxlength="12"
              placeholder="例如 K7M2PQXF"
              autocomplete="off"
            />
          </label>
          <PixelButton :pending="battleStore.challenging" @press="onChallenge">
            {{ battleStore.challenging ? '战斗中…' : '开打' }}
          </PixelButton>
        </form>
      </section>

      <section v-if="battleStore.current" class="battle-panel battle-panel--wide">
        <h2 class="panel-title">
          战报 · {{ mySideLabel }}
        </h2>
        <BattleTimeline :battle="battleStore.current" />
      </section>

      <section class="battle-panel battle-panel--wide">
        <h2 class="panel-title">最近对战</h2>
        <p v-if="battleStore.recent.length === 0" class="panel-hint">
          还没有对战记录。用好友码约一场吧。
        </p>
        <ul v-else class="recent-list">
          <li v-for="item in battleStore.recent" :key="item.id" class="recent-item">
            <button
              type="button"
              class="recent-button"
              :class="{ 'is-selected': battleStore.current?.id === item.id }"
              @click="battleStore.open(item.id)"
            >
              <span class="recent-opponent">
                对手 {{ item.opponentName }}（{{ SPECIES_NAMES[item.opponentSpecies] }}）
              </span>
              <span
                class="recent-result"
                :class="{
                  'is-win': item.winner === item.viewer,
                  'is-draw': item.winner === null,
                }"
              >
                {{ item.winner === null ? '平局' : item.winner === item.viewer ? '胜' : '负' }}
              </span>
              <span class="recent-meta">
                {{ item.rounds }} 回合 · {{ sideLabel(item.viewer) }}
              </span>
            </button>
          </li>
        </ul>
      </section>
    </div>
  </main>
</template>

<style scoped>
.battle {
  width: 100%;
  max-width: 1080px;
  margin: 0 auto;
  padding: var(--space-md);
  color: var(--color-cream);
}

.battle-header {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-md);
}

.battle-title {
  margin: 0;
  font-size: 1.375rem;
  letter-spacing: 2px;
}

.battle-header-right {
  display: flex;
  gap: var(--space-sm);
  align-items: center;
}

.battle-conn {
  font-size: 0.75rem;
  opacity: 0.7;
}

.battle-conn.is-online {
  color: var(--color-warm-orange);
  opacity: 1;
}

.battle-alert {
  margin: 0 0 var(--space-sm);
  padding: var(--space-sm);
  border: 3px solid var(--color-coral);
  background: var(--color-screen);
  color: var(--color-coral);
  font-size: 0.875rem;
  font-weight: 700;
}

.battle-layout {
  display: grid;
  gap: var(--space-md);
}

.battle-panel {
  padding: var(--space-md);
  background: var(--color-cream);
  border: var(--border-pixel);
  box-shadow: var(--shadow-pixel);
  color: var(--color-ink-green);
}

.panel-title {
  margin: 0 0 var(--space-xs);
  font-size: 1rem;
}

.panel-hint {
  margin: 0 0 var(--space-sm);
  font-size: 0.8125rem;
  color: #4a5c50;
}

.code-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  align-items: center;
}

.code-value {
  flex: 1 1 auto;
  min-width: 10em;
  padding: var(--space-sm);
  border: 3px solid var(--border-pixel-color);
  background: var(--color-screen);
  font-family: var(--font-mono);
  font-size: 1.25rem;
  font-weight: 700;
  letter-spacing: 3px;
  text-align: center;
}

.challenge-form {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  align-items: stretch;
}

.challenge-field {
  flex: 1 1 12em;
}

.challenge-input {
  width: 100%;
  min-height: 44px;
  padding: 0 var(--space-sm);
  border: 3px solid var(--border-pixel-color);
  background: var(--color-screen);
  color: var(--color-ink-green);
  font-family: var(--font-mono);
  font-size: 1rem;
  letter-spacing: 2px;
  text-transform: uppercase;
}

.recent-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  margin: 0;
  padding: 0;
  list-style: none;
}

.recent-button {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 2px var(--space-sm);
  width: 100%;
  min-height: 44px;
  padding: var(--space-sm);
  border: 2px solid var(--border-pixel-color);
  background: var(--color-screen);
  color: var(--color-ink-green);
  font-family: var(--font-ui);
  text-align: left;
  cursor: pointer;
}

.recent-button.is-selected {
  background: var(--color-warm-orange);
}

.recent-opponent {
  font-weight: 700;
}

.recent-result {
  font-weight: 700;
  color: var(--color-coral);
}

.recent-result.is-win {
  color: #2f7d4f;
}

.recent-result.is-draw {
  color: #4a5c50;
}

.recent-meta {
  grid-column: 1 / -1;
  font-size: 0.75rem;
  color: #4a5c50;
}

@media (min-width: 720px) {
  .battle-layout {
    grid-template-columns: 1fr 1fr;
    align-items: start;
  }

  .battle-panel--wide {
    grid-column: 1 / -1;
  }
}
</style>
