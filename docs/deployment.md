# 部署上线（单台云服务器）

对应 `TECH_DESIGN.md` 12.3 的「公网部署」：**后端直接部署，不用 Docker**。

目标架构：

```
浏览器
  │  http://<公网IP>:8000
  ▼
nginx  ──── /            → /opt/pet/pet-web   （前端构建产物）
  │    ──── /api/        → 127.0.0.1:8080    （Spring Boot）
  │    ──── /ws          → 127.0.0.1:8080    （WebSocket 升级）
  ▼
后端 pet-server.jar  ──── MySQL 127.0.0.1:3306
```

**前后端同源**，所以前端一行都不用改（它用的就是相对路径），也**不需要 CORS** ——
后端至今没有跨域配置，这套方案下也不需要加。

**为什么是 8000 而不是 80**：大陆地域的 80/443 必须完成 ICP 备案才能对外服务，
没备案的话请求会被直接拦掉，表现为**连接超时**而不是任何报错页面 —— 很容易误判成
「防火墙没开」或「服务没起来」。所以先用高位端口把功能跑通，备案下来再切回 80/443。

---

## 0. 前置条件

| 项 | 要求 |
| --- | --- |
| 实例 | 2核4GiB 起。2核2GiB 要收紧 JVM 堆和 MySQL 缓冲池，见文末 |
| 系统 | Ubuntu 22.04 或 Alibaba Cloud Linux 3（**不要预装宝塔**） |
| 安全组 | 放行 **8000**（或你选定的端口）。**不要**放行 8080 和 3306 |
| 公网 IP | 记住它，下面凡出现 `<IP>` 的地方都换成它 |

> **为什么不要宝塔：** 它会自己装一套 nginx 和 MySQL 并占住 80/443、3306，
> 和下面这些配置直接打架 —— 表现为「明明改了配置却不生效」，因为读的是另一个文件。

---

## 1. 装环境（服务器上）

```bash
# Ubuntu 22.04
sudo apt update
sudo apt install -y openjdk-17-jre-headless nginx mysql-server

sudo systemctl enable --now nginx mysql
java -version   # 必须是 17.x
```

Alibaba Cloud Linux 3 把 `apt` 换成 `dnf install -y java-17-openjdk-headless nginx mysql-server`。

---

## 2. 建库和账号

**只给这个库的权限，不要用 root 跑应用。**

```bash
sudo mysql
```

```sql
CREATE DATABASE virtual_pet CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'pet_app'@'127.0.0.1' IDENTIFIED BY '换成强密码';
-- Liquibase 需要 DDL 权限来建表和写自己的 DATABASECHANGELOG
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES
  ON virtual_pet.* TO 'pet_app'@'127.0.0.1';
FLUSH PRIVILEGES;
```

**建表不用手工做** —— 后端启动时 Liquibase 会自动跑 `db/changelog` 里的 changeset。

> ⚠️ **第一次部署必须重点验证这一条。** 项目此前只在 **MySQL 5.7** 上跑过，
> `001-init.sql` 里那段关于 `TIMESTAMP` 默认值的踩坑注释就是为 5.7 写的。
> 理论上 8.0 也能过（每个 `TIMESTAMP NOT NULL` 列都显式写了 `DEFAULT`），
> 但这属于「没验证过就不算数」。启动后先看日志里 Liquibase 是否 `Run: 16` 无报错。
> 万一 8.0 建表失败，退路是改装 MySQL 5.7，数据库结构不用改。

---

## 3. 本机构建产物（Windows / PowerShell）

```powershell
$env:JAVA_HOME = 'D:\JDK1'

# 后端：跳过测试（第 4 步已经在本地跑过了），产出可执行 fat jar
mvn -f pet-server/pom.xml package -DskipTests

# 前端：产出静态文件到 pet-web/dist
npm.cmd --prefix pet-web run build
```

产物：

- `pet-server/target/pet-server-*.jar` → 上传后重命名为 `pet-server.jar`
- `pet-web/dist/` → 整个目录

