# 像素宠物屋（Virtual Pet）

像素宠物屋是一款怀旧向虚拟宠物养成 Web 应用。玩家领养一只猫、狗或像素龙，通过喂食、玩耍、清洁和睡觉照顾它，看它从幼年成长为最终形态。宠物在离线期间继续变化，并用不同性格的台词回应玩家。

- 产品需求：`PRD.md`
- 技术设计：`TECH_DESIGN.md`
- 开发规则（AI 代理与人类开发者共用）：`AGENTS.md`
- Claude Code 工作约定：`CLAUDE.md`

## 当前进度

已完成 **批次 0–6**：工程骨架、领域规则、数据与接口、前端主流程、内容与打磨、
验收与修复，以及 P1 的异步对战。

养成主链路可以完整游玩：匿名会话 → 领养命名 → 四项照护 → 离线衰减 → 生病与进化 →
照护日志。对战也已打通：好友码 → 发起挑战 → 服务端确定性自动战斗 → 战报 → 实时通知。

正在进行的批次见 `AGENTS.md` 第 8 节，接口契约见 `docs/api.md`。

### 玩一局对战

对战是异步的：**被挑战方不需要在线**，战斗打的是开战那一刻的快照。

1. 打开 <http://localhost:5173>，主界面右上角点「对战」，拿到自己的好友码。
2. 把好友码发给朋友，或者自己开一个**无痕窗口**再领养一只当对手。
3. 在「发起挑战」里填对方的好友码，点「开打」——服务端算完立刻返回战报。
4. 被挑战的一方打开对战页就能在「最近对战」里看到这一场，双方看到的是同一份战报，
   只是「你」和「对手」的位置互换。

战斗**不影响养成**：不打折经验、不掉属性，纯切磋。

## 目录结构

