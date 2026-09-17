import { createRouter, createWebHistory } from 'vue-router'

import { usePetStore } from '@/stores/petStore'
import { useSessionStore } from '@/stores/sessionStore'
import BattleView from '@/views/BattleView.vue'
import HomeView from '@/views/HomeView.vue'
import OnboardingView from '@/views/OnboardingView.vue'

/**
 * 三个主视图：领养、主界面、对战（TECH_DESIGN 7.4，对战是批次 6 加的）。
 *
 * 守卫保证：进主界面之前会话已经建立、宠物状态已经拉过一次；
 * 没领养就送去领养页，已领养就别再停在领养页。
 * 对战页同样要求先有宠物 —— 没有宠物没什么可打的。
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
  if (to.name === 'onboarding' && petStore.pet !== null) {
    return { name: 'home' }
  }
  return true
})

export default router