> **不要在服务器上构建。** Maven 构建的峰值内存能顶爆 2GiB 的机器，
> 而且服务器上没必要装 JDK 开发工具链和 Node。

---

## 4. 上传

```bash
# 在服务器上先建目录和专用账号
sudo useradd --system --no-create-home --shell /usr/sbin/nologin petapp
sudo mkdir -p /opt/pet /etc/pet-server
```

```powershell
# 本机执行（把 <IP> 换成你的公网地址）
scp pet-server\target\pet-server-*.jar root@<IP>:/tmp/pet-server.jar
scp -r pet-web\dist root@<IP>:/tmp/pet-web
scp deploy\pet-server.service root@<IP>:/tmp/
scp deploy\nginx-pet.conf root@<IP>:/tmp/
```

```bash
# 服务器上归位
sudo mv /tmp/pet-server.jar /opt/pet/pet-server.jar
sudo rm -rf /opt/pet/pet-web && sudo mv /tmp/pet-web /opt/pet/pet-web
sudo chown -R petapp:petapp /opt/pet
sudo chmod 640 /opt/pet/pet-server.jar
```

---

## 5. 配置环境变量

敏感项和站点来源放这里，**不要写进单元文件**（单元文件是 644，谁都能读）：

```bash
sudo tee /etc/pet-server/env >/dev/null <<'EOF'
PET_DB_PASSWORD=第2步设的那个密码
PET_ALLOWED_ORIGINS=http://<IP>:8000
EOF

sudo chown root:petapp /etc/pet-server/env
sudo chmod 640 /etc/pet-server/env
```

> ⚠️ **`PET_ALLOWED_ORIGINS` 必须和浏览器地址栏里的来源逐字一致，端口号不能少。**
> 没有域名的部署就是 `http://<IP>:8000` —— 写成 `http://<IP>`（漏端口）或
> `http://<IP>:80` 都会让 WebSocket 握手被 403 拒掉。
>
> 这个配错的症状特别隐蔽：**页面一切正常**，四个操作都能点、状态条都在动，
> 只有对战页的实时通知一直显示「未连接」。不知道这条的话会去怀疑 nginx、
> 怀疑 WebSocket 服务，其实只是白名单少了个端口号。
>
> 改完这个文件要 `sudo systemctl restart pet-server` 才生效（reload 不重读 env）。

---

## 6. 起后端

```bash
sudo cp /tmp/pet-server.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now pet-server

# 看启动日志：确认 Liquibase 跑完、Tomcat 起在 8080
journalctl -u pet-server -f
```

期望看到 `Started VirtualPetApplication` 和 Liquibase 的 `Run: 16`。

```bash
curl -s http://127.0.0.1:8080/actuator/health   # => {"status":"UP"}
```

---

## 7. 起 nginx

```bash
sudo cp /tmp/nginx-pet.conf /etc/nginx/conf.d/pet.conf
sudo nginx -t          # 必须先过语法检查
sudo systemctl reload nginx
```

⚠️ `nginx -t` 是这一步的**关键动作**，不要跳过直接 reload —— 配置有语法错时
reload 会失败但**旧配置继续生效**，于是你会以为改动没生效，实际是根本没加载。

打开 `http://<IP>:8000/` 应该能看到领养页。

---

## 8. 上线验收

按 `PRD.md` 第 7 节的验收标准，重点验这几条**只有线上才暴露**的：

- [ ] 领养一只宠物，刷新页面存档还在
- [ ] 四个操作都有反应、状态条会变
- [ ] **进对战页，确认实时通知显示「已连接」** —— 这条验证 `PET_ALLOWED_ORIGINS`、WebSocket 白名单和 nginx 升级头三者同时正确
- [ ] 让另一个浏览器（或用 `scripts/acceptance.mjs`）发起一次挑战，战报能收到推送
- [ ] 直接访问 `http://<IP>:8000/battle` 并刷新，不出现 404（验证 SPA 兜底）
- [ ] `curl http://<IP>:8000/health` 返回 `{"status":"UP"}` 而不是 HTML（见下）
- [ ] **确认 dev profile 没被带上**（见下）
- [ ] 从外网 `curl` 一下 `http://<IP>:8080/actuator/health`，应当**连不上** —— 确认后端没有直接暴露

