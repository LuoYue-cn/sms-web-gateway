<?php
declare(strict_types=1);

namespace SmsGate\Repositories;

use SmsGate\Database\Database;

final class SettingsRepository
{
    /** @var array<string,string> */
    public const DEFAULTS = [
        'site_name' => 'SMS Web Gateway',
        'timezone' => 'UTC',
        'online_threshold_seconds' => '60',
        'send_paused' => '0',
        'send_rate_per_minute' => '10',
        'max_content_length' => '1000',
        'home_country_code' => '+86',
        'heartbeat_interval_s' => '30',
        'poll_wait_s' => '20',
        // AI 日报：不进 all()（那是公开给前端的通用设置），由 /ai/config 单独读写；api_key 只写不读
        'ai_enabled' => '0',
        'ai_base_url' => '',
        'ai_model' => '',
        'ai_api_key' => '',
        'ai_detail_level' => '2',
    ];

    /** @var array<string,string>|null */
    private ?array $cache = null;

    public function __construct(private Database $db)
    {
    }

    /** @return array<string,string> */
    private function load(): array
    {
        if ($this->cache === null) {
            $this->cache = self::DEFAULTS;
            foreach ($this->db->all('SELECT key, value FROM settings') as $r) {
                $this->cache[$r['key']] = (string) $r['value'];
            }
        }
        return $this->cache;
    }

    /** Drop the in-memory cache so the next read hits the database (long-running requests). */
    public function refresh(): void
    {
        $this->cache = null;
    }

    public function get(string $key): string
    {
        return $this->load()[$key] ?? '';
    }

    public function int(string $key): int
    {
        return (int) $this->get($key);
    }

    public function bool(string $key): bool
    {
        return $this->get($key) === '1';
    }

    public function set(string $key, string $value): void
    {
        $this->db->exec('INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value', [$key, $value]);
        $this->cache = null;
    }

    /** @return array<string,mixed> typed view for the admin API */
    public function all(): array
    {
        $s = $this->load();
        return [
            'site_name' => $s['site_name'],
            'timezone' => $s['timezone'],
            'online_threshold_seconds' => (int) $s['online_threshold_seconds'],
            'send_paused' => $s['send_paused'] === '1',
            'send_rate_per_minute' => (int) $s['send_rate_per_minute'],
            'max_content_length' => (int) $s['max_content_length'],
            'home_country_code' => $s['home_country_code'],
            'heartbeat_interval_s' => (int) $s['heartbeat_interval_s'],
            'poll_wait_s' => (int) $s['poll_wait_s'],
        ];
    }

    /** @return array<string,int> */
    public function deviceConfig(): array
    {
        return [
            'heartbeat_interval_s' => $this->int('heartbeat_interval_s'),
            'poll_wait_s' => $this->int('poll_wait_s'),
            'online_threshold_seconds' => $this->int('online_threshold_seconds'),
        ];
    }
}
