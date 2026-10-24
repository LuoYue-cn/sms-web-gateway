<?php
declare(strict_types=1);

namespace SmsGate\Services;

use SmsGate\Database\Database;
use SmsGate\Repositories\AuditRepository;
use SmsGate\Repositories\DeviceRepository;
use SmsGate\Repositories\MessageRepository;
use SmsGate\Repositories\SettingsRepository;
use SmsGate\Repositories\TaskRepository;
use SmsGate\Support\ApiException;
use SmsGate\Support\Clock;
use SmsGate\Support\Id;
use SmsGate\Support\PhoneNumber;
use SmsGate\Support\Validator;

/** Everything a paired Android device may do. */
final class DeviceService
{
    public const MAX_BATCH = 500;
    public const POLL_MAX_WAIT = 25;

    public function __construct(
        private Database $db,
        private DeviceRepository $devices,
        private MessageRepository $messages,
        private TaskRepository $tasks,
        private SettingsRepository $settings,
        private AuditRepository $audit,
        private EventBus $bus,
    ) {
    }

    // ---- pairing --------------------------------------------------------

    /**
     * @param array<string,mixed> $body
     * @return array<string,mixed>
     */
    public function pair(array $body, string $ip): array
    {
        $v = new Validator($body);
        $token = $v->string('pairing_token', 8, 200);
        $name = $v->string('device_name', 1, 100);
        $android = $v->string('android_version', 0, 32, false);
        $app = $v->string('app_version', 0, 32, false);
        $sims = $v->int('sim_count', 0, 8, false, 0);
        $v->check();

        $out = $this->db->transaction(function () use ($token, $name, $android, $app, $sims): array {
            $pairingId = $this->devices->consumePairingToken($token);
            if ($pairingId === null) {
                throw new ApiException(400, 'invalid_pairing_token', '绑定令牌无效、已过期或已被使用');
            }
            $deviceId = $this->devices->create($name, $android, $app, (int) $sims);
            $this->devices->attachPairing($pairingId, $deviceId);
            return ['device_id' => $deviceId, 'device_token' => $this->devices->issueToken($deviceId)];
        });
        $this->audit->log('device', $name, 'device_pair', $out['device_id'], 'device:' . $out['device_id'], '', $ip);
        $this->bus->deviceUpdate($out['device_id']);
        return $out + ['server_time' => Clock::nowMs(), 'config' => $this->settings->deviceConfig()];
    }

    /** @return array<string,string> */
    public function rotate(int $deviceId, string $ip): array
    {
        $token = $this->devices->rotateToken($deviceId);
        $this->audit->log('device', 'device:' . $deviceId, 'device_token_rotate', $deviceId, '', '', $ip);
        return ['device_token' => $token];
    }

    // ---- heartbeat ------------------------------------------------------

