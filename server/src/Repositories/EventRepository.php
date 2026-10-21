<?php
declare(strict_types=1);

namespace SmsGate\Repositories;

use SmsGate\Database\Database;
use SmsGate\Http\Response;
use SmsGate\Support\Clock;

/** Durable event log backing the SSE stream (works across PHP worker processes). */
final class EventRepository
{
    public function __construct(private Database $db)
    {
    }

    /** @param array<string,mixed> $payload */
    public function publish(string $type, array $payload): void
    {
        $this->db->exec('INSERT INTO events (ts, type, payload) VALUES (?, ?, ?)', [Clock::nowMs(), $type, Response::encode($payload)]);
        if (random_int(1, 200) === 1) {
            $this->db->exec('DELETE FROM events WHERE ts < ?', [Clock::nowMs() - 3600_000]);
        }
    }

    public function lastId(): int
    {
        return (int) $this->db->value('SELECT COALESCE(MAX(id), 0) FROM events');
    }

    /** @return list<array{id:int,type:string,payload:string}> */
    public function since(int $id, int $limit = 200): array
    {
        $rows = $this->db->all('SELECT id, type, payload FROM events WHERE id > ? ORDER BY id LIMIT ?', [$id, $limit]);
        foreach ($rows as &$r) {
            $r['id'] = (int) $r['id'];
        }
        return $rows;
    }
}
