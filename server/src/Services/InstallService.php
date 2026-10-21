<?php
declare(strict_types=1);

namespace SmsGate\Services;

use SmsGate\Database\Database;
use SmsGate\Database\Migrator;
use SmsGate\Repositories\SettingsRepository;
use SmsGate\Repositories\UserRepository;
use SmsGate\Support\ApiException;
use SmsGate\Support\Clock;
use SmsGate\Support\Config;
use SmsGate\Support\Validator;

/** First-run wizard. The install lock file is the single source of truth for "installed". */
final class InstallService
{
    public function __construct(private Config $config)
    {
    }

    public function isInstalled(): bool
    {
        return is_file($this->config->lockPath());
    }

    /**
     * 数据目录能否被网页直接读取。往 data/ 写一个探针文件，再用 HTTP 请求它：
     * 能读到内容说明 Web 服务器把 data/ 当静态目录公开了（SQLite 数据库就能被下载）。
     *
     * @return array{ok:bool,detail:string}
     */
    public function dataExposure(?string $host, bool $https): array
    {
        if ($host === null || $host === '' || !is_dir($this->config->dataDir) || !is_writable($this->config->dataDir)) {
            return ['ok' => true, 'detail' => '无法检测（跳过）'];
        }
        $name = 'probe-' . bin2hex(random_bytes(6)) . '.txt';
        $path = $this->config->dataDir . '/' . $name;
        $token = bin2hex(random_bytes(8));
        if (@file_put_contents($path, $token) === false) {
            return ['ok' => true, 'detail' => '无法检测（跳过）'];
        }
        try {
            $ctx = stream_context_create(['http' => ['timeout' => 3, 'ignore_errors' => true, 'follow_location' => 0], 'ssl' => ['verify_peer' => false, 'verify_peer_name' => false]]);
            // 运行目录设为站点根时，数据目录会出现在 /data/ 下；运行目录为 /public 时它不在网站目录里，请求只会回落到前端首页。
            $url = ($https ? 'https://' : 'http://') . $host . '/data/' . $name;
            $body = @file_get_contents($url, false, $ctx);
            $found = is_string($body) && str_contains($body, $token);
        } finally {
            @unlink($path);
        }
        return $found
            ? ['ok' => false, 'detail' => '数据目录可被网页直接读取！数据库会泄露。请把站点「运行目录」设为 /public，或为 /data 添加拒绝规则']
            : ['ok' => true, 'detail' => '数据目录无法从网页访问'];
    }

    /** @return list<array{key:string,label:string,ok:bool,detail:string}> */
    public function checks(?string $host = null, bool $https = false): array
    {
        $dataDir = $this->config->dataDir;
        $writable = (is_dir($dataDir) && is_writable($dataDir)) || (!is_dir($dataDir) && is_writable(dirname($dataDir)));
        $sessOk = function_exists('session_start') && (is_dir($this->config->sessionPath()) ? is_writable($this->config->sessionPath()) : $writable);
        return [
            ['key' => 'php', 'label' => 'PHP 版本 ≥ 8.1', 'ok' => PHP_VERSION_ID >= 80100, 'detail' => PHP_VERSION],
            ['key' => 'pdo_sqlite', 'label' => 'PDO SQLite 扩展', 'ok' => extension_loaded('pdo_sqlite'), 'detail' => extension_loaded('pdo_sqlite') ? '已加载' : '缺失'],
            ['key' => 'data_dir', 'label' => '数据目录可写', 'ok' => $writable, 'detail' => $dataDir],
            ['key' => 'openssl', 'label' => 'OpenSSL 可用', 'ok' => extension_loaded('openssl') || function_exists('random_bytes'), 'detail' => extension_loaded('openssl') ? OPENSSL_VERSION_TEXT : '使用 random_bytes'],
            ['key' => 'session', 'label' => 'Session 正常', 'ok' => $sessOk, 'detail' => $this->config->sessionPath()],
            ['key' => 'mbstring', 'label' => 'mbstring 扩展', 'ok' => extension_loaded('mbstring'), 'detail' => extension_loaded('mbstring') ? '已加载' : '缺失'],
            ['key' => 'data_exposure', 'label' => '数据目录不可被网页读取'] + $this->dataExposure($host, $https),
            ['key' => 'migrations', 'label' => '迁移文件存在', 'ok' => glob($this->config->migrationsDir . '/*.sql') !== [] && glob($this->config->migrationsDir . '/*.sql') !== false, 'detail' => $this->config->migrationsDir],
        ];
    }

    /**
     * @param array<string,mixed> $body
     */
    public function install(array $body, ?string $host = null, bool $https = false): void
    {
        if ($this->isInstalled()) {
            throw new ApiException(409, 'already_installed', '系统已安装');
        }
        $v = new Validator($body);
        $username = $v->string('username', 3, 64);
        $password = $v->string('password', 1, 200, true, false);
        $site = $v->string('site_name', 1, 100);
        $tz = $v->string('timezone', 1, 64);
        $v->check();
        if (preg_match('/^[A-Za-z0-9_.@-]+$/', $username) !== 1) {
            throw ApiException::invalid(['username' => '只能包含字母、数字和 _ . @ -']);
        }
        AuthService::assertStrong($password);
        if (!in_array($tz, \DateTimeZone::listIdentifiers(), true) && $tz !== 'UTC') {
            throw ApiException::invalid(['timezone' => '无效的时区']);
        }
        foreach ($this->checks($host, $https) as $c) {
            if (!$c['ok'] && in_array($c['key'], ['php', 'pdo_sqlite', 'data_dir', 'migrations', 'data_exposure'], true)) {
                throw new ApiException(400, 'environment_check_failed', '环境检测未通过: ' . $c['label']);
            }
        }

        // Exclusive creation of the lock makes concurrent installs mutually exclusive.
        if (!is_dir($this->config->dataDir) && !@mkdir($this->config->dataDir, 0775, true) && !is_dir($this->config->dataDir)) {
            throw new ApiException(500, 'internal_error', '无法创建数据目录');
        }
        $partial = $this->config->dataDir . '/install.partial';
        $fh = @fopen($partial, 'x');
        if ($fh === false) {
            if (is_file($partial) && time() - (int) filemtime($partial) > 120) {
                @unlink($partial);
            }
            throw new ApiException(409, 'install_in_progress', '安装正在进行中');
        }
        fclose($fh);
        try {
            $db = new Database($this->config->dbPath());
            (new Migrator($db, $this->config->migrationsDir))->migrate();
            $db->transaction(function () use ($db, $username, $password, $site, $tz): void {
                if ((int) $db->value('SELECT COUNT(*) FROM users') > 0) {
                    throw new ApiException(409, 'already_installed', '系统已安装');
                }
                (new UserRepository($db))->create($username, password_hash($password, PASSWORD_DEFAULT));
                $s = new SettingsRepository($db);
                $s->set('site_name', $site);
                $s->set('timezone', $tz);
                $s->set('installed_at', (string) Clock::nowMs());
            });
            file_put_contents($this->config->lockPath(), 'installed ' . gmdate('c') . "\n");
            @chmod($this->config->lockPath(), 0640);
        } finally {
            @unlink($partial);
        }
    }
}
