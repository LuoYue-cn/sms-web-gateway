# SMS Web Gateway — API 文档（契约）

统一前缀 `/api/v1/`。JSON，UTF-8。**所有时间戳均为 Unix 毫秒（UTC，整数）**；前端负责转换成本地时间。

## 1. 通用约定

成功：`{"ok": true, "data": <任意>}`
失败：`{"ok": false, "error": {"code": "snake_case_code", "message": "人类可读说明", "details": {…可选}}}`

常用 HTTP 状态：200/201 成功；400 `invalid_request`（校验失败，`details.fields` 为字段→原因）；401 `unauthenticated` / `invalid_token` / `device_revoked`；403 `csrf_failed` / `forbidden`；404 `not_found`；409 冲突（`already_installed`、`not_claimable` 等）；422 `confirmation_required`；423 `send_paused`；429 `rate_limited` / `too_many_attempts`（带 `details.retry_after` 秒）；500 `internal_error`；503 `database_busy`。

### 认证
- **管理员 API**：PHP Session Cookie（`sms_session`，HttpOnly，SameSite=Lax，HTTPS 下 Secure）。所有非 GET 的管理员请求必须带头 `X-CSRF-Token`（取自 `login` / `auth/me` 返回的 `csrf_token`）。
- **设备 API**：`Authorization: Bearer <device_token>`。设备令牌**不能**访问管理员 API，管理员 Session **不能**访问设备 API。
- 设备令牌被吊销或设备被删除 → 设备 API 返回 `401` + `error.code = "device_revoked"`。客户端收到后必须清除本地凭据并回到「未绑定」状态。令牌无效返回 `401 invalid_token`。
- **不存在任何「下发 Shell 命令 / 脚本」的接口**（规格 10.9 第 9、12 条）。

### 手机号
服务端规范化：去掉空格、`-`、`()`、`.`；保留开头 `+`。校验：`^\+?[0-9]{3,20}$`。字母数字发件人（如 `10086`、`ICBC`）在**接收**时原样保留（`phone_number` 可含字母，最长 32）；**发送**必须通过数字校验。会话键 = (`device_id`, 规范化号码)。

---

## 2. 安装

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/install/status` | 公开。`{installed: bool, checks?: [...]}`。未安装时附带环境检测：`[{key, label, ok, detail}]`（PHP 版本、pdo_sqlite、数据目录可写、openssl、session、配置） |
| POST | `/install` | 公开，仅未安装时可用，已安装返回 `409 already_installed`。Body `{username, password, site_name, timezone}`；密码 ≥ 10 位。成功创建管理员、初始化设置、写安装锁，返回 `{installed: true}` |

重新安装只能通过服务器命令行：`php server/bin/reset.php --yes`。

## 3. 管理员 API

### 认证
| 方法 | 路径 | Body → 返回 |
|---|---|---|
| POST | `/auth/login` | `{username, password}` → `{user:{id,username}, csrf_token, site_name, timezone}`。失败次数限制：同一用户名+IP 15 分钟内 5 次失败 → `429 too_many_attempts` |
| POST | `/auth/logout` | → `{}` |
| GET | `/auth/me` | → 同 login 返回；未登录 `401` |
| POST | `/auth/password` | `{current_password, new_password}` → `{}`。成功后使该用户的其他 Session 失效 |

### 设备
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/devices` | → `{items: [Device]}` |
| POST | `/devices/pairing` | Body 可选 `{server_url}`。→ `{pairing_token, expires_at, server_url, qr_payload}`；`qr_payload` 是字符串 `{"server_url":"…","pairing_token":"…"}`（直接用来生成二维码）。令牌 5 分钟过期、一次性 |
| DELETE | `/devices/{id}` | 吊销设备令牌并删除设备记录（短信记录默认保留，`?purge=1` 同时删除该设备的会话与短信，仅服务端） |
| PATCH | `/devices/{id}` | `{device_name?, sim_numbers?}`，至少提供一个。`sim_numbers` 是 `{卡槽: 号码}`，例如 `{"0": "+8613800138000", "1": ""}`：号码按发送规则规范化（3–20 位数字，可带开头 `+`），**空字符串 = 清除该卡槽的手动值**（回退到手机上报的号码）。返回 `{device}`。写审计日志 `device_sim_numbers`（只记录卡槽，不记录号码） |