### 关于 /health：为什么它必须单独配

SPA 兜底（`try_files ... /index.html`）会让**任何**没匹配到文件的路径都返回
200 + index.html。如果不管，`GET /actuator/health` 也会被兜过去 ——
外部监控拿到 200 就认为服务正常，**后端挂了它照样报健康**，这比没有健康检查更危险。

所以 nginx 配置里把 `/health` 单独拎出来反代到后端的 `/actuator/health`，
并且只放行这一个端点，不开 `/actuator/` 整个前缀。

验收时确认：

```bash
curl -i http://<IP>:8000/health | head -3
# 期望：HTTP/1.1 200 且 body 是 {"status":"UP"}
# 如果 body 是 <!DOCTYPE html>，说明 location 顺序又错了
```

### 怎么确认 dev profile 没被激活

这是上线验收里**最重要的一条**。`dev` profile 会注册 `/api/v1/dev/advance-time`
和 `/api/v1/dev/set-state`，前者允许任何调用方推进宠物时间，后者能随便铺设经验属性
—— 等于把游戏规则交给公网。

最可靠的检查是**看启动日志里激活了哪些 profile**：

```bash
journalctl -u pet-server | grep "profile is active"
# 期望：The following 1 profile is active: "prod"
# 如果出现 dev，立刻停服检查 ExecStart
```

> ⚠️ 不要用「`curl` 打 dev 接口看是不是 404」来判断 —— **那样会误判**。
> 认证拦截器在路由之前执行，未带令牌的请求一律返回 **401**，
> 无论这个接口存不存在，401 区分不出「接口没注册」和「接口在但你没登录」。
>
> 真要用 `curl` 验，得先领一只宠物拿到令牌，然后看**响应体里的业务码**：
>
> ```bash
> # 没注册的接口 -> {"code":"NOT_FOUND","message":"接口不存在"}
> # 注册了但没权限 -> {"code":"UNAUTHORIZED",...}
> curl -s -X POST -H "Authorization: Bearer <令牌>" -H "Content-Type: application/json" \
>      -d '{"hours":1}' http://127.0.0.1:8080/api/v1/dev/advance-time
> # 必须看到 "接口不存在"。看到别的（尤其是 200）就说明 dev 被激活了
> ```
>
> 顺带一个排查陷阱：用 `curl -d` 传**中文**参数在某些终端下会因为编码被截坏，
> 表现为 `INVALID_REQUEST`（请求参数不合法）。要手测带中文的接口时用纯 ASCII 值，
> 或者把 JSON 写进文件再 `--data-binary @file`。

---

## 9. 更新流程

```powershell
mvn -f pet-server/pom.xml package -DskipTests
scp pet-server\target\pet-server-*.jar root@<IP>:/tmp/pet-server.jar
```

```bash
sudo systemctl stop pet-server
sudo mv /tmp/pet-server.jar /opt/pet/pet-server.jar
sudo chown petapp:petapp /opt/pet/pet-server.jar
sudo systemctl start pet-server
```

前端更新：重新 `scp -r` 到 `/opt/pet/pet-web`，`sudo systemctl reload nginx` 即可
（`index.html` 已配 `no-cache`，发版后用户不会拿到旧壳子）。

### 带数据库迁移的更新（**先备份**）

Liquibase 在启动时自动跑迁移，所以「换 jar 重启」就等于「改生产库结构」。
**发布前必须先备份**，而且**尽量在低峰做** —— 迁移期间服务是不可用的：

