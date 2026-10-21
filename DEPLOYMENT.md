# 部署指南

## 1. 前置条件

- Debian / Ubuntu 服务器（任意装有 Docker Engine 24+ 与 Docker Compose v2 的 Linux 均可）。
- 一个指向服务器的域名，以及 HTTPS 证书（见第 4 节）。**手机绑定、同步短信、发送短信必须走 HTTPS**；HTTP 仅用于本地初始化与开发测试。

## 2. 启动

```bash
git clone <本仓库> sms-web-gateway && cd sms-web-gateway
cp .env.example .env          # 按需修改 HTTP_PORT / PUBLIC_URL / TZ
docker compose up -d --build
```

浏览器打开 `http://服务器IP:8080/install`，按向导完成：环境检测 → 管理员账号（密码至少 10 位）→ 系统名称与时区 → 完成。之后 `/install` 只显示「系统已安装」。

构建内容：`nginx` 镜像内含 Vue 前端构建产物；`php` 镜像为 PHP-FPM（默认 8.1，PDO SQLite + mbstring）。想用仍受安全维护的 PHP 版本运行同一套代码：

```bash
docker compose build --build-arg PHP_VERSION=8.3 && docker compose up -d
```

> PHP 8.1 已结束官方安全支持。代码保持 8.1 兼容，正式部署建议使用 8.3 及以上。

## 3. 数据与备份

所有数据（SQLite 数据库、安装锁、PHP Session）都在命名卷 `smsgate_data`（容器内 `/var/lib/smsgate`）。`docker compose down` / 重建镜像**不会**删除它；只有 `docker compose down -v` 才会。

```bash
# 在线备份（SQLite VACUUM INTO，服务运行中可执行）
docker compose exec php php bin/backup.php /var/lib/smsgate/backups/manual.sqlite
docker cp "$(docker compose ps -q php)":/var/lib/smsgate/backups ./backups

# 恢复（先停止服务）
docker compose stop nginx php
docker compose run --rm php php bin/backup.php --restore /var/lib/smsgate/backups/manual.sqlite --yes
docker compose start php nginx
```

建议用 cron 定时备份并把备份拷贝到其他机器。

数据库迁移：容器启动后首个请求会自动应用 `server/migrations/*.sql` 中未执行的迁移；也可手动执行 `docker compose exec php php bin/migrate.php`。

## 4. HTTPS（反向代理示例）

compose 暴露的是明文 `8080`，请在前面放一个终止 TLS 的反向代理（宿主机 Nginx / Caddy / Traefik）。宿主机 Nginx 示例：

```nginx
server {
    listen 443 ssl http2;
    server_name sms.example.com;
    ssl_certificate     /etc/letsencrypt/live/sms.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/sms.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-For $remote_addr;
        # 长轮询（≤25s）与 SSE（≈55s）不能被缓冲或过早切断
        proxy_buffering off;
        proxy_read_timeout 90s;
    }
}
server { listen 80; server_name sms.example.com; return 301 https://$host$request_uri; }
```

必须传递 `X-Forwarded-Proto: https`，否则 Session Cookie 不会带 `Secure` 标记（该头只对 `TRUSTED_PROXIES` 内的来源生效，见 4.1）。把 compose 的端口只绑定到本机：在 `.env` 里无法改绑定地址时，可把 `docker-compose.yml` 的 `ports` 改成 `"127.0.0.1:8080:80"`。

### 4.1 真实客户端 IP 与登录限流

登录限流按「用户名 + 来源 IP」统计，另有「同一用户名跨所有来源」的总量上限（见 SECURITY.md 2.2）。要让第一层正确工作，服务端必须知道**真实的客户端 IP**：

- **默认不信任**任何 `X-Forwarded-For`（否则攻击者每次伪造一个值就能绕过限流，这是已修复过的真实漏洞）。
- 只有当 `SMSGATE_TRUST_PROXY=1` **并且**请求的直接来源属于 `SMSGATE_TRUSTED_PROXIES`（IP 或 CIDR，逗号分隔）时，才会采信转发头；采信时取转发链里**从右往左第一个不可信地址**。
- Docker：`docker-compose.yml` 已开启，并默认信任容器网络和常见内网段；Nginx 容器会把真实对端地址写入 `X-Forwarded-For`。
  - 如果你在 Docker 之前还有一层公网反代，把它的地址加进 `.env` 的 `TRUSTED_PROXIES`，并让那层反代传 `X-Forwarded-For $remote_addr`。
