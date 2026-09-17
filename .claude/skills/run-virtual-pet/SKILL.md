---
name: run-virtual-pet
description: 构建、启动、运行和驱动「像素宠物屋」全栈项目。启动后端（Spring Boot）、启动前端（Vite/ npm run dev）、跑测试、构建、用无头 Chrome 截图并验收 360/768/1440 三档布局、检查控制台报错、在页面里执行 JS。Use when asked to run the frontend/backend, start the dev server, screenshot the UI, or verify layout at the three PRD widths.
---

像素宠物屋是一个前后端分离的 Web 应用：`pet-server`（Spring Boot 3.5 + Java 17，端口 8080）+ `pet-web`（Vue 3 + Vite，端口 5173）。

**驱动方式**：先起 Vite 开发服务器，再用 `.claude/skills/run-virtual-pet/driver.mjs` 启动无头 Chrome，通过 CDP 在多个视口宽度下测量布局、截图并收集控制台报错。驱动只用 Node 内置模块（`fetch` + 内置 `WebSocket`），**不需要 npm install**。

本文所有路径都相对于仓库根目录 `D:\code\virtual pet`。

## Prerequisites

已经在装的（本机现状，无需重新安装）：

| 依赖 | 版本 | 位置 |
| --- | --- | --- |
| JDK 17 | 17.0.8 | `D:\JDK1` |
| Maven | 3.8.1 | PATH |
| Node.js | 24.18.0 | PATH |
| Chrome（或 Edge） | 152 | `C:\Program Files\Google\Chrome\Application\chrome.exe` |

驱动会自动在以下位置找浏览器，找不到就报错退出（退出码 3）：`CHROME_PATH` 环境变量 → Chrome 的常见安装路径 → Edge 的常见安装路径 → `/usr/bin/google-chrome`。

第一次跑之前装一次前端依赖：

```powershell
npm.cmd --prefix pet-web install
```

## Build

```powershell
# 后端（必须先设置 JAVA_HOME）
$env:JAVA_HOME = 'D:\JDK1'
mvn -f pet-server/pom.xml -B test

# 前端类型检查 + 生产构建
npm.cmd --prefix pet-web run build
```

## Run (agent path)

**第一步，起开发服务器**（后台跑，占住当前终端）：

```powershell
npm.cmd --prefix pet-web run dev
# -> VITE v8.3.0  ready in ~240 ms
# -> Local: http://localhost:5173/
```

**第二步，用驱动打开它并验收**：

```powershell
node .claude/skills/run-virtual-pet/driver.mjs
```

输出示例（在当前批次 0 的骨架页上实际跑出来的）：

```
浏览器: C:/Program Files/Google/Chrome/Application/chrome.exe
目标  : http://localhost:5173/

===== 视口 360px =====
  标题        : 像素宠物屋
  根节点渲染  : yes
  scrollWidth : 360   clientWidth: 360
  横向溢出    : 0px
  页面文本    : 像素宠物屋 批次 0 · 工程骨架 前端骨架已就绪，宠物养成功能将在后续批次实现。
  截图        : D:\code\virtual pet\.artifacts\screenshots\pet-web-360.png
...
✓ 全部通过：无横向溢出，控制台无报错
```

### 驱动命令

| 命令 | 作用 |
| --- | --- |
| `node .claude/skills/run-virtual-pet/driver.mjs` | 默认：360/768/1440 三档测量 + 截图 |
| `--widths 360,1440` | 只测指定宽度 |
| `--out <dir>` | 截图输出目录（默认 `.artifacts/screenshots`） |
| `--eval "<js>"` | 在页面里执行 JS 并打印 JSON 结果（只跑第一个宽度，不截图） |
| `--prepare "<js>"` | 截图前先在页面里跑一段 JS，只在第一个宽度执行一次。用来把界面驱动到要验收的状态，例如先完成领养再看主界面 |
| `--url <url>` | 换一个页面（默认 `http://localhost:5173/`） |
| `--settle <ms>` | 渲染完成后额外等待（默认 800ms，测动画时加长） |
| `--chrome-arg <flag>` | 追加一个浏览器开关，可重复。用来模拟只能靠开关造出来的环境，例如 `--chrome-arg --force-prefers-reduced-motion` |
| `--press <键名>` | 发一次**真实按键**，可重复。支持 `Tab` `Enter` `Escape` `Space` 和四个方向键 |
| `--init-js <js>` | 在**页面脚本开跑之前**执行，用来铺 localStorage |
| `--keep-open` | 跑完不关浏览器，留着手动看 |

