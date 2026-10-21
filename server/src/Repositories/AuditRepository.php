<?php
declare(strict_types=1);

namespace SmsGate\Repositories;

use SmsGate\Database\Database;
use SmsGate\Support\Clock;

final class AuditRepository
{
    public function __construct(private Database $db)
    {
    }

    /** Never pass SMS bodies, passwords or tokens in $detail. */
    public function log(string $actorType, string $actor, string $action, ?int $deviceId = null, string $target = '', string $detail = '', string $ip = ''): void
    {
        $this->db->exec(
            'INSERT INTO audit_logs (ts, actor_type, actor, action, device_id, target, detail, ip) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
            [Clock::nowMs(), $actorType, mb_substr($actor, 0, 64), $action, $deviceId, mb_substr($target, 0, 128), mb_substr($detail, 0, 500), mb_substr($ip, 0, 64)]
        );
    }

    /** @return array{items:list<array<string,mixed>>,total:int} */
    public function page(int $page, int $pageSize, string $action, ?int $deviceId): array
    {
        $where = [];
        $p = [];
        if ($action !== '') {
            $where[] = 'action = ?';
            $p[] = $action;
        }
        if ($deviceId !== null) {
            $where[] = 'device_id = ?';
            $p[] = $deviceId;
        }
        $w = $where ? 'WHERE ' . implode(' AND ', $where) : '';
        $total = (int) $this->db->value("SELECT COUNT(*) FROM audit_logs $w", $p);
        $rows = $this->db->all(
            "SELECT id, ts, actor_type, actor, action, device_id, target, detail, ip FROM audit_logs $w ORDER BY id DESC LIMIT ? OFFSET ?",
            array_merge($p, [$pageSize, ($page - 1) * $pageSize])
        );
        foreach ($rows as &$r) {
            $r['id'] = (int) $r['id'];
            $r['ts'] = (int) $r['ts'];
            $r['device_id'] = $r['device_id'] === null ? null : (int) $r['device_id'];
        }
        return ['items' => $rows, 'total' => $total];
    }
}