    /**
     * @param array<string,mixed> $body
     * @return array<string,mixed>
     */
    public function heartbeat(int $deviceId, array $body, string $ip): array
    {
        $v = new Validator($body);
        $hb = [];
        $battery = $v->int('battery_level', 0, 100, false);
        if ($battery !== null) {
            $hb['battery_level'] = $battery;
        }
        foreach (['charging', 'root_granted', 'battery_whitelisted'] as $k) {
            if ($v->has($k)) {
                $hb[$k] = $v->bool($k) ? 1 : 0;
            }
        }
        if ($v->has('network_type')) {
            $hb['network_type'] = $v->enum('network_type', ['wifi', 'mobile', 'ethernet', 'none', 'other', 'vpn', 'unknown']);
        }
        if ($v->has('sim_count')) {
            $hb['sim_count'] = $v->int('sim_count', 0, 8);
        }
        foreach (['sim_operator' => 100, 'app_version' => 32, 'android_version' => 32] as $k => $max) {
            if ($v->has($k)) {
                $hb[$k] = $v->string($k, 0, $max, false);
            }
        }
        if ($v->has('sync_status')) {
            $hb['sync_status'] = $v->enum('sync_status', ['idle', 'running', 'done', 'error', 'never']);
        }
        if ($v->has('keepalive_mode')) {
            $hb['keepalive_mode'] = $v->enum('keepalive_mode', ['normal', 'root_enhanced']);
        }
        if ($v->has('watchdog_status')) {
            $hb['watchdog_status'] = $v->enum('watchdog_status', ['running', 'stopped', 'not_installed']);
        }
        if ($v->has('protection_state')) {
            $hb['protection_state'] = $v->enum('protection_state', ['none', 'cooldown', 'protected']);
        }
        foreach (['last_sync_at', 'last_recovery_at'] as $k) {
            if ($v->has($k)) {
                $hb[$k] = $v->int($k, 0);
            }
        }
        if ($v->has('recovery_count')) {
            $hb['recovery_count'] = $v->int('recovery_count', 0);
        }
        if (isset($body['sims']) && is_array($body['sims'])) {
            $sims = [];
            foreach (array_slice($body['sims'], 0, 8) as $s) {
                if (is_array($s)) {
                    $entry = [
                        'slot' => (int) ($s['slot'] ?? 0),
                        'operator' => mb_substr((string) ($s['operator'] ?? ''), 0, 100),
                        'state' => in_array($s['state'] ?? '', ['ready', 'absent', 'locked', 'unknown'], true) ? $s['state'] : 'unknown',
                    ];
                    // 手机读不到号码时不会带这个字段；只接受规范的号码，其他一律丢弃而不是报错
                    $num = isset($s['number']) && is_string($s['number']) ? PhoneNumber::normalizeForSend($s['number']) : null;
                    if ($num !== null) {
                        $entry['number'] = $num;
                    }
                    $sims[] = $entry;
                }
            }
            $hb['sims_json'] = json_encode($sims, JSON_UNESCAPED_UNICODE);
        }
        $audit = $this->validateRootAudit($body['root_audit'] ?? null, $v);
        $v->check();

        $this->db->transaction(function () use ($deviceId, $hb, $audit, $ip): void {
            $this->devices->applyHeartbeat($deviceId, $hb);
            foreach ($audit as $a) {
                $this->audit->log('device', 'device:' . $deviceId, 'root_op', $deviceId, $a['action'], 'result=' . $a['result'] . ' exit=' . $a['exit_code'] . ' ts=' . $a['ts'], $ip);
            }
        });
        $swept = $this->tasks->sweep();
        $this->publishSwept($swept);
        $this->bus->deviceUpdate($deviceId);
        return [
            'server_time' => Clock::nowMs(),
            'send_paused' => $this->settings->bool('send_paused'),
            'config' => $this->settings->deviceConfig(),
        ];
    }

    /**
     * Keeps only whitelisted summary fields so SMS bodies / tokens cannot leak into audit logs.
     * Duplicates (same ts+action) already stored are skipped.
     *
     * @return list<array{ts:int,action:string,result:string,exit_code:int}>
     */
    private function validateRootAudit(mixed $raw, Validator $v): array
    {
        if ($raw === null) {
            return [];
        }
        if (!is_array($raw) || !array_is_list($raw)) {
            $v->fail('root_audit', '必须是数组');
            return [];
        }
        $out = [];
        foreach (array_slice($raw, 0, 20) as $a) {
            if (!is_array($a)) {
                continue;
            }
            $action = (string) ($a['action'] ?? '');
            $result = (string) ($a['result'] ?? '');
            if (preg_match('/^[a-z0-9_.:-]{1,48}$/i', $action) !== 1 || preg_match('/^[a-z0-9_.:-]{1,24}$/i', $result) !== 1) {
                continue;
            }
            $ts = (int) ($a['ts'] ?? 0);
            $exists = $this->db->value("SELECT 1 FROM audit_logs WHERE action = 'root_op' AND target = ? AND detail LIKE ? LIMIT 1", [$action, '% ts=' . $ts]);
            if ($exists !== null) {
                continue;
            }
            $out[] = ['ts' => $ts, 'action' => $action, 'result' => $result, 'exit_code' => (int) ($a['exit_code'] ?? 0)];
        }
        return $out;
    }

    // ---- message sync ---------------------------------------------------

