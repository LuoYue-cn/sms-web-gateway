<?php
declare(strict_types=1);

namespace SmsGate\Services;

use SmsGate\Repositories\DeviceRepository;
use SmsGate\Repositories\EventRepository;
use SmsGate\Repositories\MessageRepository;
use SmsGate\Repositories\SettingsRepository;

/** Publishes the SSE event types documented in API.md. */
final class EventBus
{
    public function __construct(
        private EventRepository $events,
        private MessageRepository $messages,
        private DeviceRepository $devices,
        private SettingsRepository $settings,
    ) {
    }

    public function messageNew(int $messageId): void
    {
        $m = $this->messages->message($messageId);
        if ($m === null) {
            return;
        }
        $this->events->publish('message.new', ['message' => $m, 'conversation' => $this->messages->conversation($m['conversation_id'])]);
    }

    public function messageStatus(int $messageId, string $taskId, int $conversationId, string $status, ?string $error): void
    {
        $this->events->publish('message.status', [
            'message_id' => $messageId, 'task_id' => $taskId, 'conversation_id' => $conversationId, 'status' => $status, 'error_message' => $error,
        ]);
        $this->conversationUpdate($conversationId);
    }

    public function conversationUpdate(int $conversationId): void
    {
        $c = $this->messages->conversation($conversationId);
        if ($c !== null) {
            $this->events->publish('conversation.update', ['conversation' => $c]);
        }
    }

    public function conversationDeleted(int $id): void
    {
        $this->events->publish('conversation.update', ['deleted' => true, 'id' => $id]);
    }

    public function deviceUpdate(int $deviceId): void
    {
        $d = $this->devices->find($deviceId);
        if ($d !== null) {
            $this->events->publish('device.update', ['device' => $d]);
        }
    }

    public function deviceRemoved(int $deviceId): void
    {
        $this->events->publish('device.update', ['device' => ['id' => $deviceId], 'removed' => true]);
    }

    /** @param array<string,mixed> $cursor */
    public function syncProgress(int $deviceId, array $cursor): void
    {
        $this->events->publish('sync.progress', [
            'device_id' => $deviceId, 'sync_status' => $cursor['state'], 'sync_total' => $cursor['total'], 'sync_synced' => $cursor['synced'],
        ]);
    }

    public function settingsUpdate(): void
    {
        $this->events->publish('settings.update', $this->settings->all());
    }
}
