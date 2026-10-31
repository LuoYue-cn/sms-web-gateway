<?php
declare(strict_types=1);

namespace SmsGate\Repositories;

use SmsGate\Database\Database;
use SmsGate\Support\Clock;
use SmsGate\Support\PhoneNumber;
use SmsGate\Support\SmsClassifier;
use SmsGate\Support\VerificationCode;

/** Conversations and messages: storage, de-duplication, merging and presentation. */
final class MessageRepository
{
    public const RECEIVER_MERGE_WINDOW_MS = 120_000;
    public const TASK_MERGE_WINDOW_MS = 600_000;

    public function __construct(private Database $db)
    {
    }

    // ---- conversations --------------------------------------------------

    public function ensureConversation(int $deviceId, string $address, string $contactName = ''): int
    {
        $key = PhoneNumber::key($address);
        $id = $this->db->value('SELECT id FROM conversations WHERE device_id = ? AND phone_key = ?', [$deviceId, $key]);
        if ($id !== null) {
            if ($contactName !== '') {
                $this->db->exec("UPDATE conversations SET contact_name = ? WHERE id = ? AND contact_name <> ?", [$contactName, $id, $contactName]);
            }
            return (int) $id;
        }
        $this->db->exec(
            'INSERT INTO conversations (device_id, phone_number, phone_key, contact_name, created_at) VALUES (?, ?, ?, ?, ?)',
            [$deviceId, PhoneNumber::normalizeAddress($address), $key, $contactName, Clock::nowMs()]
        );
        return $this->db->lastId();
    }

    /** Recompute the denormalised summary columns from the messages table. */
    public function refreshConversation(int $id): void
    {
        $last = $this->db->one('SELECT content, timestamp, direction, code FROM messages WHERE conversation_id = ? ORDER BY timestamp DESC, id DESC LIMIT 1', [$id]);
        $unread = (int) $this->db->value("SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND direction = 'incoming' AND is_read = 0", [$id]);
        $failed = (int) $this->db->value("SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND direction = 'outgoing' AND status IN ('failed','unknown')", [$id]);
        $this->db->exec(
            'UPDATE conversations SET last_message = ?, last_message_at = ?, last_direction = ?, unread_count = ?, has_failed = ?, last_code = ? WHERE id = ?',
            [
                $last === null ? '' : mb_substr((string) $last['content'], 0, 200),
                $last === null ? 0 : (int) $last['timestamp'],
                $last === null ? 'incoming' : $last['direction'],
                $unread, $failed > 0 ? 1 : 0, $last['code'] ?? null, $id,
            ]
        );
    }

    /** @return array<string,mixed>|null */
    public function conversation(int $id): ?array
    {
        $r = $this->db->one(
            'SELECT c.*, d.device_name FROM conversations c JOIN devices d ON d.id = c.device_id WHERE c.id = ?',
            [$id]
        );
        return $r === null ? null : self::presentConversation($r);
    }

