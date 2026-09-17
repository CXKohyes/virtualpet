<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { fetchGameConfig } from '@/api/gameConfig'
import PixelButton from '@/components/PixelButton.vue'
import { usePetStore } from '@/stores/petStore'

import type { GameConfigSpecies, Species } from '@/types/pet'

/**
 * 领养页（PRD 2.2、4.2）。
 *
 * 三只候选宠物的特性标签来自 `GET /game/config`，不是写死的字符串 ——
 * 数值改了前端自动跟着变，不会出现文档和界面不一致。
 */
const router = useRouter()
const petStore = usePetStore()

const SPECIES_ORDER: Species[] = ['CAT', 'DOG', 'DRAGON']

const SPECIES_NAMES: Record<Species, string> = {
  CAT: '猫',
  DOG: '狗',
  DRAGON: '像素龙',
}

const SPECIES_INTROS: Record<Species, string> = {
  CAT: '灵巧又爱玩，陪它玩的时候心情涨得特别快，也没那么容易弄脏。',
  DOG: '均衡又亲人，照顾得好，健康恢复得比谁都快。',
  DRAGON: '贪吃又强壮，吃饱了特别有精神，精力掉得慢。',
}

const NAME_MAX_LENGTH = 8

const speciesConfig = ref<GameConfigSpecies[]>([])
const selected = ref<Species>('CAT')
const name = ref('')
const submitting = ref(false)

onMounted(async () => {
  try {
    speciesConfig.value = (await fetchGameConfig()).species
  } catch {
    // 配置拉不到不影响领养，只是特性标签不显示
    speciesConfig.value = []
  }
})

/**
 * 把配置里的倍率转成给人看的标签。
 *
 * 只做数值到文案的格式化，不含任何判定 —— 阈值和效果仍然以服务端为准。
 */
function traitChips(code: Species): string[] {
  const found = speciesConfig.value.find((item) => item.code === code)
  if (!found) {
    return []
  }
  const m = found.modifier
  const chips: string[] = []
  if (m.playMoodBonus !== 1) {
    chips.push(`玩耍心情 ${percent(m.playMoodBonus)}`)
  }
  if (m.careGainBonus !== 1) {
    chips.push(`正向照护 ${percent(m.careGainBonus)}`)
  }
  if (m.feedSatietyBonus !== 1) {
    chips.push(`喂食饱食 ${percent(m.feedSatietyBonus)}`)
  }
  if (m.hygieneDecayScale !== 1) {
    chips.push(`清洁衰减 ${percent(m.hygieneDecayScale)}`)
  }
  if (m.energyDecayScale !== 1) {
    chips.push(`精力衰减 ${percent(m.energyDecayScale)}`)
  }
  if (m.healthRecoveryBonus !== 0) {
    chips.push(`健康恢复 +${m.healthRecoveryBonus}/小时`)
  }
  return chips
}

/**
 * 把倍率转成百分比文案。
 *
 * 配置里的字段全是倍率，1 表示不变：1.25 是 +25%，0.75 是 -25%。
 */
function percent(multiplier: number): string {
  const delta = Math.round((multiplier - 1) * 100)
  return `${delta > 0 ? '+' : ''}${delta}%`
}

function validate(): string | null {
  const trimmed = name.value.trim()
  if (trimmed.length === 0) {
    return '先给它起个名字吧'
  }
  if ([...trimmed].length > NAME_MAX_LENGTH) {
    return `名字最多 ${NAME_MAX_LENGTH} 个字`
  }
  return null
}

const localError = ref<string | null>(null)

async function submit(): Promise<void> {
  localError.value = validate()
  if (localError.value !== null) {
    return
  }
  submitting.value = true
  const created = await petStore.adopt(selected.value, name.value.trim())
  submitting.value = false
  if (created) {
    await router.push({ name: 'home' })
  }
}
</script>

