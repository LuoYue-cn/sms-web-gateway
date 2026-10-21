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

    /** @return list<array{key:string,label:string,ok:bool,detail:string}> */
    public function checks(): array
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
            ['key' => 'migrations', 'label' => '迁移文件存在', 'ok' => glob($this->config->migrationsDir . '/*.sql') !== [] && glob($this->config->migrationsDir . '/*.sql') !== false, 'detail' => $this->config->migrationsDir],
        ];
    }

    /**
     * @param array<string,mixed> $body
     */
    public function install(array $body): void
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
        foreach ($this->checks() as $c) {
            if (!$c['ok'] && in_array($c['key'], ['php', 'pdo_sqlite', 'data_dir', 'migrations'], true)) {
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
