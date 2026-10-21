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

必须传递 `X-Forwarded-Proto: https`，否则 Session Cookie 不会带 `Secure` 标记。把 compose 的端口只绑定到本机：在 `.env` 里无法改绑定地址时，可把 `docker-compose.yml` 的 `ports` 改成 `"127.0.0.1:8080:80"`。

登录频率限制按「用户名 + 客户端 IP」统计。容器内 Nginx 把**直接对端地址**当作客户端 IP（不信任外部传入的 `X-Forwarded-For`，防止伪造绕过限制），所以在反向代理后面，所有客户端共享同一个 IP 计数；5 次失败/15 分钟的限制仍然按用户名生效。

## 5. 命令行运维

| 操作 | 命令 |
|---|---|
| 查看日志 | `docker compose logs -f php nginx` |
| 重置安装（**删除全部数据**，会先备份） | `docker compose exec php php bin/reset.php --yes` |
| 升级 | `git pull && docker compose up -d --build` |
| 停止 | `docker compose down`（保留数据） |

## 6. 容量与调优

每台设备持有 1 个长轮询连接，每个打开的网页持有 1 个 SSE 连接，各占用一个 PHP-FPM worker。`docker/php/zz-smsgate.conf` 默认 `pm.max_children = 40`，据此估算：设备数 + 同时打开的网页数 + 余量 ≤ 40。设备更多时调大该值。

## 7. 不使用 Docker 的本地开发

```bash
cd web && npm install && npm run build && cd ..
SMSGATE_DATA_DIR=/tmp/smsdata PHP_CLI_SERVER_WORKERS=8 php -S 127.0.0.1:8080 -t server/public server/public/index.php
```

`PHP_CLI_SERVER_WORKERS` 必须 ≥ 2，否则长轮询会阻塞其他请求。运行测试：`python3 server/tests/api_test.py`。
