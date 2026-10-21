<?php
declare(strict_types=1);

namespace SmsGate\Repositories;

use SmsGate\Database\Database;
use SmsGate\Support\Clock;
use SmsGate\Support\Id;

final class DeviceRepository
{
    public const PAIRING_TTL_MS = 300_000;
    public const ROTATE_GRACE_MS = 600_000;

    public function __construct(private Database $db, private SettingsRepository $settings)
    {
    }

    // ---- pairing tokens -------------------------------------------------

    /** @return array{token:string,expires_at:int} */
    public function createPairingToken(?int $userId): array
    {
        $token = Id::token(24);
        $now = Clock::nowMs();
        $this->db->exec('DELETE FROM pairing_tokens WHERE expires_at < ?', [$now - 3600_000]);
        $this->db->exec(
            'INSERT INTO pairing_tokens (token_hash, created_by, created_at, expires_at) VALUES (?, ?, ?, ?)',
            [Id::hash($token), $userId, $now, $now + self::PAIRING_TTL_MS]
        );
        return ['token' => $token, 'expires_at' => $now + self::PAIRING_TTL_MS];
    }

    /**
     * Atomically consume a pairing token. The conditional UPDATE guarantees single use even under
     * concurrent requests.
     */
    public function consumePairingToken(string $token): ?int
    {
        $now = Clock::nowMs();
        $n = $this->db->exec(
            'UPDATE pairing_tokens SET used_at = ? WHERE token_hash = ? AND used_at IS NULL AND expires_at >= ?',
            [$now, Id::hash($token), $now]
        );
        if ($n !== 1) {
            return null;
        }
        return (int) $this->db->value('SELECT id FROM pairing_tokens WHERE token_hash = ?', [Id::hash($token)]);
    }

    public function attachPairing(int $pairingId, int $deviceId): void
    {
        $this->db->exec('UPDATE pairing_tokens SET device_id = ? WHERE id = ?', [$deviceId, $pairingId]);
    }

    // ---- devices --------------------------------------------------------

    public function create(string $name, string $androidVersion, string $appVersion, int $simCount): int
    {
        $this->db->exec(
            'INSERT INTO devices (device_name, android_version, app_version, sim_count, last_seen, created_at) VALUES (?, ?, ?, ?, ?, ?)',
            [$name, $androidVersion, $appVersion, $simCount, Clock::nowMs(), Clock::nowMs()]
        );
        $id = $this->db->lastId();
        $this->db->exec('INSERT INTO sync_cursors (device_id, state, updated_at) VALUES (?, ?, ?)', [$id, 'never', Clock::nowMs()]);
        return $id;
    }

    /** @return array<string,mixed>|null */
    public function row(int $id): ?array
    {
        return $this->db->one('SELECT * FROM devices WHERE id = ?', [$id]);
    }

    public function rename(int $id, string $name): bool
    {
        return $this->db->exec('UPDATE devices SET device_name = ? WHERE id = ?', [$name, $id]) === 1;
    }

    /** Revoke all tokens, then delete (cascade removes tokens/tasks/conversations/messages unless $keepData). */
    public function revokeAndDelete(int $id, bool $purge): void
    {
        $this->db->transaction(function () use ($id, $purge): void {
            $now = Clock::nowMs();
            $this->db->exec('UPDATE device_tokens SET revoked_at = ? WHERE device_id = ? AND revoked_at IS NULL', [$now, $id]);
            // The device row stays (marked revoked) so a phone that still holds the old token
            // learns it was removed (`device_revoked`) instead of getting an ambiguous invalid_token.
            $this->db->exec('UPDATE devices SET revoked_at = ? WHERE id = ?', [$now, $id]);
            if ($purge) {
                $this->db->exec('DELETE FROM messages WHERE device_id = ?', [$id]);
                $this->db->exec('DELETE FROM conversations WHERE device_id = ?', [$id]);
                $this->db->exec('DELETE FROM send_tasks WHERE device_id = ?', [$id]);
                return;
            }
            $this->db->exec(
                "UPDATE send_tasks SET status = 'failed', error_code = 'device_revoked', error_message = '设备已被移除', updated_at = ?, completed_at = ? WHERE device_id = ? AND status IN ('queued','claimed')",
                [$now, $now, $id]
            );
        });
    }

