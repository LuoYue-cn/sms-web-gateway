<?php
declare(strict_types=1);

namespace SmsGate\Support;

/** Environment-driven runtime configuration. */
final class Config
{
    public function __construct(
        public readonly string $dataDir,
        public readonly string $migrationsDir,
        public readonly string $webRoot,
        public readonly bool $trustProxy,
        /** @var list<string> 可信反向代理地址（IP 或 CIDR）。只有来自这些地址的 X-Forwarded-* 才会被采信 */
        public readonly array $trustedProxies,
        public readonly string $publicUrl,
    ) {
    }

    public static function fromEnv(): self
    {
        $root = dirname(__DIR__, 2);
        $data = self::env('SMSGATE_DATA_DIR', $root . '/data');
        $migrations = self::env('SMSGATE_MIGRATIONS_DIR', $root . '/migrations');
        $webEnv = self::env('SMSGATE_WEB_ROOT', '');
        if ($webEnv !== '') {
            $webRoot = $webEnv;
        } elseif (is_file($root . '/public/index.html')) {
            $webRoot = $root . '/public';
        } elseif (is_file(dirname($root) . '/public/index.html')) {
            $webRoot = dirname($root) . '/public';
        } elseif (is_file(dirname($root) . '/web/dist/index.html')) {
            $webRoot = dirname($root) . '/web/dist';
        } else {
            $webRoot = $root . '/public';
        }
        return new self(
            rtrim($data, '/'),
            rtrim($migrations, '/'),
            rtrim($webRoot, '/'),
            in_array(strtolower(self::env('SMSGATE_TRUST_PROXY', '0')), ['1', 'true', 'yes'], true),
            array_values(array_filter(array_map('trim', explode(',', self::env('SMSGATE_TRUSTED_PROXIES', '127.0.0.1,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16'))))),
            rtrim(self::env('SMSGATE_PUBLIC_URL', ''), '/'),
        );
    }

    public function dbPath(): string
    {
        return $this->dataDir . '/sms_gateway.sqlite';
    }

    public function lockPath(): string
    {
        return $this->dataDir . '/install.lock';
    }

    public function sessionPath(): string
    {
        return $this->dataDir . '/sessions';
    }

    private static function env(string $key, string $default): string
    {
        $v = getenv($key);
        if (($v === false || $v === '') && isset($_SERVER[$key]) && is_string($_SERVER[$key])) {
            $v = $_SERVER[$key]; // Nginx: fastcgi_param SMSGATE_TRUST_PROXY 1;
        }
        return ($v === false || $v === '') ? $default : $v;
    }
}
