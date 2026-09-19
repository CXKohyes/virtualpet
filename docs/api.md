# 像素宠物屋 API

> 覆盖到 P2 的多宠物槽。数值规则以 `pet-server/src/main/java/com/virtualpet/game` 为准。
> 文档与代码不一致时以代码为准，并同步修改本文。

## 1. 通用约定

- 所有业务接口前缀 `/api/v1`。
- 时间一律是 UTC 的 ISO-8601 字符串（`2026-09-17T12:00:00Z`），前端负责转本地时区显示。
- 请求和响应都是 JSON，UTF-8。
- 需要鉴权的接口带 `Authorization: Bearer <token>`。
- 每个响应都带 `X-Request-Id` 响应头。客户端可以传入自己的 `X-Request-Id`，服务端会清理非法字符、限制长度并回显；不传时由服务端生成。
- 服务端为每个 HTTP 请求记录 `requestId`、HTTP 方法、路径、HTTP 状态、业务结果码和耗时（PRD 6.6）。日志只记录 request URI，不记录查询字符串、请求头或请求体。

### 1.1 统一响应结构

成功和失败使用同一个信封：

```json
{
  "code": "OK",
  "message": "success",
  "data": {},
  "serverTime": "2026-09-17T12:00:00Z"
}
```

失败时 `data` 为 `null`，HTTP 状态码非 2xx：

```json
{
  "code": "ACTION_COOLDOWN",
  "message": "它还在缓一缓，稍等一下",
  "data": null,
  "serverTime": "2026-09-17T12:00:00Z"
}
```

`serverTime` 由服务端统一填充，客户端不要自己推断服务器时间。

### 1.2 错误码

| HTTP | code | 场景 |
| ---: | --- | --- |
| 400 | `INVALID_ACTION` | 操作名非法（空值、未知操作） |
| 400 | `INVALID_NAME` | 宠物名字非法 |
| 400 | `INVALID_REQUEST` | 请求体缺失、JSON 格式错误、字段校验失败、**路径参数类型不对** |
| 401 | `UNAUTHORIZED` | 令牌缺失或无效 |
| 404 | `PET_NOT_FOUND` | 尚未领养宠物 |
| 404 | `NOT_FOUND` | 接口不存在 |
| 409 | `PET_SLOTS_FULL` | 三个槽位都占用了，领养不下 |
| 409 | `ACTION_NO_EFFECT` | 当前操作无效果（`message` 给出具体原因） |
| 409 | `CONFLICT` | 乐观锁冲突且重试用尽 |
| 429 | `ACTION_COOLDOWN` | 操作冷却中（游戏规则，换个人来点也一样） |
| 429 | `RATE_LIMITED` | 请求过于频繁（基础设施保护，按来源 IP 分桶） |
| 404 | `FRIEND_CODE_NOT_FOUND` | 好友码不存在 |
| 409 | `SELF_CHALLENGE` | 拿自己的好友码挑战自己 |
| 409 | `OPPONENT_NO_PET` | 对方还没有领养宠物 |
| 404 | `BATTLE_NOT_FOUND` | 对战记录不存在，或与当前玩家无关 |
| 500 | `INTERNAL_ERROR` | 未预期的服务端错误 |

鉴权先于路由：`/api/v1/**` 下即使是**不存在**的路径，没有令牌也会先返回 401。

**限流先于鉴权**：`RateLimitFilter` 跑在 DispatcherServlet 之前，所以超限时
**不带令牌也会返回 429**（而不是 401），响应里带 `Retry-After`（秒）。
默认阈值见 `application.yml` 的 `app.rate-limit`；开发档和测试档是关掉的。

---

## 2. 接口

### 2.1 建立匿名会话（免鉴权）

```http
POST /api/v1/session
Content-Type: application/json

{ "deviceId": "8f6f2d0e-..." }
```

- `deviceId` 由浏览器生成并保存在本地，1–64 字符。
- 同一 `deviceId` 反复调用**不会新建玩家**，但每次都会**换发新令牌**，旧令牌立即失效。
- 服务端只保存令牌的 SHA-256，明文令牌只在这里返回一次。
- `players.last_seen_at` 在这个接口更新（不在每个请求上更新，避免拦截器里写库）。