    /**
     * @param array<string,mixed> $body
     * @return array<string,mixed>
     */
    public function syncMessages(int $deviceId, array $body, string $ip): array
    {
        $v = new Validator($body);
        $batchId = $v->string('batch_id', 8, 64);
        $v->check();
        if (!isset($body['messages']) || !is_array($body['messages']) || !array_is_list($body['messages'])) {
            throw ApiException::invalid(['messages' => '必须是数组']);
        }
        if (count($body['messages']) > self::MAX_BATCH) {
            throw ApiException::invalid(['messages' => '单批最多 ' . self::MAX_BATCH . ' 条']);
        }
        $prev = $this->db->value('SELECT result_json FROM sync_batches WHERE device_id = ? AND batch_id = ?', [$deviceId, $batchId]);
        if ($prev !== null) {
            return json_decode((string) $prev, true) + ['replayed' => true];
        }
        $clean = [];
        $errors = [];
        foreach ($body['messages'] as $i => $raw) {
            try {
                $clean[] = $this->validateMessage(is_array($raw) ? $raw : []);
            } catch (ApiException $e) {
                $errors["messages.$i"] = implode('; ', $e->details['fields'] ?? [$e->getMessage()]);
            }
        }
        if ($errors !== []) {
            throw ApiException::invalid(array_slice($errors, 0, 10, true));
        }

        $newIncoming = [];
        $touchedConv = [];
        $result = $this->db->transaction(function () use ($deviceId, $batchId, $clean, &$newIncoming, &$touchedConv): array {
            $counts = ['inserted' => 0, 'updated' => 0, 'merged' => 0, 'ignored' => 0];
            $last = 0;
            foreach ($clean as $m) {
                $r = $this->messages->ingest($deviceId, $m);
                $counts[$r['result']]++;
                $touchedConv[$r['conversation_id']] = true;
                if ($r['result'] === 'inserted' && $m['direction'] === 'incoming') {
                    $newIncoming[] = $r['id'];
                }
                if ($m['android_sms_id'] !== null) {
                    $last = max($last, (int) $m['android_sms_id']);
                }
            }
            foreach (array_keys($touchedConv) as $cid) {
                $this->messages->refreshConversation((int) $cid);
            }
            $out = $counts + ['total' => count($clean), 'last_android_sms_id' => $last];
            $this->db->exec('INSERT INTO sync_batches (device_id, batch_id, result_json, created_at) VALUES (?, ?, ?, ?)', [$deviceId, $batchId, json_encode($out), Clock::nowMs()]);
            $this->db->exec('UPDATE devices SET last_sync_at = ?, last_seen = ? WHERE id = ?', [Clock::nowMs(), Clock::nowMs(), $deviceId]);
            if (random_int(1, 50) === 1) {
                $this->db->exec('DELETE FROM sync_batches WHERE created_at < ?', [Clock::nowMs() - 7 * 86400_000]);
            }
            return $out;
        });

        // Large historical syncs would flood SSE; only announce live arrivals individually.
        if (count($newIncoming) <= 20) {
            foreach ($newIncoming as $id) {
                $this->bus->messageNew($id);
            }
        } else {
            foreach (array_keys($touchedConv) as $cid) {
                $this->bus->conversationUpdate((int) $cid);
            }
        }
        return $result;
    }

    /**
     * @param array<string,mixed> $raw
     * @return array<string,mixed>
     */
    private function validateMessage(array $raw): array
    {
        $v = new Validator($raw);
        $source = (string) $v->enum('source_type', ['android_provider', 'android_receiver']);
        $phone = $v->string('phone_number', 1, 64);
        $content = $v->string('content', 0, 20000, false, false);
        $direction = (string) $v->enum('direction', ['incoming', 'outgoing']);
        $ts = $v->int('timestamp', 0);
        $smsId = $v->int('android_sms_id', 0, null, $source === 'android_provider');
        $key = $v->string('source_key', 0, 64, $source === 'android_receiver');
        if ($source === 'android_receiver' && $key !== '' && !Id::isUuid($key)) {
            $v->fail('source_key', '必须是 UUID');
        }
        $sim = $v->int('sim_slot', 0, 7, false);
        $contact = $v->string('contact_name', 0, 100, false);
        $read = (bool) $v->bool('read', true);
        $v->check();
        return [
            'source_type' => $source,
            'android_sms_id' => $source === 'android_provider' ? $smsId : null,
            'source_key' => $source === 'android_receiver' ? $key : null,
            'phone_number' => $phone,
            'contact_name' => $contact,
            'content' => $content,
            'direction' => $direction,
            'timestamp' => (int) $ts,
            'sim_slot' => $sim,
            'read' => $read,
        ];
    }

