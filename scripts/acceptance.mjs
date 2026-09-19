#!/usr/bin/env node
/**
 * 批次 5 验收脚本（PRD 7 验收标准）。
 *
 * 对着**真实运行的后端**跑一遍完整流程，逐条核对 PRD 的验收项。
 * 只用 Node 内置的 fetch，不需要装依赖。
 *
 *   node scripts/acceptance.mjs
 *   node scripts/acceptance.mjs --base http://localhost:8080
 *
 * 每次运行都用全新的 deviceId，所以可以反复跑，不会互相污染存档。
 *
 * 需要后端以 dev profile 启动（进化那几项依赖 /api/v1/dev/advance-time）：
 *
 *   $env:JAVA_HOME = 'D:\JDK1'
 *   mvn -f pet-server/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev,local
 */

import { randomUUID } from 'node:crypto'

const args = process.argv.slice(2)
const baseIndex = args.indexOf('--base')
const BASE = baseIndex >= 0 ? args[baseIndex + 1] : 'http://localhost:8080'

// ---------------------------------------------------------------- 断言

const results = []

function check(name, ok, detail = '') {
  results.push({ name, ok: Boolean(ok), detail })
}

function checkEqual(name, actual, expected) {
  const ok = actual === expected
  check(name, ok, ok ? '' : `期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`)
}

function checkNear(name, actual, expected, tolerance = 1) {
  const ok = typeof actual === 'number' && Math.abs(actual - expected) <= tolerance
  check(name, ok, ok ? '' : `期望约 ${expected}（±${tolerance}），实际 ${actual}`)
}

// ---------------------------------------------------------------- 请求

let token = ''

async function call(method, path, body, { auth = true } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (auth && token) {
    headers.Authorization = `Bearer ${token}`
  }
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  let payload = null
  try {
    payload = await response.json()
  } catch {
    payload = null
  }
  return { status: response.status, body: payload }
}

/** 期待成功的调用：HTTP 2xx 且 code 是 OK。 */
async function ok(method, path, body, options) {
  const result = await call(method, path, body, options)
  if (result.status >= 400 || result.body?.code !== 'OK') {
    throw new Error(`${method} ${path} 失败: ${result.status} ${JSON.stringify(result.body)}`)
  }
  return result.body.data
}

/** 期待失败的调用，返回错误码。 */
async function fails(method, path, body, options) {
  const result = await call(method, path, body, options)
  if (result.status < 400) {
    throw new Error(
      `${method} ${path} 本该失败，却返回 ${result.status}：${JSON.stringify(body ?? {})} -> ${JSON.stringify(result.body?.data)}`,
    )
  }
  return result.body?.code
}

/** 每个验收场景用一台全新"设备"，互不影响。 */
async function newPlayer() {
  const deviceId = `acceptance-${randomUUID()}`
  token = ''
  const session = await ok('POST', '/api/v1/session', { deviceId }, { auth: false })
  token = session.token
  return session
}

const HOUR = 1000 * 60 * 60

// ---------------------------------------------------------------- 各场景

/** 验收 1：首次打开可以选择猫、狗或龙并完成命名。 */
async function acceptAdoption() {
  for (const [species, name] of [
    ['CAT', '咪咪'],
    ['DOG', '旺财'],
    ['DRAGON', '小蓝'],
  ]) {
    await newPlayer()
    const pet = await ok('POST', '/api/v1/pets', { species, name })
    checkEqual(`领养 ${species} 成功且名字正确`, pet.name, name)
    checkEqual(`${species} 初始等级 1`, pet.level, 1)
    checkEqual(`${species} 初始形态为幼年`, pet.evolutionStage, 0)
    checkEqual(`${species} 初始健康 100`, pet.health, 100)
    check(`${species} 四项核心属性初始 80`, [pet.satiety, pet.mood, pet.hygiene, pet.energy].every((v) => v === 80))

    // 原来这里断言的是「第二次领养必须被 409 拦住」，那是单宠物时代的规则。
    // 多宠物槽之后重复领养是正常操作，不变的是**不能覆盖**已有那只。
    const second = await ok('POST', '/api/v1/pets', { species, name: `${name}二号` })
    checkEqual(`再领养一只 ${species} 占用 1 号槽`, second.slot, 1)
    const roster = await ok('GET', '/api/v1/pets')
    checkEqual(`再领养没有覆盖原存档`, roster.length, 2)
    checkEqual(`原存档仍在 0 号槽且名字没变`, roster.find((item) => item.slot === 0).name, name)

    // 非法名字
    await newPlayer()
    checkEqual('非法名字被拒绝', await fails('POST', '/api/v1/pets', { species, name: 'a b!' }), 'INVALID_NAME')
    checkEqual('非法物种被拒绝', await fails('POST', '/api/v1/pets', { species: 'FISH', name: 'x' }), 'INVALID_REQUEST')
  }
}