**`pet` 字段是结算过的，并且带着 `settlement` 摘要。** 打开应用时它往往就是
「回访」的那一刻，所以客户端应当**直接使用它**，不要再紧跟着发一次 `GET /pets/me`：

服务端的每条读取路径都会先做懒结算，所以第二次读的时候时间已经被第一次结算完了，
`settlement` 会变成 `null` —— 离线变化摘要就这样被读丢了。本项目的做法是
会话响应抵达时把宠物种进 petStore，之后只额外拉一次照护日志。

响应：

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "playerId": 1,
    "token": "一次性返回的随机令牌",
    "pet": null
  },
  "serverTime": "2026-09-17T12:00:00Z"
}
```

已有宠物时 `pet` 返回结算后的宠物对象（结构见 2.3）。

### 2.2 领养宠物

```http
POST /api/v1/pets
Authorization: Bearer <token>

{ "species": "CAT", "name": "咪咪" }
```

- `species`：`CAT` / `DOG` / `DRAGON` / `RABBIT`，非法值返回 400。
- `name`：去掉首尾空格后 1–8 个字符，只允许字母和数字（涵盖中文、英文和数字）；
  空白、标点、HTML 特殊字符、表情一律 400 `INVALID_NAME`。
- 一名玩家最多 3 只（PRD 2.1，多宠物槽），槽位满了返回 409 `PET_SLOTS_FULL`，
  不覆盖任何已有存档。上限由配置接口的 `maxSlots` 给出。
- 新领养的自动占用**最小的空闲槽位**，并成为当前宠物。

初始状态：四项核心属性 80，健康 100，1 级，0 经验，幼年形态，状态 `NORMAL`。

> PRD 2.2 没有规定初始数值。取 80 是为了让玩家一进游戏就能立刻喂食和清洁（两者门槛都是 95），
> 同时精力 80 高于睡觉门槛 70，一上来还不能睡。

响应 `data` 是宠物对象（结构见 2.3）。

### 2.3 查询当前宠物

```http
GET /api/v1/pets/me
Authorization: Bearer <token>
```

读取时会先做懒结算（离线衰减），返回结算后的完整状态。

**多宠物槽之后，`/pets/me` 指的是「当前宠物」**（PRD 2.1）。于是四个操作、
照护日志、对战这些既有接口的语义都不用改 —— 它们本来就作用在这一只上。
全部宠物见 [2.13 名册](#213-名册)，换一只见 [2.14 切换当前宠物](#214-切换当前宠物)。

响应 `data`：

```json
{
  "id": 1,
  "slot": 0,
  "active": true,
  "species": "CAT",
  "name": "咪咪",
  "satiety": 80,
  "mood": 80,
  "hygiene": 80,
  "energy": 80,
  "health": 100,
  "status": "NORMAL",
  "level": 1,
  "exp": 0,
  "evolutionStage": 0,
  "sleepingSince": null,
  "lastSettledAt": "2026-09-17T12:00:00Z",
  "cooldowns": {},
  "settlement": null
}
```

- `status`：`NORMAL` / `HUNGRY` / `DIRTY` / `TIRED` / `SAD` / `SICK` / `SLEEPING`，
  按 `SLEEPING > SICK > HUNGRY > TIRED > DIRTY > SAD > NORMAL` 取最高优先级。
  多项异常同时存在时只返回一个，但五项属性都会如实返回。
- `slot`：槽位号 0–2，同一玩家的宠物按它升序排列。
- `active`：是不是当前宠物。`/pets/me` 返回的这只恒为 `true`，
  名册里只有一只是 `true`。
- `evolutionStage`：0 幼年 / 1 成长 / 2 最终。
- `cooldowns`：仍在冷却中的操作 → 冷却结束时刻，只包含还没结束的项。
  FEED / PLAY / CLEAN 各自 60 秒冷却，互不影响；SLEEP / WAKE 没有冷却。
- `settlement`：**本次读取结算出来的变化摘要**（PRD 2.5），时间没有前进时为 `null`：

  ```json
  {
    "settledHours": 12,
    "deltas": { "satiety": -60, "mood": -48, "hygiene": -27, "energy": -48, "health": -24 },
    "statusBefore": "NORMAL",
    "statusAfter": "HUNGRY",
    "wokeUp": false,
    "sleptHours": 0
  }
  ```

  前端用它展示「你不在时发生了什么」（PRD 4.3）。`settledHours` 已经按 12 小时封顶；
  `deltas` 是整段时间的净变化，和操作响应里的 `deltas`（只含操作本身）不是一回事。

### 2.4 执行操作

```http
POST /api/v1/pets/me/actions
Authorization: Bearer <token>

