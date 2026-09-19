<script setup lang="ts">
import { computed } from 'vue'

import { ATTRIBUTE_META, STATUS_LABELS } from '@/content/messages'
import { spriteFor } from '@/content/petSprites'

import type { Pet } from '@/types/pet'

/**
 * 名册：全部宠物，点一下切换当前宠物（PRD 2.1，多宠物槽）。
 *
 * <p>这个组件的核心任务不是"列出宠物"，而是<b>让人一眼看出谁快不行了</b>。
 * 非活跃宠物照常衰减，所以三只都得照顾；看不出谁在挨饿的话，
 * 这个功能就会从"收集的乐趣"变成"照顾的负担"。</p>
 *
 * <p>为此做了两件事，都没有引入任何新的游戏规则：</p>
 * <ul>
 *   <li>状态徽章直接用服务端算好的 `status`（饿/脏/累/低落/生病都是它给的），
 *       前端不自己判阈值。</li>
 *   <li>再显示一个"最缺的那一项"——纯粹是服务端给的五个数里取最小，
 *       属于排版而不是规则。它的用处是：三只都是 NORMAL 时，
 *       这项还能分出"谁更接近出问题"。</li>
 * </ul>
 */
const props = defineProps<{
  /** 名册，按槽位升序，已由 store 从服务端取回。 */
  pets: Pet[]
  /** 槽位上限，来自配置接口。 */
  maxSlots: number
  /** 正在切换或送走时禁用整排按钮。 */
  busy: boolean
}>()

const emit = defineEmits<{
  select: [petId: number]
  add: []
}>()

/** 还能不能再养一只。maxSlots 拿不到（0）时不显示入口，由服务端去拒绝。 */
const canAdd = computed(() => props.maxSlots > 0 && props.pets.length < props.maxSlots)

/**
 * 每只最缺的那一项。
 *
 * 取最小值是排版，不是规则 —— 真正的判定（饿/脏/累）仍然只看服务端给的 status。
 */
type AttributeMeta = (typeof ATTRIBUTE_META)[number]

function worstAttribute(pet: Pet): { label: string; value: number } {
  // 显式标注类型：不写的话 worst 会被收窄成 ATTRIBUTE_META[0] 的字面量类型，
  // 后面把其它项赋给它就报错了
  let worst: AttributeMeta = ATTRIBUTE_META[0]
  let value = pet[worst.key]
  for (const meta of ATTRIBUTE_META) {
    if (pet[meta.key] < value) {
      worst = meta
      value = pet[meta.key]
    }
  }
  return { label: worst.label, value }
}

/** 睡觉不是问题状态，不该被标成需要照顾。 */
function needsAttention(pet: Pet): boolean {
  return pet.status !== 'NORMAL' && pet.status !== 'SLEEPING'
}

/** 渲染用的卡片数据，避免在模板里对同一只重复算。 */
const cards = computed(() =>
  props.pets.map((pet) => ({
    pet,
    alert: needsAttention(pet),
    worst: worstAttribute(pet),
    sprite: spriteFor(pet.species, pet.evolutionStage),
  })),
)
</script>

<template>
  <section class="roster" aria-label="我的宠物">
    <ul class="roster-list">
      <li v-for="card in cards" :key="card.pet.id" class="roster-item">
        <button
          type="button"
          class="roster-card"
          :class="{ 'is-active': card.pet.active, 'is-alert': card.alert }"
          :aria-current="card.pet.active ? 'true' : undefined"
          :disabled="busy || card.pet.active"
          :title="card.pet.active ? '正在照看这一只' : `切换到${card.pet.name}`"
          @click="emit('select', card.pet.id)"
        >
          <img class="roster-sprite" :src="card.sprite" :alt="`${card.pet.name}的精灵图`" />
          <span class="roster-name">{{ card.pet.name }}</span>
          <span class="roster-level">Lv.{{ card.pet.level }}</span>
          <span v-if="card.alert" class="roster-status">
            {{ STATUS_LABELS[card.pet.status] }}
          </span>
          <span v-if="card.alert" class="roster-worst">
            {{ card.worst.label }} {{ card.worst.value }}
          </span>
          <span v-if="card.pet.active" class="roster-current">当前</span>
        </button>
      </li>

      <li v-if="canAdd" class="roster-item">
        <button
          type="button"
          class="roster-card is-add"
          :disabled="busy"
          hint="再养一只"
          @click="emit('add')"
        >
          <span class="roster-plus" aria-hidden="true">＋</span>
          <span class="roster-name">再养一只</span>
          <span class="roster-level">{{ pets.length }}/{{ maxSlots }}</span>
        </button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.roster {
  width: 100%;
}

.roster-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  margin: 0;
  padding: 0;
  list-style: none;
}

.roster-item {
  flex: 0 1 auto;
}

.roster-card {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-xs);
  width: 84px;
  min-height: 44px;
  padding: var(--space-sm) var(--space-xs);
  border: var(--border-pixel);
  background: var(--color-screen);
  color: var(--color-ink-green-dark);
  font-family: var(--font-ui);
  cursor: pointer;
}

.roster-card:disabled {
  cursor: default;
}

.roster-card.is-active {
  border-color: var(--color-warm-orange);
  box-shadow: var(--shadow-pixel);
}

/* 需要照顾的那只描一圈珊瑚红，扫一眼就能找到 */
.roster-card.is-alert {
  border-color: var(--color-coral);
}

.roster-card.is-alert:not(.is-active) {
  background: #fbe9e7;
}

.roster-sprite {
  width: 40px;
  height: 40px;
  /* 最近邻缩放，禁止模糊插值（PRD 4.1） */
  image-rendering: pixelated;
}

.roster-name {
  max-width: 100%;
  overflow: hidden;
  font-size: 0.75rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.roster-level {
  color: var(--color-ink-green);
  font-size: 0.6875rem;
}

.roster-status {
  padding: 0 4px;
  background: var(--color-coral);
  color: var(--color-cream);
  font-size: 0.625rem;
  line-height: 1.4;
}

.roster-worst {
  color: var(--color-coral);
  font-size: 0.625rem;
  line-height: 1.4;
}

.roster-current {
  position: absolute;
  top: -1px;
  right: -1px;
  padding: 0 4px;
  background: var(--color-warm-orange);
  color: var(--color-cream);
  font-size: 0.625rem;
  line-height: 1.4;
}

.roster-plus {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  color: var(--color-ink-green);
  font-size: 1.5rem;
}

.roster-card.is-add {
  border-style: dashed;
  background: transparent;
}
</style>