`--prepare` 和 `--press` 在 `--eval` 模式下同样生效，顺序是 init-js → 页面加载 → prepare → press → eval。

#### 恢复存档（`--init-js`）

`--prepare` 是在页面加载**之后**跑的，那时候应用早就建好会话了，所以想验证
「刷新页面 / 换浏览器之后存档还在」只能用 `--init-js`：

```powershell
node .claude/skills/run-virtual-pet/driver.mjs --widths 1440 `
  --init-js "localStorage.setItem('virtual-pet.device-id', '<上次那个 deviceId>');" `
  --eval "(() => ({ 名字: document.querySelector('.home-title')?.textContent }))()"
# => { "名字": "存档测试" }
```

先跑一次领养把 `localStorage.getItem('virtual-pet.device-id')` 记下来，重启后端，
再用上面这条带上同一个 deviceId —— 页面会像老玩家回访一样把宠物读回来。

`--eval` 的表达式是**原样**丢给 `Runtime.evaluate` 的，所以多行脚本要自己包一层 IIFE：

```powershell
node .claude/skills/run-virtual-pet/driver.mjs --eval "({ buttons: document.querySelectorAll('button').length, bg: getComputedStyle(document.body).backgroundColor })"
# => { "buttons": 0, "bg": "rgb(31, 61, 43)" }
```

#### 键盘可达性（PRD 4.4）

页面里写 `el.focus()` **不会**命中 `:focus-visible`，验不出焦点框。只有真按键才算数：

```powershell
node .claude/skills/run-virtual-pet/driver.mjs --widths 1440 `
  --prepare "<先领养一只的脚本>" `
  --press Tab --press Tab --press Tab --press Tab --press Tab `
  --eval "(() => { const el = document.activeElement; const cs = getComputedStyle(el); return { 焦点: el.textContent.trim(), 轮廓: cs.outlineWidth + ' ' + cs.outlineStyle } })()"
# => { "焦点": "睡觉", "轮廓": "3px solid" }
```

#### 减弱动画（PRD 2.10）

要量的是真的在动的元素。主界面之前（例如领养页）没有任何动画，随便挑一个元素查 `animationName` 两种模式下都会是 `none`，看不出区别：

```powershell
node .claude/skills/run-virtual-pet/driver.mjs --widths 1440 `
  --prepare "<先领养一只的脚本>" `
  --chrome-arg --force-prefers-reduced-motion `
  --eval "(() => { const s = getComputedStyle(document.querySelector('.pet-sprite')); return { reduced: matchMedia('(prefers-reduced-motion: reduce)').matches, duration: s.animationDuration, name: s.animationName } })()"
# 加上开关 => { "reduced": true,  "duration": "1e-06s", "name": "none" }
# 去掉开关 => { "reduced": false, "duration": "1.8s",   "name": "pet-bob-xxxx" }
```

动画停掉，但**数值和文案反馈照常**：升级提示条依然会出现在页面上，只是不再抖动。

### 退出码

| 码 | 含义 |
| --- | --- |
| 0 | 全部通过：无横向溢出，控制台无报错 |
| 1 | 有横向溢出，或控制台有 error/warning，或驱动自身抛错 |
| 2 | 开发服务器打不开（没启动） |
| 3 | 找不到 Chrome / Edge |

所以这个驱动可以直接当冒烟测试用：`if ($LASTEXITCODE -ne 0) { ... }`。

### 截图落在哪

`.artifacts/screenshots/pet-web-<宽度>.png`（已被 `.gitignore` 忽略）。

**agent 必须真的去看这些 PNG。** 只看驱动打印的 `横向溢出: 0px` 不够 —— 白屏、样式全丢、元素重叠都不会让 `scrollWidth` 溢出，但截图上看得出来。

### 后端（需要时再起）

批次 0 的骨架页不需要后端。需要时另开一个终端：

```powershell
$env:JAVA_HOME = 'D:\JDK1'
mvn -f pet-server/pom.xml -B spring-boot:run
```

验证：

```powershell
curl.exe http://localhost:8080/actuator/health
# => {"status":"UP"}
```

`/api` 代理的三种状态可以拿来快速判断环境（Vite 把 `/api` 转发到 `:8080`）：

