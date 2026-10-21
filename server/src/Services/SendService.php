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
use SmsGate\Support\Id;
use SmsGate\Support\PhoneNumber;
use SmsGate\Support\RateLimiter;
use SmsGate\Support\Validator;

/** Admin-side: create send tasks, resolve unknown tasks. Device-side task protocol lives in DeviceService. */
final class SendService
{
    public function __construct(
        private Database $db,
        private TaskRepository $tasks,
        private MessageRepository $messages,
        private DeviceRepository $devices,
        private SettingsRepository $settings,
        private AuditRepository $audit,
        private RateLimiter $limiter,
        private EventBus $bus,
    ) {
    }

    /**
     * @param array<string,mixed> $body
     * @param array<string,mixed> $user
     * @return array<string,mixed>
     */
    public function send(array $body, array $user, string $ip): array
    {
        $v = new Validator($body);
        $deviceId = $v->int('device_id', 1);
        $recipientRaw = $v->string('recipient', 1, 100);
        $maxLen = $this->settings->int('max_content_length');
        $content = $v->string('content', 1, $maxLen, true, false);
        $sim = $v->int('sim_slot', 0, 3, false);
        $confirm = (bool) $v->bool('confirm_warnings', false);
        $v->check();

        if (PhoneNumber::looksLikeMultiple($recipientRaw)) {
            throw ApiException::invalid(['recipient' => '一次只能发送给一个号码，禁止群发']);
        }
        $recipient = PhoneNumber::normalizeForSend($recipientRaw);
        if ($recipient === null) {
            throw ApiException::invalid(['recipient' => '号码格式不正确（支持国际格式，如 +8613800138000）']);
        }
        if (trim($content) === '') {
            throw ApiException::invalid(['content' => '短信内容不能为空']);
        }
        if ($this->settings->bool('send_paused')) {
            throw new ApiException(423, 'send_paused', '发送功能已被紧急暂停');
        }
        $device = $this->devices->find((int) $deviceId);
        if ($device === null) {
            throw ApiException::notFound('设备');
        }
        $warnings = PhoneNumber::warnings($recipient, $this->settings->get('home_country_code'));
        if ($warnings !== [] && !$confirm) {
            throw new ApiException(422, 'confirmation_required', '该号码需要确认后才能发送', ['warnings' => $warnings]);
        }
        $this->limiter->hit('send:' . $deviceId, max(1, $this->settings->int('send_rate_per_minute')), 60);

        $taskId = Id::uuid();
        $result = $this->db->transaction(function () use ($taskId, $deviceId, $recipient, $content, $sim, $user): array {
            $this->tasks->create($taskId, (int) $deviceId, $recipient, $content, $sim, (int) $user['id']);
            $conv = $this->messages->ensureConversation((int) $deviceId, $recipient);
            $msgId = $this->messages->insertOutgoingForTask((int) $deviceId, $conv, $recipient, $content, $taskId, $sim);
            $this->messages->refreshConversation($conv);
            return ['conv' => $conv, 'msg' => $msgId];
        });
        $this->audit->log('admin', $user['username'], 'send_message', (int) $deviceId, 'task:' . $taskId, 'recipient=' . self::maskNumber($recipient) . ' len=' . mb_strlen($content), $ip);
        $this->bus->messageNew($result['msg']);
        $this->bus->conversationUpdate($result['conv']);
        $this->bus->deviceUpdate((int) $deviceId);
        return [
            'task' => $this->tasks->find($taskId),
            'message' => $this->messages->message($result['msg']),
            'conversation' => $this->messages->conversation($result['conv']),
        ];
    }

    /**
     * @param array<string,mixed> $body
     * @param array<string,mixed> $user
     * @return array<string,mixed>
     */
    public function resolve(string $taskId, array $body, array $user, string $ip): array
    {
        $v = new Validator($body);
        $action = (string) $v->enum('action', ['resend', 'mark_sent', 'mark_failed']);
        $v->check();
        $old = $this->tasks->find($taskId);
        if ($old === null) {
            throw ApiException::notFound('任务');
        }
        $out = $this->db->transaction(function () use ($taskId, $action, $old, $user): array {
            $task = $this->tasks->resolve($taskId, $action);
            $new = null;
            $msgStatus = $task['status'];
            $this->messages->setTaskMessageStatus($taskId, $msgStatus, $task['error_message']);
            if ($action === 'resend') {
                $newId = Id::uuid();
                $this->tasks->create($newId, $old['device_id'], $old['recipient'], $old['content'], $old['sim_slot'], (int) $user['id']);
                $conv = $this->messages->ensureConversation($old['device_id'], $old['recipient']);
                $this->messages->insertOutgoingForTask($old['device_id'], $conv, $old['recipient'], $old['content'], $newId, $old['sim_slot']);
                $this->messages->refreshConversation($conv);
                $new = $this->tasks->find($newId);
            }
            return ['task' => $task, 'new_task' => $new];
        });
        $this->audit->log('admin', $user['username'], 'resolve_task', $old['device_id'], 'task:' . $taskId, 'action=' . $action, $ip);
        $m = $this->db->one('SELECT id, conversation_id FROM messages WHERE task_id = ?', [$taskId]);
        if ($m !== null) {
            $this->bus->messageStatus((int) $m['id'], $taskId, (int) $m['conversation_id'], $out['task']['status'], $out['task']['error_message']);
        }
        if ($out['new_task'] !== null) {
            $nm = $this->db->one('SELECT id FROM messages WHERE task_id = ?', [$out['new_task']['task_id']]);
            if ($nm !== null) {
                $this->bus->messageNew((int) $nm['id']);
            }
        }
        return $out;
    }

    private static function maskNumber(string $n): string
    {
        $len = strlen($n);
        return $len <= 4 ? str_repeat('*', $len) : substr($n, 0, 3) . str_repeat('*', $len - 5) . substr($n, -2);
    }
}
