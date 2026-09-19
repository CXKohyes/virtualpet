# CLAUDE.md

本文件是 Claude Code 在本仓库的工作约定。仓库的施工规则以 `AGENTS.md` 为准，本文件只补充操作层面的约定。

## 1. 每次开发前必须阅读

开始任何任务前，按顺序完整阅读以下三份文档：

1. **`AGENTS.md`** —— 施工规则、目录约定、技术栈、禁止事项、完成定义。优先级最高。
2. **`PRD.md`** —— 产品需求、功能细节、验收标准。
3. **`TECH_DESIGN.md`** —— 技术设计、数据模型、API 设计、核心算法、测试策略。

补充说明：

- `设计方案.md` 是立项初期的原始方案，其中的 Canvas/`pet-engine`、`@Scheduled` 定时衰减、Redis、MySQL 8 等选型已被 `TECH_DESIGN.md` 取代。**两者冲突时一律以 `TECH_DESIGN.md` 为准**，不要在实现中采用 `设计方案.md` 的过期选型。
- 只读文档不够，开始前还要用 `rg` 搜索现有实现和测试，确认本次只处理一个功能切片。

## 2. 环境约定（Windows + PowerShell）

- 本机是 Windows 11，开发命令使用 **PowerShell 语法**。不要使用 `export`、`source`、`/tmp`、`./mvnw` 等 Bash 写法。
- 项目根目录：`D:\code\virtual pet`。
- **Maven 必须使用 Java 17**。本机 `mvn` 默认指向 JDK 21（`D:\JDK\JDK_21`），所以每条 Maven 命令前都必须显式设置：

  ```powershell
  $env:JAVA_HOME = 'D:\JDK1'
  ```

- **PowerShell 下请使用 `npm.cmd` 而不是 `npm`**。本机执行策略禁止运行 `npm.ps1`，直接调用 `npm` 会报 `PSSecurityException`。不要为此修改系统执行策略，改用 `npm.cmd` 即可。

- 不要修改系统全局环境变量、全局 npm 配置或用户级 Maven 设置。
- 不要安装 MySQL、Redis 或其他服务；需要的服务由开发者本人准备。

## 3. 标准命令

### 后端（`pet-server`）

```powershell
$env:JAVA_HOME = 'D:\JDK1'

# 运行测试（每批开发结束必须执行）
mvn -f pet-server/pom.xml test

# 只编译
mvn -f pet-server/pom.xml compile

# 启动服务（默认 http://localhost:8080）
mvn -f pet-server/pom.xml spring-boot:run
```

### 前端（`pet-web`）

```powershell
npm.cmd --prefix pet-web install
npm.cmd --prefix pet-web run dev        # 开发服务器 http://localhost:5173
npm.cmd --prefix pet-web run test       # Vitest
npm.cmd --prefix pet-web run build      # vue-tsc -b && vite build
```

### 每批开发结束必须全部通过

```powershell
$env:JAVA_HOME = 'D:\JDK1'
mvn -f pet-server/pom.xml test
npm.cmd --prefix pet-web run test
npm.cmd --prefix pet-web run build
```

如果依赖下载失败：先报告**具体命令和完整错误**，不要绕过、不要修改全局源、不要换用未验证的仓库。项目内如需指定源，只在 `pet-web/.npmrc` 中配置。

## 4. 开发节奏

- **每批只做一个功能切片**，建议单批改动不超过 5 个文件。
- 先写测试再写实现（后端规则），先定义类型和 store 再写组件（前端）。
- 不顺手重构无关模块，不删除或覆盖已有文件。
- 每批结束按 `AGENTS.md` 7.3 报告：改了什么、为什么、跑了哪些命令、是否通过、还有什么遗留问题。

**进度以 `README.md`「当前进度」一节为准**，那份会随提交更新；本节不再重复。

> 这里原来写的是「已完成批次 0，下一批是批次 1」—— 那句话从批次 1 落地那天起
> 就不准了，之后又陆续做完了批次 2–6、8 和 P2 的多宠物槽，一直没人回头改。
> **进度写在两个地方就一定会漂**，所以这里只留指向，不留副本。

## 5. 硬性禁止事项

完整清单见 `AGENTS.md` 第 9 节，最容易踩的几条：

- 禁止在 Controller 里写游戏规则；规则必须放在 Service 或领域类。
- 禁止在前端重复实现衰减、经验或进化算法；服务端是唯一事实来源。
- 禁止把 WebSocket 引入 MVP 养成主链路，禁止为 MVP 引入 Redis。
- 禁止使用未验证的 `pet-engine` 或类似依赖，禁止在运行时调用 LLM。
- 禁止提交任何密钥、数据库密码、令牌和个人信息。
- 禁止手改数据库结构而不写 Liquibase changeset。
- 禁止用删除测试、跳过测试或 `--no-verify` 让流程通过。
- 禁止使用 `git reset --hard` 等破坏性操作。
- **禁止在没有实际运行的情况下声称测试、构建或功能已经通过。**

## 6. 代码约定速查

- 后端分层：`Controller -> Service -> Mapper -> Database`；构造器注入；DTO 用 `record`；不引入 Lombok。
- 时间统一用注入的 `Clock`，禁止业务代码直接调用 `Instant.now()`；数据库存 UTC。
- 前端：Vue 3 组合式 API + `<script setup lang="ts">`，禁止 `any`，组件不直接调用 Axios。
- 用户可见文本一律中文；CSS 类名 kebab-case，状态类用 `is-` 前缀。
- 数据库：`snake_case` 表名字段名，枚举存字符串，属性值 0–100 由应用层钳制。