```bash
# 1) 备份。--single-transaction 不锁表；--routines 把存储过程也带上
sudo mysqldump --single-transaction --routines --triggers virtual_pet \
  > /root/virtual_pet-$(date +%F-%H%M).sql
sudo ls -lh /root/virtual_pet-*.sql   # 确认文件不是空的

# 2) 再走上面的换 jar 流程
```

启动后**先看日志里的迁移摘要**再放开流量：

```bash
journalctl -u pet-server -n 80 | grep -A 6 "UPDATE SUMMARY"
# 期望：Run: 16 / Previously run: 0 / 没有 ERROR
```

万一迁移失败，且失败在中途（MySQL 的 DDL 自动提交，没有「整个迁移回滚」这回事），
**不要反复重启** —— Liquibase 会把已经成功的那几条记下来，反复重启会在剩下的
changeset 上打转。先看是哪一个 changeset 挂了，必要时从备份恢复再排查。

> 多宠物槽那一批（2026-09）就是这样一次结构变更：它给 `pets` 加了 `slot`、
> 给 `players` 加了 `active_pet_id`，还把 `pets` 上的唯一键从 `(player_id)` 换成了
> `(player_id, slot)`。换唯一键在 MySQL 上会被外键挡住（`ERROR 1553`），
> 所以那批用的是「摘外键 → 删唯一键 → 加回外键」三步。本地 MySQL 5.7 上验证过，
> **线上 MySQL 8.0 的首次执行仍需在发布时确认**。

---

## 10. 之后：换域名 + HTTPS（**备案下来再做，现在跳过**）

没有域名就没法申证书（certbot 要验证域名归属），而且大陆地域的 80/443
必须先备案。备案下来之后按下面做，改动很小：

```bash
# 1) nginx 改回 80/443
sudo sed -i 's/^    listen 8000;/    listen 80;/' /etc/nginx/conf.d/pet.conf
sudo nginx -t && sudo systemctl reload nginx

# 2) 申证书（会自动改写 server 块并加 80→443 跳转）
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d pet.example.com
```

**第 3 步不能漏：同步改后端的来源白名单**，否则 WebSocket 会被 403 拒掉，
而症状还是那个隐蔽的「页面正常、只有通知未连接」：

```bash
sudo sed -i 's|^PET_ALLOWED_ORIGINS=.*|PET_ALLOWED_ORIGINS=https://pet.example.com|' /etc/pet-server/env
sudo systemctl restart pet-server
```

> 端口变了、协议从 `http` 变成 `https`，**来源字符串就变了** ——
> `PET_ALLOWED_ORIGINS` 必须跟着改，这是这套部署里最容易漏的一步。

---

## 11. 排错

### 启动报 `Public Key Retrieval is not allowed`

MySQL 8.0 默认认证插件是 `caching_sha2_password`。它在**非加密连接**上首次认证时
需要向服务端索取公钥，而驱动默认拒绝这个动作。本项目的 JDBC URL 里没有相关参数。

驱动是 `mysql-connector-j:9.7.0`，`sslMode` 默认 `PREFERRED`，而 MySQL 8.0 默认带
自签 TLS 证书 —— 所以**很可能不会触发**。真遇到了，改成这样（数据库在回环地址上，
不加密没有实际风险）：

```yaml
url: jdbc:mysql://127.0.0.1:3306/virtual_pet?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false
```

### Liquibase 建表失败

先看是不是 `TECH_DESIGN.md` 12.3 说的 MySQL 8.0。项目此前只在 5.7 上验证过，
`001-init.sql` 里那段 `TIMESTAMP` 默认值的注释就是为 5.7 写的。
退回 MySQL 5.7 不需要改任何 SQL。

### 页面上「实时通知」一直显示未连接

九成是 `PET_ALLOWED_ORIGINS` 和浏览器地址栏对不上。**必须逐字一致**，
包括 `http` 还是 `https`、有没有端口号。改完 `/etc/pet-server/env` 要
`sudo systemctl restart pet-server` 才生效。