| 响应 | 含义 |
| --- | --- |
| `502` | 代理生效，但后端没启动 |
| `404` | 代理生效，后端也在，但没这个接口 |
| `200` | 接口存在（批次 2 之后才会有） |

## Run (human path)

```powershell
npm.cmd --prefix pet-web run dev
```

然后浏览器打开 <http://localhost:5173/>，`Ctrl+C` 停止。人类直接用这个，不用驱动。

## Test

```powershell
$env:JAVA_HOME = 'D:\JDK1'
mvn -f pet-server/pom.xml -B test      # 2 tests
npm.cmd --prefix pet-web run test      # 1 test (Vitest)
npm.cmd --prefix pet-web run build     # vue-tsc + vite build
```

## Gotchas

- **无头 Chrome 的 `--window-size` 不设置布局视口。** 用 `chrome --headless --window-size=360,640 --screenshot=...` 截图时，页面会按一个更宽的默认视口排版，然后图片被裁到 360px —— 看起来就像「右边被切掉、有横向溢出」，但实际布局完全正常。我为这个假象排查了一轮。**正确做法是 CDP 的 `Emulation.setDeviceMetricsOverride`**，驱动里用的就是这个。别再用 `--window-size` 判断响应式布局。

- **绝对不要按进程名杀 Chrome。** `Get-Process chrome | Stop-Process` 会把用户自己开着的浏览器（含未保存的标签页）一起杀掉。驱动给每次运行分配唯一的 `--user-data-dir=<tmp>/pet-cdp-<pid>-<时间戳>`，只杀自己 `spawn` 出来的那个 PID，退出码路径在 `finally` 里。

- **PowerShell 禁止运行 `npm.ps1`。** 本机执行策略会报 `PSSecurityException: 无法加载文件 D:\nvm\nodejs\npm.ps1`。用 `npm.cmd` 替代，不要把执行策略改成 `RemoteSigned` 来绕过。

- **`mvn` 默认用 JDK 21，不是 JDK 17。** 本机 `mvn -v` 显示 `runtime: D:\JDK\JDK_21`，但项目要求 Java 17。每条 Maven 命令前都要 `$env:JAVA_HOME = 'D:\JDK1'`。

- **Chrome 往 stderr 吐无关报错是正常的。** 例如 `external_registry_loader_win.cc: File D:\quark-cloud-drive\...\main.crx ... does not exist`。这是本机装了夸克网盘的浏览器扩展注册项，和你的页面无关。驱动靠累积 stderr 再正则匹配 `DevTools listening on`，不会被这些噪声干扰。

- **`--remote-debugging-port=0` 让系统分配端口。** 不要硬编码 9222 —— 上一轮手动调试时，残留的调试实例会让端口被占用。port 0 由 Chrome 选一个空闲端口，实际端口从它打印的 `DevTools listening on ws://127.0.0.1:<port>/...` 里读出来。

- **驱动零依赖。** 用的是 Node ≥22 内置的 `WebSocket` 和 `fetch`，所以不需要 `npm install`，也不需要 Playwright/puppeteer/chromium-cli（本机都没装）。

## Troubleshooting

- **`✗ 打不开 http://localhost:5173/`（退出码 2）**：开发服务器没起。先 `npm.cmd --prefix pet-web run dev`。

- **`✗ 找不到 Chrome 或 Edge`（退出码 3）**：设 `$env:CHROME_PATH = 'C:\Program Files\Google\Chrome\Application\chrome.exe'`。

- **`npm : 无法加载文件 ...\npm.ps1，因为在此系统上禁止运行脚本`**：改用 `npm.cmd`。

- **`invalid target release: 17` 或编译报 Java 版本错**：`$env:JAVA_HOME` 没设成 `D:\JDK1`。

- **`/api/...` 返回 502**：代理没问题，是后端没起。起后端（见上）。

- **截图是空的/全白**：看驱动打印的「根节点渲染」是不是 `NO`。是 `NO` 就说明前端 JS 没跑起来，先看控制台报错（驱动会打出来），或手动 `curl.exe http://localhost:5173/src/main.ts` 看 Vite 是否编译失败。

- **`[WARNING] Some problems were encountered while building the effective settings` + `Unrecognised tag: 'mirrors'`**：这是本机全局 Maven 配置 `D:\maven\apache-maven-3.8.1\conf\settings.xml:147` 有个嵌套的重复 `<mirrors>` 标签，**与项目无关，不要改**。阿里云镜像仍然生效，构建照常成功。
