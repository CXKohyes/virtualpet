<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import PetParticles from '@/components/PetParticles.vue'
import { STATUS_LABELS } from '@/content/messages'
import { STAGE_LABELS, spriteFor } from '@/content/petSprites'
import { SPECIES_NAMES } from '@/content/speciesText'

import type { ParticleBurst } from '@/content/particles'
import type { Pet } from '@/types/pet'

/**
 * 宠物舞台（PRD 4.2 第 3 项）。
 *
 * 精灵图由 `scripts/generate_sprites.py` 程序化生成，每个物种 3 张 64×64 的 PNG，
 * 覆盖全部进化阶段。这里只负责挑图、缩放和播动画，
 * **不含任何游戏规则** —— 显示哪个形态完全由服务端给的 evolutionStage 决定。
 *
 * 尺寸固定 128px，正好是 64 的 2 倍整数缩放：像素图一旦按非整数倍缩放，
 * 就会出现有的像素占 2 个屏幕像素、有的占 3 个的"毛边"。
 *
 * 操作反馈有三个层次，都从这里发出：精灵自身的 CSS 位移动画（`is-acting-*`、
 * `is-level-up`、`is-evolving`）、`PetParticles` 的方块粒子、以及舞台之外的
 * 音效和提示条（由 `HomeView` 负责）。
 */
const props = defineProps<{
  pet: Pet
  /** 正在执行的操作，用来播放对应的反馈动画。 */
  acting: string | null
  /** 一次性强调动画：升级或进化（PRD 4.3）。 */
  flash?: 'LEVEL_UP' | 'EVOLVE' | null
}>()

const speciesLabel = computed(() => SPECIES_NAMES[props.pet.species])

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

/* ---- 粒子触发（PRD 4.2 第 3 项） ----
   粒子由这里根据**已有的 props** 推导，不新增 store 字段、也不改 HomeView：
   `acting` / `flash` / `pet.status` 本来就已经传进来了，触发条件和动画类名
   说的是同一件事，放在一起才不会两处走样。 */

/** 有粒子反馈的照护动作。睡觉和唤醒只是状态切换，不炸粒子。 */
const CARE_BURSTS: Readonly<Record<string, ParticleBurst>> = {
  FEED: 'FEED',
  PLAY: 'PLAY',
  CLEAN: 'CLEAN',
}

const burst = ref<ParticleBurst | null>(null)
const burstId = ref(0)

/**
 * 触发一次爆发。
 *
 * `burstId` 递增是重放的关键：同一个操作连做两次时 `burst` 的值没变，
 * 只有这个序号变了，`PetParticles` 才会重挂载并把动画从头播一遍。
 */
function fire(next: ParticleBurst): void {
  burst.value = next
  burstId.value += 1
}

// 升级和进化用 flash 表达，它比 acting 晚发生，所以谁后到谁作数。
watch(
  () => props.flash,
  (flash) => {
    // flash 是可选的，没传时是 undefined，和 null 一样表示「这次没有强调动画」。
    if (flash !== null && flash !== undefined) {
      fire(flash)
    }
  },
)

watch(
  () => props.acting,
  (acting) => {
    const next = acting === null ? undefined : CARE_BURSTS[acting]
    if (next !== undefined) {
      fire(next)
    }
  },
)

watch(
  () => props.pet.status,
  (status, before) => {
    // 只在「刚生病」那一刻炸一次；一直病着不反复炸。
    if (status === 'SICK' && before !== undefined && before !== 'SICK') {
      fire('SICK')
    }
  },
)
</script>

<template>
  <div class="pet-stage">
    <div class="pet-figure">
      <img
        class="pet-sprite"
        :class="stateClasses"
        :src="sprite"
        :alt="stageAlt"
        width="64"
        height="64"
        draggable="false"
      />
      <PetParticles :burst="burst" :burst-id="burstId" />
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
  min-height: 200px;
  padding: var(--space-md);
  background: linear-gradient(180deg, var(--color-screen) 0%, #e8dcc0 100%);
  border: var(--border-pixel);
  box-shadow: var(--shadow-pixel);
}

/* 精灵和粒子共用的定位上下文，尺寸跟着精灵走。
   粒子从这个盒子的中心（也就是宠物身体中心）出发，
   所以要包一层而不是直接挂在 `.pet-stage` 上 —— 后者是整块舞台，
   中心点在宠物下方一大截，粒子会像从地上冒出来。 */
.pet-figure {
  position: relative;
  display: flex;
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