### 换 HTTPS 之后一切正常，只有通知连不上

同上 —— 从 `http://` 改成 `https://` 之后忘了同步改 `PET_ALLOWED_ORIGINS`。

```bash
journalctl -u pet-server | grep -i "origin"
```

### 正常玩家被限流了 / 想调整阈值

限流按来源 IP 分桶，两档（PRD 6.2）：

| 档位 | 覆盖 | 生产默认 | 环境变量 |
| --- | --- | --- | --- |
| 严格 | `POST /api/v1/session` | 60/分钟 | `PET_RATE_LIMIT_SESSION` |
| 宽松 | 其余 `/api/v1/**` | 300/分钟 | `PET_RATE_LIMIT_GENERAL` |

`/actuator/**` 不参与限流 —— 被限掉的健康检查等于没有健康检查。

被限掉时返回 **429**，响应体仍是统一的信封（`code` 为 `RATE_LIMITED`），
并带 `Retry-After` 头。日志里能看到：

```bash
journalctl -u pet-server | grep "限流命中"
```

要改阈值就改 `/etc/pet-server/env` 里的两个变量再 `systemctl restart pet-server`。

> **注意：`dev` profile 下限流是关掉的。** `scripts/acceptance.mjs` 一次运行会从
> 同一个 IP 发 113 个请求（其中 22 个建会话），那是验收脚本的正常行为。
> 所以别拿 dev 档去验限流 —— 要验就用 `RateLimitFilterTest`，或者临时把
> `PET_RATE_LIMIT_SESSION` 调小后在 prod 档下试。

### 关于 `trust-forwarded-header`

生产配置里它是 `true`，即采信 `X-Real-IP`。**这依赖两个前提同时成立**：

1. nginx 用 `proxy_set_header X-Real-IP $remote_addr` —— 是覆盖不是追加，
   客户端自带的同名头会被冲掉，伪造不了
2. 后端只监听 `127.0.0.1` —— 公网连不上后端，请求一定经过 nginx

只要动了其中任何一条（改了 `server.address`、加了别的入口、绕过 nginx 直接反代 8080），
**必须把它改回 `false`**，否则攻击者只要每次换一个假的 `X-Real-IP` 就能完全绕过限流。

---

## 12. 已知限制 / 待办

这台机器跑起来之后，`TECH_DESIGN.md` 12.3 里还剩这些没做：

- **没有监控和告警** —— 只有 `/actuator/health` 一个端点
- **单实例** —— 内存 broker，重启会断掉所有 WebSocket 连接（前端会自动重连）
- **数据库没有备份策略** —— 第 9 节写的是"发布前手工备份一次"，那是流程不是策略：
  没有定时任务、没有保留周期、没有异地副本，也**没有验证过备份能不能恢复**
- **没有 HTTPS/WSS** —— 卡在 ICP 备案。`PRD.md:297` 要求公网部署必须 HTTPS，
  所以这是**既定偏差**而不是遗漏：现在线上跑的是明文 HTTP，令牌在链路上是可读的。
  备案下来之后按第 10 节切，改动很小
- **没有账号体系** —— 存档绑在浏览器 `deviceId` 上，清了浏览器数据就找不回
  （PRD 2.1 的既定范围）。曾评估过做账号，因为 `PRD.md:336` 不收集邮箱手机号、
  也就没有密码找回通道，做出来会是个半成品，最终搁置

### 2核2GiB 机型的额外调整

内存是这套部署唯一的紧约束，选小机器的话要同时收紧两边：

```ini
# /etc/systemd/system/pet-server.service 里
ExecStart=/usr/bin/java -Xms192m -Xmx384m -jar /opt/pet/pet-server.jar --spring.profiles.active=prod
```

```ini
# /etc/mysql/mysql.conf.d/mysqld.cnf 里
innodb_buffer_pool_size = 128M
performance_schema = OFF
```

并且**绝对不要在服务器上构建** —— 本机打好 jar 再传。