    /** @return array{items:list<array<string,mixed>>,total:int} */
    public function conversations(string $q, ?int $deviceId, int $page, int $pageSize): array
    {
        $where = ['1=1'];
        $p = [];
        if ($deviceId !== null) {
            $where[] = 'c.device_id = ?';
            $p[] = $deviceId;
        }
        if ($q !== '') {
            $like = '%' . self::escapeLike($q) . '%';
            $where[] = "(c.phone_number LIKE ? ESCAPE '\\' OR c.contact_name LIKE ? ESCAPE '\\' OR c.last_message LIKE ? ESCAPE '\\'
                OR EXISTS (SELECT 1 FROM messages m WHERE m.conversation_id = c.id AND m.content LIKE ? ESCAPE '\\'))";
            array_push($p, $like, $like, $like, $like);
        }
        $w = implode(' AND ', $where);
        $total = (int) $this->db->value("SELECT COUNT(*) FROM conversations c WHERE $w", $p);
        $rows = $this->db->all(
            "SELECT c.*, d.device_name FROM conversations c JOIN devices d ON d.id = c.device_id WHERE $w
             ORDER BY c.pinned DESC, c.last_message_at DESC, c.id DESC LIMIT ? OFFSET ?",
            array_merge($p, [$pageSize, ($page - 1) * $pageSize])
        );
        return ['items' => array_map([self::class, 'presentConversation'], $rows), 'total' => $total];
    }

    public function markRead(int $conversationId): int
    {
        $n = $this->db->exec("UPDATE messages SET is_read = 1 WHERE conversation_id = ? AND direction = 'incoming' AND is_read = 0", [$conversationId]);
        $this->refreshConversation($conversationId);
        return $n;
    }

    public function pin(int $id, bool $pinned): void
    {
        $this->db->exec('UPDATE conversations SET pinned = ? WHERE id = ?', [$pinned ? 1 : 0, $id]);
    }

    /** Server-side delete only; never touches the phone. */
    public function deleteConversation(int $id): void
    {
        $this->db->exec('DELETE FROM conversations WHERE id = ?', [$id]);
    }

    // ---- messages -------------------------------------------------------

    /** @return array{items:list<array<string,mixed>>,has_more:bool} ascending by time */
    public function conversationMessages(int $conversationId, ?int $beforeId, int $limit): array
    {
        $p = [$conversationId];
        $cond = '';
        if ($beforeId !== null) {
            $cond = ' AND (m.timestamp, m.id) < (SELECT b.timestamp, b.id FROM messages b WHERE b.id = ?)';
            $p[] = $beforeId;
        }
        $p[] = $limit + 1;
        $rows = $this->db->all(
            "SELECT m.*, c.contact_name AS conv_contact FROM messages m JOIN conversations c ON c.id = m.conversation_id
             WHERE m.conversation_id = ?$cond ORDER BY m.timestamp DESC, m.id DESC LIMIT ?",
            $p
        );
        $more = count($rows) > $limit;
        $rows = array_slice($rows, 0, $limit);
        $rows = array_reverse($rows);
        return ['items' => array_map([self::class, 'presentMessage'], $rows), 'has_more' => $more];
    }

    /** @return array<string,mixed>|null */
    public function message(int $id): ?array
    {
        $r = $this->db->one('SELECT m.*, c.contact_name AS conv_contact FROM messages m JOIN conversations c ON c.id = m.conversation_id WHERE m.id = ?', [$id]);
        return $r === null ? null : self::presentMessage($r);
    }

    /** @return array{items:list<array<string,mixed>>,total:int} */
    public function listMessages(?int $deviceId, string $direction, string $status, int $page, int $pageSize): array
    {
        $where = ['1=1'];
        $p = [];
        if ($deviceId !== null) {
            $where[] = 'm.device_id = ?';
            $p[] = $deviceId;
        }
        if (in_array($direction, ['incoming', 'outgoing'], true)) {
            $where[] = 'm.direction = ?';
            $p[] = $direction;
        }
        if ($status !== '') {
            $where[] = 'm.status = ?';
            $p[] = $status;
        }
        $w = implode(' AND ', $where);
        $total = (int) $this->db->value("SELECT COUNT(*) FROM messages m WHERE $w", $p);
        $rows = $this->db->all(
            "SELECT m.*, c.contact_name AS conv_contact FROM messages m JOIN conversations c ON c.id = m.conversation_id WHERE $w ORDER BY m.timestamp DESC, m.id DESC LIMIT ? OFFSET ?",
            array_merge($p, [$pageSize, ($page - 1) * $pageSize])
        );
        return ['items' => array_map([self::class, 'presentMessage'], $rows), 'total' => $total];
    }

    /** @return array{items:list<array<string,mixed>>,total:int} */
    public function search(string $q, ?int $deviceId, int $page, int $pageSize): array
    {
        $like = '%' . self::escapeLike($q) . '%';
        $where = ["(m.content LIKE ? ESCAPE '\\' OR m.phone_number LIKE ? ESCAPE '\\' OR c.contact_name LIKE ? ESCAPE '\\')"];
        $p = [$like, $like, $like];
        if ($deviceId !== null) {
            $where[] = 'm.device_id = ?';
            $p[] = $deviceId;
        }
        $w = implode(' AND ', $where);
        $total = (int) $this->db->value("SELECT COUNT(*) FROM messages m JOIN conversations c ON c.id = m.conversation_id WHERE $w", $p);
        $rows = $this->db->all(
            "SELECT m.*, c.contact_name AS conv_contact FROM messages m JOIN conversations c ON c.id = m.conversation_id WHERE $w
             ORDER BY m.timestamp DESC, m.id DESC LIMIT ? OFFSET ?",
            array_merge($p, [$pageSize, ($page - 1) * $pageSize])
        );
        $items = array_map([self::class, 'presentMessage'], $rows);
        foreach ($items as &$it) {
            $it['conversation'] = $this->conversation($it['conversation_id']);
        }
        return ['items' => $items, 'total' => $total];
    }

    /** @return \Generator<int,array<string,mixed>> */
    public function exportAll(?int $deviceId): \Generator
    {
        $lastId = 0;
        while (true) {
            $p = [$lastId];
            $dev = '';
            if ($deviceId !== null) {
                $dev = ' AND m.device_id = ?';
                $p[] = $deviceId;
            }
            $p[] = 500;
            $rows = $this->db->all(
                "SELECT m.*, c.contact_name AS conv_contact FROM messages m JOIN conversations c ON c.id = m.conversation_id WHERE m.id > ?$dev ORDER BY m.id LIMIT ?",
                $p
            );
            if ($rows === []) {
                return;
            }
            foreach ($rows as $r) {
                $lastId = (int) $r['id'];
                yield self::presentMessage($r);
            }
        }
    }

    // ---- ingestion (device sync) ---------------------------------------

    /**
     * Ingest one message from the phone. Returns 'inserted' | 'updated' | 'merged' | 'ignored'
     * plus the message id and conversation id.
     *
     * @param array<string,mixed> $m validated message (see SyncService)
     * @return array{result:string,id:int,conversation_id:int}
     */
    public function ingest(int $deviceId, array $m): array
    {
        $sourceType = $m['source_type'];
        $address = PhoneNumber::normalizeAddress($m['phone_number']);
        $conv = $this->ensureConversation($deviceId, $address, (string) $m['contact_name']);
        $read = $m['direction'] === 'outgoing' ? 1 : ($m['read'] ? 1 : 0);

        if ($sourceType === 'android_provider') {
            $existing = $this->db->one(
                'SELECT id, conversation_id FROM messages WHERE device_id = ? AND source_type = ? AND android_sms_id = ?',
                [$deviceId, 'android_provider', $m['android_sms_id']]
            );
            if ($existing !== null) {
                // UPSERT: refresh mutable fields (read state, sim, contact) without touching send status.
                $this->db->exec(
                    "UPDATE messages SET is_read = MAX(is_read, ?), sim_slot = COALESCE(?, sim_slot), contact_name = CASE WHEN ? <> '' THEN ? ELSE contact_name END WHERE id = ?",
                    [$read, $m['sim_slot'], $m['contact_name'], $m['contact_name'], $existing['id']]
                );
                return ['result' => 'updated', 'id' => (int) $existing['id'], 'conversation_id' => (int) $existing['conversation_id']];
            }
            $merged = $this->findMergeCandidate($deviceId, $conv, $m, true);
            if ($merged !== null) {
                $this->db->exec(
                    "UPDATE messages SET source_type = 'android_provider', android_sms_id = ?, sim_slot = COALESCE(?, sim_slot), is_read = MAX(is_read, ?) WHERE id = ?",
                    [$m['android_sms_id'], $m['sim_slot'], $read, $merged['id']]
                );
                return ['result' => 'merged', 'id' => (int) $merged['id'], 'conversation_id' => (int) $merged['conversation_id']];
            }
            return $this->insertMessage($deviceId, $conv, $m, $address, $read, (int) $m['android_sms_id'], null);
        }

        // android_receiver
        $existing = $this->db->one('SELECT id, conversation_id FROM messages WHERE device_id = ? AND source_key = ?', [$deviceId, $m['source_key']]);
        if ($existing !== null) {
            return ['result' => 'ignored', 'id' => (int) $existing['id'], 'conversation_id' => (int) $existing['conversation_id']];
        }
        $merged = $this->findMergeCandidate($deviceId, $conv, $m, false);
        if ($merged !== null) {
            $this->db->exec('UPDATE messages SET source_key = ? WHERE id = ?', [$m['source_key'], $merged['id']]);
            return ['result' => 'merged', 'id' => (int) $merged['id'], 'conversation_id' => (int) $merged['conversation_id']];
        }
        return $this->insertMessage($deviceId, $conv, $m, $address, $read, null, (string) $m['source_key']);
    }

    /**
     * @param array<string,mixed> $m
     * @return array<string,mixed>|null
     */
    private function findMergeCandidate(int $deviceId, int $conv, array $m, bool $incomingIsProvider): ?array
    {
        if ($incomingIsProvider) {
            // A provider row may absorb (a) a receiver row not yet linked to a provider id, or (b) a web-task outgoing row.
            if ($m['direction'] === 'outgoing') {
                $w = self::TASK_MERGE_WINDOW_MS;
                $sql = "SELECT id, conversation_id FROM messages WHERE device_id = ? AND conversation_id = ? AND direction = 'outgoing'
                        AND android_sms_id IS NULL AND source_type IN ('web_task','android_receiver') AND content = ?
                        AND timestamp BETWEEN ? AND ? ORDER BY ABS(timestamp - ?) LIMIT 1";
                return $this->db->one($sql, [$deviceId, $conv, $m['content'], $m['timestamp'] - $w, $m['timestamp'] + $w, $m['timestamp']]);
            }
            $w = self::RECEIVER_MERGE_WINDOW_MS;
            $sql = "SELECT id, conversation_id FROM messages WHERE device_id = ? AND conversation_id = ? AND direction = ?
                    AND android_sms_id IS NULL AND source_type = 'android_receiver' AND content = ?
                    AND timestamp BETWEEN ? AND ? ORDER BY ABS(timestamp - ?) LIMIT 1";
            return $this->db->one($sql, [$deviceId, $conv, $m['direction'], $m['content'], $m['timestamp'] - $w, $m['timestamp'] + $w, $m['timestamp']]);
        }
        // Receiver row arriving after the provider sync already stored it: one-to-one with rows lacking a source_key.
        $w = self::RECEIVER_MERGE_WINDOW_MS;
        $sql = "SELECT id, conversation_id FROM messages WHERE device_id = ? AND conversation_id = ? AND direction = ?
                AND source_type = 'android_provider' AND source_key IS NULL AND content = ?
                AND timestamp BETWEEN ? AND ? ORDER BY ABS(timestamp - ?) LIMIT 1";
        return $this->db->one($sql, [$deviceId, $conv, $m['direction'], $m['content'], $m['timestamp'] - $w, $m['timestamp'] + $w, $m['timestamp']]);
    }

    /**
     * @param array<string,mixed> $m
     * @return array{result:string,id:int,conversation_id:int}
     */
    private function insertMessage(int $deviceId, int $conv, array $m, string $address, int $read, ?int $androidId, ?string $sourceKey): array
    {
        $incoming = $m['direction'] === 'incoming';
        $status = $incoming ? 'received' : 'sent';
        $code = $incoming ? VerificationCode::extract((string) $m['content']) : null;
        $category = $incoming ? SmsClassifier::category((string) $m['content'], $code) : null;
        $this->db->exec(
            'INSERT INTO messages (device_id, conversation_id, source_type, android_sms_id, source_key, phone_number, contact_name, content, direction, timestamp, status, sim_slot, is_read, created_at, code, category, code_ver)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
            [$deviceId, $conv, $m['source_type'], $androidId, $sourceKey, $address, $m['contact_name'], $m['content'], $m['direction'], $m['timestamp'], $status, $m['sim_slot'], $read, Clock::nowMs(), $code, $category, VerificationCode::VERSION]
        );
        return ['result' => 'inserted', 'id' => $this->db->lastId(), 'conversation_id' => $conv];
    }

    /** Queue-side outgoing message for a web-created send task. */
    public function insertOutgoingForTask(int $deviceId, int $conv, string $recipient, string $content, string $taskId, ?int $simSlot): int
    {
        $this->db->exec(
            "INSERT INTO messages (device_id, conversation_id, source_type, source_key, phone_number, content, direction, timestamp, status, sim_slot, task_id, is_read, created_at)
             VALUES (?, ?, 'web_task', ?, ?, ?, 'outgoing', ?, 'queued', ?, ?, 1, ?)",
            [$deviceId, $conv, $taskId, $recipient, $content, Clock::nowMs(), $simSlot, $taskId, Clock::nowMs()]
        );
        return $this->db->lastId();
    }

    /** Mirror a task status onto its message row. Returns affected message (id, conversation_id) or null. */
    public function setTaskMessageStatus(string $taskId, string $status, ?string $error): ?array
    {
        $m = $this->db->one('SELECT id, conversation_id FROM messages WHERE task_id = ?', [$taskId]);
        if ($m === null) {
            return null;
        }
        $this->db->exec('UPDATE messages SET status = ?, error_message = ? WHERE id = ?', [$status, $error, $m['id']]);
        $this->refreshConversation((int) $m['conversation_id']);
        return ['id' => (int) $m['id'], 'conversation_id' => (int) $m['conversation_id']];
    }

    // ---- presentation ---------------------------------------------------

    /** @param array<string,mixed> $r */
    public static function presentConversation(array $r): array
    {
        return [
            'id' => (int) $r['id'],
            'device_id' => (int) $r['device_id'],
            'device_name' => $r['device_name'] ?? '',
            'phone_number' => $r['phone_number'],
            'contact_name' => $r['contact_name'],
            'last_message' => $r['last_message'],
            'last_message_at' => (int) $r['last_message_at'],
            'last_direction' => $r['last_direction'],
            'unread_count' => (int) $r['unread_count'],
            'has_failed' => (bool) $r['has_failed'],
            'pinned' => (bool) $r['pinned'],
            'last_code' => $r['last_code'] ?? null,
        ];
    }

    /** @param array<string,mixed> $r */
    public static function presentMessage(array $r): array
    {
        return [
            'id' => (int) $r['id'],
            'device_id' => (int) $r['device_id'],
            'conversation_id' => (int) $r['conversation_id'],
            'phone_number' => $r['phone_number'],
            'contact_name' => $r['conv_contact'] ?? $r['contact_name'],
            'content' => $r['content'],
            'direction' => $r['direction'],
            'timestamp' => (int) $r['timestamp'],
            'status' => $r['status'],
            'sim_slot' => $r['sim_slot'] === null ? null : (int) $r['sim_slot'],
            'task_id' => $r['task_id'],
            'error_message' => $r['error_message'],
            'read' => (bool) $r['is_read'],
            'code' => $r['code'] ?? null,
            'category' => $r['category'] ?? null,
        ];
    }

    private static function escapeLike(string $s): string
    {
        return str_replace(['\\', '%', '_'], ['\\\\', '\\%', '\\_'], $s);
    }
}
