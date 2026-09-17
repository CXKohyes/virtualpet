# AI 代理开发指令

本文件是本仓库所有 AI 代理和自动化编码工具的施工规则。Claude Code、Codex 或其他代理在修改代码前必须完整阅读本文件，并优先遵循更靠近目标目录的 `AGENTS.md`。

## 1. 项目概述

本项目是“像素宠物屋”，一个怀旧向虚拟宠物养成 Web 应用。

首个里程碑只实现单人养成闭环：

- 匿名设备存档。
- 猫、狗、像素龙三选一和命名。
- 饱食、心情、清洁、精力、健康五项状态。
- 喂食、玩耍、清洁、睡觉、唤醒。
- 懒结算离线衰减，单次最多 12 小时。
- 生病与恢复，宠物不死亡。
- 10 级经验和三阶段单线进化。
- 物种特性、性格台词、8-bit 合成音效。
- 响应式手机/桌面界面。

第二个里程碑实现异步宠物对战：

- 好友码。
- 宠物快照。
- 服务端确定性自动战斗。
- 战报和 WebSocket 通知。

关联文档：

- 产品需求：`PRD.md`
- 技术设计：`TECH_DESIGN.md`
- 原始方案：`设计方案.md`

## 2. 技术栈

### 前端

- Vue 3.5.x
- Vite
- TypeScript 5.x，开启 strict
- Pinia
- Vue Router
- Axios
- Vitest + Vue Test Utils
- 原生 CSS + CSS 变量

### 后端

- Java 17，本机路径 `D:\JDK1`
- Spring Boot 3.5.x
- Spring MVC
- Spring Validation
- Spring WebSocket + STOMP（预埋）
- MyBatis-Plus 3.5.x（`mybatis-plus-spring-boot3-starter`）
- Liquibase formatted SQL
- MySQL 5.7.44（本机）；生产目标 MySQL 8
- JUnit 5、MockMvc、H2 MySQL 模式

### 工具

- Maven 3.8.1
- Node.js 24.18.0
- npm 11.16.0
- Python 3.10.6 + Pillow 10.3
- Git

## 3. 目录约定

```text
D:\code\virtual pet\
├── AGENTS.md
├── PRD.md
├── TECH_DESIGN.md
├── README.md
├── CLAUDE.md
├── docs\
├── scripts\
├── pet-server\
└── pet-web\
```

- 后端代码只放在 `pet-server`。
- 前端代码只放在 `pet-web`。
- 脚本放在 `scripts`，不得把临时脚本写到仓库根目录。
- 生成资源放在 `pet-web/src/assets`。
- 数据库迁移只放在 `pet-server/src/main/resources/db/changelog`。
- 文档改动必须同步检查 `PRD.md` 与 `TECH_DESIGN.md` 是否冲突。

## 4. 标准命令

所有 Maven 命令之前必须设置 Java 17，禁止依赖系统默认 Java 8。

```powershell
$env:JAVA_HOME = 'D:\JDK1'

mvn -f pet-server/pom.xml test
mvn -f pet-server/pom.xml spring-boot:run

npm --prefix pet-web install
npm --prefix pet-web run dev
npm --prefix pet-web run test
npm --prefix pet-web run build

py -3 scripts/generate_sprites.py
```

如果依赖下载失败：

- npm 使用 `pet-web/.npmrc` 中的 HTTPS 镜像，不修改用户级 npm 配置。
- Maven 默认使用 Central；必要时使用 `scripts/maven-settings.xml` 的镜像配置。
- 网络请求需要用户批准时，说明具体命令和原因。

## 5. 开发规范

### 5.1 通用

- 开始任务前先阅读相关源码和测试。
- 一次只实现一个功能切片。
- 不顺手重构无关模块。
- 不删除、覆盖或重命名用户已有文件，除非任务明确要求。
- 不引入未验证的第三方依赖。
- 不把 API Key、数据库密码、令牌或个人信息写入仓库。
- 所有新增行为必须有测试或明确的人工验收步骤。
- 修改 API 时同步修改 `docs/api.md`（如存在）和 `TECH_DESIGN.md`。