/** 验收 2：主界面展示五项状态、精灵、四个操作和日志（这里验服务端侧）。 */
async function acceptActions() {
  await newPlayer()
  const created = await ok('POST', '/api/v1/pets', { species: 'CAT', name: '咪咪' })
  check('宠物对象带齐五项属性', ['satiety', 'mood', 'hygiene', 'energy', 'health'].every((k) => typeof created[k] === 'number'))

  const requestId = () => randomUUID()

  // ---- 喂食 ----
  const feed = await ok('POST', '/api/v1/pets/me/actions', { action: 'FEED', clientRequestId: requestId() })
  checkNear('喂食后饱食 +30（被封顶到 100）', feed.pet.satiety, 100)
  checkEqual('喂食返回经验 6', feed.xpGained, 6)
  check('喂食返回冷却时间', typeof feed.cooldownUntil === 'string')
  check('喂食写入了日志条目', typeof feed.journalEntry?.id === 'number')
  checkEqual('喂食日志的动作名', feed.journalEntry?.action, 'FEED')

  // 冷却中重复点必须被拒绝（服务端顺序：结算 → 幂等 → 冷却 → 条件校验）
  checkEqual('冷却中再次喂食返回 429', await fails('POST', '/api/v1/pets/me/actions', { action: 'FEED', clientRequestId: requestId() }), 'ACTION_COOLDOWN')

  // 饱食 100 时喂食无效果 —— 换一只新宠物，免得被上面那条冷却挡住
  await newPlayer()
  await ok('POST', '/api/v1/pets', { species: 'CAT', name: '咪咪' })
  await ok('POST', '/api/v1/pets/me/actions', { action: 'FEED', clientRequestId: requestId() })

  // ---- 幂等：同一个 clientRequestId 重复提交 ----
  const idem = requestId()
  await newPlayer()
  await ok('POST', '/api/v1/pets', { species: 'DOG', name: '旺财' })
  const first = await ok('POST', '/api/v1/pets/me/actions', { action: 'PLAY', clientRequestId: idem })
  const replay = await ok('POST', '/api/v1/pets/me/actions', { action: 'PLAY', clientRequestId: idem })
  checkEqual('幂等重放返回同一条日志', replay.journalEntry.id, first.journalEntry.id)
  checkEqual('幂等重放不重复扣精力', replay.pet.energy, first.pet.energy)
  checkEqual('幂等重放不重复加经验', replay.pet.exp, first.pet.exp)
  checkEqual('幂等重放不会绕过冷却', typeof replay.cooldownUntil, 'string')

  // ---- 清洁 / 睡觉 / 唤醒（玩耍刚用过，冷却中） ----
  const clean = await ok('POST', '/api/v1/pets/me/actions', { action: 'CLEAN', clientRequestId: requestId() })
  checkEqual('清洁返回经验 6', clean.xpGained, 6)
  check('清洁提升清洁度', clean.pet.hygiene > 0)

  const sleep = await ok('POST', '/api/v1/pets/me/actions', { action: 'SLEEP', clientRequestId: requestId() })
  checkEqual('睡觉后状态为 SLEEPING', sleep.pet.status, 'SLEEPING')
  check('睡觉写入了入睡时刻', typeof sleep.pet.sleepingSince === 'string')
  checkEqual('睡觉没有冷却', sleep.cooldownUntil, null)

  const wake = await ok('POST', '/api/v1/pets/me/actions', { action: 'WAKE', clientRequestId: requestId() })
  checkEqual('唤醒后不再处于睡觉状态', wake.pet.sleepingSince, null)
  check('唤醒后状态不再是 SLEEPING', wake.pet.status !== 'SLEEPING')

  // 清醒时唤醒无效果
  checkEqual('清醒时唤醒返回 409', await fails('POST', '/api/v1/pets/me/actions', { action: 'WAKE', clientRequestId: requestId() }), 'ACTION_NO_EFFECT')

  // ---- 玩耍：换一只干净的宠物，免得撞上冷却 ----
  await newPlayer()
  await ok('POST', '/api/v1/pets', { species: 'DRAGON', name: '小蓝' })
  const play = await ok('POST', '/api/v1/pets/me/actions', { action: 'PLAY', clientRequestId: requestId() })
  checkEqual('玩耍返回经验 8', play.xpGained, 8)
  check('玩耍消耗精力', play.pet.energy < 80)
  check('玩耍提升心情', play.pet.mood > 80)

  // 非法操作。空值和未知操作都归 INVALID_ACTION（docs/api.md 1.2 错误码表）
  checkEqual('未知操作返回 400', await fails('POST', '/api/v1/pets/me/actions', { action: 'DANCE', clientRequestId: requestId() }), 'INVALID_ACTION')
  checkEqual('空操作返回 400', await fails('POST', '/api/v1/pets/me/actions', { action: '', clientRequestId: requestId() }), 'INVALID_ACTION')
  checkEqual('缺少 clientRequestId 返回 400', await fails('POST', '/api/v1/pets/me/actions', { action: 'FEED' }), 'INVALID_REQUEST')

  // ---- 日志 ----
  const journal = await ok('GET', '/api/v1/pets/me/journal?limit=20')
  check('日志拿得到刚才那次玩耍', Array.isArray(journal) && journal.length >= 1)
  check('日志带 messageKey 供前端选台词', journal.every((entry) => typeof entry.messageKey === 'string'))
  check('日志新的在前', journal.length < 2 || journal[0].id > journal[1].id)
  check('日志条数受 limit 限制', (await ok('GET', '/api/v1/pets/me/journal?limit=1')).length === 1)
}