**Device**
```json
{
  "id": 1, "device_name": "Redmi K40", "android_version": "15", "app_version": "1.0.0",
  "battery_level": 98, "charging": true, "network_type": "wifi",
  "sim_count": 2, "sim_operator": "中国电信",
  "sims": [{"slot": 0, "operator": "中国电信", "state": "ready", "number": "+8613800138000", "number_source": "device"},
           {"slot": 1, "operator": "", "state": "absent", "number": null, "number_source": null}],
  "last_seen": 1760000000000, "online": true,
  "sync_status": "idle|running|done|error|never",
  "sync_total": 12000, "sync_synced": 3400,
  "pending_tasks": 0,
  "keepalive_mode": "normal|root_enhanced",
  "watchdog_status": "running|stopped|not_installed",
  "root_granted": false, "battery_whitelisted": true,
  "last_sync_at": 1760000000000, "last_recovery_at": null, "recovery_count": 0,
  "protection_state": "none|cooldown|protected",
  "created_at": 1760000000000
}
```
`sims[].number`：SIM 号码，**手动填写优先，其次是手机上报**，都没有则为 `null`；`number_source` 为 `"manual"` / `"device"` / `null`。很多运营商（国内尤其常见）不往 SIM 里写号码，手机读出来是空的，所以网页支持手动补填；手动值只存服务器，不会修改手机。

`online` = 最近 `online_threshold_seconds`（设置项，默认 60）秒内有心跳。

### 会话与短信
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/conversations` | Query: `q`（号码/姓名/内容模糊）、`device_id`、`page`(1)、`page_size`(30，≤100)。→ `{items:[Conversation], total, page, page_size}`；置顶优先，其次最近消息时间倒序 |
| GET | `/conversations/{id}/messages` | Query: `before_id`（向前翻页）、`limit`(50，≤200)。→ `{items:[Message]}`，**按时间升序**；`has_more: bool` |
| POST | `/conversations/{id}/read` | 标记已读 → `{}` |
| POST | `/conversations/{id}/pin` | `{pinned: bool}` |
| DELETE | `/conversations/{id}` | 仅删除服务端记录，**不删除手机短信** |
| GET | `/messages` | Query: `device_id`、`direction`、`status`、`page`、`page_size` → `{items, total}` |
| GET | `/messages/search` | Query: `q`（必填，≥1 字符）、`device_id`、`page`、`page_size` → `{items:[Message + conversation 信息], total}` |
| POST | `/messages/send` | 见下 |
| POST | `/tasks/{task_id}/resolve` | 处理 `unknown` 任务：`{action: "resend"|"mark_sent"|"mark_failed"}`。`resend` 创建**新**任务（新 UUID），原任务标 `failed`（"已由管理员确认重发"）；返回 `{task}` |
| GET | `/export/messages` | Query: `format=csv|json`、`device_id`；流式下载 |

**Conversation**
```json
{"id": 5, "device_id": 1, "device_name": "Redmi K40", "phone_number": "13800138000",
 "contact_name": "张三", "last_message": "你好", "last_message_at": 1760000000000,
 "last_direction": "incoming", "unread_count": 2, "has_failed": false, "pinned": false,
 "last_code": "835261"}
```
`last_code`：最后一条消息是收到的短信且识别出验证码时为该验证码，否则 `null`。

**Message**
```json
{"id": 101, "device_id": 1, "conversation_id": 5, "phone_number": "13800138000",
 "contact_name": "张三", "content": "你好", "direction": "incoming|outgoing",
 "timestamp": 1760000000000,
 "status": "received|queued|claimed|sending|sent|delivered|failed|unknown",
 "sim_slot": 0, "task_id": null, "error_message": null, "read": true,
 "code": "835261", "category": "verification"}
```
`code`：服务端从**收到的**短信里识别出的验证码，没有为 `null`（发出的短信永远是 `null`）。`category`：`verification|bank|logistics|carrier|marketing|other`（收到的短信才有，由内容规则归类；不是 AI 判断）。
接收的短信 `status = "received"`。`sent` 不代表对方已收到；`delivered` 仅在运营商支持回执时出现。

**POST /messages/send**
Body：`{device_id, recipient, content, sim_slot?: int, confirm_warnings?: bool}`。
- 只允许**单个**收件人（含 `,` `;` 空格分隔多号码 → `400`）；`content` 1–1000 字符（`max_content_length` 可配置）。
- `send_paused` 为 true → `423 send_paused`。
- 速率限制：每设备每分钟 `send_rate_per_minute`（默认 10）→ `429 rate_limited`。
- 国际号码（`+` 开头且不以 `home_country_code` 开头）或疑似高资费短号（4–6 位纯数字，及 `400`/`900`/`106` 开头的 ≥ 7 位号码）→ 若未带 `confirm_warnings:true`，返回 `422 confirmation_required`，`details.warnings: ["international","premium_suspect"]`。
- 成功 `201`：`{task: Task, message: Message, conversation: Conversation}`，task 初始 `queued`。写审计日志。

**Task**
```json
{"task_id": "uuid", "device_id": 1, "recipient": "+8613800138000", "content": "…",
 "sim_slot": null, "status": "queued", "error_code": null, "error_message": null,
 "created_at": 0, "claimed_at": null, "completed_at": null, "resolved_action": null}
