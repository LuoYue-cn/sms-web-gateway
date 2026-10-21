# Android APK ↔ Root Watchdog 接口约定

applicationId: `com.smswebgateway.android`。Watchdog 只与本机 APK 交互，不联网、不接受服务器命令、不执行任何外部传入的脚本。

## 1. 文件（均不修改系统分区）

| 路径 | 写入者 | 读取者 | 内容 |
|---|---|---|---|
| `/data/user_de/0/com.smswebgateway.android/files/keepalive/status.json` | APK（设备加密存储 DE，开机未解锁也可写） | Watchdog（root 读） | APK 状态快照，见下 |
| `/data/adb/smsgate/control` | APK 经 `su` 写 | Watchdog | 键值文本：`enabled=1`、`interval=60`（30–300）、`max_failures=5`、`pkg=com.smswebgateway.android` |
| `/data/adb/smsgate/watchdog.status` | Watchdog | APK 经 `su` 读 | `pid=…`、`ts=<unix 秒>`、`state=running|cooldown|protected|disabled`、`failures=N`、`recovery_count=N`、`last_recovery_ts=<秒>` |
| `/data/adb/smsgate/watchdog.log` | Watchdog | APK 经 `su` 读 | 每行：`<ISO8601> <level> <action> <detail>`，限 256 KB 自动轮转 |
| `/data/adb/smsgate/watchdog.lock` | Watchdog | Watchdog | 防多实例：存 pid，启动时若 pid 存活（且 cmdline 匹配）则退出 |

`control` 文件缺失或 `enabled=0` → Watchdog 不做任何恢复，只写 `state=disabled` 并保持空闲（或退出，下次开机/用户启用时再起）。

### status.json（APK 每 15 秒或状态变化时写入，原子替换）
```json
{
  "pkg": "com.smswebgateway.android",
  "ts": 1760000000,
  "service_running": true,
  "service_started_at": 1760000000,
  "last_sync_ok_at": 1760000000,
  "last_server_ok_at": 1760000000,
  "network_ok": true,
  "bound": true,
  "user_stopped": false,
  "sending_task_ids": [],
  "protection_state": "none"
}
```
- `bound=false`（未绑定服务器）或 `user_stopped=true`（用户在应用内主动停止服务）→ Watchdog **不得**恢复。
- 用户在系统设置里「强制停止」APK：Android 会阻止被停止的包接收广播，Watchdog 发出的恢复广播不会生效——**这正是预期行为，必须尊重，禁止用 `am start`/`cmd package` 去绕过 stopped 状态**。Watchdog 只发送隐式不可达的显式广播，不做任何「解除 stopped」的操作。

## 2. 恢复动作（Watchdog 唯一允许对 APK 做的事）

判断「不健康」的条件（任一）：
1. `status.json` 不存在或 `now - ts > 120` 秒（APK 内部心跳停了）且应用进程不存在或同样不响应；
2. `service_running=false` 且 `bound=true` 且 `user_stopped=false`；
3. `network_ok=true` 且 `now - last_server_ok_at > 600` 秒 且 `service_running`（同步卡死）。
仅进程存在不算健康。开机后若设备尚未首次解锁（`getprop sys.boot_completed`=1 但 `/data/user/0/<pkg>` 不可访问 / `sys.user.0.ce_available` 不为 true），**不读取 CE 数据，只检查 DE 的 status.json**，且不进行恢复，等待解锁。

恢复只做一件事：向 APK 发送显式广播
```
am broadcast --user 0 -a com.smswebgateway.android.action.WATCHDOG_RECOVER \
  -n com.smswebgateway.android/.keepalive.RecoverReceiver --es reason "<unhealthy_reason>" --ei attempt <N>
```
`RecoverReceiver`（manifest `exported=false`：Android 只允许 root、system 与本应用自己向它发送显式广播，其他应用和 adb shell 会被系统拒绝。这是系统强制的门禁；不要用 `getSentFromUid()` 校验，发送方不带 `--share-identity` 时它恒为 -1）负责：
- 通过 `ForegroundServiceStarter` 尝试启动同步服务（遵守 Android 15 FGS 限制；失败则排 WorkManager 一次性恢复任务）；
- **崩溃恢复规则**：把本地 Room 中状态为 `sending` 的发送任务全部上报并标记 `unknown`，**绝不重发**；`claimed` 的任务保持不动（由服务端 5 分钟回收或设备在上报 `sending` 之前再继续）；
- 记录审计（`recovery_count`++、`last_recovery_at`）；
- 若已有服务实例在运行（单例锁）则什么也不做。

## 3. 冷却与保护

连续失败（恢复后下一个周期仍不健康）次数 N：等待 10s → 30s → 60s → 之后 300s；连续失败达 `max_failures`（默认 5）进入 `protected` 状态：不再尝试恢复，仅写日志，直到：APK 成功写出健康的 status.json（自动解除）或用户从 APK 里点「重置保护」（APK 写 `control` 中 `reset_protection=<ts>`）。

## 4. 禁止事项（Watchdog 与 RootManager 共同遵守）

不 `am force-stop` / `pm disable` 任何包（包括本应用），不 `setenforce 0`，不改 SELinux 策略，不 `settings put global` 关闭 Doze/省电，不 `kill` 其他应用，不挂载/重挂载系统分区，不执行任何来自网络或 APK 传入的字符串作为命令（`pkg`、`reason` 等参数在使用前必须匹配白名单正则 `^[A-Za-z0-9._]+$`）。

## 5. APK 侧 RootManager 允许执行的命令（白名单，字符串常量，不拼接用户输入）

- `id`（确认 uid=0）、`getprop`、`su -v`（识别 KernelSU / Magisk / APatch）
- `dumpsys deviceidle whitelist +<applicationId>` / `-<applicationId>`（applicationId 来自 `BuildConfig`/`packageName`，非用户输入），随后 `dumpsys deviceidle whitelist` 复查
- 安装 Watchdog 模块：把随 APK 打包的 `root-module.zip`（assets）解压到 `/data/adb/modules/smsgate_watchdog/`（KernelSU 模块目录；Magisk 同路径，需 `touch` 之外无其他修改），并写 `/data/adb/smsgate/control`；卸载/禁用：写 `enabled=0`、`touch /data/adb/modules/smsgate_watchdog/disable`（KernelSU/Magisk 标准禁用方式）、移除模块目录标记 `remove`。
- 读取 `watchdog.status` / `watchdog.log`
- 每个命令：5–15 秒超时，捕获 exit code + stderr，写本地审计表（Room `root_audit`），并在心跳里上报最近 ≤20 条摘要（action/result/exit_code/ts，**无敏感内容**）。
- 所有变更前保存原始状态（如：加入白名单前是否已在白名单），关闭 Root 增强或点「恢复系统原始设置」时还原（原本就在白名单的不移除）。
