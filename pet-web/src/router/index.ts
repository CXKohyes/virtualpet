import { createRouter, createWebHistory } from 'vue-router'

import HomeView from '@/views/HomeView.vue'

/**
 * MVP 只有两个主视图：领养页和主界面。
 * 批次 0 只建立主界面路由，领养页和“有宠物进 Home、无宠物进 Onboarding”的守卫在批次 3 加入。
 */
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView,
    },
  ],
})

export default router