```

### 验证码
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/codes/recent` | Query: `minutes`(10，≤1440)、`limit`(20，≤100)。→ `{items:[{message_id, conversation_id, code, timestamp, source, device_name}], minutes}`，新的在前。`source` 是短信签名（如「京东」），没有则是联系人名或号码 |

识别规则（`server/src/Support/VerificationCode.php`）：必须出现「验证码 / 动态码 / code / OTP」等关键词，取其附近的 4–8 位数字（中文短信也认字母数字混合），并排除金额、日期、时间、小数、尾号、电话、订单号。入库时识别一次；升级时历史短信由 `bin/backfill_codes.php` 回填（服务端第一次请求也会自动分批做）。

### 统计
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/stats` | Query: `range`=`today|7d|30d|90d|custom`（默认 `7d`）、`from`/`to`（`custom` 时必填，`YYYY-MM-DD`，最多 366 天）、`device_id`。日期按站点时区的自然日 |
| GET | `/stats/health` | Query: `device_id`。最近 48 小时每小时的平均电量与在线率（心跳数 ÷ 应有心跳数；心跳只保留 7 天） |

`/stats` 返回：`range`（含时区与上一周期的起止）、`overview.current|previous`（`incoming/outgoing/codes/senders`）、`daily[]`、`heatmap`（7×24，周一在前，仅收到的短信）、`categories`、`devices[]`（按设备与 SIM 卡槽）、`send_quality`（各状态数、`success_rate`、失败/未知原因分布）、`top_senders[]`（有联系人名用名字，否则号码中间打码，**不返回完整号码**）。

### AI 日报
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/ai/config` | → `{ai_enabled, ai_base_url, ai_model, ai_detail_level(0-2), key_set, key_source:"env|db|none"}`。**不返回 API Key** |
| PUT | `/ai/config` | 任意子集：`ai_enabled`、`ai_base_url`（`http(s)://…/v1`）、`ai_model`、`ai_detail_level`、`ai_api_key`（只写；留空=不变）、`clear_api_key:true`。审计只记录改了哪些键，不记录值 |
| POST | `/ai/test` | 用当前配置发一个最小请求 → `{ok, message, model?, reply?}`（失败也是 200，`ok:false` + 原因）。限频 6 次/分钟 |
| POST | `/ai/preview` | `{date?}` → `{date, detail_level, messages:[{role,content}], chars}`：**将要发给 AI 的完整消息**（只读取并脱敏，不联网） |
| GET | `/reports` | → `{items:[Report]}`，日期倒序 |
| GET | `/reports/{date}` | → `Report`；不存在 `404` |
| POST | `/reports/generate` | `{date?, use_ai?: true}`。立刻返回 `202` + 状态为 `running` 的 `Report`，响应发出后在同一个 PHP 进程里继续生成；前端轮询 `GET /reports/{date}`。同一天重新生成会覆盖。限频 10 次/分钟 |
| DELETE | `/reports/{date}` | 删除 |

**Report**：`{date, status:"running|done|failed", source:"ai|rules", model, detail_level, content:{summary, highlights[], anomalies[], suggestions[], facts}, error, prompt_tokens, completion_tokens, created_at, updated_at}`。`facts` 是当天的数字摘要（不含号码、正文、验证码）。AI 不可用或返回无法解析的内容时自动回退为规则模板（`source:"rules"`，`error` 说明原因）；`running` 超过 20 分钟视为失败。

请求体只有 `model`、`messages`、`stream:false` 三项：**不发 `max_tokens`、`temperature` 和任何思考相关参数**，由模型与服务商用默认值自动决定（DeepSeek 等思考型模型因此会自动思考）。单次请求最长等待 240 秒，最多尝试 2 次；回答为空时，错误信息会说明原因（输出上限用尽 / 只有思考过程 / 内容安全过滤），而不是笼统地说格式不对。