- 宝塔 / 裸 PHP-FPM：默认关闭，此时来源 IP 就是 `REMOTE_ADDR`。如果站点前面还有 CDN / 另一层 Nginx，在站点的伪静态或 `fastcgi_param` 里设置：
  ```nginx
  fastcgi_param SMSGATE_TRUST_PROXY 1;
  fastcgi_param SMSGATE_TRUSTED_PROXIES "127.0.0.1,你的反代IP或CIDR";
  ```
  并确认那层反代会传 `X-Forwarded-For` 与 `X-Forwarded-Proto`。
- **不配置也安全**：最坏情况是所有人共享同一个来源，第一层限流变严格；第二层（按用户名）仍然有效。

### 4.2 实时推送（SSE）与反向代理缓冲

网页的实时更新走 SSE（`/api/v1/events`）。服务端会发送 `X-Accel-Buffering: no`，**Nginx 看到它就不会缓冲**，所以：

- 宝塔 / 直接 Nginx → PHP-FPM：**不需要任何额外配置**。（我们用真实的 Nginx 1.24 + PHP-FPM 实测：带这个头时事件 0.11 秒到达、15 秒心跳准时；把这个头去掉后，事件和心跳 22 秒内都没有到达。）
- **多一层代理才需要处理**：如果在它前面又套了 CDN、另一层 Nginx 或云厂商网关，那一层可能缓冲响应或剥掉这个头，网页就收不到实时推送（前端会自动退化为 5 秒轮询，功能不丢，只是不实时）。该层需要关闭缓冲：
  - Nginx：`proxy_buffering off;`（上面的示例已包含）并 `proxy_read_timeout 90s;`
  - Cloudflare 等：确认对 `/api/v1/events` 不做缓冲 / 缓存。
- 单个 SSE 连接最长约 55 秒后正常结束，浏览器会自动重连并带 `Last-Event-ID` 续传，不会丢事件。

## 5. 命令行运维

| 操作 | 命令 |
|---|---|
| 查看日志 | `docker compose logs -f php nginx` |
| 重置安装（**删除全部数据**，会先备份） | `docker compose exec php php bin/reset.php --yes` |
| 升级 | `git pull && docker compose up -d --build` |
| 停止 | `docker compose down`（保留数据） |

## 6. 容量与调优

**SQLite 并发**：已开启 WAL（`journal_mode=WAL`），写入使用 `BEGIN IMMEDIATE` 事务并设置 8 秒 `busy_timeout`。实测（PHP 内置服务器，16–32 个 worker，同时有管理员轮询会话列表、SSE 连接和发送队列）：

| 场景 | 结果 |
|---|---|
| 4 台设备同时灌 4 × 10,000 条 | 约 9,800 条/秒，80 个批次全部成功，**0 个 `database_busy`**，管理端读取 p95 6 ms |
| 10 台设备同时灌 10 × 20,000 条（共 20 万条）+ 4 个读者 + 3 个 SSE + 发送队列 | 约 6,200 条/秒，400 个批次全部成功，**0 错误**；同步批次 p50 353 ms / p95 1.95 s；管理端读取 p95 6 ms |

结论：单台服务器管理几台到十几台设备、几万条历史短信的同步，写锁不是瓶颈。以上是单机本地压测，不代表你的磁盘和 CPU；设备数成百上千时才需要考虑换数据库。

**WAL 文件体积**：大批量同步时 WAL 文件会**暂时**涨得很大（实测 12 万条并发灌入峰值约 950 MB）。已设置 `journal_size_limit = 64MB`，检查点完成后 WAL 会被截断回这个大小；平时空闲时为 0。如果你的数据盘很小，请留出几百 MB 余量，或让设备分批、错峰首次同步。

**PHP-FPM worker**：每台设备持有 1 个长轮询连接，每个打开的网页持有 1 个 SSE 连接，各占用一个 PHP-FPM worker。`docker/php/zz-smsgate.conf` 默认 `pm.max_children = 40`，据此估算：设备数 + 同时打开的网页数 + 余量 ≤ 40。设备更多时调大该值。

## 7. 不使用 Docker 的本地开发

```bash
cd web && npm install && npm run build && cd ..
SMSGATE_DATA_DIR=/tmp/smsdata PHP_CLI_SERVER_WORKERS=8 php -S 127.0.0.1:8080 -t server/public server/public/index.php
```

`PHP_CLI_SERVER_WORKERS` 必须 ≥ 2，否则长轮询会阻塞其他请求。运行测试：`python3 server/tests/api_test.py`。
