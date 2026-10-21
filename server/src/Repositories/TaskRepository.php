<?php
declare(strict_types=1);

namespace SmsGate\Repositories;

use SmsGate\Database\Database;
use SmsGate\Support\ApiException;
use SmsGate\Support\Clock;

/**
 * Send-task state machine.
 *
 * Invariant: a task that may have reached SmsManager (status sending / sent / delivered / unknown)
 * is NEVER moved back to queued. Only `claimed` (device had not yet reported `sending`) can be requeued.
 */
final class TaskRepository
{
    public const CLAIM_TIMEOUT_MS = 300_000;
    public const SENDING_TIMEOUT_MS = 600_000;
    public const MAX_REQUEUES = 5;

    /** @var array<string,list<string>> */
    private const TRANSITIONS = [
        'claimed' => ['sending', 'failed', 'unknown'],
        'sending' => ['sent', 'failed', 'unknown'],
        'sent' => ['delivered', 'failed'],
    ];

    public function __construct(private Database $db)
    {
    }

    /** @return array<string,mixed>|null */
    public function find(string $taskId): ?array
    {
        $r = $this->db->one('SELECT * FROM send_tasks WHERE task_id = ?', [$taskId]);
        return $r === null ? null : self::present($r);
    }

    public function create(string $taskId, int $deviceId, string $recipient, string $content, ?int $simSlot, ?int $userId): void
    {
        $now = Clock::nowMs();
        $this->db->exec(
            "INSERT INTO send_tasks (task_id, device_id, recipient, content, sim_slot, status, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'queued', ?, ?, ?)",
            [$taskId, $deviceId, $recipient, $content, $simSlot, $userId, $now, $now]
        );
    }

    /** @return list<array<string,mixed>> */
    public function queuedFor(int $deviceId, int $limit = 10): array
    {
        $rows = $this->db->all("SELECT * FROM send_tasks WHERE device_id = ? AND status = 'queued' ORDER BY created_at LIMIT ?", [$deviceId, $limit]);
        return array_map(fn(array $r) => [
            'task_id' => $r['task_id'],
            'recipient' => $r['recipient'],
            'content' => $r['content'],
            'sim_slot' => $r['sim_slot'] === null ? null : (int) $r['sim_slot'],
            'created_at' => (int) $r['created_at'],
        ], $rows);
    }

    public function countRecent(int $deviceId, int $sinceMs): int
    {
        return (int) $this->db->value('SELECT COUNT(*) FROM send_tasks WHERE device_id = ? AND created_at >= ?', [$deviceId, $sinceMs]);
    }

    /**
     * Atomic queued -> claimed. The conditional UPDATE is the single arbiter: of any number of
     * concurrent claimers exactly one sees rowCount = 1.
     *
     * @return array<string,mixed>
     */
    public function claim(int $deviceId, string $taskId): array
    {
        $now = Clock::nowMs();
        $n = $this->db->exec(
            "UPDATE send_tasks SET status = 'claimed', claimed_at = ?, updated_at = ? WHERE task_id = ? AND device_id = ? AND status = 'queued'",
            [$now, $now, $taskId, $deviceId]
        );
        $task = $this->owned($deviceId, $taskId);
        if ($n !== 1) {
            throw new ApiException(409, 'not_claimable', '任务当前不可领取', ['status' => $task['status']]);
        }
        return $task;
    }

    /**
     * Report a result from the device. Returns [task, changed].
     *
     * @return array{0:array<string,mixed>,1:bool}
     */
    public function report(int $deviceId, string $taskId, string $status, ?string $errorCode, ?string $errorMessage): array
    {
        $task = $this->owned($deviceId, $taskId);
        $cur = $task['status'];
        if ($cur === $status) {
            return [$task, false];
        }
        if (!in_array($status, self::TRANSITIONS[$cur] ?? [], true)) {
            throw new ApiException(409, 'invalid_transition', "不允许从 $cur 转为 $status", ['status' => $cur]);
        }
        $now = Clock::nowMs();
        $terminal = in_array($status, ['sent', 'delivered', 'failed', 'unknown'], true);
        $n = $this->db->exec(
            'UPDATE send_tasks SET status = ?, error_code = ?, error_message = ?, updated_at = ?, completed_at = CASE WHEN ? THEN ? ELSE completed_at END WHERE task_id = ? AND status = ?',
            [$status, $errorCode, $errorMessage === null ? null : mb_substr($errorMessage, 0, 300), $now, $terminal ? 1 : 0, $now, $taskId, $cur]
        );
        if ($n !== 1) {
            // Lost a race with another writer; re-read and treat equal state as idempotent.
            $fresh = $this->owned($deviceId, $taskId);
            if ($fresh['status'] === $status) {
                return [$fresh, false];
            }
            throw new ApiException(409, 'invalid_transition', '任务状态已变化', ['status' => $fresh['status']]);
        }
        return [$this->owned($deviceId, $taskId), true];
    }

