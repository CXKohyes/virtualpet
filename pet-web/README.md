# pet-web

像素宠物屋前端：Vue 3 + Vite + TypeScript + Pinia + Vue Router。

启动方式、环境要求和构建命令见仓库根目录的 [`../README.md`](../README.md)。

## 目录说明

```text
pet-web\
├── index.html            入口 HTML
├── vite.config.ts        Vite 配置（/api 开发代理 + Vitest 配置）
├── src\
│   ├── main.ts           应用入口，注册 Pinia 和 Vue Router
│   ├── App.vue           根组件，只渲染 RouterView
│   ├── router\           路由定义
│   ├── views\            页面组件
│   └── styles\           主题变量与全局样式
```

后续会按 `TECH_DESIGN.md` 第 3 节补充 `api\`、`components\`、`composables\`、`stores\`、`content\` 和 `assets\pets\`。

## 约定

- 统一使用 `<script setup lang="ts">` 和组合式 API，禁止 `any`。
- 组件不直接调用 Axios，统一通过 `src/api` 模块。
- 服务端返回的宠物状态是唯一事实，前端不重复实现衰减、经验或进化算法。
- 用户可见文本一律中文。CSS 类名 kebab-case，状态类使用 `is-` 前缀。