发给 AI 的内容按 `ai_detail_level` 分三档，**任何档位都不发短信原文、完整号码、联系人姓名和验证码**：`0` 只有聚合数字；`1` 加发件来源（短信签名或打码号码）和类别计数；`2` 再加脱敏后的正文摘要（最多 40 条 × 90 字，验证码短信不带正文）。

### 设置 / 审计 / 紧急暂停
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/settings` | → `{site_name, timezone, online_threshold_seconds, send_paused, send_rate_per_minute, max_content_length, home_country_code, heartbeat_interval_s, poll_wait_s}` |
| PUT | `/settings` | 任意子集更新 |
| POST | `/settings/emergency-pause` | `{paused: bool}` 紧急暂停所有发送（设备 poll 将不再收到任务，已 claimed 未发送的任务保持，设备端发送前复查）。写审计日志 |
| GET | `/audit-logs` | Query: `page`、`page_size`、`action`、`device_id` → `{items:[{id, ts, actor_type:"admin|device|system", actor, action, target, detail, ip}], total}` |

审计的 `action` 包括：`login`、`login_failed`、`logout`、`password_change`、`send_message`、`resolve_task`、`device_pair`、`device_revoke`、`device_token_rotate`、`emergency_pause`、`settings_update`、`ai_config_update`、`report_generate`、`report_delete`、`root_op`（设备上报的 Root 操作摘要）。

### 实时事件（SSE）
`GET /events`，Session 认证，`Content-Type: text/event-stream`。支持 `Last-Event-ID`（断线续传）。每 ≤15 秒发一次注释心跳 `: ping`。单个连接最长 ~55 秒后服务端正常结束，浏览器 `EventSource` 会自动重连并带 `Last-Event-ID`。

| event | data |
|---|---|
| `message.new` | `{message: Message, conversation: Conversation}` |
| `message.status` | `{message_id, task_id, conversation_id, status, error_message}` |
| `conversation.update` | `{conversation: Conversation}`（已读 / 置顶 / 删除后 `{deleted: true, id}`） |
| `device.update` | `{device: Device}` |
| `sync.progress` | `{device_id, sync_status, sync_total, sync_synced}` |
| `settings.update` | `{send_paused, …}` |

---

## 4. 设备 API（Bearer 设备令牌）

### 绑定
`POST /device/pair`（**无需** Bearer，凭一次性 pairing_token）
Body：`{pairing_token, device_name, android_version, app_version, sim_count}`
→ `201 {device_id, device_token, server_time, config:{heartbeat_interval_s, poll_wait_s, online_threshold_seconds}}`。
令牌无效/过期/已使用 → `400 invalid_pairing_token`。`device_token` 仅此一次明文返回，服务端只存 SHA-256 哈希。

`POST /device/token/rotate` → `{device_token}`。旧令牌在轮换后 10 分钟内仍可用（防止响应丢失）。

### 心跳
`POST /device/heartbeat`，建议每 30 秒（前台服务运行时）；也在 WorkManager 周期任务中发送。
```json
{
  "battery_level": 98, "charging": true, "network_type": "wifi|mobile|ethernet|none",
  "sim_count": 2, "sim_operator": "中国电信",
  "sims": [{"slot": 0, "operator": "中国电信", "state": "ready|absent|locked|unknown", "number": "+8613800138000"}],
  "app_version": "1.0.0", "android_version": "15",
  "sync_status": "idle|running|done|error|never",
  "keepalive_mode": "normal|root_enhanced",
  "watchdog_status": "running|stopped|not_installed",
  "root_granted": false, "battery_whitelisted": true,
  "last_sync_at": 1760000000000, "last_recovery_at": null, "recovery_count": 0,
  "protection_state": "none|cooldown|protected",
  "root_audit": [{"ts": 1760000000000, "action": "whitelist_add", "result": "ok", "exit_code": 0}]
}
```
所有字段可选，缺省则保持原值。`sims[].number` 仅在手机读到号码时才带；格式不合法的号码会被服务端丢弃（不会让整个心跳失败）。`root_audit` 最多 20 条，**仅摘要，不含短信内容或令牌**，服务端写入 `audit_logs`（action=`root_op`）。
→ `{server_time, send_paused, config:{heartbeat_interval_s, poll_wait_s, online_threshold_seconds}}`。

### 短信同步
`POST /device/messages/sync`，每批 ≤ 500 条（推荐 100）。
```json
{"batch_id": "uuid",
 "messages": [{
   "source_type": "android_provider|android_receiver",
   "android_sms_id": 1234,
   "source_key": "uuid（source_type=android_receiver 时必填）",
   "phone_number": "+8613800138000", "contact_name": "张三",
   "content": "你好", "direction": "incoming|outgoing",
   "timestamp": 1760000000000, "sim_slot": 0, "read": true
 }]}
