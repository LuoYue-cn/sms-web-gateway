# Android 客户端安装与配置指南 (ANDROID_SETUP.md)

本文档介绍如何在 Android 备用机上安装、授权、绑定并长期稳定运行 **SMS Web Gateway**。

---

## 1. 适用环境与系统要求

- **操作系统**：Android 8.0 ~ Android 15（API Level 26 ~ 35）。本项目原生针对 Android 15 (compileSdk=35, targetSdk=35) 优化。
- **系统类型**：AOSP / Pixel 原生 / LineageOS 等类原生系统，或 MIUI / HyperOS / ColorOS 等厂商定制 ROM。
- **Root 权限**：**可选**。
  - **普通模式**（无 Root）：完全支持历史短信同步、实时接收新短信、网页下发短信发送、前台服务保活和 WorkManager 周期补偿。
  - **Root 增强模式**（KernelSU / KernelSU Next / Magisk / APatch）：提供自动加入电池优化白名单、Root Watchdog 独立守护进程拉起与保护机制。
- **Google Play Services**：**完全不需要**。二维码扫描采用 CameraX + 本地 ZXing 解码，不依赖 ML Kit 或任何云端服务。

---

## 2. 安装 APK

### 2.1 获取安装包

编译产物位于项目的 `dist/` 目录：
- `dist/smsgate-debug.apk`：调试版本，支持连接本地或局域网明文 HTTP（例如 `http://127.0.0.1:8080`），便于本地调试。
- `dist/smsgate-release.apk`：生产优化版本，开启了代码缩减与资源优化，**强制必须使用 HTTPS** 且严格校验服务器证书（禁止明文 HTTP，禁止忽略证书错误）。

### 2.2 通过 adb 侧载安装

手机开启「开发者选项」与「USB 调试」后，使用 USB 连接电脑执行：

```bash
# 检查设备连接
adb devices

# 安装 APK
adb install -r dist/smsgate-debug.apk
```

也可以直接把 APK 文件传输到手机文件管理器中点击安装。

---

## 3. 权限授予与配置引导

应用打开后，如果在首页未绑定状态，下方会提示未授予的权限，点击「权限设置」可逐项检查与授权。

### 3.1 核心权限

| 权限 | 用途 | 是否必需 | 说明 |
|---|---|---|---|
| **读取短信** (`READ_SMS`) | 同步收件箱、已发送历史短信 | **必需** | 首次同步时分批读取系统短信库 |
| **接收短信** (`RECEIVE_SMS`) | 实时监听新到短信广播 | **必需** | 通过 `SMS_RECEIVED` 广播实时入库 |
| **发送短信** (`SEND_SMS`) | 网页端发起短信发送 | **必需** | 调用系统 `SmsManager` 调度硬件发送 |
| **读取电话状态** (`READ_PHONE_STATE`) | 读取 SIM 卡数量与运营商 | 推荐 | 用于双卡支持及状态卡片显示 |
| **读取联系人** (`READ_CONTACTS`) | 解析号码对应的联系人姓名 | 可选 | 仅本地匹配姓名，便于会话列表展示 |
| **通知权限** (`POST_NOTIFICATIONS`) | 显示前台保活服务通知 | 推荐 | Android 13+ 维持前台服务必需 |
| **相机权限** (`CAMERA`) | 扫描绑定二维码 | 可选 | 不授权限亦可手动输入地址与令牌 |

**快捷批量授权（仅限通过 adb 调试时）：**
```bash
adb shell pm grant com.smswebgateway.android android.permission.READ_SMS
adb shell pm grant com.smswebgateway.android android.permission.RECEIVE_SMS
adb shell pm grant com.smswebgateway.android android.permission.SEND_SMS
adb shell pm grant com.smswebgateway.android android.permission.READ_PHONE_STATE
adb shell pm grant com.smswebgateway.android android.permission.READ_CONTACTS
adb shell pm grant com.smswebgateway.android android.permission.POST_NOTIFICATIONS
```

---

## 4. 设备绑定流程

### 4.1 二维码扫码绑定（推荐）

1. 在电脑浏览器登录 SMS Web Gateway 网页端（如 `https://sms.example.com`）。
2. 进入导航栏「设备管理」页面，点击「+ 添加设备」。
3. 网页将弹出包含服务器地址与一次性配对令牌（有效期 5 分钟）的二维码。
4. 在手机 App 首页点击「扫描二维码」，对准网页二维码扫描。
5. App 解析成功后将自动提交配对请求，成功后保存凭据并自动进入主控台。

### 4.2 手动输入绑定（调试或无摄像头环境）

