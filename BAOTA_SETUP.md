# SMS Web Gateway — 宝塔面板（aaPanel）部署指南

本文档介绍如何在**宝塔面板（BT-Panel / aaPanel）**上使用本纯净源码包，一键搭建并运行自托管短信网关服务。

---

## 一、环境准备

在宝塔面板「软件商店」中确保已安装：
- **Web 服务**：Nginx（推荐）或 Apache。
- **PHP 版本**：**PHP 8.1**、**PHP 8.2** 或 **PHP 8.3**。
- **PHP 扩展检查**（在宝塔「PHP 管理」->「安装扩展」中确认）：
  - `pdo_sqlite`（通常已内置）
  - `sqlite3`（通常已内置）
  - `mbstring`（通常已内置）
  - `fileinfo`（若未安装请在列表点击安装）
  - `openssl`（默认已内置）
- **函数禁用检查**：
  - 本系统 Web 端完全不使用 `exec`、`shell_exec`、`system` 等危险系统函数，宝塔默认的禁用函数列表**无需修改**，安全合规。

---

## 二、部署步骤（5分钟完成）

### 第一步：新建站点
1. 登录宝塔面板，进入「网站」->「添加站点」。
2. 填写你的**域名**（例如 `sms.yourdomain.com`）。
3. 根目录保持默认（例如 `/www/wwwroot/sms.yourdomain.com`）。
4. **数据库**选择**「不创建」**（系统使用内置 SQLite，自动创建数据库文件，零运维）。
5. **PHP 版本**选择 **PHP-81**、**PHP-82** 或 **PHP-83**。
6. 点击「提交」。

### 第二步：上传并解压源码包
1. 进入宝塔「文件」管理器，打开刚创建的网站目录（`/www/wwwroot/sms.yourdomain.com`）。
2. 将本压缩包（`smsgate-baota-clean.zip`）上传至该目录。
3. 点击「解压」，解压到当前网站根目录中。
4. 解压后，确保网站根目录下包含 `public/`、`src/`、`migrations/`、`data/`、`bin/` 等文件与文件夹。

### 第三步：设置运行目录（至关重要）
1. 回到宝塔「网站」列表，点击你的网站名称进入设置。
2. 在左侧菜单点击**「网站目录」**。
3. 找到**「运行目录」**下拉框，选择 **`/public`**。
4. 点击「保存」。

> ⚠️ **必须设为 `/public`，这不是可选项。** 如果运行目录是站点根，Web 服务器会把 `data/` 当静态目录公开，**`/data/sms_gateway.sqlite`（全部短信的明文数据库）可以被任何人直接下载**（我们用真实 Nginx 实测复现过）。
> 安装向导会实际请求一次 `/data/` 探针文件：**读得到就拒绝安装**，并提示你修改运行目录。
> 另外建议在伪静态末尾保留「禁止访问 data/src/migrations/bin」的规则（本包的 `nginx.rewrite.conf` 已包含）。

### 第四步：配置伪静态（URL Rewrite）
1. 在网站设置窗口的左侧菜单点击**「伪静态」**。
2. 如果你的 Web 服务器是 **Nginx**（绝大多数情况）：
   清空原有内容，将以下规则复制粘贴进去，点击「保存」：

```nginx
location /api/ {
    try_files $uri $uri/ /index.php$is_args$args;
}

location / {
    try_files $uri $uri/ /index.html /index.php$is_args$args;
}

# 禁止外部直接访问后端核心与数据目录
location ~ ^/(data|migrations|src|bin|\.git|\.env|install\.lock) {
    deny all;
    return 404;
}
```

3. 如果你的 Web 服务器是 **Apache**：
   压缩包内的 `.htaccess` 已自动生效，无需额外配置。

### 第五步：开启 SSL（配置 HTTPS）
> **安全说明：** 手机 Android 原生端与服务端通信涉及短信及一次性验证码传输，**必须使用 HTTPS**。调试版（debug APK）允许本地 HTTP，但生产使用必须开启 SSL！
1. 在网站设置左侧点击**「SSL」**。
2. 可以选择「Let's Encrypt」免费证书，勾选域名，一键申请并启用「强制 HTTPS」。
3. 保存生效。