```
- `android_provider`：必须带非空 `android_sms_id`，唯一键 `(device_id, source_type, android_sms_id)`，重复上传 UPSERT。
- `android_receiver`（实时广播、尚未能从系统库取到 ID 时的回退）：必须带 `source_key`，唯一键 `(device_id, source_type, source_key)`。之后同一条短信从 provider 同步到时，服务端按 (设备、号码、内容、方向相同，时间差 ≤ 2 分钟) 合并到已有记录（补上 `android_sms_id`），不产生重复。
- 外发短信若匹配到网页任务（同设备、号码、内容、10 分钟内）的消息，则合并而非新增。
- `batch_id` 幂等：同一 batch_id 重放返回首次结果。整批在一个事务内处理。
→ `{inserted, updated, merged, total, last_android_sms_id}`。新插入的 incoming 消息会触发 `message.new` 事件。

`POST /device/messages/status`：设备上报某条**外发**消息的回执，`{updates:[{task_id, status:"sent|delivered|failed", error_message?, timestamp?}]}` → `{updated}`。等价于 tasks/result 的批量轻量版，遵守同一状态机。

### 任务（发送）
- `GET /device/tasks/poll?wait=20` — 长轮询（`wait` 秒，≤ 25，服务端每 ~1s 检查）。有 `queued` 任务立即返回。**poll 不改变任务状态**。
  → `{tasks:[{task_id, recipient, content, sim_slot, created_at}], send_paused, server_time}`；`send_paused=true` 时 `tasks` 恒为空。
- `POST /device/tasks/claim` `{task_id}` — 原子地 `queued → claimed`。成功 `200 {task}`；否则 `409 not_claimable`（`details.status` 给出当前状态）。**客户端只有在收到 200 的那次尝试里才允许发送**，且发送前必须先本地持久化任务 ID。
- `POST /device/tasks/result`
  `{task_id, status:"sending|sent|delivered|failed|unknown", error_code?, error_message?, parts_total?, parts_sent?, timestamp?}`
  允许的转移：`claimed→sending`、`claimed→failed`、`claimed→unknown`、`sending→sent|failed|unknown`、`sent→delivered|failed`。相同状态重复上报幂等返回 200；非法转移 `409 invalid_transition`。
  → `{task}`。

**幂等 / 崩溃协议（核心不变量）**
1. 客户端 `claim` 成功 → 在本地 Room 记录任务 ID（状态 `claimed`）。
2. 客户端先成功上报 `sending`（服务端写入 `sending`），**之后**才调用 `SmsManager`。上报失败则不发送，保留 `claimed`。
3. 客户端重启后发现本地任务停留在 `sending` → **绝不重发**，上报 `unknown`。Root Watchdog 触发的恢复同样遵守。
4. 服务端后台清理：`claimed` 超过 5 分钟无进展 → 回到 `queued`（此时设备一定尚未上报 `sending`，故未调用过 SmsManager，可安全重新下发）；`sending` 超过 10 分钟无结果 → `unknown`。`unknown` 只能由管理员处理（`/tasks/{id}/resolve`）。
5. 设备本地已处理过的任务 ID 必须持久化；再次收到同一 `task_id` 一律不发送。

### 同步进度
- `POST /device/sync/progress` `{state:"idle|running|done|error", total, synced, last_android_sms_id}` → `{}`。写 `sync_cursors`，触发 `sync.progress` 事件。
- `GET /device/sync/progress` → `{state, total, synced, last_android_sms_id}`（用于重装 APK 或清数据后从服务端游标恢复）。

---

## 5. 限制与错误示例

```json
{"ok": false, "error": {"code": "confirmation_required", "message": "该号码为国际号码，请确认", "details": {"warnings": ["international"]}}}
```
设备 API 同样受频率限制（每设备每分钟 600 次请求；长轮询不计入）。
