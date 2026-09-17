<script setup lang="ts">
import { computed } from 'vue'

import { STATUS_LABELS } from '@/content/messages'
import { STAGE_LABELS, spriteFor } from '@/content/petSprites'

import type { Pet } from '@/types/pet'

/**
 * 宠物舞台（PRD 4.2 第 3 项）。
 *
 * 精灵图由 `scripts/generate_sprites.py` 程序化生成，9 张 64×64 的 PNG，
 * 覆盖三个物种 × 三个进化阶段。这里只负责挑图、缩放和播动画，
 * **不含任何游戏规则** —— 显示哪个形态完全由服务端给的 evolutionStage 决定。
 *
 * 尺寸固定 128px，正好是 64 的 2 倍整数缩放：像素图一旦按非整数倍缩放，
 * 就会出现有的像素占 2 个屏幕像素、有的占 3 个的"毛边"。
 */
const props = defineProps<{
  pet: Pet
  /** 正在执行的操作，用来播放对应的反馈动画。 */
  acting: string | null
  /** 一次性强调动画：升级或进化（PRD 4.3）。 */
  flash?: 'LEVEL_UP' | 'EVOLVE' | null
}>()

const speciesLabel = computed(
  () => ({ CAT: '猫', DOG: '狗', DRAGON: '像素龙' })[props.pet.species],
)

const sprite = computed(() => spriteFor(props.pet.species, props.pet.evolutionStage))

/** 给读屏和图片替代文本用的描述（PRD 4.4）。 */
const stageAlt = computed(
  () =>
    `${props.pet.name}，${speciesLabel.value}，${STAGE_LABELS[props.pet.evolutionStage] ?? '幼年'}形态，` +
    `当前${STATUS_LABELS[props.pet.status]}`,
)

const stateClasses = computed(() => ({
  'is-asleep': props.pet.status === 'SLEEPING',
  'is-sick': props.pet.status === 'SICK',
  [`is-acting-${props.acting?.toLowerCase() ?? ''}`]: props.acting !== null,
  'is-level-up': props.flash === 'LEVEL_UP',
  'is-evolving': props.flash === 'EVOLVE',
}))
</script>

<template>
  <div class="pet-stage">
    <img
      class="pet-sprite"
      :class="stateClasses"
      :src="sprite"
      :alt="stageAlt"
      width="64"
      height="64"
      draggable="false"
    />
    <div class="stage-ground" aria-hidden="true" />
  </div>
</template>

<style scoped>
.pet-stage {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  min-height: 200px;
  padding: var(--space-md);
  background: linear-gradient(180deg, var(--color-screen) 0%, #e8dcc0 100%);
  border: var(--border-pixel);
  box-shadow: var(--shadow-pixel);
}

/* 64px 的图按整数倍放大。**改动尺寸时只能取 64 的整数倍**（128、192、256），
   非整数倍会让有的像素占 2 个屏幕像素、有的占 3 个，边缘就毛了。 */
.pet-sprite {
  width: 128px;
  height: 128px;
  image-rendering: pixelated;
  transition: filter 200ms steps(2, end);
  animation: pet-bob 1.8s steps(2, end) infinite;
}

/* 屏幕稍微宽一点就把主角放大，不然宠物在舞台里显得太小（PRD 4.2 舞台区）。
   门槛取 420px：360px 的手机上舞台内宽只有 288px，塞 192px 的宠物就太挤了。 */
@media (min-width: 420px) {
  .pet-sprite {
    width: 192px;
    height: 192px;
  }

  .stage-ground {
    width: 156px;
  }
}

/* ---- 状态动画 ---- */

.pet-sprite.is-asleep {
  animation: none;
  filter: brightness(0.82) saturate(0.7);
}

.pet-sprite.is-sick {
  animation: pet-sick 1.6s steps(2, end) infinite;
  filter: saturate(0.55);
}

/* ---- 操作反馈 ---- */

.pet-sprite.is-acting-feed {
  animation: pet-nom 320ms steps(2, end) 3;
}

.pet-sprite.is-acting-play {
  animation: pet-hop 260ms steps(2, end) 3;
}

.pet-sprite.is-acting-clean {
  animation: pet-shake 200ms steps(2, end) 3;
}

/* ---- 升级 / 进化的一次性强调（PRD 4.3） ---- */

.pet-sprite.is-level-up {
  animation: pet-level-up 700ms steps(4, end) 2;
}

.pet-sprite.is-evolving {
  animation: pet-evolve 1100ms steps(5, end) 2;
}

@keyframes pet-bob {
  0%,
  100% {
    translate: 0 0;
  }
  50% {
    translate: 0 -6px;
  }
}

@keyframes pet-nom {
  0%,
  100% {
    scale: 1;
  }
  50% {
    scale: 1.07;
  }
}

@keyframes pet-hop {
  0%,
  100% {
    translate: 0 0;
  }
  50% {
    translate: 0 -18px;
  }
}

@keyframes pet-shake {
  0%,
  100% {
    rotate: 0deg;
  }
  50% {
    rotate: 5deg;
  }
}

@keyframes pet-sick {
  0%,
  100% {
    translate: 0 0;
  }
  50% {
    translate: 0 3px;
  }
}

@keyframes pet-level-up {
  0% {
    translate: 0 0;
    filter: none;
  }
  50% {
    translate: 0 -20px;
    filter: brightness(1.9) saturate(1.5);
  }
  100% {
    translate: 0 0;
    filter: none;
  }
}

@keyframes pet-evolve {
  0% {
    scale: 1;
    filter: brightness(1.6);
  }
  40% {
    scale: 1.25;
    filter: brightness(4) saturate(0);
  }
  70% {
    scale: 1.15;
    filter: brightness(2.2) saturate(1.4);
  }
  100% {
    scale: 1;
    filter: none;
  }
}

/* ---- 地面阴影 ----
   紧贴精灵下沿：精灵图里宠物是踩在画布底边的，中间留空就会像浮在半空。 */
.stage-ground {
  width: 104px;
  height: 12px;
  margin-top: 2px;
  background: rgba(20, 38, 26, 0.28);
  border-radius: 50%;
}

/* 系统开启"减弱动画"时全部停掉。数值和文案反馈仍然照常更新（PRD 2.10）。 */
@media (prefers-reduced-motion: reduce) {
  .pet-sprite,
  .pet-sprite.is-asleep,
  .pet-sprite.is-sick,
  .pet-sprite.is-acting-feed,
  .pet-sprite.is-acting-play,
  .pet-sprite.is-acting-clean,
  .pet-sprite.is-level-up,
  .pet-sprite.is-evolving {
    animation: none;
  }
}
</style>