```text
D:\code\virtual pet\
├── AGENTS.md            开发规则
├── PRD.md               产品需求
├── TECH_DESIGN.md       技术设计
├── CLAUDE.md            Claude Code 工作约定
├── README.md            本文件
├── docs\
│   └── api.md           接口契约、错误码与已知取舍
├── scripts\
│   ├── generate_sprites.py   程序化生成 9 张精灵图（不要手改产物）
│   └── acceptance.mjs        对着真实后端跑一遍 PRD 验收项
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
| MySQL | **5.7.44** | 后端启动就要连库，见下方「MySQL 前置条件」 |
| Python | 3.10.6 | 仅重新生成精灵图时需要，需装 Pillow |

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

**先按下面的「MySQL 前置条件」把库和账号准备好**，否则启动会因为连不上库而失败。

```powershell
$env:JAVA_HOME = 'D:\JDK1'
mvn -f pet-server/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev,local
```

两个 profile 各有用途，缺一不可：

| profile | 作用 |
| --- | --- |
| `local` | 读 `application-local.yml` 里的数据库密码。**profile 配置文件只在自己那个 profile 生效**，不加 `local` 就会以空密码连库，报 `Access denied ... (using password: NO)` |
| `dev` | 打开 `/api/v1/dev/**` 下的验收工具（推进时间、铺设状态）。**生产环境绝对不能加** |

如果不建 `application-local.yml`、改用环境变量注入密码，那 `local` 就可以省掉：

```powershell
$env:PET_DB_PASSWORD = '你的本地密码'
mvn -f pet-server/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev
```

启动后访问健康检查：

```powershell
curl.exe http://localhost:8080/actuator/health
# 期望输出：{"status":"UP"}
```

后端默认端口 `8080`。Liquibase 会在启动时自动建表，不需要手工执行 SQL。

### 2. 启动前端

新开一个 PowerShell 窗口：

```powershell
npm.cmd --prefix pet-web run dev
```

打开 <http://localhost:5173>，首次进入是领养页。随便填个名字、选一只宠物就能开始。

Vite 已配置开发代理：`/api` 和 `/ws` 都转发到 `http://localhost:8080`，开发期不需要额外处理跨域或 WebSocket 地址。

> `/ws` 那条代理必须带 `ws: true`。不开的话 Vite 会把它当普通 HTTP 请求转发，
> WebSocket 升级请求到不了后端 —— 表现出来就是对战页一直显示「实时通知未连接」，
> 而且**只有真的开浏览器才看得出来**。

### 3. 停止服务

在各自窗口按 `Ctrl+C`。

## 构建与测试

```powershell
# 后端测试（跑在 H2 的 MySQL 兼容模式上，不需要本机 MySQL）
$env:JAVA_HOME = 'D:\JDK1'
mvn -f pet-server/pom.xml test

# 前端测试
npm.cmd --prefix pet-web run test

# 前端类型检查 + 生产构建
npm.cmd --prefix pet-web run build
```

`pet-web` 支持 `npm run test:watch` 进入 Vitest 监听模式，`npm run type-check` 只做类型检查不打包。

> **上面三条都要跑。** 只跑 `npm run test` 会漏掉类型错误 —— `npm run build` 里的
> `vue-tsc` 才会检查类型，而 Vitest 不做类型检查。

### 重新生成精灵图

9 张精灵是脚本画出来的，**不要手改产物**，要调就改脚本再跑一次：

```powershell
py -3 scripts/generate_sprites.py
```

固定随机种子，重复执行生成的文件逐字节一致。脚本自己会校验尺寸、透明度和颜色数量，
产物落在 `pet-web/src/assets/pets/`，同时还生成一张 `contact-sheet.png` 供肉眼核对。

### 接口验收

对着**真实运行的后端**跑一遍 PRD 7 的验收项（需要后端以 `dev,local` 启动）：

```powershell
node scripts/acceptance.mjs
# 可用 --base 指向别的地址：node scripts/acceptance.mjs --base http://localhost:8080
```

每次运行都用全新的 `deviceId`，可以反复跑、互不污染。覆盖领养、五个操作、冷却、幂等、
离线结算与 12 小时封顶、生病与恢复、进化、存档重置和错误码。

## MySQL 前置条件

> 后端启动就会连库，所以这一步是**必需**的，不是可选项。

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
- 仓库中只保留不含密码的示例文件 `application-local.yml.example`，照着复制一份填密码即可。
- 也可以使用环境变量 `PET_DB_PASSWORD` 注入密码，避免把密码写进任何文件；这是更推荐的做法。
- 建表结构只能通过 Liquibase changeset 修改，禁止手改数据库结构。表在启动时自动创建。

## 常见问题

**`npm` 报 PSSecurityException / 无法加载 npm.ps1**
执行策略禁止运行 PowerShell 脚本。改用 `npm.cmd --prefix pet-web ...`，不要修改系统执行策略。

**Maven 报 `invalid target release: 17` 或编译报 Java 版本错误**
`JAVA_HOME` 没有设置为 `D:\JDK1`。在当前窗口重新执行 `$env:JAVA_HOME = 'D:\JDK1'` 后再构建。

**端口被占用**
后端端口在 `pet-server/src/main/resources/application.yml` 的 `server.port` 修改；前端端口在 `pet-web/vite.config.ts` 的 `server.port` 修改。

**改动被 Git 忽略 / 本地配置不小心被跟踪**
检查 `.gitignore`。`application-local.yml`、`.env`、`target`、`node_modules`、日志和 IDE 文件都不应该进入版本库。

**启动报 `Access denied for user 'pet_app'@'localhost' (using password: NO)`**
密码没被读进来。注意 `using password: NO` 是"一个密码都没传"，不是"密码错了"。
要么激活 `local` profile（`-Dspring-boot.run.profiles=dev,local`），要么设置 `$env:PET_DB_PASSWORD`。
profile 配置文件只在自己那个 profile 生效，只写 `dev` 是不会加载 `application-local.yml` 的。

**`/api/v1/dev/advance-time` 返回 404**
后端没激活 `dev` profile。这个接口只在 `dev` 下注册，换 profile 后要重启后端。

**改完表结构后启动报 `databasechangelog already exists`**
H2 的 `DATABASE_TO_LOWER=TRUE` 会让 Liquibase 认不出自己的表。本项目已经去掉这个参数，
如果本地改回去会复现。

**要排查某个请求**
每个响应都带 `X-Request-Id`，后端日志的每一行也带同一个 ID：

```text
18:25:03.421 INFO  [7f3a1c9e-...] RequestLoggingFilter - POST /api/v1/pets/me/actions -> 200 OK (37ms)
```

也可以自己传 `X-Request-Id` 进来，后端会原样透传（会做字符清洗和截断）。