    /**
     * Device-reported receipts for outgoing messages. Same state machine as tasks/result.
     *
     * @param array<string,mixed> $body
     * @return array<string,int>
     */
    public function messageStatus(int $deviceId, array $body): array
    {
        if (!isset($body['updates']) || !is_array($body['updates']) || !array_is_list($body['updates']) || count($body['updates']) > 100) {
            throw ApiException::invalid(['updates' => '必须是不超过 100 项的数组']);
        }
        $n = 0;
        foreach ($body['updates'] as $u) {
            if (!is_array($u)) {
                continue;
            }
            try {
                $this->taskResult($deviceId, $u);
                $n++;
            } catch (ApiException) {
                // Out-of-order receipts are expected; skip rather than fail the whole batch.
            }
        }
        return ['updated' => $n];
    }

    // ---- tasks ----------------------------------------------------------

    /** @return array<string,mixed> */
    public function poll(int $deviceId, int $wait): array
    {
        $wait = max(0, min(self::POLL_MAX_WAIT, $wait));
        $deadline = microtime(true) + $wait;
        $swept = $this->tasks->sweep();
        $this->publishSwept($swept);
        while (true) {
            $paused = $this->settings->bool('send_paused');
            $tasks = $paused ? [] : $this->tasks->queuedFor($deviceId);
            if ($tasks !== [] || microtime(true) >= $deadline || connection_aborted()) {
                $this->devices->touch($deviceId);
                return ['tasks' => $tasks, 'send_paused' => $paused, 'server_time' => Clock::nowMs()];
            }
            usleep(1_000_000);
            // Settings are cached per request; reload so a pause set during the wait is honoured.
            $this->settings->refresh();
        }
    }

    /**
     * @param array<string,mixed> $body
     * @return array<string,mixed>
     */
    public function claim(int $deviceId, array $body): array
    {
        $v = new Validator($body);
        $taskId = $v->string('task_id', 8, 64);
        $v->check();
        if ($this->settings->bool('send_paused')) {
            throw new ApiException(423, 'send_paused', '发送功能已被紧急暂停');
        }
        $task = $this->tasks->claim($deviceId, $taskId);
        $this->mirror($task);
        return ['task' => $task];
    }

    /**
     * @param array<string,mixed> $body
     * @return array<string,mixed>
     */
    public function taskResult(int $deviceId, array $body): array
    {
        $v = new Validator($body);
        $taskId = $v->string('task_id', 8, 64);
        $status = (string) $v->enum('status', ['sending', 'sent', 'delivered', 'failed', 'unknown']);
        $code = $v->string('error_code', 0, 64, false);
        $msg = $v->string('error_message', 0, 300, false);
        $v->check();
        [$task, $changed] = $this->tasks->report($deviceId, $taskId, $status, $code === '' ? null : $code, $msg === '' ? null : $msg);
        if ($changed) {
            $this->mirror($task);
        }
        return ['task' => $task];
    }

    /** @param array<string,mixed> $task */
    private function mirror(array $task): void
    {
        $status = $task['status'];
        $m = $this->messages->setTaskMessageStatus($task['task_id'], $status, $task['error_message']);
        if ($m !== null) {
            $this->bus->messageStatus($m['id'], $task['task_id'], $m['conversation_id'], $status, $task['error_message']);
        }
        $this->bus->deviceUpdate($task['device_id']);
    }

    /** @param list<array{task_id:string,status:string}> $swept */
    private function publishSwept(array $swept): void
    {
        foreach ($swept as $s) {
            $t = $this->tasks->find($s['task_id']);
            if ($t !== null) {
                $this->mirror($t);
            }
        }
    }

    // ---- sync progress --------------------------------------------------

    /**
     * @param array<string,mixed> $body
     * @return array<string,never>
     */
    public function saveProgress(int $deviceId, array $body): array
    {
        $v = new Validator($body);
        $state = (string) $v->enum('state', ['idle', 'running', 'done', 'error']);
        $total = (int) $v->int('total', 0);
        $synced = (int) $v->int('synced', 0);
        $last = (int) $v->int('last_android_sms_id', 0, null, false, 0);
        $v->check();
        $this->devices->saveCursor($deviceId, $state, $total, min($synced, max($total, $synced)), $last);
        $this->bus->syncProgress($deviceId, $this->devices->cursor($deviceId));
        return [];
    }

    /** @return array<string,mixed> */
    public function progress(int $deviceId): array
    {
        return $this->devices->cursor($deviceId);
    }
}
