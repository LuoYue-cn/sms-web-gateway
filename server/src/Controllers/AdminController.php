<?php
declare(strict_types=1);

namespace SmsGate\Controllers;

use SmsGate\Http\Request;
use SmsGate\Http\Response;
use SmsGate\Repositories\AuditRepository;
use SmsGate\Repositories\DeviceRepository;
use SmsGate\Repositories\EventRepository;
use SmsGate\Repositories\MessageRepository;
use SmsGate\Repositories\SettingsRepository;
use SmsGate\Services\AuthService;
use SmsGate\Services\EventBus;
use SmsGate\Services\SendService;
use SmsGate\Support\ApiException;
use SmsGate\Support\Clock;
use SmsGate\Support\Validator;

/** Session-authenticated admin endpoints (auth + CSRF are enforced by the kernel before these run). */
final class AdminController
{
    public function __construct(
        private AuthService $auth,
        private DeviceRepository $devices,
        private MessageRepository $messages,
        private SettingsRepository $settings,
        private AuditRepository $audit,
        private EventRepository $events,
        private EventBus $bus,
        private SendService $sender,
    ) {
    }

    // ---- auth -----------------------------------------------------------

    public function me(Request $req): Response
    {
        return Response::json($this->auth->sessionPayload($req->attributes['user']));
    }

    public function logout(Request $req): Response
    {
        $this->audit->log('admin', $req->attributes['user']['username'], 'logout', null, '', '', $req->ip);
        $this->auth->logout($req);
        return Response::json(new \stdClass());
    }

    public function changePassword(Request $req): Response
    {
        $v = new Validator($req->json());
        $cur = $v->string('current_password', 1, 200, true, false);
        $new = $v->string('new_password', 1, 200, true, false);
        $v->check();
        $this->auth->changePassword($req, $req->attributes['user'], $cur, $new);
        return Response::json(new \stdClass());
    }

    // ---- devices --------------------------------------------------------

    public function devices(Request $req): Response
    {
        return Response::json(['items' => $this->devices->listAll()]);
    }

    public function createPairing(Request $req): Response
    {
        $body = $req->json();
        $url = isset($body['server_url']) && is_string($body['server_url']) ? rtrim(trim($body['server_url']), '/') : '';
        if ($url === '') {
            $url = $this->defaultServerUrl($req);
        }
        if (!preg_match('#^https?://[^\s/]+(/[^\s]*)?$#i', $url)) {
            throw ApiException::invalid(['server_url' => '必须是 http(s) 地址']);
        }
        $t = $this->devices->createPairingToken((int) $req->attributes['user']['id']);
        $this->audit->log('admin', $req->attributes['user']['username'], 'device_pair_token', null, '', 'pairing token created', $req->ip);
        return Response::json([
            'pairing_token' => $t['token'],
            'expires_at' => $t['expires_at'],
            'server_url' => $url,
            'qr_payload' => Response::encode(['server_url' => $url, 'pairing_token' => $t['token']]),
        ], 201);
    }

    public function renameDevice(Request $req, array $p): Response
    {
        $v = new Validator($req->json());
        $name = $v->string('device_name', 1, 100);
        $v->check();
        $id = (int) $p['id'];
        if ($this->devices->find($id) === null || !$this->devices->rename($id, $name)) {
            throw ApiException::notFound('设备');
        }
        $this->bus->deviceUpdate($id);
        return Response::json(['device' => $this->devices->find($id)]);
    }

    public function deleteDevice(Request $req, array $p): Response
    {
        $id = (int) $p['id'];
        if ($this->devices->find($id) === null) {
            throw ApiException::notFound('设备');
        }
        $purge = $req->queryString('purge') === '1';
        $this->devices->revokeAndDelete($id, $purge);
        $this->audit->log('admin', $req->attributes['user']['username'], 'device_revoke', $purge ? null : $id, 'device:' . $id, $purge ? 'purge=1' : 'purge=0', $req->ip);
        $this->bus->deviceRemoved($id);
        return Response::json(new \stdClass());
    }

    // ---- conversations & messages --------------------------------------

    public function conversations(Request $req): Response
    {
        $dev = $req->queryInt('device_id', 0);
        return Response::json($this->pageMeta(
            $this->messages->conversations(
                mb_substr($req->queryString('q'), 0, 100),
                $dev > 0 ? $dev : null,
                $req->queryInt('page', 1, 1, 100000),
                $req->queryInt('page_size', 30, 1, 100)
            ),
            $req,
            30
        ));
    }

    public function conversationMessages(Request $req, array $p): Response
    {
        $id = (int) $p['id'];
        if ($this->messages->conversation($id) === null) {
            throw ApiException::notFound('会话');
        }
        $before = $req->queryInt('before_id', 0);
        return Response::json($this->messages->conversationMessages($id, $before > 0 ? $before : null, $req->queryInt('limit', 50, 1, 200)));
    }

