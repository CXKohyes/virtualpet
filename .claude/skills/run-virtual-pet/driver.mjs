#!/usr/bin/env node
/**
 * 像素宠物屋 —— 本地浏览器驱动（Chrome DevTools Protocol）
 *
 * 用无头 Chromium 打开 Vite 开发服务器，在指定宽度下测量布局、截图，
 * 并收集控制台报错。仅使用 Node 内置模块，无需 npm install。
 *
 * 用法见同目录 SKILL.md。常用：
 *   node .claude/skills/run-virtual-pet/driver.mjs
 *   node .claude/skills/run-virtual-pet/driver.mjs --widths 360,1440 --out .artifacts/shots
 *   node .claude/skills/run-virtual-pet/driver.mjs --eval "document.querySelectorAll('button').length"
 *
 * 退出码：0 正常；1 页面报错或有横向溢出；2 开发服务器不可达；3 找不到浏览器。
 */

import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const REPO_ROOT = resolve(fileURLToPath(new URL('../../..', import.meta.url)))

// ---------------------------------------------------------------- 参数解析

function parseArgs(argv) {
  const opts = {
    url: 'http://localhost:5173/',
    widths: [360, 768, 1440],
    out: join(REPO_ROOT, '.artifacts', 'screenshots'),
    evalExpr: null,
    prepareExpr: null,
    settleMs: 800,
    keepOpen: false,
  }

  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i]
    const next = () => {
      const value = argv[i + 1]
      if (value === undefined || value.startsWith('--')) {
        throw new Error(`参数 ${arg} 缺少值`)
      }
      i += 1
      return value
    }

    if (arg === '--url') opts.url = next()
    else if (arg === '--widths') opts.widths = next().split(',').map((w) => Number(w.trim()))
    else if (arg === '--out') opts.out = resolve(REPO_ROOT, next())
    else if (arg === '--eval') opts.evalExpr = next()
    else if (arg === '--prepare') opts.prepareExpr = next()
    else if (arg === '--settle') opts.settleMs = Number(next())
    else if (arg === '--keep-open') opts.keepOpen = true
    else if (arg === '--help' || arg === '-h') {
      console.log('用法: node driver.mjs [--url URL] [--widths 360,768,1440] [--out DIR] [--eval JS] [--prepare JS] [--settle MS] [--keep-open]')
      process.exit(0)
    } else {
      throw new Error(`未知参数: ${arg}`)
    }
  }

  if (opts.widths.some((w) => !Number.isFinite(w) || w <= 0)) {
    throw new Error('--widths 必须是正整数，用逗号分隔')
  }
  return opts
}

// ---------------------------------------------------------------- 浏览器定位

function findBrowser() {
  const candidates = [
    process.env.CHROME_PATH,
    process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, 'Google/Chrome/Application/chrome.exe'),
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
    // 非 Windows 环境（CI / 容器）兜底
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
  ].filter(Boolean)

  return candidates.find((p) => existsSync(p)) ?? null
}

/**
 * 启动无头浏览器并等待它打印 DevTools 地址。
 *
 * 用 --remote-debugging-port=0 让系统分配空闲端口，避免和别的调试实例撞端口；
 * 用每次运行唯一的 --user-data-dir，保证后面清理时只杀自己启的进程。
 */
function launchBrowser(binary) {
  const userDataDir = join(tmpdir(), `pet-cdp-${process.pid}-${Date.now()}`)

  const child = spawn(
    binary,
    [
      '--headless',
      '--disable-gpu',
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-extensions',
      '--disable-background-networking',
      '--remote-debugging-port=0',
      `--user-data-dir=${userDataDir}`,
      'about:blank',
    ],
    { stdio: ['ignore', 'pipe', 'pipe'] },
  )

  const devtoolsUrl = new Promise((resolvePromise, rejectPromise) => {
    let buffered = ''
    const timer = setTimeout(() => {
      rejectPromise(new Error('浏览器 20 秒内没有报告 DevTools 地址'))
    }, 20000)

    child.stderr.on('data', (chunk) => {
      buffered += chunk.toString()
      const match = buffered.match(/DevTools listening on (ws:\/\/\S+)/)
      if (match) {
        clearTimeout(timer)
        resolvePromise(match[1])
      }
    })
    child.once('exit', (code) => {
      clearTimeout(timer)
      rejectPromise(new Error(`浏览器提前退出，退出码 ${code}`))
    })
  })

  return { child, userDataDir, devtoolsUrl }
}

