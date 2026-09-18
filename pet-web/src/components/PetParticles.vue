<script setup lang="ts">
import { computed } from 'vue'

import { PARTICLE_RECIPES, particleSeeds } from '@/content/particles'

import type { ParticleBurst } from '@/content/particles'

/**
 * 操作反馈粒子（PRD 4.2 第 3 项的「粒子反馈」）。
 *
 * 纯 DOM/CSS：一组绝对定位的方块，靠 `@keyframes` 飞出去，**没有任何 JS 定时器
 * 或逐帧循环**。动画用 `forwards` 停在 `opacity: 0`，所以结束后 DOM 里留几个
 * 透明的 `<span>` 是无害的 —— 省掉了清理定时器这一整类竞态。
 *
 * 重放靠 `:key="burstId"`：同一个操作连做两次时，`burstId` 递增会让容器被
 * 整体替换而不是复用，动画才会从头再播一遍。
 *
 * 这层永远不接收指针事件，也不进读屏（`aria-hidden`），满足 PRD 4.4
 * 的「动画不遮挡关键操作按钮」。
 */
const props = defineProps<{
  /** 要播放的爆发类型，`null` 表示不渲染任何东西。 */
  burst: ParticleBurst | null
  /** 每次触发递增。值变化会让容器重挂载，从而重播动画。 */
  burstId: number
}>()

const recipe = computed(() => (props.burst === null ? null : PARTICLE_RECIPES[props.burst]))

/** `null` 时给空数组，模板里就不用再判一次。 */
const seeds = computed(() => (props.burst === null ? [] : particleSeeds(props.burst)))

function seedStyle(index: number): Record<string, string> {
  const seed = seeds.value[index]
  if (seed === undefined) {
    return {}
  }
  return {
    '--dx': `${seed.dx}px`,
    '--dy': `${seed.dy}px`,
    '--delay': `${seed.delayMs}ms`,
    '--size': `${seed.size}px`,
    '--tint': seed.tint,
  }
}
</script>

<template>
  <div
    v-if="burst !== null && recipe !== null"
    :key="burstId"
    class="pet-particles"
    :data-burst="burst"
    :data-burst-id="burstId"
    :style="{ '--duration': `${recipe.durationMs}ms` }"
    aria-hidden="true"
  >
    <span
      v-for="(_, index) in seeds"
      :key="index"
      class="particle"
      :class="`is-${recipe.shape}`"
      :style="seedStyle(index)"
    />
  </div>
</template>

<style scoped>
/* 铺满整个精灵区（父级 `.pet-figure` 是定位上下文），
   粒子从 50%/50% 也就是宠物身体中心出发。 */
.pet-particles {
  position: absolute;
  inset: 0;
  overflow: visible;
  /* 只作为视觉反馈存在，绝不能挡住下面的按钮。 */
  pointer-events: none;
  /* 位移缩放系数。断点和倍率与 `.pet-sprite` 的尺寸变化**保持一致**：
     精灵在 420px 以上从 128px 放大到 192px（1.5 倍），粒子飞行的距离
     也要同步放大。否则宽屏上粒子只飞半个身位，全缩在宠物身体里，
     看起来像精灵图上的脏点而不是特效。 */
  --burst-scale: 1;
}

.particle {
  position: absolute;
  left: 50%;
  top: 50%;
  width: var(--size, 8px);
  height: var(--size, 8px);
  background: var(--tint, var(--color-warm-orange));
  /* 先隐藏：延迟期间不该看见，轮到它时动画从 opacity 1 开始。 */
  opacity: 0;
  /* 负 margin 让方块以自身中心对齐到原点，之后 translate 才是纯位移。 */
  margin: calc(var(--size, 8px) / -2) 0 0 calc(var(--size, 8px) / -2);
  /* step(6) 而不是更粗的 steps(4)：4 档在 620ms 里每档 155ms，
     前四分之一的时间粒子还停在原点不动，看着像没反应。 */
  animation: particle-fly var(--duration, 600ms) steps(6, end) forwards;
  animation-delay: var(--delay, 0ms);
}

/* ---- 形状 ----
   全部是方块或转 45° 的方块，不引圆角，保住像素块的观感（PRD 4.1）。 */

.particle.is-spark {
  rotate: 45deg;
}

/* 泡泡：方块挖个亮圈，像肥皂泡的轮廓。 */
.particle.is-bubble {
  box-shadow: inset 0 0 0 3px rgba(253, 246, 227, 0.75);
}

/* 星星：菱形 + 更细的亮圈，与 spark 区分开。 */
.particle.is-star {
  rotate: 45deg;
  box-shadow: inset 0 0 0 2px rgba(253, 246, 227, 0.85);
}

@keyframes particle-fly {
  from {
    translate: 0 0;
    scale: 1;
    opacity: 1;
  }
  to {
    translate: calc(var(--dx, 0px) * var(--burst-scale, 1))
      calc(var(--dy, 0px) * var(--burst-scale, 1));
    scale: 0.4;
    opacity: 0;
  }
}

/* 精灵在 420px 以上放大到 1.5 倍（见 `PetStage.vue`），粒子跟着放大。 */
@media (min-width: 420px) {
  .pet-particles {
    --burst-scale: 1.5;
  }
}

/* 系统开启「减弱动画」时整层不渲染（PRD 2.10）。
   数值、文案和升级提示条全部照常，只是不再有粒子。 */
@media (prefers-reduced-motion: reduce) {
  .pet-particles {
    display: none;
  }
}
</style>