    // ---- device tokens --------------------------------------------------

    public function issueToken(int $deviceId): string
    {
        $token = Id::token(32);
        $this->db->exec('INSERT INTO device_tokens (device_id, token_hash, created_at) VALUES (?, ?, ?)', [$deviceId, Id::hash($token), Clock::nowMs()]);
        return $token;
    }

    /** Rotate: issue a new token; older ones stay valid for the grace period then expire. */
    public function rotateToken(int $deviceId): string
    {
        return $this->db->transaction(function () use ($deviceId): string {
            $exp = Clock::nowMs() + self::ROTATE_GRACE_MS;
            $this->db->exec('UPDATE device_tokens SET expires_at = ? WHERE device_id = ? AND revoked_at IS NULL AND expires_at IS NULL', [$exp, $deviceId]);
            return $this->issueToken($deviceId);
        });
    }

    /**
     * @return array{state:'ok',device:array<string,mixed>}|array{state:'revoked'}|array{state:'invalid'}
     */
    public function authenticate(string $token): array
    {
        if ($token === '') {
            return ['state' => 'invalid'];
        }
        $row = $this->db->one(
            'SELECT t.id AS token_id, t.revoked_at AS token_revoked, t.expires_at, d.* FROM device_tokens t JOIN devices d ON d.id = t.device_id WHERE t.token_hash = ?',
            [Id::hash($token)]
        );
        if ($row === null) {
            return ['state' => 'invalid'];
        }
        if ($row['token_revoked'] !== null || $row['revoked_at'] !== null) {
            return ['state' => 'revoked'];
        }
        if ($row['expires_at'] !== null && (int) $row['expires_at'] < Clock::nowMs()) {
            return ['state' => 'invalid'];
        }
        return ['state' => 'ok', 'device' => $row];
    }

    // ---- heartbeat ------------------------------------------------------

    /** @param array<string,mixed> $hb already validated */
    public function applyHeartbeat(int $deviceId, array $hb): void
    {
        $now = Clock::nowMs();
        $map = [
            'battery_level' => 'battery_level', 'charging' => 'charging', 'network_type' => 'network_type',
            'sim_count' => 'sim_count', 'sim_operator' => 'sim_operator', 'app_version' => 'app_version',
            'android_version' => 'android_version', 'sync_status' => 'sync_status',
            'keepalive_mode' => 'keepalive_mode', 'watchdog_status' => 'watchdog_status',
            'root_granted' => 'root_granted', 'battery_whitelisted' => 'battery_whitelisted',
            'last_sync_at' => 'last_sync_at', 'last_recovery_at' => 'last_recovery_at',
            'recovery_count' => 'recovery_count', 'protection_state' => 'protection_state',
        ];
        $sets = ['last_seen = ?'];
        $params = [$now];
        foreach ($map as $key => $col) {
            if (array_key_exists($key, $hb)) {
                $sets[] = "$col = ?";
                $params[] = $hb[$key];
            }
        }
        if (isset($hb['sims_json'])) {
            $sets[] = 'sims_json = ?';
            $params[] = $hb['sims_json'];
        }
        $params[] = $deviceId;
        $this->db->exec('UPDATE devices SET ' . implode(', ', $sets) . ' WHERE id = ?', $params);

        $this->db->exec(
            'INSERT INTO device_heartbeats (device_id, ts, battery_level, charging, network_type, sync_status, keepalive_mode, watchdog_status, root_granted, battery_whitelisted, last_sync_at, last_recovery_at, recovery_count, protection_state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
            [
                $deviceId, $now, $hb['battery_level'] ?? null, $hb['charging'] ?? null, $hb['network_type'] ?? null,
                $hb['sync_status'] ?? null, $hb['keepalive_mode'] ?? null, $hb['watchdog_status'] ?? null,
                $hb['root_granted'] ?? null, $hb['battery_whitelisted'] ?? null, $hb['last_sync_at'] ?? null,
                $hb['last_recovery_at'] ?? null, $hb['recovery_count'] ?? null, $hb['protection_state'] ?? null,
            ]
        );
        if (random_int(1, 100) === 1) {
            $this->db->exec('DELETE FROM device_heartbeats WHERE ts < ?', [$now - 7 * 86400_000]);
        }
    }

