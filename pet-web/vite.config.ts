import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    // 开发代理：前端统一请求 /api，由 Vite 转发到本机后端，避免开发期跨域配置。
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // WebSocket 也要代理，否则前端连的是 Vite 自己，握手直接失败。
      // `ws: true` 是必须的：不开的话 Vite 会按普通 HTTP 请求转发，
      // 升级请求到不了后端 —— 表现出来就是通知一直是"未连接"。
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts'],
  },
})