/** 验收 4 + 5：时间推进、12 小时封顶。 */
async function acceptOfflineDecay() {
  // ---- 8 小时：状态下降但不生病 ----
  await newPlayer()
  await ok('POST', '/api/v1/pets', { species: 'CAT', name: '咪咪' })
  let pet = await ok('POST', '/api/v1/dev/advance-time', { hours: 8 })
  checkEqual('离开 8 小时按 8 小时结算', pet.settlement?.settledHours, 8)
  check('8 小时后饱食下降', pet.satiety < 80)
  check('8 小时后心情下降', pet.mood < 80)
  check('8 小时后仍健康（不生病）', pet.status !== 'SICK')
  check('8 小时不足以扣健康', pet.health === 100)

  // ---- 12 小时 ----
  pet = await ok('POST', '/api/v1/dev/advance-time', { hours: 12 })
  checkEqual('离开 12 小时按 12 小时结算', pet.settlement?.settledHours, 12)

  // ---- 24 小时：封顶 12 ----
  const capped = await ok('POST', '/api/v1/dev/advance-time', { hours: 24 })
  checkEqual('离开 24 小时只按 12 小时结算（PRD 2.5）', capped.settlement?.settledHours, 12)
  check('结算摘要给出属性净变化', typeof capped.settlement?.deltas?.satiety === 'number')
  check('结算摘要给出状态前后', typeof capped.settlement?.statusBefore === 'string' && typeof capped.settlement?.statusAfter === 'string')
}

