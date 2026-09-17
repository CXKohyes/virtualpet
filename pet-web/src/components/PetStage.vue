<script setup lang="ts">
import { computed } from 'vue'

import { STATUS_LABELS } from '@/content/messages'

import type { Pet } from '@/types/pet'

/**
 * 宠物舞台。
 *
 * **批次 3 用纯 CSS 画占位宠物**，正式的程序化生成精灵图在第 4 批接入
 * （`scripts/generate_sprites.py` → `assets/pets/*.png`）。
 * 换渲染方式时只需要改这个组件，不影响 store 和游戏状态。
 */
const props = defineProps<{
  pet: Pet
  /** 正在执行的操作，用来播放对应的反馈动画。 */
  acting: string | null
}>()

/** 进化阶段决定体型，0 幼年最小、2 最终形态最大。 */
const scale = computed(() => [1, 1.18, 1.36][props.pet.evolutionStage] ?? 1)

const speciesLabel = computed(
  () => ({ CAT: '猫', DOG: '狗', DRAGON: '像素龙' })[props.pet.species],
)

/** 给读屏和图片替代文本用的描述（PRD 4.4）。 */
const stageAlt = computed(
  () => `${props.pet.name}，${speciesLabel.value}，当前${STATUS_LABELS[props.pet.status]}`,
)
</script>

<template>
  <div class="pet-stage" :class="{ 'is-asleep': pet.status === 'SLEEPING' }">
    <div
      class="pet"
      :class="[`pet--${pet.species.toLowerCase()}`, acting ? `is-acting-${acting.toLowerCase()}` : '']"
      :style="{ '--pet-scale': scale }"
      role="img"
      :aria-label="stageAlt"
    >
      <span class="pet-ear pet-ear-left" aria-hidden="true" />
      <span class="pet-ear pet-ear-right" aria-hidden="true" />
      <span class="pet-body" aria-hidden="true">
        <span class="pet-eye pet-eye-left" />
        <span class="pet-eye pet-eye-right" />
        <span class="pet-mouth" />
        <span class="pet-tail" />
      </span>
    </div>
    <div class="stage-ground" aria-hidden="true" />
  </div>
</template>

<style scoped>
.pet-stage {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  min-height: 180px;
  padding: var(--space-md);
  background: linear-gradient(180deg, var(--color-screen) 0%, #e8dcc0 100%);
  border: var(--border-pixel);
  box-shadow: var(--shadow-pixel);
}

.pet {
  position: relative;
  display: grid;
  grid-template-columns: auto auto;
  justify-content: center;
  transform: scale(var(--pet-scale, 1));
  transform-origin: bottom center;
  animation: bob 2.4s steps(2, end) infinite;
}

.pet.is-asleep {
  animation: none;
  opacity: 0.85;
}

/* ---- 身体 ---- */
.pet-body {
  position: relative;
  grid-column: 1 / span 2;
  width: 88px;
  height: 76px;
  border: 4px solid var(--border-pixel-color);
  background: var(--pet-color, var(--color-warm-orange));
}

/* ---- 眼睛和嘴 ---- */
.pet-eye {
  position: absolute;
  top: 26px;
  width: 12px;
  height: 12px;
  background: var(--border-pixel-color);
}

.pet-eye-left {
  left: 16px;
}

.pet-eye-right {
  right: 16px;
}

/* 睡着时眼睛闭成一条线 */
.is-asleep .pet-eye {
  height: 4px;
  top: 30px;
}

.pet-mouth {
  position: absolute;
  bottom: 18px;
  left: 50%;
  width: 20px;
  height: 4px;
  margin-left: -10px;
  background: var(--border-pixel-color);
}

/* ---- 耳朵 / 角：按物种换形状 ---- */
.pet-ear {
  width: 22px;
  height: 22px;
  border: 4px solid var(--border-pixel-color);
  background: var(--pet-color, var(--color-warm-orange));
}

.pet-ear-left {
  justify-self: end;
}

.pet-ear-right {
  justify-self: start;
}

/* 猫：尖耳，三角形靠 clip-path 做 */
.pet--cat .pet-ear {
  clip-path: polygon(50% 0, 100% 100%, 0 100%);
  border-width: 0 0 4px 0;
}

/* 狗：垂耳，扁而宽，挂在头两侧 */
.pet--dog .pet-ear {
  width: 20px;
  height: 34px;
  border-radius: 0 0 8px 8px;
}

/* 龙：角 + 翅膀，角更高更尖 */
.pet--dragon .pet-ear {
  clip-path: polygon(50% 0, 100% 100%, 0 100%);
  border-width: 0 0 4px 0;
  height: 30px;
}

/* ---- 尾巴 ----
   放在 .pet-body 内部，位置相对身体算，保证一定接在身体上；
   去掉左边框，让它读起来是从身体里"长出来"的而不是贴在旁边的方块。 */
.pet-tail {
  position: absolute;
  right: -18px;
  bottom: 12px;
  width: 22px;
  height: 12px;
  border: 4px solid var(--border-pixel-color);
  border-left: none;
  background: var(--pet-color, var(--color-warm-orange));
}

.pet--dragon .pet-tail {
  clip-path: polygon(0 0, 100% 50%, 0 100%);
  border-width: 4px 0 4px 0;
  height: 20px;
}

/* ---- 物种配色 ---- */
.pet--cat {
  --pet-color: var(--color-warm-orange);
}

.pet--dog {
  --pet-color: var(--color-cream);
}

.pet--dragon {
  --pet-color: var(--color-sky-blue);
}

/* ---- 地面阴影 ---- */
.stage-ground {
  width: 110px;
  height: 12px;
  margin-top: var(--space-sm);
  background: rgba(20, 38, 26, 0.28);
  border-radius: 50%;
}

/* ---- 反馈动画 ---- */
.pet.is-acting-feed {
  animation: nom 320ms steps(2, end) 3;
}

.pet.is-acting-play {
  animation: hop 260ms steps(2, end) 3;
}

.pet.is-acting-clean {
  animation: shake 200ms steps(2, end) 3;
}

@keyframes bob {
  0%,
  100% {
    translate: 0 0;
  }
  50% {
    translate: 0 -6px;
  }
}

@keyframes nom {
  0%,
  100% {
    scale: 1;
  }
  50% {
    scale: 1.06;
  }
}

@keyframes hop {
  0%,
  100% {
    translate: 0 0;
  }
  50% {
    translate: 0 -16px;
  }
}

@keyframes shake {
  0%,
  100% {
    rotate: 0deg;
  }
  50% {
    rotate: 4deg;
  }
}

/* 系统开启"减弱动画"时只保留数值反馈（PRD 2.10） */
@media (prefers-reduced-motion: reduce) {
  .pet,
  .pet.is-acting-feed,
  .pet.is-acting-play,
  .pet.is-acting-clean {
    animation: none;
  }
}
</style>