{ "action": "FEED", "clientRequestId": "a7c1b2..." }
```

- `action`：`FEED` / `PLAY` / `CLEAN` / `SLEEP` / `WAKE`，大小写不敏感，非法值返回 400 `INVALID_ACTION`。
- `clientRequestId`：必填，≤64 字符。**幂等键**，重复提交只返回第一次的结果，不会重复加减属性或经验。
  客户端每次点击生成一个新的随机值。

响应 `data`：

```json
{
  "pet": { "...": "结算并应用操作后的完整宠物状态，结构同 2.3" },
  "deltas": { "satiety": 20, "mood": 3, "hygiene": -2, "energy": 0, "health": 0 },
  "xpGained": 6,
  "levelUp": false,
  "evolved": false,
  "messageKey": "FEED_OK",
  "cooldownUntil": "2026-09-17T12:01:00Z",
  "journalEntry": {
    "id": 12,
    "action": "FEED",
    "at": "2026-09-17T12:00:00Z",
    "deltas": { "satiety": 20, "mood": 3, "hygiene": -2, "energy": 0, "health": 0 },
    "xpGained": 6,
    "levelUp": false,
    "evolved": false,
    "messageKey": "FEED_OK"
  }
}
```

`journalEntry` 是这次操作刚写进日志的那一条，前端直接拿它更新日志区，
不用再发一次请求去拉列表。**幂等重放时返回的是当初那一条**（id 相同），
所以重复提交不会在日志里多记一笔。

**`deltas` 是"生效后的真实差值"，不是配置表里的名义值。** 属性上限 100、下限 0，
所以饱食 80 的宠物喂食（名义 +30）`deltas.satiety` 返回 20。
这样前端显示的 "+N" 和状态条的实际变化一致；前端不需要自己钳制，那会变成重复实现规则。

`deltas` 只表示**操作本身**的效果（已叠加物种修正），不含离线衰减。要看整体变化请对比 `pet` 前后两次的值。

各操作的效果与条件：

| 操作 | 基础效果 | 使用条件 | 冷却 | 经验 |
| --- | --- | --- | ---: | ---: |
| FEED | 饱食 +30、心情 +3、清洁 -2 | 饱食 < 95 | 60s | 6 |
| PLAY | 心情 +22、精力 -12、饱食 -5、清洁 -4 | 清醒且精力 ≥15 | 60s | 8 |
| CLEAN | 清洁 +35、心情 +5 | 清洁 < 95 | 60s | 6 |
| SLEEP | 精力 +10/小时，饱食 -3、心情 -1、清洁 -1 | 清醒且精力 <70 | 无 | 醒来时结算 |
| WAKE | 提前结束睡觉 | 正在睡觉 | 无 | 按已睡整小时结算 |

物种修正：猫 玩耍心情 +25%、清洁衰减 -25%；狗 所有正向收益 +10%、健康恢复额外 +1/小时；
龙 喂食饱食 +15%、精力衰减 -25%；兔子 玩耍心情 +15%、清洁衰减 -30%。
倍率只作用于**正向**收益，代价不受影响。

睡觉经验：每小时 2 点，单次（从入睡到醒来）最多 12 点。
自动醒来（精力到 95 或睡满 10 小时）时由 `GET /pets/me` 补发经验。

### 2.5 游戏配置（免鉴权）

```http
GET /api/v1/game/config
```

返回物种修正、操作效果与冷却、等级经验阈值、进化条件、槽位上限 `maxSlots`。
前端只缓存用于展示，**不得据此自己计算衰减、经验或进化** ——
`maxSlots` 也一样：前端拿它决定要不要显示「再养一只」，但能不能领养成功
仍然由服务端说了算。

### 2.6 送走一只宠物

```http
DELETE /api/v1/pets/{petId}
Authorization: Bearer <token>
```

删除这只宠物和它的全部操作日志，**玩家记录保留**。前端必须二次确认，
而且要说清送走的是**哪一只**（多宠物下「重新领养」这个说法已经不成立了）。

- 送走的如果正是当前宠物，服务端会在**同一个事务里**把当前宠物改成剩下里
  槽位最小的那只；一只都不剩就置空，前端据此回到领养页。
- 对战记录不受影响：`battles` 存的是开战时的快照，`*_pet_id` 也刻意没有外键。
- **不是幂等的**：重复送走同一只返回 404 `PET_NOT_FOUND`。
  这条和原来的 `DELETE /pets/me`（幂等）不同，是有意的 —— 单宠物时代
  「重置」没有对象，现在「送走哪一只」有明确对象，对象已经没了就该说没了，
  客户端也能据此知道自己的名册是旧的。
- 传别人的宠物 ID 也是 404，和传一个不存在的 ID 无从区分：
  能区分就等于给了一个探测别人存档是否存在的接口。

### 2.7 照护日志

```http
GET /api/v1/pets/me/journal?limit=20
Authorization: Bearer <token>
```

最近的照护记录，新的在前（PRD 4.2 日志区）。

- `limit` 可选，默认 20，服务端钳制在 1–50，超出范围不报错而是按边界处理。
- 数据来自 `pet_action_logs`，所以**刷新页面、关掉浏览器之后记录都还在**。
- 送走宠物会连同日志一起删掉；当前宠物不存在时返回 404 `PET_NOT_FOUND`。
- **只返回当前宠物的日志**。按宠物分开查留到以后（名册里点进去看的是这一只）。

响应 `data` 是数组：

```json
[
  {
    "id": 12,
    "action": "FEED",
    "at": "2026-09-17T12:00:00Z",
    "deltas": { "satiety": 20, "mood": 3, "hygiene": -2, "energy": 0, "health": 0 },
    "xpGained": 6,
    "levelUp": false,
    "evolved": false,
    "messageKey": "FEED_OK"
  }
]
```

`messageKey` 是给前端选台词用的键，服务端不返回现成文案，由前端映射。

### 2.8 开发用接口（仅 dev profile）

这一节的接口**只在 `dev` profile 下存在**，其他 profile 下返回 404 `NOT_FOUND`。
生产环境绝对不能激活该 profile。

#### 2.8.1 推进时间

```http
POST /api/v1/dev/advance-time
Authorization: Bearer <token>