    public function touch(int $deviceId): void
    {
        $this->db->exec('UPDATE devices SET last_seen = ? WHERE id = ?', [Clock::nowMs(), $deviceId]);
    }

    // ---- presentation ---------------------------------------------------

    /** @return list<array<string,mixed>> */
    public function listAll(): array
    {
        $rows = $this->db->all('SELECT * FROM devices WHERE revoked_at IS NULL ORDER BY id');
        return array_map(fn(array $r) => $this->present($r), $rows);
    }

    /** @return array<string,mixed>|null */
    public function find(int $id): ?array
    {
        $r = $this->db->one('SELECT * FROM devices WHERE id = ? AND revoked_at IS NULL', [$id]);
        return $r === null ? null : $this->present($r);
    }

    /** @param array<string,mixed> $r */
    public function present(array $r): array
    {
        $id = (int) $r['id'];
        $threshold = $this->settings->int('online_threshold_seconds') * 1000;
        $lastSeen = $r['last_seen'] === null ? null : (int) $r['last_seen'];
        $cur = $this->db->one('SELECT * FROM sync_cursors WHERE device_id = ?', [$id]) ?? [];
        $pending = (int) $this->db->value("SELECT COUNT(*) FROM send_tasks WHERE device_id = ? AND status IN ('queued','claimed','sending')", [$id]);
        $sims = json_decode((string) $r['sims_json'], true);
        return [
            'id' => $id,
            'device_name' => $r['device_name'],
            'android_version' => $r['android_version'],
            'app_version' => $r['app_version'],
            'battery_level' => $r['battery_level'] === null ? null : (int) $r['battery_level'],
            'charging' => (bool) $r['charging'],
            'network_type' => $r['network_type'],
            'sim_count' => (int) $r['sim_count'],
            'sim_operator' => $r['sim_operator'],
            'sims' => is_array($sims) ? $sims : [],
            'last_seen' => $lastSeen,
            'online' => $lastSeen !== null && (Clock::nowMs() - $lastSeen) <= $threshold,
            'sync_status' => ($cur['state'] ?? 'never') === 'never' ? $r['sync_status'] : ($cur['state'] ?? $r['sync_status']),
            'sync_total' => (int) ($cur['total'] ?? 0),
            'sync_synced' => (int) ($cur['synced'] ?? 0),
            'pending_tasks' => $pending,
            'keepalive_mode' => $r['keepalive_mode'],
            'watchdog_status' => $r['watchdog_status'],
            'root_granted' => (bool) $r['root_granted'],
            'battery_whitelisted' => (bool) $r['battery_whitelisted'],
            'last_sync_at' => $r['last_sync_at'] === null ? null : (int) $r['last_sync_at'],
            'last_recovery_at' => $r['last_recovery_at'] === null ? null : (int) $r['last_recovery_at'],
            'recovery_count' => (int) $r['recovery_count'],
            'protection_state' => $r['protection_state'],
            'created_at' => (int) $r['created_at'],
        ];
    }

    // ---- sync cursor ----------------------------------------------------

    /** @return array<string,mixed> */
    public function cursor(int $deviceId): array
    {
        $c = $this->db->one('SELECT * FROM sync_cursors WHERE device_id = ?', [$deviceId]);
        return [
            'state' => $c['state'] ?? 'never',
            'total' => (int) ($c['total'] ?? 0),
            'synced' => (int) ($c['synced'] ?? 0),
            'last_android_sms_id' => (int) ($c['last_android_sms_id'] ?? 0),
        ];
    }

    public function saveCursor(int $deviceId, string $state, int $total, int $synced, int $lastId): void
    {
        $this->db->exec(
            'INSERT INTO sync_cursors (device_id, state, total, synced, last_android_sms_id, updated_at) VALUES (?, ?, ?, ?, ?, ?)
             ON CONFLICT(device_id) DO UPDATE SET state = excluded.state, total = excluded.total, synced = excluded.synced,
               last_android_sms_id = MAX(last_android_sms_id, excluded.last_android_sms_id), updated_at = excluded.updated_at',
            [$deviceId, $state, $total, $synced, $lastId, Clock::nowMs()]
        );
        $this->db->exec('UPDATE devices SET sync_status = ? WHERE id = ?', [$state, $deviceId]);
    }
}