1. 在网页「添加设备」弹窗中，可查看服务器地址及一串配对令牌字符串。
2. 在手机 App 首页点击「手动输入服务器地址」。
3. 填入服务器完整地址（如 `http://127.0.0.1:8080` 或 `https://sms.example.com`）和配对令牌。
4. 点击「绑定」完成配对。

> **提示（本地开发模式）：** 若服务端运行在电脑宿主机 `http://127.0.0.1:8080`，可通过 `adb reverse tcp:8080 tcp:8080` 把手机端口映射回宿主机，直接输入 `http://127.0.0.1:8080` 进行本地联调。

---

## 5. 后台保活机制

### 5.1 普通模式（默认，无需 Root）

适用于所有已绑定设备：
- **前台服务 (Foreground Service)**：绑定成功后 App 会启动前台常驻服务（类型为 Android 15 合规的 `specialUse`），在通知栏显示持久状态，维持长轮询连接与每 30 秒心跳。
- **WorkManager 兜底补偿**：注册周期任务，网络恢复或前台连接断开时，每隔 15 分钟触发一次周期同步与状态刷新。
- **开机自动拉起**：监听 `BOOT_COMPLETED` 广播，手机重启后在后台排布恢复 Worker。
- **Doze / 电池优化白名单引导**：在 App「设置」页面点击「去设置电池优化」，将应用设置为「无限制」或「加入电池优化白名单」，防止系统息屏后进入深睡切断长轮询。

### 5.2 Root 增强保活（适用于已 Root 的备用机）

在 App「设置 → Root 保活管理」中提供更强的守护与自恢复能力：

1. **启用步骤**：
   - 如果使用 **Magisk**：点击「启用 Root 增强」，在弹出的超级用户请求对话框中选择「允许」。
   - 如果使用 **KernelSU / KernelSU Next / APatch**：**不会**自动弹出请求框。请先在手机上打开 KernelSU App，在「超级用户」列表中找到「短信网关」并手动开启超级用户授权，然后返回本 App 点击「启用 Root 增强」。
2. **启用后的效果**：
   - 自动执行 `dumpsys deviceidle whitelist +com.smswebgateway.android` 将自身加入电池白名单（记录原始状态，关闭时可完全还原）。
   - 在设备加密存储（DE 目录 `/data/user_de/0/com.smswebgateway.android/files/keepalive/status.json`）定期写入健康心跳，供底层 Watchdog 读取。
3. **安装 Root Watchdog 独立模块（可选）**：
   - 在面板点击「安装 Watchdog 模块」，App 会通过内置 su 将随包带有的 Watchdog 守护脚本部署至 `/data/adb/modules/smsgate_watchdog` 和 `/data/adb/smsgate/`。
   - 重启手机后，Magisk / KernelSU 会在开机脚本阶段以后台守护进程运行 `watchdog.sh`。
   - Watchdog 按照 30~300 秒间隔监控应用健康状态；发现应用被系统回收且处于异常状态时，通过显式广播受控拉起恢复。
4. **回退与还原**：
   - 在同一页面点击「禁用 Root 增强」，App 会停止守护（写入 `enabled=0`）并自动撤销电池白名单设置（若原本未在白名单中），完全恢复系统原始状态。

---

## 6. 定制 ROM 注意事项（HyperOS / MIUI / ColorOS / OriginOS 等）

如果你的备用机运行的是厂商定制系统，系统的省电管理通常比原生 Android 更加严苛，建议配置：

1. **自启动权限**：在系统「设置 → 应用管理 → 短信网关 → 权限管理」中，开启「自启动」和「关联启动」。
2. **省电策略**：在应用的「省电策略 / 电池管理」中，将默认的「智能推荐」改为「无限制」。
3. **多任务卡片锁定**：在最近任务列表中长按应用卡片，点击小锁图标「锁定后台」，避免被一键清理清理掉。
4. **系统默认短信应用限制**：本项目**不需要**成为默认短信应用即可完成读取、接收与发送；但部分定制系统在非默认短信应用发送短信时会弹出系统确认框，请在首次发送时选择「一律允许」。

---

## 7. 设备解绑与数据清理

如需将备用机切换到其他服务器或停用：
1. 打开 App 进入「设置」页面，滑到底部。
2. 点击「解绑并清除本地数据」，确认弹窗。
3. App 会执行以下清理：
   - 吊销并清除 Keystore 中的加密设备令牌。
   - 清空本地 Room 待发任务队列、待上传队列与本地日志。
   - 重置历史同步游标。
   - 停止前台服务。
   - 若启用了 Root 增强，自动停止底层 Watchdog 并还原电池优化设置。
   - 页面自动返回至初始的「尚未绑定服务器」界面。
4. 服务端对应的设备记录可保留历史聊天，或由管理员在网页端「设备管理」中选择「移除设备」。