    /**
     * Housekeeping, called opportunistically.
     * - claimed and idle > 5 min -> queued (device never reported `sending`, so SmsManager was never called)
     * - sending and idle > 10 min -> unknown (needs a human)
     *
     * @return list<array{task_id:string,status:string}> tasks whose status changed
     */
    public function sweep(): array
    {
        $now = Clock::nowMs();
        $changed = [];
        $stale = $this->db->all("SELECT task_id, requeue_count FROM send_tasks WHERE status = 'claimed' AND updated_at < ?", [$now - self::CLAIM_TIMEOUT_MS]);
        foreach ($stale as $t) {
            $to = ((int) $t['requeue_count']) >= self::MAX_REQUEUES ? 'failed' : 'queued';
            $n = $this->db->exec(
                "UPDATE send_tasks SET status = ?, claimed_at = NULL, updated_at = ?, requeue_count = requeue_count + 1,
                   error_code = CASE WHEN ? = 'failed' THEN 'claim_expired' ELSE error_code END,
                   error_message = CASE WHEN ? = 'failed' THEN '设备多次领取后未执行' ELSE error_message END,
                   completed_at = CASE WHEN ? = 'failed' THEN ? ELSE NULL END
                 WHERE task_id = ? AND status = 'claimed' AND updated_at < ?",
                [$to, $now, $to, $to, $to, $now, $t['task_id'], $now - self::CLAIM_TIMEOUT_MS]
            );
            if ($n === 1) {
                $changed[] = ['task_id' => $t['task_id'], 'status' => $to];
            }
        }
        $stuck = $this->db->all("SELECT task_id FROM send_tasks WHERE status = 'sending' AND updated_at < ?", [$now - self::SENDING_TIMEOUT_MS]);
        foreach ($stuck as $t) {
            $n = $this->db->exec(
                "UPDATE send_tasks SET status = 'unknown', error_code = 'result_timeout', error_message = '发送结果未上报，无法确认是否已发出', updated_at = ?, completed_at = ?
                 WHERE task_id = ? AND status = 'sending' AND updated_at < ?",
                [$now, $now, $t['task_id'], $now - self::SENDING_TIMEOUT_MS]
            );
            if ($n === 1) {
                $changed[] = ['task_id' => $t['task_id'], 'status' => 'unknown'];
            }
        }
        return $changed;
    }

    /** Admin resolution of an `unknown` task. */
    public function resolve(string $taskId, string $action): array
    {
        $task = $this->find($taskId);
        if ($task === null) {
            throw ApiException::notFound('任务');
        }
        if ($task['status'] !== 'unknown') {
            throw new ApiException(409, 'not_resolvable', '只有结果未知的任务可以处理', ['status' => $task['status']]);
        }
        $to = match ($action) {
            'mark_sent' => 'sent',
            'mark_failed', 'resend' => 'failed',
            default => throw ApiException::invalid(['action' => '必须是 resend / mark_sent / mark_failed']),
        };
        $msg = match ($action) {
            'mark_sent' => '管理员确认已发送',
            'resend' => '管理员确认后重新发送（已创建新任务）',
            default => '管理员确认发送失败',
        };
        $now = Clock::nowMs();
        $n = $this->db->exec(
            "UPDATE send_tasks SET status = ?, resolved_action = ?, error_message = ?, updated_at = ?, completed_at = ? WHERE task_id = ? AND status = 'unknown'",
            [$to, $action, $msg, $now, $now, $taskId]
        );
        if ($n !== 1) {
            throw new ApiException(409, 'not_resolvable', '任务状态已变化');
        }
        return $this->find($taskId) ?? [];
    }

    /** @return array<string,mixed> */
    private function owned(int $deviceId, string $taskId): array
    {
        $r = $this->db->one('SELECT * FROM send_tasks WHERE task_id = ? AND device_id = ?', [$taskId, $deviceId]);
        if ($r === null) {
            throw ApiException::notFound('任务');
        }
        return self::present($r);
    }

    /** @param array<string,mixed> $r */
    public static function present(array $r): array
    {
        return [
            'task_id' => $r['task_id'],
            'device_id' => (int) $r['device_id'],
            'recipient' => $r['recipient'],
            'content' => $r['content'],
            'sim_slot' => $r['sim_slot'] === null ? null : (int) $r['sim_slot'],
            'status' => $r['status'],
            'error_code' => $r['error_code'],
            'error_message' => $r['error_message'],
            'created_at' => (int) $r['created_at'],
            'claimed_at' => $r['claimed_at'] === null ? null : (int) $r['claimed_at'],
            'completed_at' => $r['completed_at'] === null ? null : (int) $r['completed_at'],
            'resolved_action' => $r['resolved_action'],
        ];
    }
}