    public function markRead(Request $req, array $p): Response
    {
        $id = (int) $p['id'];
        $this->requireConversation($id);
        $this->messages->markRead($id);
        $this->bus->conversationUpdate($id);
        return Response::json(new \stdClass());
    }

    public function pin(Request $req, array $p): Response
    {
        $id = (int) $p['id'];
        $this->requireConversation($id);
        $v = new Validator($req->json());
        $pinned = (bool) $v->bool('pinned', true);
        $v->check();
        $this->messages->pin($id, $pinned);
        $this->bus->conversationUpdate($id);
        return Response::json(new \stdClass());
    }

    public function deleteConversation(Request $req, array $p): Response
    {
        $id = (int) $p['id'];
        $this->requireConversation($id);
        $this->messages->deleteConversation($id);
        $this->bus->conversationDeleted($id);
        return Response::json(new \stdClass());
    }

    public function messagesList(Request $req): Response
    {
        $dev = $req->queryInt('device_id', 0);
        return Response::json($this->pageMeta(
            $this->messages->listMessages(
                $dev > 0 ? $dev : null,
                $req->queryString('direction'),
                $req->queryString('status'),
                $req->queryInt('page', 1, 1, 100000),
                $req->queryInt('page_size', 30, 1, 100)
            ),
            $req,
            30
        ));
    }

    public function search(Request $req): Response
    {
        $q = trim($req->queryString('q'));
        if ($q === '') {
            throw ApiException::invalid(['q' => '必填']);
        }
        $dev = $req->queryInt('device_id', 0);
        return Response::json($this->pageMeta(
            $this->messages->search(mb_substr($q, 0, 100), $dev > 0 ? $dev : null, $req->queryInt('page', 1, 1, 100000), $req->queryInt('page_size', 30, 1, 100)),
            $req,
            30
        ));
    }

    public function send(Request $req): Response
    {
        return Response::json($this->sender->send($req->json(), $req->attributes['user'], $req->ip), 201);
    }

    public function resolveTask(Request $req, array $p): Response
    {
        return Response::json($this->sender->resolve($p['task_id'], $req->json(), $req->attributes['user'], $req->ip));
    }

    public function export(Request $req): Response
    {
        $format = $req->queryString('format', 'csv');
        if (!in_array($format, ['csv', 'json'], true)) {
            throw ApiException::invalid(['format' => 'csv 或 json']);
        }
        $dev = $req->queryInt('device_id', 0);
        $rows = $this->messages->exportAll($dev > 0 ? $dev : null);
        $this->audit->log('admin', $req->attributes['user']['username'], 'export_messages', $dev > 0 ? $dev : null, '', 'format=' . $format, $req->ip);
        $name = 'messages-' . gmdate('Ymd-His') . '.' . $format;
        $headers = [
            'Content-Type' => $format === 'csv' ? 'text/csv; charset=utf-8' : 'application/json; charset=utf-8',
            'Content-Disposition' => 'attachment; filename="' . $name . '"',
            'Cache-Control' => 'no-store',
        ];
        return Response::stream(static function () use ($rows, $format): void {
            if ($format === 'json') {
                echo '[';
                $first = true;
                foreach ($rows as $r) {
                    echo ($first ? '' : ',') . Response::encode($r);
                    $first = false;
                }
                echo ']';
                return;
            }
            $out = fopen('php://output', 'w');
            fwrite($out, "\xEF\xBB\xBF");
            $cols = ['id', 'device_id', 'conversation_id', 'phone_number', 'contact_name', 'direction', 'status', 'timestamp', 'sim_slot', 'content'];
            fputcsv($out, $cols, ',', '"', '');
            foreach ($rows as $r) {
                $line = [];
                foreach ($cols as $c) {
                    $val = (string) ($r[$c] ?? '');
                    // Neutralise spreadsheet formula injection.
                    if ($val !== '' && strpbrk($val[0], "=+-@\t\r") !== false && $c === 'content') {
                        $val = "'" . $val;
                    }
                    $line[] = $val;
                }
                fputcsv($out, $line, ',', '"', '');
            }
            fclose($out);
        }, $headers);
    }

    // ---- settings / audit ----------------------------------------------

    public function getSettings(Request $req): Response
    {
        return Response::json($this->settings->all());
    }

