# SMS Web Gateway — 完整开发任务书（整合版）

> 本文档整合了两份方案：
> - **方案一**：Android SMS Web Gateway 完整开发任务书（服务端 + Android 客户端 + 部署）
> - **方案二**：第二十一章「Root 增强型后台保活系统」
>
> 整合方式：方案二不再作为独立附录，而是并入了架构、设备管理、API、数据库、边界情况、开发阶段、验收测试和交付物各章节；Root 保活的详细设计集中在 **第 10 章**。文末有一份「整合说明」，列出了合并时所做的调整。
>
> 本文档可直接交给 AI 编程 Agent 执行。

---

## 目录

1. [项目定位与目标](#1-项目定位与目标)
2. [技术栈](#2-技术栈)
3. [整体架构](#3-整体架构)
4. [网页端：安装向导与登录](#4-网页端安装向导与登录)
5. [短信管理界面](#5-短信管理界面)
6. [历史短信同步](#6-历史短信同步)
7. [实时接收短信](#7-实时接收短信)
8. [网页发送短信](#8-网页发送短信)
9. [Android 后台运行（普通模式）](#9-android-后台运行普通模式)
10. [Root 增强型后台保活系统](#10-root-增强型后台保活系统)
11. [设备管理与设备绑定](#11-设备管理与设备绑定)
12. [API 设计](#12-api-设计)
13. [数据库设计](#13-数据库设计)
14. [Docker 部署](#14-docker-部署)
15. [安全要求](#15-安全要求)
16. [Android 15 兼容性](#16-android-15-兼容性)
17. [必须处理的边界情况](#17-必须处理的边界情况)
18. [开发阶段](#18-开发阶段)
19. [验收测试](#19-验收测试)
20. [交付要求](#20-交付要求)
21. [Agent 执行规则与优先级](#21-agent-执行规则与优先级)
22. [补充建议](#22-补充建议)
23. [整合说明](#23-整合说明)

---

## 1. 项目定位与目标

开发一个完整的开源自托管短信管理系统，暂定名称 **SMS Web Gateway**。

项目由两个独立部分组成：

1. **PHP Web Server**：运行在 Debian Docker 环境中的网页管理系统。
2. **Android Client**：安装在 Android 15 手机上的原生 APK（可选配套 Root Watchdog 模块）。

手机插入 SIM 卡后，用户通过浏览器远程管理手机短信。

### 1.1 目标环境

- **手机端**：Android 15，已 Root 的专用备用机，Kotlin 原生 APK。
- **服务端**：PHP 8.1 + SQLite。
- **部署方式**：Debian + Docker Compose。
- **通信方式**：HTTPS + REST API + 长轮询。

### 1.2 必须支持的能力

- 查看手机全部历史短信。
- 实时接收新短信。
- 通过网页发送短信。
- 按联系人或手机号显示完整聊天记录。
- 查看发送状态。
- 搜索短信。
- 管理多个安卓设备。
- 查看手机运行状态。
- 断网后自动恢复同步。
- 手机开机后自动恢复服务。
- 远程使用，无需手机与服务器处于同一局域网。
- **（Root 增强）** APK 被系统回收后受控自动恢复，息屏、锁屏、长期充电时保持稳定运行。

### 1.3 总体约束

项目必须完全自托管，**不依赖** Firebase、FCM、Google Play Services、第三方短信 API 或外部账号系统。

APK 采用侧载安装，不要求发布 Google Play。

### 1.4 核心设计原则

1. 优先使用 Android 官方 API。**Root 只做增强与恢复，不是短信收发的必要条件。**
2. 可靠的本地缓存、同步补偿和任务状态管理优先于「零延迟」。
3. 数据完整性与发送任务幂等性优先于一切保活手段：**不能因为守护程序恢复服务而重复发送短信。**

---

## 2. 技术栈

### 2.1 服务端

| 组件 | 技术 |
|---|---|
| 后端语言 | PHP 8.1 |
| Web 服务器 | Nginx |
| 数据库 | SQLite 3 |
| 数据库接口 | PDO SQLite |
| 前端 | Vue 3 + TypeScript |
| UI | Naive UI |
| 构建工具 | Vite |
| API | REST API |
| 认证 | PHP Session（管理员）+ Device Token（设备） |
| 部署 | Docker Compose |
| 通信 | HTTPS + JSON |
| 实时更新 | SSE + HTTP 长轮询 |

要求：

1. 所有 PHP 代码兼容 PHP 8.1。
2. 不使用 Laravel 等大型 PHP 框架；允许用 Composer 管理必要依赖。
3. 不依赖 MySQL、Redis、MongoDB。
4. 数据持久化必须使用 Docker Volume。
5. 提供完整的 `docker-compose.yml`，并允许通过环境变量配置服务。
6. 默认使用 UTC 存储时间，前端转换为用户本地时间。
7. PHP 后端采用模块化架构，不得将所有业务逻辑放入单个 PHP 文件。

### 2.2 Android 客户端

- Kotlin、Android SDK、Jetpack Compose
- Room、WorkManager、OkHttp、Kotlin Coroutines
- Android Foreground Service

目标环境：Android 15，API Level 35，Rooted Device。

```text
minSdk     = 26
targetSdk  = 35
compileSdk = 35
```

如果构建时使用更新的 SDK，可以提高 `compileSdk`，但必须保证 Android 15 正常运行。

不依赖 Google Play Services。

**Root 功能只能作为可选增强**，基础短信收发、同步和通信必须尽量使用 Android 官方 API。

### 2.3 可选：Root Watchdog 模块

面向 Magisk / KernelSU 的独立模块（Shell 脚本），详见 [第 10 章](#10-root-增强型后台保活系统)。

---

## 3. 整体架构

```text
                     用户
                      │
                      ▼
              Web Browser
                      │
                   HTTPS
                      │
                      ▼
              Nginx Reverse Proxy
                      │
                      ▼
              PHP 8.1 Server
             ┌───────────────────┐
             │ Authentication    │
             │ Device Manager    │
             │ SMS Manager       │
             │ Message Queue     │
             │ Sync Engine       │
             │ REST API          │
             │ SSE Events        │
             └───────────────────┘
                      │
                      ▼
                  SQLite
                      ▲
                      │
                 HTTPS API
                      │
                      ▼
              Android 15 APK
             ┌───────────────────┐
             │ SMS Receiver      │
             │ SMS Provider      │
             │ SMS Sender        │
             │ Local Room DB     │
             │ Sync Worker       │
             │ Task Poller       │
             │ Device Monitor    │
             │ RootManager (可选)│
             │ Keepalive Monitor │
             └───────────────────┘
                 │            ▲
                 │            │ 检查 / 受控恢复
                 ▼            │
               SIM 卡   Root Watchdog（可选，Magisk/KernelSU 模块）
```

- 所有连接由 Android 客户端**主动发起**，服务器不需要直接访问安卓设备。
- 安卓设备可以处于 Wi-Fi、移动数据、NAT、无公网 IP 的网络中。
- Root Watchdog 只与本机 APK 交互，**不与服务器通信，也不接受服务器下发的命令**。

---

## 4. 网页端：安装向导与登录

### 4.1 首次安装向导

首次启动访问 `/install`；数据库尚未初始化时进入安装向导。

**第一步：环境检测**

- PHP 版本是否为 8.1。
- PDO SQLite 是否可用。
- SQLite 数据目录是否可写。
- OpenSSL 是否可用。
- Session 是否正常。
- 必要配置是否存在。

**第二步：管理员设置**

- 管理员用户名、密码。
- 系统名称。
- 默认时区。

密码必须使用 `password_hash()` / `password_verify()`，禁止明文保存。

**第三步：初始化数据库** —— 创建数据库表及索引。

**第四步：完成安装**

- 创建安装锁。
- 禁止再次进入初始化流程，禁止通过删除 URL 参数重新安装，禁止未经授权重新初始化管理员。
- `/install` 显示系统已安装。
- 如需重新安装，必须通过服务器命令行执行明确的重置操作。

### 4.2 登录系统

提供独立登录页面。

- 用户名密码登录、Session 身份认证、退出登录。
- 登录失败次数限制。
- Session 过期。
- CSRF 防护、安全 Cookie。
- 修改管理员密码。

默认只有管理员角色，不需要注册功能，后续可扩展普通用户权限。

---

## 5. 短信管理界面

### 5.1 整体布局

网页采用类似现代短信应用的布局。

桌面端：

```text
┌─────────────────────────────────────────────────────────┐
│ SMS Web Gateway                  设备状态   设置  用户   │
├──────────────┬──────────────────────────────────────────┤
│              │                                          │
│ 搜索短信     │ 13800138000                              │
│              │                                          │
│ + 新建短信   │                                          │
│              │ 你好                                     │
│ 联系人列表   │                                          │
│              │                          你好呀          │
│ 张三         │                                          │
│ 13800000000  │                                          │
│              │                                          │
│ 李四         │                                          │
│ 13900000000  │                                          │
│              ├──────────────────────────────────────────┤
│              │ 输入短信内容...                   发送   │
└──────────────┴──────────────────────────────────────────┘
```

移动端采用响应式布局：会话列表 → 点击进入聊天 → 返回会话列表，底部输入框。

要求 UI 美观、简洁、流畅，支持浅色 / 深色模式。

### 5.2 会话列表

按最近消息时间倒序排列。每个会话显示：

- 联系人名称、手机号码
- 最近一条短信内容、最近消息时间
- 未读消息数量
- 发送失败提示
- 所属安卓设备

支持：搜索、分页、删除网页中的会话记录、标记已读、置顶会话。

> **注意**：网页删除默认仅删除服务端记录，**不删除手机短信**，必须在 UI 中明确说明。

### 5.3 聊天页面

接收短信显示在左侧，发送短信显示在右侧。

每条短信包含：

```text
message_id, device_id, conversation_id, phone_number, contact_name,
content, direction, timestamp, status, sim_slot
```

消息方向：`incoming` / `outgoing`。

发送状态：

| 状态 | 含义 |
|---|---|
| `queued` | 服务器已创建发送任务 |
| `claimed` | 安卓客户端已领取任务 |
| `sending` | 安卓客户端正在发送 |
| `sent` | Android 返回发送成功结果 |
| `delivered` | 收到运营商交付回执 |
| `failed` | 发送失败 |
| `unknown` | 发送结果无法确定 |

注意：

- `sent` **不代表**对方已经收到。
- `delivered` 仅在设备和运营商支持交付回执时显示。

---

## 6. 历史短信同步

这是项目最重要的功能之一。首次连接手机时，必须能够同步手机已有的短信。

### 6.1 同步范围

- 收件箱短信、已发送短信、系统短信数据库中可读取的其他短信记录。
- 历史短信时间、发送者号码、接收者号码、短信内容、短信方向。
- SIM 卡信息（系统提供时）、已读状态。

使用 Android 官方短信数据库 `Telephony.Sms.CONTENT_URI`，通过 ContentResolver 查询。

需要权限（运行时请求）：

```xml
<uses-permission android:name="android.permission.READ_SMS" />
```

如果用户拒绝授权，必须显示明确提示，**不允许静默失败**。

### 6.2 同步机制

采用分批同步，每批建议 **100 条**。

```text
APK 读取本地短信
      ▼
转换为统一数据结构
      ▼
写入本地同步队列
      ▼
上传到 PHP API
      ▼
服务器批量写入 SQLite
      ▼
返回成功确认
      ▼
APK 更新同步游标
      ▼
继续下一批
```

- 不得一次性将全部历史短信加载到内存。
- 支持数万条短信的分批同步。

### 6.3 去重机制

每条短信使用以下字段建立设备内唯一标识：

```text
device_id + source_type + android_sms_id      （source_type = android_provider）
```

- SQLite 创建唯一索引。
- 同一短信重复上传时执行 **UPSERT**，而不是插入重复记录。
- **不要**只通过手机号、内容和时间判断是否重复——用户可能收到内容相同的短信。

---

## 7. 实时接收短信

Android 使用 `BroadcastReceiver` 监听 `android.provider.Telephony.SMS_RECEIVED`。

收到短信后：

1. 解析短信内容。
2. 处理长短信分段。
3. 保存到本地 Room 队列。
4. 请求服务器同步。
5. 服务端保存短信。
6. 网页通过 SSE 或轮询更新。

要求：

- 正确处理中文、Unicode、长短信、短信分段。
- 支持双 SIM 卡。
- 避免重复同步。
- 断网时缓存短信。
- **广播接收器不得执行耗时网络操作**，同步工作交给后台任务调度组件。
- Android 15 对后台任务有限制，不能假设广播接收后可以无限制启动后台服务。
- 如果实时上传失败，必须由后续同步任务补偿。

---

## 8. 网页发送短信

### 8.1 发送流程

```text
用户在网页输入手机号
        ▼
PHP 创建发送任务
        ▼
SQLite 保存任务
        ▼
APK 通过长轮询获取任务
        ▼
APK 校验任务
        ▼
调用 Android SmsManager
        ▼
等待发送结果
        ▼
APK 上传发送状态
        ▼
网页更新消息状态
```

### 8.2 Android 发送接口

使用 `SmsManager`，申请 `android.permission.SEND_SMS`。

支持：普通短信、中文短信、长短信分段、指定 SIM 卡发送、发送结果回调、交付结果回调（设备支持时）。

发送前应验证手机号格式。**不得**默认将所有号码强制转换为中国大陆手机号格式，需支持国际号码，例如 `+8613800138000`、`+819012345678`。

### 8.3 发送任务幂等性

每个发送任务必须有唯一 UUID。服务端保存：

```text
task_id, device_id, recipient, content, status,
created_at, claimed_at, completed_at
```

- 安卓客户端必须**持久化已处理过的任务 ID**；同一任务重复返回时不能直接重复发送。
- **短信发送属于不可安全重试的外部操作。** 如果 Android 已调用短信发送接口，但客户端在上报结果前崩溃，服务器不能简单地把任务重新放回队列。这类任务进入 `unknown`，由管理员确认后决定是否重新发送。
- 目标是尽可能避免重复发送，而不是声称可以实现绝对的端到端恰好一次发送。
- **Root Watchdog 或任何自动恢复流程重启服务时，同样必须遵守上述规则**：恢复后只能继续处理未发送的任务，对已处于 `sending` 的任务只能上报 `unknown`，不得重发。

---

## 9. Android 后台运行（普通模式）

目标设备：Android 15 + Root。普通模式**不依赖 Root**。

要求 APK 支持：

- 开机恢复、网络恢复后同步。
- 电池优化设置引导。
- 前台服务运行状态显示。
- 后台任务调度、服务异常恢复、本地任务持久化。

必须正确处理 Android 15 的前台服务限制：

- 不能使用不符合实际用途的 Foreground Service 类型。
- 不能依赖无限运行的 WorkManager 任务。

推荐组合：

```text
Foreground Service + WorkManager + BroadcastReceiver + Room
```

- **Foreground Service**：在系统允许且用户明确启用时，维持持续连接。
- **WorkManager**：执行可延迟的可靠同步任务。
- **BroadcastReceiver**：接收短信、开机和网络相关事件。
- **Room**：保存待同步数据。

如果无法合法、稳定地持续运行长轮询，应允许退化为定期同步，并在界面明确显示同步延迟。

> Root 增强（第 10 章）建立在本章机制之上，是叠加层，不是替代品。

---

## 10. Root 增强型后台保活系统

### 10.1 设计目标

目标设备是 Android 15、已 Root 的专用备用机。Root 增强型保活系统用于尽可能保证 APK 长期稳定运行：

1. APK 开机后自动启动短信同步服务。
2. APK 被系统回收后能自动恢复。
3. 手机锁屏、息屏后继续接收和同步短信。
4. 手机长期充电时保持稳定运行。
5. 网络断开后自动重连。
6. Root 模式下允许用户主动启用更强的后台保活策略。
7. 尽量降低系统省电策略对短信同步的影响。
8. 提供保活状态监控、异常检测和自动恢复。
9. 不依赖 Firebase、FCM 或 Google Play Services。
10. 不通过高频空轮询持续唤醒 CPU。

**设计原则：优先使用 Android 官方机制，Root 负责增强与恢复，不将 Root 作为短信收发的必要条件。**

### 10.2 Root 权限管理

使用 `su` 获取 Root 权限。首次启用 Root 增强模式时：

1. 检测设备是否已 Root。
2. 检测 `su` 是否可用。
3. 弹出明确的 Root 权限申请提示。
4. 用户授权后执行环境检测。
5. 显示当前支持的保活功能。
6. 允许用户逐项启用或关闭。

Root 权限申请**必须由用户主动触发**。Root 命令通过独立的 `RootManager` 模块统一执行，要求：

- 所有命令设置超时。
- 捕获退出码和错误输出。
- 不允许将用户输入直接拼接到 Shell 命令。
- 不允许静默修改无关系统设置。
- 所有系统设置变更都保存原始状态，支持恢复。

### 10.3 电池优化白名单

检测应用是否位于 Android 电池优化白名单中。

- **普通模式**：引导用户通过系统设置授权。
- **Root 模式**：用户明确启用后，可尝试执行（包名替换为 APK 实际 applicationId）：

```bash
dumpsys deviceidle whitelist +<applicationId>
```

执行后重新查询系统状态，确认是否成功。

注意：

- 该命令在不同 ROM 上可能受限。
- 白名单不代表应用绝对不会被终止，不得将执行成功视为后台服务永久存活。
- 必须提供移除白名单及恢复原始状态的功能。

### 10.4 Root Watchdog

设计独立于 APK 主进程的 Root Watchdog。使用 Magisk 或 KernelSU 的设备可提供可选 Root 模块，实现开机启动守护脚本。

```text
root-module/
├── module.prop
├── service.sh
├── watchdog.sh
└── uninstall.sh
```

守护程序负责：

- 检查 APK 服务运行状态。
- 检查最近一次同步时间。
- 检查最近一次成功连接服务器的时间。
- 检查网络是否可用。
- 检测客户端是否异常退出。
- 满足条件时尝试恢复客户端服务。

要点：

- **不要简单地通过检测进程是否存在来判断服务正常**，应同时检查应用内部心跳、同步任务状态和最近一次成功通信时间。
- 守护检查间隔默认 **60 秒**，可配置为 **30～300 秒**；不得每秒检测或无限高频循环。
- Watchdog 只执行预定义的检查与恢复操作。

### 10.5 分级自动恢复策略

**第一级：网络恢复**

检测到网络恢复后：重新建立 HTTPS 连接 → 恢复待上传短信队列 → 恢复待发送任务轮询 → 上报设备状态。

**第二级：服务恢复**

检测到同步服务异常：

- 优先通过应用内部机制重新启动。
- 检查 Android 前台服务启动限制。
- 系统不允许直接启动时，安排允许的恢复任务。

**第三级：Root 守护恢复**

应用进程已异常退出时：

- 由独立 Root Watchdog 检查状态。
- 在系统和设备环境允许的情况下恢复应用组件。
- 避免重复启动多个同步服务实例。

**冷却与保护状态**：所有恢复操作必须有冷却时间。

| 失败次数 | 等待时间 |
|---|---|
| 首次 | 10 秒 |
| 第二次 | 30 秒 |
| 第三次 | 60 秒 |
| 之后 | 300 秒 |

连续失败达到阈值后进入**保护状态**，避免无限重启。

**不得使用强制停止其他应用、关闭系统安全服务或修改系统核心权限的方式实现保活。**

### 10.6 开机自动启动

两套机制并存：

**普通模式**

- 注册 `BOOT_COMPLETED`。
- 使用 WorkManager 恢复待同步任务。
- 根据 Android 15 的限制启动允许的后台组件。

**Root 模式**

- 支持 Magisk / KernelSU 开机脚本。
- 等待系统完成启动。
- 检查网络及应用安装状态。
- 检查短信服务是否已正常启动。
- 必要时执行恢复操作。

必须考虑用户**尚未首次解锁**的情况：凭据加密存储未解锁前，不得尝试读取需要用户解锁才能访问的数据。

### 10.7 屏幕关闭与休眠管理

目标是息屏后仍可靠同步短信。实现：

- 电池优化状态检测、Doze 状态检测。
- 前台服务状态检测、网络连接状态检测。
- 最近同步时间监控。
- 息屏后的同步补偿。

可选提供部分 WakeLock 机制，但必须：

1. 使用明确的超时时间。
2. 仅在必要的短时同步操作中持有。
3. 使用 `try/finally` 确保释放。
4. 不默认永久持有 CPU WakeLock。
5. 不强制保持屏幕常亮。

Root 模式下可提供实验性增强选项，但**不得默认全局关闭 Doze 或系统电源管理**。

### 10.8 保活状态面板

**Android 端：设置 → Root 保活管理**

| 项目 | 状态 |
|---|---|
| Root 权限 | 已授权 / 未授权 |
| Root 管理器 | Magisk / KernelSU / 其他 |
| 电池优化白名单 | 已加入 / 未加入 |
| 前台服务 | 运行中 / 已停止 |
| Root Watchdog | 运行中 / 已停止 |
| 开机自启 | 已启用 / 未启用 |
| 最近成功同步 | 时间 |
| 最近服务器心跳 | 时间 |
| 最近自动恢复 | 时间 |
| 自动恢复次数 | 数量 |

提供的操作：

- 启用 / 禁用 Root 增强保活。
- 安装 Root Watchdog 模块。
- 检查后台运行状态。
- 测试自动恢复。
- 查看保活日志。
- 恢复系统原始设置。

**网页端：设备管理页面**同步展示，例如：

```text
保活模式：Root 增强
守护状态：正常
最近心跳：12 秒前
最近同步：8 秒前
自动恢复次数：2
```

这些信息通过设备心跳（见 [12.2](#122-安卓设备-api)）上报。

### 10.9 安全与稳定性约束

Root 保活模块必须满足：

1. Root 功能**默认关闭**。
2. 用户主动授权后才能启用。
3. 不修改 Android 系统分区。
4. 不关闭 SELinux。
5. 不修改系统安全策略。
6. 不禁用系统权限检查。
7. 不阻止用户主动停止应用。
8. 不对其他应用执行强制停止。
9. **不允许服务器通过 API 直接执行任意 Root 命令。**
10. **不允许将 Root Shell 暴露给网页。**
11. Root Watchdog 仅能执行预定义的检查与恢复操作。
12. **禁止从服务器远程下发 Shell 脚本。**
13. 所有 Root 操作必须记录审计日志。
14. 用户关闭 Root 增强模式后，必须停止守护进程并恢复相关设置。

> **Root 保活不等于保证短信一定能收到。** SIM 卡信号、运营商服务、Android 电话服务和设备硬件故障仍可能影响短信接收。

---

## 11. 设备管理与设备绑定

### 11.1 设备管理页面

每台设备包含：

```text
device_id, device_name, android_version, app_version,
battery_level, charging, network_type,
sim_count, sim_operator, last_seen, sync_status
```

**Root 保活相关扩展字段：**

```text
keepalive_mode        (normal / root_enhanced)
watchdog_status       (running / stopped / not_installed)
root_granted          (bool)
battery_whitelisted   (bool)
last_sync_at
last_recovery_at
recovery_count
```

页面显示：在线状态、电池电量、是否充电、网络类型、SIM 卡数量、运营商、最近同步时间、历史短信同步进度、待发送任务数量，以及上述保活状态。

**在线判断**：

```text
最近 60 秒内收到心跳：在线
超过 60 秒：离线
```

该阈值必须可配置。Android 后台限制可能导致心跳延迟，因此「离线」只表示最近未收到心跳，不代表手机一定关机。

### 11.2 设备绑定

首次打开 APK 显示：

```text
尚未绑定服务器

[扫描二维码]

[手动输入服务器地址]
```

网页设备管理页面提供「添加设备」，生成一次性绑定二维码，内容：

```json
{
  "server_url": "https://sms.example.com",
  "pairing_token": "ONE_TIME_TOKEN"
}
```

绑定流程：

1. 管理员登录网页。
2. 创建一次性绑定令牌。
3. APK 扫描二维码。
4. APK 向服务器提交绑定请求。
5. 服务器验证令牌。
6. 服务器创建设备身份。
7. 返回设备专属访问凭据。
8. APK 将凭据存入 Android Keystore 保护的存储方案。

绑定令牌要求：5 分钟过期；只能使用一次；使用后立即失效；不允许重复绑定。

设备凭据应支持轮换和吊销。服务端只保存设备令牌的安全哈希，不保存明文。

---

## 12. API 设计

统一前缀 `/api/v1/`。

### 12.1 管理员 API

```http
POST   /api/v1/auth/login
POST   /api/v1/auth/logout
GET    /api/v1/auth/me

GET    /api/v1/devices
POST   /api/v1/devices/pairing
DELETE /api/v1/devices/{id}

GET    /api/v1/conversations
GET    /api/v1/conversations/{id}/messages

GET    /api/v1/messages
POST   /api/v1/messages/send
GET    /api/v1/messages/search

GET    /api/v1/events
```

### 12.2 安卓设备 API

```http
POST /api/v1/device/pair
POST /api/v1/device/heartbeat          # 含保活状态字段

POST /api/v1/device/messages/sync
POST /api/v1/device/messages/status

GET  /api/v1/device/tasks/poll
POST /api/v1/device/tasks/claim
POST /api/v1/device/tasks/result

POST /api/v1/device/sync/progress
```

心跳在基础设备状态之外，附带 10.8 中的保活状态字段，以及可选的「最近 N 条 Root 操作审计摘要」（仅摘要，不含短信内容或令牌）。

### 12.3 通用规则

- 所有设备 API 必须验证设备身份；管理员 API 使用 Session 认证。
- **不得将管理员 Session 与设备 Token 混用。**
- 所有写操作需要严格验证输入数据。
- **API 中不得存在任何「下发 Shell 命令 / 脚本」的接口**（对应 10.9 第 9、12 条）。

---

## 13. 数据库设计

使用 SQLite，至少包含以下表：

```text
users, devices, device_tokens, pairing_tokens,
conversations, messages, send_tasks,
sync_batches, sync_cursors,
device_heartbeats, audit_logs, settings
```

`devices` 与 `device_heartbeats` 需包含 11.1 中的保活扩展字段；`audit_logs` 需能记录管理员发送操作、设备绑定 / 吊销，以及设备上报的 Root 操作摘要。

数据库必须：

- 启用 WAL、启用外键。
- 建立必要索引。
- 使用事务处理批量同步。
- 使用预处理 SQL，**不得通过字符串拼接生成 SQL**。
- 支持数据库迁移。
- 支持备份和恢复。

重要索引：

```sql
CREATE UNIQUE INDEX idx_sms_source
ON messages(device_id, source_type, android_sms_id);

CREATE INDEX idx_messages_conversation_time
ON messages(conversation_id, timestamp);

CREATE INDEX idx_messages_phone
ON messages(phone_number);

CREATE INDEX idx_tasks_device_status
ON send_tasks(device_id, status);
```

其中 `android_sms_id` 必须非空才使用该唯一约束；其他来源的消息需要独立的唯一键。

---

## 14. Docker 部署

项目根目录必须提供：`docker-compose.yml`、`Dockerfile`、`.env.example`、`README.md`。

建议目录结构：

```text
sms-web-gateway/
│
├── server/
│   ├── public/
│   ├── src/
│   │   ├── Controllers/
│   │   ├── Services/
│   │   ├── Repositories/
│   │   ├── Middleware/
│   │   └── Database/
│   ├── migrations/
│   ├── composer.json
│   └── Dockerfile
│
├── web/
│   ├── src/
│   ├── package.json
│   └── vite.config.ts
│
├── android/
│   ├── app/
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
├── root-module/               # 可选：Magisk / KernelSU Watchdog 模块
│   ├── module.prop
│   ├── service.sh
│   ├── watchdog.sh
│   └── uninstall.sh
│
├── docker/
│   └── nginx/
│
├── docs/
│
├── docker-compose.yml
├── .env.example
└── README.md
```

运行 `docker compose up -d --build` 即可启动网页服务，默认暴露 `8080`，通过 `http://服务器IP:8080` 访问首次安装页面。

> **HTTP 仅允许用于本地初始化和开发测试。正式绑定手机、同步短信、发送短信时必须使用 HTTPS。** 推荐使用现有 Nginx 反向代理绑定域名。

数据库文件保存在持久化目录中，容器删除或重建后短信数据不能丢失。

---

## 15. 安全要求

短信可能包含私人通信和验证码，安全是核心要求。

### 15.1 服务端

HTTPS、安全 Session、CSRF 防护、XSS 防护、SQL 注入防护、API 请求频率限制、登录失败限制、管理员身份验证、设备令牌撤销、审计日志、数据库备份、敏感日志脱敏。

### 15.2 安卓端

- HTTPS 证书验证，**不允许忽略 TLS 错误**。
- 使用 Android Keystore 保护设备凭据。
- 不在日志中打印完整短信内容或 API Token。
- 本地数据库尽量减少敏感数据留存。
- 设备解绑后清理认证凭据。
- 权限必须由用户明确授予。

### 15.3 短信发送安全

- 限制单次发送数量，默认禁止批量群发。
- 对国际短信和高资费号码提供警告。
- 防止任务重复发送。
- 记录管理员发送操作。
- 提供**紧急暂停发送**功能。

### 15.4 Root 相关安全

见 [10.9](#109-安全与稳定性约束)。核心：默认关闭、用户主动授权、不接收远程命令、Watchdog 仅执行预定义操作、所有 Root 操作有审计日志、关闭后恢复原始设置。

---

## 16. Android 15 兼容性

Agent 必须重点研究和处理：

1. Android 15 的后台执行限制。
2. Android 15 的 Foreground Service 限制。
3. `SMS_RECEIVED` 广播行为。
4. `READ_SMS` / `SEND_SMS` / `RECEIVE_SMS` 权限。
5. `READ_CONTACTS` 权限（可选）。
6. 双卡短信发送。
7. 短信数据库读取。
8. 系统默认短信应用限制。
9. 首次解锁前的凭据加密存储（Direct Boot）限制。
10. Doze 与电池优化白名单的实际效果。

需要明确区分：**普通短信应用权限能够实现的功能**，和**必须成为默认短信应用才能实现的功能**。如果某些操作需要默认短信应用身份，应明确告知用户，不得假装普通应用可以执行。

特别注意：

- 不允许未经用户同意修改系统短信。
- 不允许绕过 Android 权限授权机制。
- 不允许通过 Root 静默读取其他应用隐私数据。
- 不允许使用不安全的系统权限修改方案。

---

## 17. 必须处理的边界情况

所有失败情况都必须有明确的状态和恢复机制。

**通用情况**

1. 手机突然断网。
2. 手机突然重启。
3. PHP 服务重启。
4. SQLite 数据库暂时锁定。
5. 同一短信重复上传。
6. 发送任务重复下发。
7. 发送后客户端崩溃。
8. 手机没有 SIM 卡。
9. 手机双卡但指定 SIM 不可用。
10. 短信发送失败。
11. 中文长短信。
12. 短信分段。
13. 短信发送成功但没有交付回执。
14. 手机电池优化导致后台暂停。
15. 管理员修改密码。
16. 设备 Token 被撤销。
17. 手机系统时间不准确。
18. 多台设备收到相同内容短信。
19. 手机短信被其他应用删除。
20. 服务端已保存短信，但客户端没有收到确认。

**Root 保活相关**

21. Root 权限被撤销（降级为普通模式）。
22. Watchdog 异常退出或被多次启动（避免重复实例）。
23. 恢复循环（触发冷却与保护状态）。
24. 开机后用户尚未首次解锁。
25. 用户主动禁用保活或在系统设置中强制停止 APK（必须尊重，不得绕过）。
26. 守护程序恢复服务时，存在处于 `sending` 的任务（必须转 `unknown`，不得重发）。

---

## 18. 开发阶段

Agent 必须按以下阶段实施。

### 第一阶段：项目基础

项目目录、Docker Compose、PHP 8.1 环境、SQLite、数据库迁移、安装向导、管理员登录、基础前端。
**验收**：网页能正常打开，管理员能登录。

### 第二阶段：安卓客户端

Kotlin 项目、Compose UI、权限申请、设备绑定、SMS Receiver、SMS Provider 读取、Room 数据库、API 客户端。
**验收**：APK 能安装、启动、绑定服务器。

### 第三阶段：历史短信同步

短信读取、分页同步、服务端存储、去重、断网恢复、同步进度。
**验收**：手机已有短信能完整显示在网页中。

### 第四阶段：短信发送

网页发送、任务队列、安卓任务领取、SmsManager、状态回执、幂等处理。
**验收**：用户可通过网页发送短信，手机实际完成发送。

### 第五阶段：实时通信与普通模式保活

新短信上传、SSE、长轮询、心跳、自动重连、网络恢复补偿；前台服务 + WorkManager + BOOT_COMPLETED 的普通模式后台运行。
**验收**：手机收到短信后，正常联网情况下网页能快速显示；重启手机后服务可恢复。

### 第六阶段：Root 增强保活（新增，对应原第二十一章）

`RootManager`、电池优化白名单、Root Watchdog 模块、分级恢复与冷却、开机恢复（Root）、息屏同步补偿、保活状态面板（APK + 网页）、Root 审计日志。
**验收**：Root 增强可由用户启用 / 禁用；关闭后恢复原始设置；恢复流程不造成重复发送。

### 第七阶段：完善 UI

对话界面、联系人名称、搜索、设备管理、设置、深色模式、手机端适配。

### 第八阶段：测试和交付

单元测试、API 测试、Android 真机测试、Docker 部署测试、断网恢复测试、重复短信测试、权限拒绝测试、服务重启测试、保活验收测试（见 [第 19 章](#19-验收测试)）、安全检查。

---

## 19. 验收测试

### 19.1 基础功能测试

- 单元测试、API 测试。
- Docker 部署测试（重建容器后数据保留）。
- 断网恢复测试。
- 重复短信 / 重复任务测试。
- 权限拒绝测试。
- PHP 服务重启测试。
- 安全检查（TLS、CSRF、XSS、SQL 注入、令牌撤销）。

### 19.2 Root 保活验收测试

| 测试场景 | 预期结果 |
|---|---|
| 正常锁屏 1 小时 | 能继续接收短信并最终同步 |
| 息屏进入 Doze | 恢复运行后自动补偿同步 |
| Wi-Fi 断开 | 本地缓存短信 |
| Wi-Fi 恢复 | 自动上传未同步短信 |
| 手机重启 | 解锁后自动恢复服务 |
| APK 进程异常退出 | Watchdog 尝试恢复 |
| PHP 服务重启 | APK 自动重新连接 |
| Root 权限被撤销 | 降级为普通模式 |
| Watchdog 异常退出 | 记录异常并允许重新启用 |
| 用户主动禁用保活 | 不再自动恢复 |
| 用户在系统设置中强制停止 APK | 尊重用户操作，不绕过停止状态 |
| 手机连续运行 72 小时 | 无明显内存泄漏、异常耗电或重复发送 |

---

## 20. 交付要求

**服务端**

- 完整 PHP 8.1 源码、完整 Vue 3 前端。
- SQLite 数据库迁移。
- Docker Compose、Dockerfile、Nginx 配置、环境变量示例。
- 安装向导、API 文档。

**安卓端**

- 完整 Kotlin 源码、Gradle 构建文件、AndroidManifest。
- 权限申请界面、设备绑定界面。
- 短信同步功能、短信发送功能。
- 后台同步机制（普通模式）。
- Root 保活管理界面（设置 → Root 保活管理）与 `RootManager`。
- 可安装 APK。

**Root 模块**

- `root-module/`（`module.prop`、`service.sh`、`watchdog.sh`、`uninstall.sh`），可打包为 Magisk / KernelSU 模块 zip。

**文档**

- `README.md`
- `DEPLOYMENT.md`
- `ANDROID_SETUP.md`（含 Root 保活启用 / 回退步骤）
- `API.md`
- `SECURITY.md`（含 Root 安全约束）
- `TROUBLESHOOTING.md`

---

## 21. Agent 执行规则与优先级

### 21.1 执行规则

1. **不要只输出设计方案，必须实际创建项目文件并编写可运行代码。**
2. 不允许用伪代码代替真实实现。
3. 不允许留下没有说明的 TODO。
4. 不允许省略关键文件。
5. 每完成一个模块必须检查编译或语法错误。
6. 必须保证 PHP 8.1 兼容性。
7. 必须保证 Android 15 兼容性。
8. 不得依赖 Firebase、Google Play Services、商业短信平台。
9. 优先使用成熟、稳定的 Android 官方 API。
10. 避免过度设计，优先保证核心短信收发可靠。
11. 所有 API 必须有错误处理；所有数据库操作必须有异常处理。
12. 所有网络请求必须设置超时。
13. 必须处理并发和重复请求。
14. 必须保证 Docker 重建后数据持久化。
15. 必须提供明确的运行和部署命令。
16. 如果某项 Android 功能受到系统限制，必须如实说明并提供合理降级方案。
17. 不得声称已经完成未经实际测试的功能。
18. **Root 相关代码必须遵守第 10.9 节全部约束；任何情况下不得为了保活而牺牲短信数据完整性和发送幂等性。**

### 21.2 开发优先级

```text
P0：必须完成
├── PHP 服务端
├── 管理员登录
├── Android APK
├── 设备绑定
├── 历史短信同步
├── 接收短信
├── 发送短信
├── 断网恢复
├── 普通模式后台运行（前台服务 + WorkManager + 开机恢复）
└── Docker 部署

P1：重要功能
├── 对话式界面
├── 实时更新
├── 搜索
├── 双卡支持
├── 设备状态
├── 消息去重
├── 发送状态
├── Root 增强保活（RootManager、电池白名单、分级恢复、状态面板）
└── Root 操作审计日志

P2：体验优化
├── Root Watchdog 独立模块（Magisk / KernelSU）
├── 深色模式
├── 联系人同步
├── 会话置顶
├── 数据导出
├── 通知提示
└── UI 动画
```

### 21.3 最终目标

实现一套真正可以长期使用的、自托管的 Android 短信网页管理系统。用户只需要：

1. 在 Debian 上执行 `docker compose up -d --build`。
2. 通过浏览器完成初始化。
3. 在 Android 15 备用机上安装 APK。
4. 授予短信权限。
5. 扫描网页上的设备绑定二维码。
6. （可选）在「设置 → Root 保活管理」中启用 Root 增强保活。
7. 等待历史短信同步完成。
8. 随时通过网页查看和发送短信。

在设备正常供电、系统正常运行、网络和 SIM 卡可用的情况下，尽可能维持短信服务持续在线；即使应用被系统异常终止，也具备受控的自动恢复能力。整个系统不需要 Firebase，不需要第三方短信服务，也不要求手机具有公网 IP。

**请从项目结构和第一阶段开始实施，持续完成各阶段，直到形成可部署、可构建、可测试的完整项目。**

---

## 22. 补充建议

1. Android 15 上，短信读取和发送**不一定需要 Root**；「短信历史同步」和「长期后台在线」是两个不同的技术问题。历史同步通常通过 `READ_SMS` 和系统短信数据库即可完成；后台实时连接则受 Android 15 电池优化、前台服务启动限制和厂商系统策略影响。
2. 因此应把**可靠的本地缓存、同步补偿和任务状态管理**放在第一优先级，Root 保活作为增强层，而不是为了追求几乎零延迟使用不稳定的保活技巧。
3. PHP 8.1 已结束官方安全支持。开发时保持 8.1 兼容，但正式部署时最好使用仍受安全维护的 PHP 版本运行同一套代码。

---

## 23. 整合说明

合并两份方案时做了以下处理，原有要求均未删减：

| 项目 | 处理方式 |
|---|---|
| 原「第二十一章」 | 并入为第 10 章，紧接普通后台运行（第 9 章），便于对照阅读 |
| 架构图 | 增加 RootManager、Keepalive Monitor 与 Root Watchdog |
| 设备字段 / 心跳 | 增加保活状态字段，使网页能显示「保活模式、守护状态、自动恢复次数」 |
| API | 心跳接口扩展保活字段；明确**不提供**任何下发命令 / 脚本的接口 |
| 数据库 | `devices` / `device_heartbeats` / `audit_logs` 增加 Root 保活相关内容 |
| 边界情况 | 在原 20 条基础上增加 6 条 Root 保活相关情况 |
| 开发阶段 | 在原七阶段中插入「第六阶段：Root 增强保活」，共八阶段；普通模式后台运行提前到第五阶段 |
| 验收测试 | 将方案二的 12 项测试与方案一的基础测试合并为第 19 章 |
| 交付物 | 增加 `root-module/`、Root 保活管理界面及相关文档要求 |
| 优先级 | 方案二未给出优先级，此处为建议值：普通模式保活 P0，Root 增强 P1，Watchdog 独立模块 P2 |
| 包名 | 方案二示例中的 `com.smswebgateway.android` 改为 `<applicationId>`，以实际包名为准 |
| 幂等性 | 将方案二结尾的「恢复服务不得重复发送」同步写入 8.3、17 章和 21.1 |
