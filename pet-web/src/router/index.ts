import { createRouter, createWebHistory } from 'vue-router'

import { usePetStore } from '@/stores/petStore'
import { useSessionStore } from '@/stores/sessionStore'
import BattleView from '@/views/BattleView.vue'
import HomeView from '@/views/HomeView.vue'
import OnboardingView from '@/views/OnboardingView.vue'

/**
 * 三个主视图：领养、主界面、对战（TECH_DESIGN 7.4，对战是批次 6 加的）。
 *
 * 守卫保证：进主界面之前会话已经建立、宠物状态和名册都已经拉过一次；
 * 没有宠物就送去领养页。对战页同样要求先有宠物 —— 没有宠物没什么可打的。
 */
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView,
    },
    {
      path: '/onboarding',
      name: 'onboarding',
      component: OnboardingView,
    },
    {
      path: '/battle',
      name: 'battle',
      component: BattleView,
    },
  ],
})

router.beforeEach(async (to) => {
  const session = useSessionStore()
  if (!session.ready) {
    await session.initialize()
  }

  const petStore = usePetStore()
  await petStore.ensureLoaded()

  if ((to.name === 'home' || to.name === 'battle') && petStore.pet === null) {
    return { name: 'onboarding' }
  }
  // 领养页现在有两个用途：第一次领养，以及槽位没满时的「再养一只」。
  // 所以不能像原来那样「有宠物就弹回主页」—— 那会让「再养一只」的入口
  // 刚点进去就被守卫弹走。只有槽位真的满了才拦。
  if (to.name === 'onboarding' && petStore.slotsFull) {
    return { name: 'home' }
  }
  return true
})

export default router