    public function putSettings(Request $req): Response
    {
        $body = $req->json();
        $v = new Validator($body);
        $changes = [];
        if ($v->has('site_name')) {
            $changes['site_name'] = $v->string('site_name', 1, 100);
        }
        if ($v->has('timezone')) {
            $tz = $v->string('timezone', 1, 64);
            if (!in_array($tz, \DateTimeZone::listIdentifiers(), true) && $tz !== 'UTC') {
                $v->fail('timezone', '无效的时区');
            }
            $changes['timezone'] = $tz;
        }
        $ints = [
            'online_threshold_seconds' => [10, 86400], 'send_rate_per_minute' => [1, 600], 'max_content_length' => [1, 5000],
            'heartbeat_interval_s' => [10, 600], 'poll_wait_s' => [1, 25],
        ];
        foreach ($ints as $k => [$lo, $hi]) {
            if ($v->has($k)) {
                $changes[$k] = (string) $v->int($k, $lo, $hi);
            }
        }
        if ($v->has('home_country_code')) {
            $cc = $v->string('home_country_code', 0, 6, false);
            if ($cc !== '' && preg_match('/^\+\d{1,4}$/', $cc) !== 1) {
                $v->fail('home_country_code', '格式如 +86');
            }
            $changes['home_country_code'] = $cc;
        }
        if ($v->has('send_paused')) {
            $changes['send_paused'] = $v->bool('send_paused') ? '1' : '0';
        }
        $v->check();
        foreach ($changes as $k => $val) {
            $this->settings->set($k, (string) $val);
        }
        $this->audit->log('admin', $req->attributes['user']['username'], 'settings_update', null, '', 'keys=' . implode(',', array_keys($changes)), $req->ip);
        $this->bus->settingsUpdate();
        return Response::json($this->settings->all());
    }

    public function emergencyPause(Request $req): Response
    {
        $v = new Validator($req->json());
        $paused = $v->bool('paused', null);
        if ($paused === null) {
            $v->fail('paused', '必填');
        }
        $v->check();
        $this->settings->set('send_paused', $paused ? '1' : '0');
        $this->audit->log('admin', $req->attributes['user']['username'], 'emergency_pause', null, '', $paused ? 'paused' : 'resumed', $req->ip);
        $this->bus->settingsUpdate();
        return Response::json(['send_paused' => $paused]);
    }

    public function auditLogs(Request $req): Response
    {
        $dev = $req->queryInt('device_id', 0);
        $page = $req->queryInt('page', 1, 1, 100000);
        $size = $req->queryInt('page_size', 50, 1, 200);
        $r = $this->audit->page($page, $size, $req->queryString('action'), $dev > 0 ? $dev : null);
        return Response::json($r + ['page' => $page, 'page_size' => $size]);
    }

    // ---- SSE ------------------------------------------------------------

    public function events(Request $req): Response
    {
        $last = $req->header('last-event-id');
        $since = ctype_digit($last) ? (int) $last : $this->events->lastId();
        $events = $this->events;
        $headers = [
            'Content-Type' => 'text/event-stream; charset=utf-8',
            'Cache-Control' => 'no-cache, no-transform',
            'X-Accel-Buffering' => 'no',
            'Connection' => 'keep-alive',
        ];
        return Response::stream(static function () use ($events, $since): void {
            @ini_set('zlib.output_compression', '0');
            @ini_set('implicit_flush', '1');
            while (ob_get_level() > 0) {
                ob_end_flush();
            }
            echo "retry: 3000\n\n";
            $deadline = microtime(true) + 55;
            $lastBeat = microtime(true);
            $cursor = $since;
            while (microtime(true) < $deadline && !connection_aborted()) {
                $batch = $events->since($cursor);
                foreach ($batch as $e) {
                    echo 'id: ' . $e['id'] . "\nevent: " . $e['type'] . "\ndata: " . $e['payload'] . "\n\n";
                    $cursor = $e['id'];
                    $lastBeat = microtime(true);
                }
                if ($batch === [] && microtime(true) - $lastBeat >= 15) {
                    echo ": ping\n\n";
                    $lastBeat = microtime(true);
                }
                @flush();
                usleep($batch === [] ? 700_000 : 50_000);
            }
        }, $headers);
    }

    // ---- helpers --------------------------------------------------------

    private function requireConversation(int $id): void
    {
        if ($this->messages->conversation($id) === null) {
            throw ApiException::notFound('会话');
        }
    }

    /**
     * @param array{items:list<mixed>,total:int} $r
     * @return array<string,mixed>
     */
    private function pageMeta(array $r, Request $req, int $defaultSize): array
    {
        return $r + ['page' => $req->queryInt('page', 1, 1, 100000), 'page_size' => $req->queryInt('page_size', $defaultSize, 1, 100)];
    }

    private function defaultServerUrl(Request $req): string
    {
        $host = $req->header('x-forwarded-host') ?: $req->header('host');
        return ($req->secure ? 'https' : 'http') . '://' . ($host !== '' ? $host : 'localhost');
    }
}