/** 验收 5：健康低于 30 进入生病，恢复到 50 以上解除。 */
async function acceptSickness() {
  // ---- 真实衰减路径：一路饿下去，健康按小时流失 ----
  await newPlayer()
  await ok('POST', '/api/v1/pets', { species: 'DOG', name: '旺财' })

  let pet = null
  for (let i = 0; i < 6 && (pet === null || pet.health >= 30); i += 1) {
    pet = await ok('POST', '/api/v1/dev/advance-time', { hours: 12 })
  }
  check('饿下去健康能跌破 30', pet.health < 30)
  checkEqual('健康低于 30 进入生病', pet.status, 'SICK')
  check('健康归零宠物也不死（PRD 2.3）', pet.health >= 0)

  // ---- 滞回：30–49 之间保持原状 ----
  await newPlayer()
  await ok('POST', '/api/v1/pets', { species: 'DOG', name: '旺财' })
  await ok('POST', '/api/v1/dev/set-state', { health: 29 })
  checkEqual('健康 29 → 生病', (await ok('GET', '/api/v1/pets/me')).status, 'SICK')
  checkEqual('健康回到 40 → 仍然生病', (await ok('POST', '/api/v1/dev/set-state', { health: 40 })).status, 'SICK')
  checkEqual('健康回到 49 → 仍然生病', (await ok('POST', '/api/v1/dev/set-state', { health: 49 })).status, 'SICK')
  check('健康到 50 → 解除生病', (await ok('POST', '/api/v1/dev/set-state', { health: 50 })).status !== 'SICK')

  // ---- 恢复流程：补满四项后靠时间把健康养回来 ----
  await newPlayer()
  await ok('POST', '/api/v1/pets', { species: 'DOG', name: '旺财' })
  await ok('POST', '/api/v1/dev/set-state', { health: 25 })
  checkEqual('健康 25 → 生病', (await ok('GET', '/api/v1/pets/me')).status, 'SICK')

  // 一次结算最多 8 小时：饱食每小时 -5，而恢复要求它高于 60（100-5×8 正好卡在 60）。
  // 这里每段走 6 小时，留出余量。
  let recovered = null
  for (let round = 1; round <= 4 && recovered === null; round += 1) {
    await ok('POST', '/api/v1/dev/set-state', { satiety: 100, mood: 100, hygiene: 100, energy: 100 })
    const after = await ok('POST', '/api/v1/dev/advance-time', { hours: 6 })
    if (after.health >= 50 && after.status !== 'SICK') {
      recovered = after
    }
    check(`第 ${round} 轮恢复后健康确实在涨`, after.health > 25)
  }
  check('持续照护能恢复到 50 以上', recovered !== null)
}

/** 验收 6：依次进化到成长形态和最终形态。 */
async function acceptEvolution() {
  // 经验要 600 点，而每次照护只有 6–8 点还带 60 秒冷却（PRD 2.7 的正常节奏是
  // 第 16 天到 8 级），所以用 dev 接口把经验铺上去，再走真实的等级与进化判定。
  await newPlayer()
  await ok('POST', '/api/v1/pets', { species: 'DRAGON', name: '小蓝' })

  let pet = await ok('POST', '/api/v1/dev/set-state', { exp: 150 })
  checkEqual('经验 150 → 4 级', pet.level, 4)
  checkEqual('4 级且健康达标 → 成长形态', pet.evolutionStage, 1)

  pet = await ok('POST', '/api/v1/dev/set-state', { exp: 490 })
  checkEqual('经验 490 → 8 级', pet.level, 8)
  checkEqual('8 级且条件达标 → 最终形态', pet.evolutionStage, 2)

  // 属性掉下去也不能退化
  const degraded = await ok('POST', '/api/v1/dev/set-state', {
    health: 5, satiety: 0, mood: 0, hygiene: 0, energy: 0,
  })
  checkEqual('进化不可逆', degraded.evolutionStage, 2)

  // 等级阈值边界：换一只新宠物验，老那只已经进化过、降不回去了
  await newPlayer()
  await ok('POST', '/api/v1/pets', { species: 'CAT', name: '咪咪' })
  checkEqual('新宠物是 1 级幼年形态', (await ok('GET', '/api/v1/pets/me')).evolutionStage, 0)
  checkEqual('经验 39 仍停在 1 级', (await ok('POST', '/api/v1/dev/set-state', { exp: 39 })).level, 1)
  checkEqual('经验 40 → 2 级', (await ok('POST', '/api/v1/dev/set-state', { exp: 40 })).level, 2)
  checkEqual('经验 720 → 满级 10 级', (await ok('POST', '/api/v1/dev/set-state', { exp: 720 })).level, 10)
  checkEqual('经验给再多也不超过 10 级', (await ok('POST', '/api/v1/dev/set-state', { exp: 9999 })).level, 10)
}