<template>
  <main class="onboarding">
    <h1 class="onboarding-title">选择你的伙伴</h1>
    <p class="onboarding-subtitle">挑一只，给它起个名字，然后开始照顾它。</p>

    <fieldset class="species-fieldset">
      <legend class="visually-hidden">候选宠物</legend>
      <label
        v-for="code in SPECIES_ORDER"
        :key="code"
        class="species-card"
        :class="{ 'is-selected': selected === code }"
      >
        <input v-model="selected" class="species-radio" type="radio" name="species" :value="code" />
        <span class="species-name">{{ SPECIES_NAMES[code] }}</span>
        <span class="species-intro">{{ SPECIES_INTROS[code] }}</span>
        <span v-if="traitChips(code).length" class="species-traits">
          <span v-for="chip in traitChips(code)" :key="chip" class="species-trait">{{ chip }}</span>
        </span>
      </label>
    </fieldset>

    <label class="name-field">
      <span class="name-label">给它起个名字（最多 {{ NAME_MAX_LENGTH }} 个字）</span>
      <input
        v-model="name"
        class="name-input"
        type="text"
        :maxlength="NAME_MAX_LENGTH"
        placeholder="例如：咪咪"
        autocomplete="off"
        @keyup.enter="submit"
      />
    </label>

    <p v-if="localError || petStore.error" class="onboarding-error" role="alert">
      {{ localError ?? petStore.error }}
    </p>

    <PixelButton :pending="submitting || petStore.loading" @press="submit">
      {{ submitting ? '正在领养…' : '就是它了' }}
    </PixelButton>
  </main>
</template>

<style scoped>
.onboarding {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
  width: 100%;
  max-width: 720px;
  margin: 0 auto;
  padding: var(--space-lg) var(--space-md);
}

.onboarding-title {
  margin: 0;
  color: var(--color-cream);
  font-size: 1.5rem;
  letter-spacing: 2px;
  text-align: center;
}

.onboarding-subtitle {
  margin: 0 0 var(--space-sm);
  color: var(--color-cream);
  font-size: 0.875rem;
  text-align: center;
}

.species-fieldset {
  display: grid;
  gap: var(--space-sm);
  margin: 0;
  padding: 0;
  border: none;
}

@media (min-width: 768px) {
  .species-fieldset {
    grid-template-columns: repeat(3, 1fr);
  }
}

.species-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  padding: var(--space-md);
  border: var(--border-pixel);
  background: var(--color-screen);
  box-shadow: var(--shadow-pixel);
  color: var(--color-ink-green);
  cursor: pointer;
}

.species-card.is-selected {
  outline: 4px solid var(--color-warm-orange);
  outline-offset: 2px;
}

.species-radio {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}

.species-card:has(.species-radio:focus-visible) {
  outline: 3px solid var(--color-sky-blue);
  outline-offset: 2px;
}

.species-name {
  font-size: 1.125rem;
  font-weight: 700;
}

.species-intro {
  font-size: 0.8125rem;
  line-height: 1.6;
  color: #4a5c50;
}

.species-traits {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
  margin-top: auto;
}

.species-trait {
  padding: 2px var(--space-xs);
  border: 2px solid var(--border-pixel-color);
  background: var(--color-cream);
  font-size: 0.75rem;
}

.name-field {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.name-label {
  color: var(--color-cream);
  font-size: 0.875rem;
}

.name-input {
  min-height: 44px;
  padding: var(--space-sm);
  border: var(--border-pixel);
  background: var(--color-screen);
  color: var(--color-ink-green);
  font-family: var(--font-ui);
  font-size: 1rem;
}

.name-input:focus-visible {
  outline: 3px solid var(--color-sky-blue);
  outline-offset: 2px;
}

.onboarding-error {
  margin: 0;
  padding: var(--space-sm);
  border: 3px solid var(--color-coral);
  background: var(--color-screen);
  color: var(--color-coral);
  font-size: 0.875rem;
  font-weight: 700;
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
</style>
