<script setup lang="ts">
import { computed } from 'vue'

import { OUTCOME_LABELS, SPECIES_NAMES } from '@/content/battleText'

import type { Battle, BattleEvent } from '@/types/battle'

/**
 * 逐回合战报（PRD 2.11）。
 *
 * **这里不做任何计算。** 每一行显示什么、谁掉了多少血、是不是暴击，
 * 全是服务端算好放在战报里的。前端连"这一下打掉了几点"都不推 ——
 * 推一次就等于把战斗规则抄进了界面，迟早跟服务端对不上。
 *
 * 血条宽度用的是服务端给的两个绝对血量除以服务端给的最大生命，
 * 只是把数字画成条，没有规则参与。
 */
const props = defineProps<{
  battle: Battle
}>()

/** 把每一回合的两个事件拆成"挑战方做了什么 / 被挑战方做了什么"。 */
interface RoundView {
  round: number
  challengerEvents: BattleEvent[]
  defenderEvents: BattleEvent[]
  challengerHp: number
  defenderHp: number
}

const rounds = computed<RoundView[]>(() =>
  props.battle.timeline.map((entry) => {
    const last = entry.events[entry.events.length - 1]
    return {
      round: entry.round,
      challengerEvents: entry.events.filter((event) => event.actor === 'CHALLENGER'),
      defenderEvents: entry.events.filter((event) => event.actor === 'DEFENDER'),
      challengerHp: last?.challengerHpAfter ?? props.battle.challenger.maxHp,
      defenderHp: last?.defenderHpAfter ?? props.battle.defender.maxHp,
    }
  }),
)

function hpPercent(hp: number, maxHp: number): number {
  return maxHp <= 0 ? 0 : Math.max(0, Math.min(100, (hp * 100) / maxHp))
}

function describeEvent(event: BattleEvent): string {
  if (event.type === 'HEAL') {
    return `回复 ${event.value}`
  }
  return `造成 ${event.value} 点伤害${event.crit ? '（暴击！）' : ''}`
}

/** 我是不是赢家。平局或还没打完时为 false。 */
const viewerWon = computed(
  () => props.battle.winner !== null && props.battle.winner === props.battle.viewer,
)

const championLabel = computed(() => {
  if (props.battle.winner === null) {
    return '平局'
  }
  return viewerWon.value ? '你赢了！' : '你输了'
})

const outcomeLabel = computed(() =>
  props.battle.outcome === null ? '' : OUTCOME_LABELS[props.battle.outcome],
)
</script>

<template>
  <section class="battle-report" aria-label="战报">
    <header class="report-head">
      <p class="report-result" :class="{ 'is-win': viewerWon }">{{ championLabel }}</p>
      <p class="report-meta">
        {{ battle.rounds }} 回合 · {{ outcomeLabel }}
      </p>
    </header>

    <!-- 双方的总览：名字、等级、形态、最终血量 -->
    <div class="report-sides">
      <div class="report-side" :class="{ 'is-me': battle.viewer === 'CHALLENGER' }">
        <span class="side-tag">{{ battle.viewer === 'CHALLENGER' ? '你' : '对手' }}</span>
        <span class="side-name">{{ battle.challenger.name }}</span>
        <span class="side-info">
          {{ SPECIES_NAMES[battle.challenger.species] }} · Lv.{{ battle.challenger.level }}
        </span>
        <span class="side-stats">
          生命 {{ battle.challenger.maxHp }} · 攻 {{ battle.challenger.attack }} ·
          防 {{ battle.challenger.defense }} · 速 {{ battle.challenger.speed }}
        </span>
      </div>
      <div class="report-side" :class="{ 'is-me': battle.viewer === 'DEFENDER' }">
        <span class="side-tag">{{ battle.viewer === 'DEFENDER' ? '你' : '对手' }}</span>
        <span class="side-name">{{ battle.defender.name }}</span>
        <span class="side-info">
          {{ SPECIES_NAMES[battle.defender.species] }} · Lv.{{ battle.defender.level }}
        </span>
        <span class="side-stats">
          生命 {{ battle.defender.maxHp }} · 攻 {{ battle.defender.attack }} ·
          防 {{ battle.defender.defense }} · 速 {{ battle.defender.speed }}
        </span>
      </div>
    </div>

    <ol class="round-list">
      <li v-for="view in rounds" :key="view.round" class="round">
        <p class="round-title">第 {{ view.round }} 回合</p>

        <div class="hp-row">
          <span class="hp-name">{{ battle.challenger.name }}</span>
          <span class="hp-track">
            <span
              class="hp-fill is-challenger"
              :style="{ width: `${hpPercent(view.challengerHp, battle.challenger.maxHp)}%` }"
            />
          </span>
          <span class="hp-value">{{ view.challengerHp }}</span>
        </div>
        <div class="hp-row">
          <span class="hp-name">{{ battle.defender.name }}</span>
          <span class="hp-track">
            <span
              class="hp-fill is-defender"
              :style="{ width: `${hpPercent(view.defenderHp, battle.defender.maxHp)}%` }"
            />
          </span>
          <span class="hp-value">{{ view.defenderHp }}</span>
        </div>

        <ul class="event-list">
          <li v-for="(event, index) in [...view.challengerEvents, ...view.defenderEvents]" :key="index">
            <span class="event-actor" :class="`is-${event.actor.toLowerCase()}`">
              {{ event.actor === 'CHALLENGER' ? battle.challenger.name : battle.defender.name }}
            </span>
            <span class="event-text" :class="{ 'is-crit': event.crit, 'is-heal': event.type === 'HEAL' }">
              {{ describeEvent(event) }}
            </span>
          </li>
        </ul>
      </li>
    </ol>

    <p class="report-seed">固定种子 {{ battle.seed }} —— 同样的快照和种子必然打出一模一样的战报</p>
  </section>