### 5.2 后端规范

分层：

```text
Controller -> Service -> Mapper -> Database
```

- Controller 只负责参数接收、校验、调用 Service 和返回响应。
- 业务规则必须放在 Service 或领域类，不能写在 Controller。
- Mapper 只负责数据访问，不写业务分支。
- DTO 与实体分离，不直接把数据库实体暴露给前端。
- 使用构造器注入，不使用字段注入。
- DTO 使用 Java `record`；实体保留普通类以兼容 MyBatis-Plus。
- 不引入 Lombok，减少编译和 IDE 兼容问题。
- 时间统一使用 `Clock`，禁止在业务代码中直接调用 `Instant.now()`。
- 所有时间以 UTC 存储和传输。
- 数据库更新必须在事务中完成。
- 使用 `@Version` 实现乐观锁，并在冲突时有限重试。
- 可重复操作必须使用 `clientRequestId` 幂等。
- 所有用户输入必须做长度、范围和枚举校验。
- 错误统一使用 `ApiResponse` 和 `ErrorCode`。
- 日志不得输出令牌、数据库密码和完整设备标识。

包结构：

```text
com.virtualpet.common
com.virtualpet.config
com.virtualpet.auth
com.virtualpet.player
com.virtualpet.pet
com.virtualpet.game
com.virtualpet.dev
com.virtualpet.battle
```

### 5.3 前端规范

- 使用 Vue 3 组合式 API 和 `<script setup lang="ts">`。
- 组件名使用 PascalCase，composable 使用 `useXxx`。
- Pinia store 使用 `defineStore`，状态、getter、action 分组。
- 组件不直接调用 Axios，统一通过 `src/api` 模块。
- 服务端返回的宠物状态是唯一事实，本地乐观更新只用于即时反馈。
- 失败时回滚乐观更新并展示原因。
- 类型定义集中放在 `src/types` 或 API 模块，禁止使用 `any` 绕过校验。
- 所有用户可见文本使用中文，错误提示必须可理解。
- 像素图统一使用 `image-rendering: pixelated`。
- 不使用大型 UI 组件库。
- 不使用 Canvas 引擎替代 DOM 渲染，除非任务明确要求。
- 音效必须用户点击后初始化，失败时可静默降级。

### 5.4 数据库规范

- 表名和字段名使用 `snake_case`。
- 时间字段统一为 UTC 的 `TIMESTAMP`。
- 主键使用 `BIGINT AUTO_INCREMENT`。
- 枚举以字符串保存，便于阅读和排查。
- 属性值必须保持 0–100，应用层负责钳制。
- 迁移文件一旦提交不得修改历史 changeset，只能新增 changeset。
- 禁止在代码中执行 `CREATE TABLE` 或手改线上表结构。
- 本地 MySQL 5.7 兼容优先，不使用 MySQL 8 独有语法。

### 5.5 测试规范

- 核心领域规则必须先有单元测试。
- 时间相关测试必须使用固定 `Clock`。
- 幂等和并发测试必须覆盖重复请求。
- API 测试使用 MockMvc。
- 前端 store 和关键组件必须有 Vitest 测试。
- 不得通过删除测试或放宽断言让构建通过。
- 修复 bug 时先补一个失败的回归测试。
- 每批开发结束至少运行：

```powershell
$env:JAVA_HOME = 'D:\JDK1'
mvn -f pet-server/pom.xml test
npm --prefix pet-web run test
npm --prefix pet-web run build
```

## 6. 代码风格

### Java

- 4 空格缩进。
- 类名 PascalCase，方法和变量 camelCase，常量 UPPER_SNAKE_CASE。
- 不使用通配符 import。
- 方法保持短小，单个方法建议不超过 40 行。
- 公开 API 必须有简洁 Javadoc 或自解释命名。
- 异常使用业务异常类型，不返回 null 表示错误。
- 优先不可变对象，DTO 使用 record。

### TypeScript / Vue