/** 验收 7：送走宠物（原「存档重置」）。 */
async function acceptRelease() {
  await newPlayer()
  const pet = await ok('POST', '/api/v1/pets', { species: 'CAT', name: '咪咪' })
  await ok('POST', '/api/v1/pets/me/actions', { action: 'FEED', clientRequestId: randomUUID() })

  const before = await ok('GET', '/api/v1/pets/me/journal')
  check('送走前有日志', before.length > 0)

  // 路由从 DELETE /pets/me 改成按 ID：多宠物之下「me」有歧义
  await ok('DELETE', `/api/v1/pets/${pet.id}`)
  checkEqual('送走后不再有宠物', await fails('GET', '/api/v1/pets/me'), 'PET_NOT_FOUND')
  checkEqual('送走后日志一并清空', await fails('GET', '/api/v1/pets/me/journal'), 'PET_NOT_FOUND')

  // 重复送走同一只不是幂等的：对象已经没了就该说没了，
  // 客户端也能据此知道自己的名册是旧的
  checkEqual('重复送走返回 404', await fails('DELETE', `/api/v1/pets/${pet.id}`), 'PET_NOT_FOUND')

  const again = await ok('POST', '/api/v1/pets', { species: 'DOG', name: '旺财' })
  checkEqual('送走后可以立刻重新领养', again.name, '旺财')
  checkEqual('重新领养回到幼年形态', again.evolutionStage, 0)
  checkEqual('重新领养占用 0 号槽', again.slot, 0)
}

/** P2：多宠物槽（PRD 2.1）。 */
async function acceptMultiPet() {
  await newPlayer()
  const first = await ok('POST', '/api/v1/pets', { species: 'CAT', name: '甲' })
  const second = await ok('POST', '/api/v1/pets', { species: 'DOG', name: '乙' })

  checkEqual('第一只占 0 号槽', first.slot, 0)
  checkEqual('第二只占 1 号槽', second.slot, 1)
  checkEqual('领养后新来的成为当前宠物', second.active, true)

  const roster = await ok('GET', '/api/v1/pets')
  checkEqual('名册返回两只', roster.length, 2)
  checkEqual('名册按槽位升序', roster.map((p) => p.slot).join(','), '0,1')
  checkEqual('名册里只有一只是当前宠物', roster.filter((p) => p.active).length, 1)
  checkEqual('名册标出的当前宠物正确', roster.find((p) => p.active).id, second.id)
  check('名册里的宠物都带状态', roster.every((p) => typeof p.status === 'string' && p.status.length > 0))

  const switched = await ok('POST', '/api/v1/pets/me/active', { petId: first.id })
  checkEqual('切换返回目标那只', switched.id, first.id)
  checkEqual('切换后 /pets/me 跟着变', (await ok('GET', '/api/v1/pets/me')).id, first.id)

  await ok('DELETE', `/api/v1/pets/${first.id}`)
  checkEqual('送走当前宠物后自动落到剩下那只', (await ok('GET', '/api/v1/pets/me')).id, second.id)

  const third = await ok('POST', '/api/v1/pets', { species: 'DRAGON', name: '丙' })
  checkEqual('空出来的 0 号槽被重新用上', third.slot, 0)

  await ok('POST', '/api/v1/pets', { species: 'CAT', name: '丁' })
  checkEqual('槽位满了返回 409', await fails('POST', '/api/v1/pets', { species: 'CAT', name: '戊' }),
    'PET_SLOTS_FULL')

  // 拿别人的宠物 ID 来切换，和拿一个不存在的 ID 回报同一个错 ——
  // 能区分就等于给了一个探测别人存档是否存在的接口。
  //
  // 顺序要紧：**先**让「别人」领一只，**再**换成另一个玩家去切换它。
  // 反过来写的话那只宠物属于新玩家自己，切换成功是正确行为，
  // 这条断言就成了空转（第一次就是这么写错的，验收脚本把它抓出来了）。
  await newPlayer()
  const strangers = await ok('POST', '/api/v1/pets', { species: 'DOG', name: '别人的' })

  await newPlayer()
  checkEqual('切换别人的宠物返回 404', await fails('POST', '/api/v1/pets/me/active', { petId: strangers.id }),
    'PET_NOT_FOUND')
}