{ "hours": 8, "settle": true }
```

- `hours`：1–240。
- `settle`：可选，默认 `true`。传 `false` 时只把结算游标往前挪、不结算，
  把结算留给下一次读取 —— 回访提示只能由读取路径产生，推进接口顺手结算掉就再也看不到它了。
- 实现方式是把宠物的结算游标往前挪，走的完全是真实结算路径，没有伪造时钟。
- 返回结算后的宠物对象。

#### 2.8.2 铺设状态

```http
POST /api/v1/dev/set-state
Authorization: Bearer <token>

{ "exp": 490, "satiety": 100 }
```

- 可传字段：`satiety` / `mood` / `hygiene` / `energy` / `health`（0–100）、
  `exp`（0–10000）。不传的字段保持原值。
- 铺设完立刻按真实规则重算状态、等级和进化，所以**进化、生病这些判定一条都没绕过**，
  换掉的只是"属性/经验是从哪来的"。
- 返回铺设后的宠物对象。

为什么需要它：升到 8 级要 490 经验，而每次照护只有 6–8 点、还带 60 秒冷却
（PRD 2.7 给正常节奏的估计是"第 16 天到 8 级"）；健康恢复也要求四项属性连续多小时
高于 60。没有铺设工具，这两条验收路径就只能靠几十分钟的真实操作。

启动 dev profile：

```powershell
$env:JAVA_HOME = 'D:\JDK1'
mvn -f pet-server/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev,local
```

> 要读本机的数据库密码（`application-local.yml`）就得把 `local` 一起激活，
> 因为 profile 配置文件只在自己那个 profile 生效。

### 2.9 好友码（P1 对战）

```http
GET /api/v1/players/me/friend-code
Authorization: Bearer <token>
```

```json
{ "friendCode": "K7M2PQXF", "length": 8 }
```

- 8 位，字母表剔掉了 `0/O` 和 `1/I/L` —— 这是要念给朋友听或者手输的东西。
- **懒生成**：第一次调用才发一个，之后幂等。绝大多数匿名玩家不会去对战，
  没必要一建号就占一个唯一码。
- 服务端用 `SecureRandom` 生成：好友码是"知道就能挑战"的凭据，可猜的码等于谁都能打别人。

### 2.10 发起挑战（P1 对战）

```http
POST /api/v1/battles
Authorization: Bearer <token>

