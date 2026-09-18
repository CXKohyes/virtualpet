/**
 * 粒子配方（PRD 4.2 第 3 项的「粒子反馈」，P1 清单的「粒子效果」）。
 *
 * 视觉上只有一张数据表：每种爆发由**配方**描述（几颗、飞多久、什么形状、
 * 朝哪个方向散、位移多远），`PetParticles.vue` 只负责把它渲染成 DOM。
 * 想调手感改这里就够，不用动组件。
 *
 * 三条设计约束：
 * 1. 纯 DOM/CSS —— `TECH_DESIGN.md` 第 13 节冻结了「不引入 Canvas 引擎」，
 *    所以这里没有画布也没有逐帧循环，动效全部交给 CSS `@keyframes`。
 * 2. 调色板只引用 `styles/theme.css` 已有的 CSS 变量（PRD 4.1 的主色），
 *    不在这里写死十六进制值，换主题时才不用两头改。
 * 3. 每颗粒子的方向、延迟、大小都由**索引确定性推导**，不用 `Math.random`——
 *    否则同样的操作每次炸出来的形状都不一样，测试也没法复现。
 */

/** 会触发粒子爆发的事件。前三个来自照护动作，后三个来自状态变化。 */
export const PARTICLE_BURSTS = [
  'FEED',
  'PLAY',
  'CLEAN',
  'LEVEL_UP',
  'EVOLVE',
  'SICK',
] as const

export type ParticleBurst = (typeof PARTICLE_BURSTS)[number]

/** 粒子形状。都是方形或旋转 45° 的方形，保持像素块的观感。 */
export type ParticleShape = 'crumb' | 'spark' | 'bubble' | 'star'

export interface ParticleRecipe {
  /** 颗粒数。越多越热闹，但移动端也要跟着算，别调太大。 */
  count: number
  /** 单颗粒子的动画时长（毫秒）。 */
  durationMs: number
  shape: ParticleShape
  /** 依次取色，至少一种。 */
  palette: readonly [string, ...string[]]
  /**
   * 抛洒扇形的起止角度，屏幕坐标系：0° 向右、90° 向下。
   * 所以「向上」是 270°，「向上偏左」是 225°。
   */
  fromDeg: number
  toDeg: number
  /**
   * 位移距离范围（像素）。这是**窄屏的基准值**：`PetParticles` 会按精灵自身的
   * 放大比例（420px 断点处 1.5 倍）同步缩放，见那里的 `--burst-scale`。
   * 上限受窄屏舞台的余量约束，不能超过约 90px，否则粒子会飞出舞台边框。
   */
  minDistance: number
  maxDistance: number
  /** 每颗粒子相对前一颗的延迟（毫秒），形成依次进发的层次。 */
  staggerMs: number
  /** 粒子基准边长（像素）。取 4 的倍数，缩放后边缘才不会毛。 */
  size: number
}

/**
 * 六种爆发的手感参数。
 *
 * 意图各不相同：喂食是食物屑向上抛洒后落下，玩耍是兴奋的星点四散，
 * 清洁是泡泡慢慢上飘，升级是干脆的六向扩散，进化最大最久，
 * 生病则是少量暗色颗粒无力地下沉。
 */
export const PARTICLE_RECIPES: Record<ParticleBurst, ParticleRecipe> = {
  FEED: {
    count: 10,
    durationMs: 620,
    shape: 'crumb',
    palette: ['var(--color-warm-orange)', 'var(--color-coral)', 'var(--color-cream)'],
    fromDeg: 200,
    toDeg: 340,
    minDistance: 46,
    maxDistance: 78,
    staggerMs: 18,
    size: 8,
  },
  PLAY: {
    count: 12,
    durationMs: 560,
    shape: 'spark',
    palette: ['var(--color-sky-blue)', 'var(--color-warm-orange)', 'var(--color-cream)'],
    fromDeg: 180,
    toDeg: 360,
    minDistance: 50,
    maxDistance: 84,
    staggerMs: 12,
    size: 8,
  },
  CLEAN: {
    count: 9,
    durationMs: 760,
    shape: 'bubble',
    palette: ['var(--color-sky-blue)', 'var(--color-cream)'],
    // 收得很窄：泡泡是一串往上飘，不是炸开。
    fromDeg: 250,
    toDeg: 290,
    minDistance: 44,
    maxDistance: 74,
    staggerMs: 40,
    size: 12,
  },
  LEVEL_UP: {
    count: 14,
    durationMs: 780,
    shape: 'star',
    palette: ['var(--color-warm-orange)', 'var(--color-cream)'],
    fromDeg: 0,
    toDeg: 360,
    minDistance: 52,
    maxDistance: 86,
    staggerMs: 10,
    size: 8,
  },
  EVOLVE: {
    count: 20,
    durationMs: 1100,
    shape: 'star',
    palette: [
      'var(--color-warm-orange)',
      'var(--color-coral)',
      'var(--color-sky-blue)',
      'var(--color-cream)',
    ],
    fromDeg: 0,
    toDeg: 360,
    minDistance: 58,
    maxDistance: 86,
    staggerMs: 16,
    size: 12,
  },
  SICK: {
    count: 6,
    durationMs: 900,
    shape: 'crumb',
    // 只用墨绿：生病时不该有鲜亮的颜色，但也不能是无色，否则看着像掉帧。
    palette: ['var(--color-ink-green)'],
    fromDeg: 60,
    toDeg: 120,
    minDistance: 26,
    maxDistance: 44,
    staggerMs: 70,
    size: 8,
  },
}

/** 一颗粒子渲染需要的全部参数，由 `particleSeeds` 推导出来。 */
export interface ParticleSeed {
  /** 相对爆发原点的位移（像素）。 */
  dx: number
  dy: number
  delayMs: number
  size: number
  /** CSS 颜色，可能是 `var(...)` 引用。 */
  tint: string
}

/**
 * 索引 → [0, 1) 的确定性伪随机数。
 *
 * 用正弦哈希而不是 `Math.random`：同一个爆发每次得到同一组参数，
 * 形状稳定、测试可复现，也不会在重渲染时跳来跳去。
 */
function hash01(seed: number): number {
  const x = Math.sin(seed * 12.9898) * 43758.5453
  return x - Math.floor(x)
}

/**
 * 把配方展开成每颗粒子的具体参数。
 *
 * 方向沿扇形均匀铺开，距离和大小再叠一层哈希抖动，
 * 免得所有粒子整整齐齐地排在一条弧线上，看着像几何题而不是爆炸。
 */
export function particleSeeds(burst: ParticleBurst): ParticleSeed[] {
  const recipe = PARTICLE_RECIPES[burst]

  return Array.from({ length: recipe.count }, (_, index) => {
    // 只有一颗时取扇形中点，否则会贴着起始角跑偏。
    const step = recipe.count === 1 ? 0.5 : index / (recipe.count - 1)
    const degrees = recipe.fromDeg + (recipe.toDeg - recipe.fromDeg) * step
    const radians = (degrees * Math.PI) / 180

    const spread = hash01(index + recipe.count)
    const distance = recipe.minDistance + (recipe.maxDistance - recipe.minDistance) * spread

    return {
      // 取整：像素风里半像素的位移会让方块边缘发虚。
      dx: Math.round(Math.cos(radians) * distance),
      dy: Math.round(Math.sin(radians) * distance),
      delayMs: Math.round(index * recipe.staggerMs),
      // 大小交错，避免一坨一样大的方块。
      size: index % 2 === 0 ? recipe.size : recipe.size + 4,
      tint: recipe.palette[index % recipe.palette.length] ?? recipe.palette[0],
    }
  })
}
