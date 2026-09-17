import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { defineComponent } from 'vue'

import App from './App.vue'

/**
 * 骨架冒烟测试：验证 Pinia、Vue Router 和根组件能一起挂载。
 *
 * 这里用一个替身路由，把真正的页面留给各自的 spec —— 页面依赖会话和
 * 宠物数据，直接塞进根组件测试会变成集成测试。
 */
const StubView = defineComponent({
  name: 'StubView',
  template: '<p>像素宠物屋</p>',
})

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', name: 'home', component: StubView }],
  })
}

describe('App', () => {
  it('渲染主界面骨架', async () => {
    const router = createTestRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, {
      global: {
        plugins: [createPinia(), router],
      },
    })

    expect(wrapper.text()).toContain('像素宠物屋')
  })
})