{ "friendCode": "K7M2PQXF" }
```

大小写不敏感，前后空格会被去掉。服务端**同步**跑完战斗并返回完整战报。

- 挑战方要有宠物，否则 404 `PET_NOT_FOUND`；对方要有宠物，否则 409 `OPPONENT_NO_PET`。
- 不能挑战自己：409 `SELF_CHALLENGE`。
- **双方宠物状态以快照冻结**，战斗过程只读快照。被挑战方可以全程离线 ——
  这正是"异步对战"的含义。打完之后的养成、进化、生病都不会改写已出的战报。
- 战斗**不改变任何养成状态**：不打折经验、不掉属性，纯切磋。

响应 `data`：

```json
{
  "id": 1,
  "status": "FINISHED",
  "viewer": "CHALLENGER",
  "challenger": { "petId": 1, "name": "小蓝", "species": "DRAGON", "level": 5,
                  "evolutionStage": 1, "maxHp": 101, "attack": 26, "defense": 11, "speed": 10 },
  "defender":   { "petId": 2, "name": "咪咪", "species": "CAT", "level": 5,
                  "evolutionStage": 1, "maxHp": 95, "attack": 23, "defense": 12, "speed": 16 },
  "winner": "CHALLENGER",
  "outcome": "KO",
  "rounds": 6,
  "seed": 123456789,
  "timeline": [
    { "round": 1, "events": [
      { "actor": "CHALLENGER", "type": "ATTACK", "value": 18, "crit": false,
        "challengerHpAfter": 101, "defenderHpAfter": 77 },
      { "actor": "DEFENDER", "type": "HEAL", "value": 3, "crit": false,
        "challengerHpAfter": 101, "defenderHpAfter": 80 }
    ] }
  ],
  "createdAt": "2026-09-17T12:00:00Z",
  "finishedAt": "2026-09-17T12:00:00Z"
}
```

- `viewer` 告诉前端"你是谁"，界面据此把己方标出来。**同一场对战双方查到的内容
  除了这一个字段之外完全相同。**
- `outcome`：`KO`（打倒）/ `TIMEOUT`（打满回合按剩余生命判定）/ `DRAW`。
- 每个事件里的两个血量都是**这个动作做完之后**的值，前端照着画血条就行，
  不需要自己按顺序累加 —— 累加就等于把战斗规则抄进了界面。
- `winner` 为 `null` 表示平局。

### 2.11 查询对战

```http
GET /api/v1/battles?limit=20        # 我的最近对战，新的在前
GET /api/v1/battles/{battleId}      # 某一场的完整战报
GET /api/v1/battles/topic           # 战报通知的订阅主题
```

- `limit` 可选，服务端钳制在 1–50。
- **只有参战双方能查单场战报**，其他人拿到 battleId 也是 404 ——
  记录里有双方宠物的完整状态。列表天然只返回自己的。
- 列表项比单场少一个 `timeline`（几十条记录各带一份完整战报会让响应大一个数量级），
  多一个 `opponentName` / `opponentSpecies`，是**相对 viewer 的对手**。

### 2.12 战报就绪通知（WebSocket）

战斗打完时推一条到 `/topic/battles/{battleId}`：

```json
{ "type": "BATTLE_FINISHED", "battleId": 1, "status": "FINISHED",
  "serverTime": "2026-09-17T12:00:00Z" }
