# 像素宠物屋 API

> 对应批次 2 的实现。数值规则以 `pet-server/src/main/java/com/virtualpet/game` 为准。
> 文档与代码不一致时以代码为准，并同步修改本文。

## 1. 通用约定

- 所有业务接口前缀 `/api/v1`。
- 时间一律是 UTC 的 ISO-8601 字符串（`2026-09-17T12:00:00Z`），前端负责转本地时区显示。
- 请求和响应都是 JSON，UTF-8。
- 需要鉴权的接口带 `Authorization: Bearer <token>`。

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
| 400 | `INVALID_REQUEST` | 请求体缺失、JSON 格式错误、字段校验失败 |
| 401 | `UNAUTHORIZED` | 令牌缺失或无效 |
| 404 | `PET_NOT_FOUND` | 尚未领养宠物 |
| 404 | `NOT_FOUND` | 接口不存在 |
| 409 | `PET_ALREADY_EXISTS` | 重复领养 |
| 409 | `ACTION_NO_EFFECT` | 当前操作无效果（`message` 给出具体原因） |
| 409 | `CONFLICT` | 乐观锁冲突且重试用尽 |
| 429 | `ACTION_COOLDOWN` | 操作冷却中 |
| 500 | `INTERNAL_ERROR` | 未预期的服务端错误 |

鉴权先于路由：`/api/v1/**` 下即使是**不存在**的路径，没有令牌也会先返回 401。

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

- `species`：`CAT` / `DOG` / `DRAGON`，非法值返回 400。
- `name`：去掉首尾空格后 1–8 个字符，只允许字母和数字（涵盖中文、英文和数字）；
  空白、标点、HTML 特殊字符、表情一律 400 `INVALID_NAME`。
- 每名玩家只能有一只宠物，重复调用返回 409 `PET_ALREADY_EXISTS`，不覆盖原存档。

初始状态：四项核心属性 80，健康 100，1 级，0 经验，幼年形态，状态 `NORMAL`。

> PRD 2.2 没有规定初始数值。取 80 是为了让玩家一进游戏就能立刻喂食和清洁（两者门槛都是 95），
> 同时精力 80 高于睡觉门槛 70，一上来还不能睡。

响应 `data` 是宠物对象（结构见 2.3）。

### 2.3 查询宠物

```http
GET /api/v1/pets/me
Authorization: Bearer <token>
```

读取时会先做懒结算（离线衰减），返回结算后的完整状态。

响应 `data`：

```json
{
  "id": 1,
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
  "cooldowns": {}
}
```

- `status`：`NORMAL` / `HUNGRY` / `DIRTY` / `TIRED` / `SAD` / `SICK` / `SLEEPING`，
  按 `SLEEPING > SICK > HUNGRY > TIRED > DIRTY > SAD > NORMAL` 取最高优先级。
  多项异常同时存在时只返回一个，但五项属性都会如实返回。
- `evolutionStage`：0 幼年 / 1 成长 / 2 最终。
- `cooldowns`：仍在冷却中的操作 → 冷却结束时刻，只包含还没结束的项。
  FEED / PLAY / CLEAN 各自 60 秒冷却，互不影响；SLEEP / WAKE 没有冷却。

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
  "cooldownUntil": "2026-09-17T12:01:00Z"
}
```

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
龙 喂食饱食 +15%、精力衰减 -25%。倍率只作用于**正向**收益，代价不受影响。

睡觉经验：每小时 2 点，单次（从入睡到醒来）最多 12 点。
自动醒来（精力到 95 或睡满 10 小时）时由 `GET /pets/me` 补发经验。

### 2.5 游戏配置（免鉴权）

```http
GET /api/v1/game/config
```

返回物种修正、操作效果与冷却、等级经验阈值、进化条件。前端只缓存用于展示，
**不得据此自己计算衰减、经验或进化**。

### 2.6 重置存档

```http
DELETE /api/v1/pets/me
Authorization: Bearer <token>
```

删除该玩家的宠物和全部操作日志，**玩家记录保留**，可以立即重新领养。
没有宠物时也返回成功（幂等）。前端必须二次确认。

### 2.7 开发用时间推进（仅 dev profile）

```http
POST /api/v1/dev/advance-time
Authorization: Bearer <token>

{ "hours": 8 }
```

- `hours`：1–240。
- **只在 `dev` profile 下存在**。其他 profile 下接口不存在，返回 404 `NOT_FOUND`。
- 实现方式是把宠物的结算游标往前挪，走的完全是真实结算路径，没有伪造时钟。
- 返回结算后的宠物对象。

启动 dev profile：

```powershell
$env:JAVA_HOME = 'D:\JDK1'
mvn -f pet-server/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev
```

---

## 3. WebSocket（预埋）

MVP 只做连通性验证，**不进入养成主链路**。状态结算、操作和进化全部走 REST。

```text
Endpoint:            /ws
Application prefix:  /app
Broker prefix:       /topic
Ping:                /app/ping  ->  /topic/ping
P1 战报主题预留:      /topic/battles/{battleId}
```

`/app/ping` 广播到 `/topic/ping`：

```json
{ "type": "PONG", "echo": { "hello": "world" }, "serverTime": "2026-09-17T12:00:00Z" }
```

端点的允许来源限定为 `http://localhost:*` 和 `http://127.0.0.1:*`。

---

## 4. 实现说明与已知取舍

这些是 PRD / TECH_DESIGN 没有明确、或与文档有出入的地方，记录在此便于后续对齐。

1. **生病是滞回状态，不是纯阈值。** PRD 2.3 说"健康低于 30 进入生病状态，恢复到 50 以上解除"，
   而 TECH_DESIGN 6.3 写的是 `SICK（health < 30）`。本实现按 PRD：`pets.sick` 列是持久化的滞回标志，
   健康 <30 置位，≥50 才清除，30–49 之间保持原状。
   **TECH_DESIGN 4.3 的字段表需要补上 `sick` 列，6.3 的状态优先级说明也需要同步。**

2. **不足一小时的离線时间不结算，游标只推进整小时。** TECH_DESIGN 6.1 的伪代码最后一行是
   `lastSettledAt = now`。照做的话，玩家频繁刷新页面时每次都结算一次，而属性是整数、按小时衰减，
   不足一小时的衰减会被抹掉 —— 频繁刷新的玩家属性永远不会下降。
   本实现改为：不足一小时完全不结算、游标不动；只有封顶 12 小时时才把游标推到 `now` 以丢弃超出部分。

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

8. **没有实现请求级的 `requestId` 访问日志。** PRD 6.6 要求"每个请求记录 requestId、路径、耗时和结果码"，
   本批次没有做，留到验收批次。

9. **会话接口存在理论上的并发竞态。** 同一设备同时发起两次首次会话时，
   唯一索引会拦住重复插入并返回 500。单浏览器客户端不会触发，暂不处理。

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