/** 关闭浏览器：先杀自己 spawn 的父进程，端口仍存活才按专属 user-data-dir 精确清扫。 */
async function shutdownBrowser({ child, userDataDir }) {
  if (!child.killed) child.kill()
  await new Promise((r) => setTimeout(r, 1500))

  if (existsSync(userDataDir)) {
    try {
      rmSync(userDataDir, { recursive: true, force: true })
    } catch {
      // 目录被占用就留给系统清理，不阻断退出
    }
  }
}

// ---------------------------------------------------------------- CDP 客户端

class Cdp {
  #ws
  #nextId = 1
  #pending = new Map()
  #listeners = new Map()

  static async connect(wsUrl) {
    const client = new Cdp()
    client.#ws = new WebSocket(wsUrl)
    client.#ws.addEventListener('message', (event) => client.#dispatch(event))
    await new Promise((resolvePromise, rejectPromise) => {
      client.#ws.addEventListener('open', resolvePromise, { once: true })
      client.#ws.addEventListener('error', () => rejectPromise(new Error('CDP WebSocket 连接失败')), { once: true })
    })
    return client
  }

  #dispatch(event) {
    const message = JSON.parse(event.data)
    if (message.id && this.#pending.has(message.id)) {
      const { resolve: resolvePromise, reject } = this.#pending.get(message.id)
      this.#pending.delete(message.id)
      if (message.error) reject(new Error(`${message.error.message} (${JSON.stringify(message.error)})`))
      else resolvePromise(message.result)
      return
    }
    if (message.method) {
      for (const handler of this.#listeners.get(message.method) ?? []) handler(message.params)
    }
  }

  on(method, handler) {
    if (!this.#listeners.has(method)) this.#listeners.set(method, [])
    this.#listeners.get(method).push(handler)
  }

  send(method, params = {}) {
    return new Promise((resolvePromise, reject) => {
      const id = this.#nextId++
      this.#pending.set(id, { resolve: resolvePromise, reject })
      this.#ws.send(JSON.stringify({ id, method, params }))
    })
  }

  close() {
    this.#ws.close()
  }
}

// ---------------------------------------------------------------- 页面操作

async function findPageTarget(port) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    try {
      const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()
      const page = targets.find((t) => t.type === 'page')
      if (page?.webSocketDebuggerUrl) return page
    } catch {
      // 调试端口还没起来，继续重试
    }
    await new Promise((r) => setTimeout(r, 250))
  }
  throw new Error('找不到可用的页面 target')
}

/** 轮询直到页面加载完成且根节点渲染出内容；比监听 load 事件更抗竞态。 */
async function waitForRender(cdp, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs
  let lastState = 'unknown'
  while (Date.now() < deadline) {
    const result = await cdp.send('Runtime.evaluate', {
      returnByValue: true,
      expression: `(() => {
        const root = document.querySelector('#app') || document.body
        return {
          readyState: document.readyState,
          rootChildren: root ? root.children.length : 0,
          url: location.href,
        }
      })()`,
    })
    lastState = JSON.stringify(result.result.value)
    const value = result.result.value
    if (value && value.readyState === 'complete' && value.rootChildren > 0) return value
    await new Promise((r) => setTimeout(r, 200))
  }
  throw new Error(`页面在 ${timeoutMs}ms 内没有渲染出内容，最后一次状态: ${lastState}`)
}

const DEFAULT_PROBE = `(() => {
  const de = document.documentElement
  const root = document.querySelector('#app') || document.body
  return {
    title: document.title,
    rootMounted: Boolean(root && root.children.length),
    scrollWidth: de.scrollWidth,
    clientWidth: de.clientWidth,
    overflowX: de.scrollWidth - de.clientWidth,
    scrollHeight: de.scrollHeight,
    clientHeight: de.clientHeight,
    text: (document.body.innerText || '').replace(/\\s+/g, ' ').trim().slice(0, 300),
  }
})()`

async function evaluate(cdp, expression) {
  const result = await cdp.send('Runtime.evaluate', { returnByValue: true, awaitPromise: true, expression })
  if (result.exceptionDetails) {
    throw new Error(`页面内执行出错: ${result.exceptionDetails.exception?.description ?? result.exceptionDetails.text}`)
  }
  return result.result.value
}

// ---------------------------------------------------------------- 主流程