```

**报文里只有对战 ID 和状态，没有宠物名字、胜负或回合数。** 原因是被挑战方
事先不知道 battleId，只能订阅通配的 `/topic/battles/*`，那是个真广播 ——
往里塞名字就等于把别人的宠物名广播给所有连上来的客户端。想知道这一场是不是
自己的，拿着 ID 去查 `/battles` 列表，那条路径有令牌校验。

**通知只是提示，不是数据。** 战报本体一律走 REST 查。

### 2.13 名册

```http
GET /api/v1/pets
Authorization: Bearer <token>
```

该玩家的全部宠物，按槽位升序（PRD 2.1）。每项结构和 [2.3](#23-查询当前宠物)
一样，`active` 标出其中哪一只是当前宠物。

**每一只都会先做懒结算**，所以返回的 `settlement` 是真的。这不是顺手做的：
名册的用处就是一眼看出谁快不行了，只读不结算的话显示的是上次读取时的旧值，
一只正在挨饿的宠物在名册上看着好好的，切过去才发现 —— 那这个名册就白给了。

代价是 N 次结算（N ≤ `maxSlots`，每次 O(1)），每只各自一个事务。
没有宠物时返回空数组，不报错。

### 2.14 切换当前宠物

```http
POST /api/v1/pets/me/active
Authorization: Bearer <token>

{ "petId": 2 }
```

把当前宠物换成 `petId` 那一只，返回**切换后已结算**的那只（结构同 2.3）。

响应里带着结算摘要，所以「切过去」这个动作本身就带回了「你不在时它怎么样了」——
回访提示不该因为换了个入口就丢掉。

`petId` 不属于当前玩家时返回 404 `PET_NOT_FOUND`，和传一个不存在的 ID
无从区分（理由同 2.6）。

---

## 3. WebSocket

**不进入养成主链路**：状态结算、操作和进化全部走 REST，WebSocket 只负责
「战报就绪」这一类通知（PRD 2.11）。P1 之后它已经不是预埋状态了。

```text
Endpoint:            /ws
Application prefix:  /app
Broker prefix:       /topic
Ping:                /app/ping  ->  /topic/ping
战报通知:             /topic/battles/{battleId}
```

`/app/ping` 广播到 `/topic/ping`：

```json
{ "type": "PONG", "echo": { "hello": "world" }, "serverTime": "2026-09-17T12:00:00Z" }
```

端点的允许来源由 `app.websocket.allowed-origins` 决定：**开发档**是
`http://localhost:*` 和 `http://127.0.0.1:*`，**生产**由环境变量
`PET_ALLOWED_ORIGINS` 注入，必须和浏览器地址栏里的来源逐字一致（包括端口号）。
配错的症状很隐蔽 —— 页面一切正常，只有对战页的实时通知一直「未连接」，
详见 `docs/deployment.md`。

---

## 4. 实现说明与已知取舍

这些是 PRD / TECH_DESIGN 没有明确、或与文档有出入的地方，记录在此便于后续对齐。

1. **生病是滞回状态，不是纯阈值。** PRD 2.3 说"健康低于 30 进入生病状态，恢复到 50 以上解除"，
   而 TECH_DESIGN 6.3 原本写的是 `SICK（health < 30）`。本实现按 PRD：`pets.sick` 列是持久化的滞回标志，
   健康 <30 置位，≥50 才清除，30–49 之间保持原状。
   *（TECH_DESIGN 4.3 的字段表和 6.3 的判定说明已在验收批次同步。）*

2. **不足一小时的离線时间不结算，游标只推进整小时。** TECH_DESIGN 6.1 的伪代码最后一行原本是
   `lastSettledAt = now`。照做的话，玩家频繁刷新页面时每次都结算一次，而属性是整数、按小时衰减，
   不足一小时的衰减会被抹掉 —— 频繁刷新的玩家属性永远不会下降。
   本实现改为：不足一小时完全不结算、游标不动；只有封顶 12 小时时才把游标推到 `now` 以丢弃超出部分。
   *（6.1 已同步。）*

3. **物种的"衰减"修正同时作用于睡觉衰减表。** PRD 2.6 只写"清洁衰减 -25%"，没区分清醒/睡觉。
   本实现按"宠物属性本身的衰减速度"理解，猫睡觉时清洁同样 -25%。
   龙的精力 -25% 在睡觉时无意义（睡觉是加精力）。

4. **睡觉按精确模型实现，不是 TECH_DESIGN 6.1 的简化伪代码。** 伪代码会把全部离线时长按睡眠表结算，
   违反"睡满 10 小时自动醒来"。本实现是：睡到精力 95 或满 10 小时即醒，醒来后的剩余时间在同一次调用里
   按清醒表继续结算。

5. **健康按结算后的属性整段一次性计算（O(1)）。** 这意味着 12 小时里如果饱食中途跌破 25，
   会按 12 小时全额扣健康。这是 O(1) 结算固有的近似，不是逐小时模拟。

6. **校验失败的操作会回滚整次结算。** 按 TECH_DESIGN 6.2 的顺序（先结算、再校验），
   操作被拒绝时抛异常回滚，这次结算也就不写库。因为结算只依赖 `lastSettledAt`、是确定性的，
   所以不会丢数据；下一次请求会重新结算。`GET /pets/me` 一定会结算，所以不会长期不推进。

7. **`client_request_id` 是全表唯一索引。** 客户端必须为每次点击生成新的随机值；
   不同宠物之间也不能复用同一个值，否则会被当成重复请求。

8. **请求级访问日志已由 `RequestLoggingFilter` 实现。** 每个请求生成或透传 requestId，写入 MDC 和
   `X-Request-Id` 响应头，并记录 method、path、status、业务 resultCode 和 durationMs。
   `ApiResponseAdvice` 负责把 `OK`、`UNAUTHORIZED` 等业务码写入请求上下文；拿不到信封的请求退回 HTTP 状态码。

9. **会话接口存在理论上的并发竞态。** 同一设备同时发起两次首次会话时，
   唯一索引会拦住重复插入并返回 500。单浏览器客户端不会触发，暂不处理。

10. **注入的 `Clock` 精度是整秒（验收批次修的）。** `pets` 的时间列是秒精度的 `TIMESTAMP`，
    而 MySQL 写入时会对小数秒**四舍五入**（不是截断）。原来时钟给的是纳秒精度，于是
    "创建于 10:20:02.871"会存成 `10:20:03`，游标凭空向前跳了 0.129 秒；紧接着结算 8 小时，
    实际经过时间是 7 小时 59.87 秒，取整成分钟就是 479 —— **整整少结算一个小时**。
    实测以 0.5 为界：小数部分小于 0.5 的存进去是截断，大于等于 0.5 的会被进位。
    修法是把时钟截断到整秒（`ClockConfig`），应用算出来的和数据库存下来的是同一个值。
    游戏按小时结算，损失不到一秒的精度没有影响。

11. **「唤醒」曾经写不进库（验收批次修的）。** MyBatis-Plus 默认的 `NOT_NULL` 更新策略会把
    null 字段从 UPDATE 语句里剔掉，于是 `sleepingSince = null` 这一步静默失效：接口响应看着是对的
    （用内存对象拼的），重新读回来却还是"在睡觉"。后果有两个 —— 界面显示状态正常、第四个按钮却是
    「唤醒」；而且每次唤醒都会拿陈旧的 `sleepingSince` 重发一份睡觉经验，可以无限刷。
    修法是给该字段单独标注 `@TableField(updateStrategy = FieldStrategy.ALWAYS)`。
    原来的测试只断言了响应体，所以一直没发现；现在补了直接查库的回归测试。

12. **对战是「同步算完 + 异步通知」，没有后台任务。** `status` 列落库的永远是 `FINISHED`。
    确定性模拟本身只要微秒级，做成后台任务只会凭空多出一个中间态和一堆竞态。
    `PENDING` 保留在枚举里，是为了将来真要做后台执行时不用改表结构和接口契约。
    "异步"指的是**被挑战方不需要在线**（打的是快照），不是"战斗在后台跑"。

13. **`battles` 表的字段比 TECH_DESIGN 4.5 的预留多了两个玩家外键。**
    那份字段表是批次 2 的草稿，只有 `challenger_pet_id` / `defender_pet_id`。
    但"我的最近对战"要按玩家查，只有宠物 ID 就得先反查宠物再反查玩家；
    而且对战记录里存了双方宠物的完整快照，用玩家 ID 做归属判断更直接。
    所以加了 `challenger_player_id` / `defender_player_id` 各带一个索引。

14. **物种对战倾向的三个数值是实测调出来的，不是拍脑袋定的。** 中间走了不少弯路，
    记下来免得下次重蹈：
    - 速度原本只决定先手，而先手大约只值半次攻击，结果**猫对谁都是 0% 胜率**。
      给速度一个能换算成伤害的出口（速度优势 → 额外出手概率）之后才站得住。
    - 物种加成原本是固定值，而基础属性随等级线性增长，于是同一个 +5 攻击在 1 级占
      基础的 50%、10 级只占 14% —— 龙在 1 级碾压（82%）、10 级被狗压着打（20%）。
      改成跟着等级缩放之后才各段一致。
    - 每回合回血原本也是固定值，同样的病：固定 3 点对 1 级的 56 点血是 5%，
      对 10 级的 140 点只剩 2%。改成按最大生命的百分比。
    - **中期（5 级）目前所有物种两两都在 40–60%**，这是主要平衡目标；
      1 级和 10 级两端还有偏差（例如 10 级狗对龙 79%、猫对龙 68%），没有继续磨。
      这几条都钉在 `BattleSimulatorTest` 里，改数值会立刻被拦下来。
    - 加兔子时又踩了一次同一个坑：第一版给它「生命 +2、速度 +7」，是猫的严格加强版，
      实测把猫压到 26%。**速度的权重远高于生命和攻击**，所以最快的那个必须
      在其他项上付出代价 —— 兔子最终攻击零加成。
    - 测试用例**遍历 `Species.values()` 而不是写字面量**。原先三对是硬编码的三行，
      加物种时它们不会失败，只会漏测新增的配对 —— 那比失败更隐蔽。

15. **多宠物槽的核心手法是让 `/pets/me` 的语义变成「当前宠物」，而不是把所有接口
    改成按 ID。** 四个操作、照护日志、对战、`POST /session` 回带的宠物 —— 这些
    既有接口一个字节都不用改，只要「当前宠物」这个概念由服务端持有
    （`players.active_pet_id`）。代价是「当前宠物」成了一个服务端状态，
    客户端不能自己决定看哪一只，必须显式调 2.14 去切。
    *（`TECH_DESIGN` 4.2 / 4.3 与第 13 节已在同批同步。）*

16. **`players.active_pet_id` 刻意不加外键。** 送走宠物要物理删除 `pets` 行，
    加了外键会让删除直接失败。这和 `battles.*_pet_id` 不加外键是同一个理由，
    一致性由 `PetService` 在同一个事务里保证。
    *（不是遗漏，别当 bug 修。）*

17. **`DELETE /pets/me` 退役，改成 `DELETE /pets/{petId}`，语义从幂等变成不幂等。**
    多宠物之下「me」是有歧义的，而且名册里送走非当前那只也必须能表达。
    原来的重置接口是幂等的，但那是单宠物时代的事：那时「重置」没有对象，
    现在「送走哪一只」有明确对象，对象已经没了就该说没了。
    前端和 `scripts/acceptance.mjs` 在同一个提交里改掉了，没留破损状态。

18. **送走当前宠物之后由服务端决定下一只是谁，前端跟着走。**
    服务端在同一个事务里把 `active_pet_id` 改到剩下里槽位最小的那只（一只不剩就置空），
    响应里的名册会用 `active` 标出来。前端**不自己定一套「该轮到谁」的规则** ——
    那就成了第二个事实来源，两边迟早对不上。

---

## 5. 测试

```powershell
# 接口测试跑在 H2 的 MySQL 兼容模式上，不依赖本机 MySQL
$env:JAVA_HOME = 'D:\JDK1'
mvn -f pet-server/pom.xml test
```

表结构由同一份 Liquibase changelog 建立，所以迁移脚本本身也在测试里被执行过一遍。

真实 MySQL 5.7 的冒烟测试步骤：

1. 按 `README.md` 的「MySQL 前置条件」建库建账号。
2. 设置密码环境变量：`$env:PET_DB_PASSWORD = '你的密码'`。
3. 启动服务：`mvn -f pet-server/pom.xml spring-boot:run`，确认 Liquibase 迁移成功、无报错。
4. 依次调用 2.1 → 2.2 → 2.3 → 2.4 → 2.6，确认行为与本文一致。