- 2 空格缩进。
- 单引号、结尾分号、尾随逗号遵循项目格式化配置。
- 变量和函数使用 camelCase，类型和组件使用 PascalCase。
- 禁止 `any`，必要时使用 `unknown` + 类型守卫。
- 模板保持简单，复杂逻辑放到 computed 或 composable。
- CSS 类名使用 kebab-case，状态类使用 `is-` 前缀。
- 使用 CSS 变量定义颜色、间距和像素边框。

### SQL

- 关键字大写。
- 每张表必须有主键和 `created_at`。
- 索引命名：`idx_`、`uk_`、`fk_`。
- 迁移文件使用 Liquibase formatted SQL。
- 一个 changeset 只做一类变更。

## 7. AI 代理工作流

### 7.1 每次任务开始

1. 阅读 `AGENTS.md`、`PRD.md`、`TECH_DESIGN.md` 的相关章节。
2. 使用 `rg` 搜索现有实现和测试。
3. 确认本次只处理一个功能切片。
4. 列出即将修改的文件和验收命令。
5. 不改动范围外文件。

### 7.2 实现过程

- 后端规则先写测试，再写实现。
- 前端先定义类型和 store，再写组件。
- 遇到接口变更，先更新文档和类型，再改实现。
- 遇到不确定的外部依赖，先验证可用性，不凭假设使用。
- 发现设计冲突时停止扩展，先记录冲突并选择最小改动方案。

### 7.3 每次任务结束

必须报告：

- 修改了什么。
- 为什么这样修改。
- 运行了哪些测试或构建命令。
- 结果是否通过。
- 还有哪些已知限制或后续任务。

## 8. 当前开发批次建议

### 批次 0：工程骨架

- 初始化 Git 和 `.gitignore`。
- 创建 `pet-server`、`pet-web`、`docs`、`scripts`。
- 配置 Java 17、Maven、Vite。
- 建立健康检查接口。

### 批次 1：领域规则

- 定义枚举、常量、属性模型。
- 实现懒结算和健康规则。
- 为 12 小时上限、生病、睡觉、物种修正写单元测试。

### 批次 2：数据与接口

- Liquibase 建表。
- 匿名会话、创建宠物、查询、操作、配置、重置接口。
- 幂等、乐观锁、错误码和 MockMvc 测试。

### 批次 3：前端主流程

- 会话初始化、领养页、主界面。
- Pinia store、API 客户端、状态条、操作区、日志区。

### 批次 4：内容与打磨

- 程序化生成 9 张精灵图。
- 性格台词、音效、动画、响应式布局。
- 三档宽度视觉验收。

### 批次 5：验收

- 完整游玩流程。
- dev 时间推进验证离线、生病、进化。
- 检查密钥、文档一致性、构建命令和 Git 状态。

### 批次 6：异步对战（P1）

- 好友码和宠物快照。
- 确定性自动战斗和战报。
- WebSocket 通知预埋转正式使用。

## 9. 禁止事项

- 禁止在 Controller 中实现游戏规则。
- 禁止在前端重复实现衰减、经验或进化算法。
- 禁止把 WebSocket 引入 MVP 养成主链路。
- 禁止为 MVP 引入 Redis。
- 禁止使用未验证的 `pet-engine` 或类似依赖。
- 禁止在游戏运行时调用 DeepSeek 或其他 LLM。
- 禁止提交 API Key、数据库密码、令牌和个人信息。
- 禁止使用 `git reset --hard`、强制覆盖他人修改等破坏性操作。
- 禁止手改数据库结构而不写 Liquibase changeset。
- 禁止用删除测试、跳过测试或 `--no-verify` 让流程通过。
- 禁止一次性重写多个模块；Flash 模型必须小步提交。
- 禁止在没有验证的情况下声称测试、构建或功能已经通过。

## 10. 完成定义

一个任务只有同时满足以下条件才算完成：

- 功能符合 `PRD.md` 的对应条目。
- 实现符合 `TECH_DESIGN.md` 的分层和数据模型。
- 新增或修改行为有测试覆盖。
- `mvn test` 和 `npm run build` 通过，或明确说明无法运行的原因。
- 没有硬编码密钥、调试输出和临时代码。
- 文档、类型和接口保持一致。
- 变更范围聚焦，没有无关重构。