### 第六步：检查目录权限
确保网站根目录及 `data/` 目录的所有者为 `www:www`。程序创建的数据库、会话、安装锁默认权限为 `0640`（目录 `0750`），不会对其他用户可读，**请不要把 `data/` 放宽到 777**。

---

## 三、访问与初始化安装

> **安装前自检（30 秒）**：在浏览器里分别打开 `https://你的域名/data/sms_gateway.sqlite` 与 `https://你的域名/data/install.lock`。看到的**必须是前端页面或 404**，如果浏览器开始下载文件，说明运行目录没设对，立刻停止并修改。

1. 用浏览器访问你的域名：`https://sms.yourdomain.com`
2. **全自动引导**：初次部署尚未初始化时，系统会自动强制定向进入 `/install` 安装向导页。
3. **环境检测**：页面第一步会自动检查 PHP 版本、SQLite、目录可写权限等，全部绿勾后点击「下一步」。
4. **管理员设置**：输入管理员用户名、密码（至少 10 位）、系统名称、时区，点击「完成安装」。
5. **安装锁自动激活**：
   - 安装完成后，服务端自动生成 `data/install.lock` 安装锁。
   - 网页端的 `/install` 安装向导页将被**永久彻底禁用**，之后任何访问均会自动跳转至 `/login` 登录页，杜绝重复安装风险。
6. 输入刚才设置的管理员账号密码，登录进入管理后台！

---

## 四、连接手机使用

1. 在管理后台点击顶部导航栏的**「设备管理」**。
2. 点击**「+ 添加设备」**，屏幕上将生成包含服务器地址和配对令牌的二维码。
3. 手机安装好 APK（可在项目 `dist/smsgate-debug.apk` 或 `dist/smsgate-release.apk` 获取）。
4. 在手机 App 首页点击「扫描二维码」（或手动输入服务器地址与令牌），完成绑定。
5. 手机将自动开始历史短信同步，网页端秒级显示设备状态、电量与短信列表！

---

## 五、日常运维与备份说明

本系统所有状态与数据均保存在网站根目录下的 `data/` 目录中：
- 数据库路径：`data/sms_gateway.sqlite`
- 安装锁：`data/install.lock`
- 会话目录：`data/sessions/`

### 1. 数据备份
在宝塔计划任务或终端中均可直接热备份数据库（使用 SQLite 在线备份，不锁表、不中断服务）：
```bash
cd /www/wwwroot/sms.yourdomain.com
php bin/backup.php
```
备份文件将生成在 `data/backups/` 目录下。直接在宝塔里备份整个 `data/` 目录也是完全支持的。

### 1.1 每天自动生成 AI 日报（可选）

先在网页「设置 → AI 日报」填好服务地址、模型和 API Key，点「测试连接」。然后在宝塔「计划任务」里新建一个 **Shell 脚本** 任务，每天早上 8 点执行（生成**昨天**的报告）：
```bash
cd /www/wwwroot/sms.yourdomain.com
/www/server/php/83/bin/php bin/daily_report.php
```
（PHP 路径按你的版本调整；加 `--no-ai` 则只生成不联网的规则模板日报，也可以带日期参数补生成某一天，如 `bin/daily_report.php 2026-10-30`。）不配计划任务也可以，在网页「AI 日报」页手动生成。

### 1.2 升级后给历史短信补验证码
从旧版本升级后，服务端第一次被访问时会自动分批给历史短信识别验证码，通常几秒内完成。如果短信量特别大，也可以手动一次跑完：
```bash
php bin/backfill_codes.php          # 只处理还没识别过的
php bin/backfill_codes.php --all    # 识别规则升级后强制重扫全部
```

### 2. 重新初始化安装（如果需要）
出于安全防范，网页端一旦安装完成，绝对无法再次通过网页重置。如果确实需要推倒重置：
在宝塔终端执行：
```bash
cd /www/wwwroot/sms.yourdomain.com
php bin/reset.php --yes
```
重置脚本会自动对当前数据库做一份安全副本，然后清除安装锁。再次访问网站即可重新进入 `/install`。