/** 验收 8：主要错误提示。 */
async function acceptErrors() {
  await newPlayer()

  checkEqual('没有宠物时查询返回 404', await fails('GET', '/api/v1/pets/me'), 'PET_NOT_FOUND')

  // 无令牌 / 坏令牌
  token = ''
  checkEqual('没有令牌返回 401', await fails('GET', '/api/v1/pets/me'), 'UNAUTHORIZED')
  token = 'not-a-real-token'
  checkEqual('伪造令牌返回 401', await fails('GET', '/api/v1/pets/me'), 'UNAUTHORIZED')

  await newPlayer()
  checkEqual('不存在的接口返回 404', await fails('GET', '/api/v1/does-not-exist'), 'NOT_FOUND')

  // 鉴权先于路由：无令牌访问不存在的路径也该先 401
  token = ''
  checkEqual('无令牌访问未知路径先返回 401', await fails('GET', '/api/v1/nope'), 'UNAUTHORIZED')
}

/** 验收 8：游戏配置（前端展示物种特性用）。 */
async function acceptConfig() {
  const config = await ok('GET', '/api/v1/game/config', undefined, { auth: false })
  checkEqual('配置返回三个物种', config.species?.length, 3)
  check('配置返回四种以上操作', (config.actions?.length ?? 0) >= 4)
  checkEqual('离线封顶 12 小时', config.offlineCapHours, 12)
  checkEqual('最高 10 级', config.maxLevel, 10)
  checkEqual('配置返回槽位上限 3', config.maxSlots, 3)
  check('配置带进化条件', Array.isArray(config.evolution) && config.evolution.length > 0)
}

