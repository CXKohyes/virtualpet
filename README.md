# 像素宠物屋（Virtual Pet）

像素宠物屋是一款怀旧向虚拟宠物养成 Web 应用。玩家领养一只猫、狗或像素龙，通过喂食、玩耍、清洁和睡觉照顾它，看它从幼年成长为最终形态。宠物在离线期间继续变化，并用不同性格的台词回应玩家。

- 产品需求：`PRD.md`
- 技术设计：`TECH_DESIGN.md`
- 开发规则（AI 代理与人类开发者共用）：`AGENTS.md`
- Claude Code 工作约定：`CLAUDE.md`

## 当前进度

已完成 **批次 0：工程骨架**。仓库当前提供可运行的前后端空壳和健康检查，**尚未实现任何宠物业务、数据库表、对战或 WebSocket 功能**。

后续批次见 `AGENTS.md` 第 8 节。

## 目录结构

```text
D:\code\virtual pet\
├── AGENTS.md            开发规则
├── PRD.md               产品需求
├── TECH_DESIGN.md       技术设计
├── CLAUDE.md            Claude Code 工作约定
├── README.md            本文件
├── docs\                接口与数值文档（批次 2 起）
├── scripts\             本地脚本（批次 4 起）
├── pet-server\          Spring Boot 3.5 后端
└── pet-web\             Vue 3 + Vite 前端
```

## 环境要求

| 工具 | 版本 | 本机路径 / 说明 |
| --- | --- | --- |
| JDK | **17** | `D:\JDK1`（Java 17.0.8） |
| Maven | 3.8.1 | `mvn` 已加入 PATH |
| Node.js | 24.18.0 | |
| npm | 11.16.0 | |
| Git | 2.53.0 | |
| MySQL | **5.7.44** | 仅批次 2 起需要，见下方「MySQL 前置条件」 |

## Java 17 设置（必读）

本机 `mvn` 默认使用 JDK 21（`D:\JDK\JDK_21`），但本项目要求 Java 17。**每条 Maven 命令前都必须在当前 PowerShell 会话中显式设置 `JAVA_HOME`**：

```powershell
$env:JAVA_HOME = 'D:\JDK1'
```

这只影响当前 PowerShell 窗口，不会修改系统环境变量。

确认设置生效：

```powershell
$env:JAVA_HOME = 'D:\JDK1'
mvn -v
# 期望输出：Java version: 17.0.8, vendor: Oracle Corporation, runtime: D:\JDK1
```

如果输出的是 `21.0.5 ... D:\JDK\JDK_21`，说明 `JAVA_HOME` 没有设置成功，构建会失败或产生难以排查的兼容问题。

## 本机启动方式（Windows）

以下命令都在项目根目录 `D:\code\virtual pet` 下执行。

### 0. 安装依赖

```powershell
npm.cmd --prefix pet-web install
```

> **PowerShell 注意**：本机执行策略禁止运行 `npm.ps1`，请使用 `npm.cmd`。不要为了省事去改系统执行策略。
> 如果依赖下载失败，请把**具体命令和完整错误**反馈出来，不要自行更换 npm 源或修改全局配置。

### 1. 启动后端

```powershell
$env:JAVA_HOME = 'D:\JDK1'
mvn -f pet-server/pom.xml spring-boot:run
```

启动后访问健康检查：

```powershell
curl.exe http://localhost:8080/actuator/health
# 期望输出：{"status":"UP"}
```

后端默认端口 `8080`。批次 0 不连接数据库，因此**不需要 MySQL 也能启动**。

### 2. 启动前端

新开一个 PowerShell 窗口：

```powershell
npm.cmd --prefix pet-web run dev
```

打开 <http://localhost:5173> 即可看到骨架页面。

Vite 已配置开发代理：前端请求 `/api` 会被转发到 `http://localhost:8080`，因此开发期不需要额外处理跨域。批次 0 后端还没有 `/api` 接口，代理配置在批次 2 开始生效。

### 3. 停止服务

在各自窗口按 `Ctrl+C`。

## 构建与测试

```powershell
# 后端测试
$env:JAVA_HOME = 'D:\JDK1'
mvn -f pet-server/pom.xml test

# 前端测试
npm.cmd --prefix pet-web run test

# 前端类型检查 + 生产构建
npm.cmd --prefix pet-web run build
```

`pet-web` 支持 `npm run test:watch` 进入 Vitest 监听模式，`npm run type-check` 只做类型检查不打包。

## MySQL 前置条件

> 批次 0 不依赖数据库。以下内容在**批次 2（数据与接口）**开始前必须准备好。

本机需要 **MySQL 5.7.44** 运行在 `3306` 端口。项目不会自动安装或修改 MySQL，请自行确认服务已启动。

需要准备的内容：

1. **数据库**：`virtual_pet`，字符集 `utf8mb4`。
2. **测试数据库**：`virtual_pet_test`，仅用于 MySQL 冒烟测试。
3. **开发账号**：`pet_app`，只授予上述数据库的读写权限，不要使用 `root`。

```sql
CREATE DATABASE virtual_pet      DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE virtual_pet_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER 'pet_app'@'localhost' IDENTIFIED BY '<本地密码>';
GRANT ALL PRIVILEGES ON virtual_pet.*      TO 'pet_app'@'localhost';
GRANT ALL PRIVILEGES ON virtual_pet_test.* TO 'pet_app'@'localhost';
FLUSH PRIVILEGES;
```

### 数据库密码不进入仓库

- 本地连接配置写在 `pet-server/src/main/resources/application-local.yml`，该文件已被 `.gitignore` 忽略。
- 仓库中只保留不含密码的示例文件（批次 2 创建）。
- 也可以使用环境变量 `PET_DB_PASSWORD` 注入密码，避免把密码写进任何文件。
- 建表结构只能通过 Liquibase changeset 修改，禁止手改数据库结构。

## 常见问题

**`npm` 报 PSSecurityException / 无法加载 npm.ps1**
执行策略禁止运行 PowerShell 脚本。改用 `npm.cmd --prefix pet-web ...`，不要修改系统执行策略。

**Maven 报 `invalid target release: 17` 或编译报 Java 版本错误**
`JAVA_HOME` 没有设置为 `D:\JDK1`。在当前窗口重新执行 `$env:JAVA_HOME = 'D:\JDK1'` 后再构建。

**端口被占用**
后端端口在 `pet-server/src/main/resources/application.yml` 的 `server.port` 修改；前端端口在 `pet-web/vite.config.ts` 的 `server.port` 修改。

**改动被 Git 忽略 / 本地配置不小心被跟踪**
检查 `.gitignore`。`application-local.yml`、`.env`、`target`、`node_modules`、日志和 IDE 文件都不应该进入版本库。