async function main() {
  const opts = parseArgs(process.argv.slice(2))

  // 1. 开发服务器必须已经起来
  try {
    await fetch(opts.url)
  } catch {
    console.error(`\n✗ 打不开 ${opts.url}`)
    console.error('  先启动开发服务器（在仓库根目录，另开一个终端）：')
    console.error('    npm.cmd --prefix pet-web run dev')
    console.error('  注意 PowerShell 执行策略禁止 npm.ps1，必须用 npm.cmd。\n')
    process.exit(2)
  }

  const binary = findBrowser()
  if (!binary) {
    console.error('\n✗ 找不到 Chrome 或 Edge。设置环境变量 CHROME_PATH 指向浏览器可执行文件后重试。\n')
    process.exit(3)
  }

  console.log(`浏览器: ${binary}`)
  console.log(`目标  : ${opts.url}`)

  const browser = launchBrowser(binary)
  const consoleMessages = []
  let cdp

  try {
    const devtoolsWsUrl = await browser.devtoolsUrl
    const port = new URL(devtoolsWsUrl).port
    const page = await findPageTarget(port)

    cdp = await Cdp.connect(page.webSocketDebuggerUrl)
    await cdp.send('Page.enable')
    await cdp.send('Runtime.enable')

    // 收集页面报错：Vue 警告、未捕获异常、console.error 都会进这里
    cdp.on('Runtime.consoleAPICalled', (p) => {
      if (p.type === 'error' || p.type === 'warning') {
        const text = p.args.map((a) => a.value ?? a.description ?? a.type).join(' ')
        consoleMessages.push({ level: p.type, text })
      }
    })
    cdp.on('Runtime.exceptionThrown', (p) => {
      const d = p.exceptionDetails
      consoleMessages.push({ level: 'exception', text: d.exception?.description ?? d.text })
    })

    if (opts.evalExpr) {
      await cdp.send('Emulation.setDeviceMetricsOverride', {
        width: opts.widths[0],
        height: 720,
        deviceScaleFactor: 1,
        mobile: false,
      })
      await cdp.send('Page.navigate', { url: opts.url })
      await waitForRender(cdp)
      const value = await evaluate(cdp, opts.evalExpr)
      console.log(`\n--eval @${opts.widths[0]}px => ${JSON.stringify(value, null, 2)}`)
    } else {
      mkdirSync(opts.out, { recursive: true })
      let sawOverflow = false

      for (const width of opts.widths) {
        await cdp.send('Emulation.setDeviceMetricsOverride', {
          width,
          height: 720,
          deviceScaleFactor: 1,
          mobile: false,
        })
        await cdp.send('Page.navigate', { url: opts.url })
        await waitForRender(cdp)
        await new Promise((r) => setTimeout(r, opts.settleMs))

        // 只在第一个宽度跑一次：同一浏览器会话里 localStorage 是共享的，
        // 后面两个宽度会直接看到 prepare 之后的状态（例如已领养的主界面）。
        if (width === opts.widths[0] && opts.prepareExpr) {
          await evaluate(cdp, opts.prepareExpr)
          await new Promise((r) => setTimeout(r, opts.settleMs))
        }

        const metrics = await evaluate(cdp, DEFAULT_PROBE)
        const shot = await cdp.send('Page.captureScreenshot', { format: 'png' })
        const file = join(opts.out, `pet-web-${width}.png`)
        writeFileSync(file, Buffer.from(shot.data, 'base64'))

        if (metrics.overflowX > 0) sawOverflow = true

        console.log(`\n===== 视口 ${width}px =====`)
        console.log(`  标题        : ${metrics.title}`)
        console.log(`  根节点渲染  : ${metrics.rootMounted ? 'yes' : 'NO  <-- 白屏'}`)
        console.log(`  scrollWidth : ${metrics.scrollWidth}   clientWidth: ${metrics.clientWidth}`)
        console.log(`  横向溢出    : ${metrics.overflowX}px ${metrics.overflowX > 0 ? ' <-- 有问题' : ''}`)
        console.log(`  页面文本    : ${metrics.text.slice(0, 80)}`)
        console.log(`  截图        : ${file}`)
      }

      if (sawOverflow) {
        console.error('\n✗ 存在横向溢出（PRD 2.10 要求三档宽度无横向滚动）')
      }
      if (consoleMessages.length > 0) {
        console.error(`\n✗ 控制台有 ${consoleMessages.length} 条报错/警告：`)
        for (const m of consoleMessages.slice(0, 10)) console.error(`  [${m.level}] ${m.text}`)
      }
      if (!sawOverflow && consoleMessages.length === 0) console.log('\n✓ 全部通过：无横向溢出，控制台无报错')
      if (sawOverflow || consoleMessages.length > 0) process.exitCode = 1
    }

    if (opts.keepOpen) {
      console.log('\n--keep-open 已指定，浏览器保持运行。按 Ctrl+C 结束。')
      await new Promise(() => {})
    }
  } finally {
    cdp?.close()
    if (!opts.keepOpen) await shutdownBrowser(browser)
  }
}

main().catch((error) => {
  console.error(`\n✗ 驱动失败: ${error.message}`)
  process.exitCode = 1
})