/** P1：异步对战（PRD 2.11）。 */
async function acceptBattle() {
  // 挑战方
  await newPlayer()
  await ok('POST', '/api/v1/pets', { species: 'DRAGON', name: '小蓝' })
  const myCode = (await ok('GET', '/api/v1/players/me/friend-code')).friendCode
  const challengerToken = token
  check('好友码是 8 位且不含形近字符', /^[A-HJ-NP-Z2-9]{8}$/.test(myCode))
  checkEqual('好友码幂等', (await ok('GET', '/api/v1/players/me/friend-code')).friendCode, myCode)

  // 自己打自己必须在**当前还是这个玩家**的时候验，换了令牌就成正常挑战了
  checkEqual('不能挑战自己', await fails('POST', '/api/v1/battles', { friendCode: myCode }), 'SELF_CHALLENGE')
  checkEqual('好友码不存在', await fails('POST', '/api/v1/battles', { friendCode: 'ZZZZZZZZ' }), 'FRIEND_CODE_NOT_FOUND')

  // 被挑战方
  await newPlayer()
  await ok('POST', '/api/v1/pets', { species: 'CAT', name: '咪咪' })
  const defenderCode = (await ok('GET', '/api/v1/players/me/friend-code')).friendCode

  // 回到挑战方发起
  token = challengerToken
  const battle = await ok('POST', '/api/v1/battles', { friendCode: defenderCode })
  checkEqual('对战立刻完成', battle.status, 'FINISHED')
  checkEqual('挑战方视角正确', battle.viewer, 'CHALLENGER')
  check('战报有逐回合记录', Array.isArray(battle.timeline) && battle.timeline.length > 0)
  check('战报带固定种子', typeof battle.seed === 'number')
  check('胜负已判定', battle.winner !== undefined)
  checkEqual('回合数对得上', battle.rounds, battle.timeline.length)
  check(
    '每个事件都带双方血量',
    battle.timeline.every((round) =>
      round.events.every((e) => typeof e.challengerHpAfter === 'number' && typeof e.defenderHpAfter === 'number')),
  )
  check(
    '血量不会变成负数',
    battle.timeline.every((round) =>
      round.events.every((e) => e.challengerHpAfter >= 0 && e.defenderHpAfter >= 0)),
  )

  // 战斗不该影响养成
  const petAfter = await ok('GET', '/api/v1/pets/me')
  checkEqual('对战不改变等级', petAfter.level, 1)
  checkEqual('对战不给经验', petAfter.exp, 0)

  // 局外人查不到：记录里有双方宠物的完整状态，只有参战双方能看
  await newPlayer()
  checkEqual('第三个人看不到这场对战', await fails('GET', `/api/v1/battles/${battle.id}`), 'BATTLE_NOT_FOUND')

  token = challengerToken
  const list = await ok('GET', '/api/v1/battles')
  check('最近对战里有这一场', list.some((item) => item.id === battle.id))
  check('列表项带对手名字', typeof list[0]?.opponentName === 'string')

  const topic = await ok('GET', '/api/v1/battles/topic')
  checkEqual('通知主题前缀', topic.prefix, '/topic/battles/')
}

// ---------------------------------------------------------------- 主流程

async function main() {
  try {
    const health = await fetch(`${BASE}/actuator/health`).then((r) => r.json())
    if (health.status !== 'UP') {
      throw new Error('后端没有就绪')
    }
  } catch (cause) {
    console.error(`\n✗ 连不上 ${BASE}：${cause.message}`)
    console.error('  先启动后端（dev profile 才有时间推进接口）：')
    console.error("    $env:JAVA_HOME = 'D:\\JDK1'")
    console.error('    mvn -f pet-server/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev,local\n')
    process.exit(2)
  }

  console.log(`验收目标: ${BASE}\n`)

  const scenarios = [
    ['1. 领养与命名', acceptAdoption],
    ['2. 五个操作、冷却与幂等', acceptActions],
    ['4/5. 离线结算与 12 小时封顶', acceptOfflineDecay],
    ['5. 生病与恢复', acceptSickness],
    ['6. 成长与最终进化', acceptEvolution],
    ['7. 送走宠物', acceptRelease],
    ['8. 错误提示', acceptErrors],
    ['8. 游戏配置', acceptConfig],
    ['P1. 异步对战', acceptBattle],
    ['P2. 多宠物槽', acceptMultiPet],
  ]

  for (const [title, run] of scenarios) {
    process.stdout.write(`── ${title}\n`)
    try {
      await run()
    } catch (cause) {
      check(`${title} 整段执行`, false, cause.message)
    }
  }

  const failed = results.filter((r) => !r.ok)
  console.log(`\n共 ${results.length} 项断言，通过 ${results.length - failed.length}，失败 ${failed.length}\n`)

  for (const item of results) {
    console.log(`  ${item.ok ? '✓' : '✗'} ${item.name}${item.detail ? ` —— ${item.detail}` : ''}`)
  }

  if (failed.length > 0) {
    console.log(`\n✗ 验收未通过：${failed.length} 项失败`)
    process.exit(1)
  }
  console.log('\n✓ PRD 7 的服务端验收项全部通过')
}

await main()