</template>

<style scoped>
.battle-report {
  padding: var(--space-md);
  background: var(--color-screen);
  border: var(--border-pixel);
  box-shadow: var(--shadow-pixel);
  color: var(--color-ink-green);
}

.report-head {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  align-items: baseline;
  margin-bottom: var(--space-md);
}

.report-result {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 700;
  color: var(--color-coral);
}

.report-result.is-win {
  color: var(--color-ink-green);
}

.report-meta {
  margin: 0;
  font-size: 0.8125rem;
  color: #4a5c50;
}

.report-sides {
  display: grid;
  gap: var(--space-sm);
  margin-bottom: var(--space-md);
}

.report-side {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--space-sm);
  border: 2px solid var(--border-pixel-color);
  background: var(--color-cream);
}

.report-side.is-me {
  background: var(--color-warm-orange);
}

.side-tag {
  font-size: 0.6875rem;
  font-weight: 700;
}

.side-name {
  font-size: 1rem;
  font-weight: 700;
}

.side-info,
.side-stats {
  font-size: 0.75rem;
}

.round-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  margin: 0;
  padding: 0;
  list-style: none;
}

.round {
  padding: var(--space-sm);
  border-left: 4px solid var(--border-pixel-color);
  background: var(--color-cream);
}

.round-title {
  margin: 0 0 var(--space-xs);
  font-weight: 700;
}

.hp-row {
  display: grid;
  grid-template-columns: 4.5em 1fr 2.5em;
  gap: var(--space-xs);
  align-items: center;
  font-size: 0.75rem;
}

.hp-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hp-track {
  height: 10px;
  border: 1px solid var(--border-pixel-color);
  background: var(--color-screen);
}

.hp-fill {
  display: block;
  height: 100%;
}

.hp-fill.is-challenger {
  background: var(--color-sky-blue);
}

.hp-fill.is-defender {
  background: var(--color-coral);
}

.hp-value {
  font-family: var(--font-mono);
  text-align: right;
}

.event-list {
  margin: var(--space-xs) 0 0;
  padding-left: var(--space-md);
  font-size: 0.8125rem;
}

.event-actor {
  font-weight: 700;
}

.event-actor.is-challenger {
  color: #2f6dab;
}

.event-actor.is-defender {
  color: #a83b38;
}

.event-text.is-crit {
  font-weight: 700;
  color: var(--color-warm-orange);
}

.event-text.is-heal {
  color: #2f7d4f;
}

.report-seed {
  margin: var(--space-md) 0 0;
  font-family: var(--font-mono);
  font-size: 0.6875rem;
  color: #4a5c50;
}

@media (min-width: 560px) {
  .report-sides {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
