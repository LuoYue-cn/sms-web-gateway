<?php
declare(strict_types=1);

namespace SmsGate\Controllers;

use SmsGate\Http\Request;
use SmsGate\Http\Response;
use SmsGate\Repositories\AuditRepository;
use SmsGate\Repositories\SettingsRepository;
use SmsGate\Database\Database;
use SmsGate\Services\AiClient;
use SmsGate\Services\AiException;
use SmsGate\Services\ReportService;
use SmsGate\Services\StatsService;
use SmsGate\Support\ApiException;
use SmsGate\Support\Clock;
use SmsGate\Support\RateLimiter;
use SmsGate\Support\Validator;

/** 验证码、统计、AI 配置与日报。认证与 CSRF 由内核统一处理。 */
final class InsightsController
{
    public function __construct(
        private Database $db,
        private SettingsRepository $settings,
        private AuditRepository $audit,
        private StatsService $stats,
        private ReportService $reports,
        private AiClient $ai,
        private RateLimiter $limiter,
    ) {
    }

    // ---- 验证码 ---------------------------------------------------------

    public function recentCodes(Request $req): Response
    {
        $minutes = $req->queryInt('minutes', 10, 1, 1440);
        $limit = $req->queryInt('limit', 20, 1, 100);
        $rows = $this->db->all(
            "SELECT m.id, m.conversation_id, m.code, m.timestamp, m.phone_number, m.content, c.contact_name, d.device_name
             FROM messages m JOIN conversations c ON c.id = m.conversation_id JOIN devices d ON d.id = m.device_id
             WHERE m.code IS NOT NULL AND m.direction = 'incoming' AND m.timestamp >= ?
             ORDER BY m.timestamp DESC, m.id DESC LIMIT ?",
            [Clock::nowMs() - $minutes * 60_000, $limit]
        );
        $items = array_map(static function (array $r): array {
            $tag = \SmsGate\Support\SmsClassifier::senderTag((string) $r['content']);
            $name = trim((string) $r['contact_name']);
            return [
                'message_id' => (int) $r['id'], 'conversation_id' => (int) $r['conversation_id'], 'code' => $r['code'],
                'timestamp' => (int) $r['timestamp'],
                'source' => $tag !== '' ? $tag : ($name !== '' ? $name : (string) $r['phone_number']),
                'device_name' => (string) $r['device_name'],
            ];
        }, $rows);
        return Response::json(['items' => $items, 'minutes' => $minutes]);
    }

    // ---- 统计 -----------------------------------------------------------

    public function stats(Request $req): Response
    {
        $range = $req->queryString('range', '7d');
        if (!in_array($range, ['today', '7d', '30d', '90d', 'custom'], true)) {
            throw ApiException::invalid(['range' => 'today / 7d / 30d / 90d / custom']);
        }
        $r = $this->stats->resolveRange($range, $req->queryString('from'), $req->queryString('to'));
        $dev = $req->queryInt('device_id', 0);
        return Response::json($this->stats->report($r['from'], $r['to'], $dev > 0 ? $dev : null));
    }

    public function health(Request $req): Response
    {
        $dev = $req->queryInt('device_id', 0);
        return Response::json($this->stats->health($dev > 0 ? $dev : null));
    }

    // ---- AI 配置 --------------------------------------------------------

    public function getAi(Request $req): Response
    {
        return Response::json($this->aiConfig());
    }

    public function putAi(Request $req): Response
    {
        $body = $req->json();
        $v = new Validator($body);
        $changes = [];
        if ($v->has('ai_enabled')) {
            $changes['ai_enabled'] = $v->bool('ai_enabled') ? '1' : '0';
        }
        if ($v->has('ai_base_url')) {
            $url = rtrim($v->string('ai_base_url', 0, 300, false), '/');
            if ($url !== '' && preg_match('#^https?://[^\s/]+(/[^\s]*)?$#i', $url) !== 1) {
                $v->fail('ai_base_url', '必须是 http(s):// 开头的地址，如 https://api.openai.com/v1');
            }
            $changes['ai_base_url'] = $url;
        }
        if ($v->has('ai_model')) {
            $changes['ai_model'] = $v->string('ai_model', 0, 100, false);
        }
        if ($v->has('ai_detail_level')) {
            $changes['ai_detail_level'] = (string) $v->int('ai_detail_level', 0, 2);
        }
        $newKey = null;
        if ($v->has('ai_api_key')) {
            $k = $v->string('ai_api_key', 0, 500, false);
            if (preg_match('/^[\x21-\x7e]*$/', $k) !== 1) {
                $v->fail('ai_api_key', 'API Key 只能包含可见的 ASCII 字符');
            }
            if ($k !== '') {
                $newKey = $k;
            }
        }
        $clearKey = $v->bool('clear_api_key') === true;
        $v->check();
        foreach ($changes as $k => $val) {
            $this->settings->set($k, $val);
        }
        if ($newKey !== null) {
            $this->settings->set('ai_api_key', $newKey);
        } elseif ($clearKey) {
            $this->settings->set('ai_api_key', '');
        }
        $keys = array_keys($changes);
        if ($newKey !== null || $clearKey) {
            $keys[] = $newKey !== null ? 'ai_api_key(set)' : 'ai_api_key(cleared)';
        }
        $this->audit->log('admin', $req->attributes['user']['username'], 'ai_config_update', null, '', 'keys=' . implode(',', $keys), $req->ip);
        return Response::json($this->aiConfig());
    }

    /** @return array<string,mixed> */
    private function aiConfig(): array
    {
        $env = AiClient::envKey() !== '';
        $db = $this->settings->get('ai_api_key') !== '';
        return [
            'ai_enabled' => $this->settings->bool('ai_enabled'),
            'ai_base_url' => $this->settings->get('ai_base_url'),
            'ai_model' => $this->settings->get('ai_model'),
            'ai_detail_level' => max(0, min(2, $this->settings->int('ai_detail_level'))),
            'key_set' => $env || $db,
            'key_source' => $env ? 'env' : ($db ? 'db' : 'none'),
        ];
    }

    public function testAi(Request $req): Response
    {
        $this->limiter->hit('ai-test', 6, 60);
        try {
            // 同步请求：必须短于常见的 PHP-FPM request_terminate_timeout（宝塔默认 100 秒），才能给出明确的超时原因而不是 502
            $r = $this->ai->chat([['role' => 'user', 'content' => '请只回复两个字：成功']], 90, 1);
        } catch (AiException $e) {
            return Response::json(['ok' => false, 'message' => $e->getMessage()]);
        }
        return Response::json(['ok' => true, 'message' => '连接成功', 'model' => $r['model'], 'reply' => mb_substr(trim($r['content']), 0, 40)]);
    }

    /** 预览将发送给 AI 的完整消息。只是读取并脱敏，不会联网。 */
    public function previewAi(Request $req): Response
    {
        $date = $this->dateParam($req->json()['date'] ?? null);
        $msgs = $this->reports->previewPrompt($date);
        return Response::json([
            'date' => $date, 'detail_level' => $this->aiConfig()['ai_detail_level'], 'messages' => $msgs,
            'chars' => array_sum(array_map(static fn($m) => mb_strlen($m['content']), $msgs)),
        ]);
    }

    // ---- 日报 -----------------------------------------------------------

    public function listReports(Request $req): Response
    {
        return Response::json(['items' => $this->reports->list($req->queryInt('limit', 60, 1, 200))]);
    }

    public function getReport(Request $req, array $p): Response
    {
        $r = StatsService::isDate($p['date']) ? $this->reports->get($p['date']) : null;
        if ($r === null) {
            throw ApiException::notFound('报告');
        }
        return Response::json($r);
    }

    public function deleteReport(Request $req, array $p): Response
    {
        if (!StatsService::isDate($p['date']) || !$this->reports->delete($p['date'])) {
            throw ApiException::notFound('报告');
        }
        $this->audit->log('admin', $req->attributes['user']['username'], 'report_delete', null, 'report:' . $p['date'], '', $req->ip);
        return Response::json(new \stdClass());
    }

    /** 立刻返回 202，响应发出后在同一个 PHP 进程里继续生成；页面轮询报告状态。 */
    public function generate(Request $req): Response
    {
        $body = $req->json();
        $date = $this->dateParam($body['date'] ?? null);
        $useAi = !array_key_exists('use_ai', $body) || $body['use_ai'] === true || $body['use_ai'] === 1;
        $this->limiter->hit('report-gen', 10, 60);
        if (!$this->reports->begin($date)) {
            return Response::json($this->reports->get($date), 202);
        }
        $this->audit->log('admin', $req->attributes['user']['username'], 'report_generate', null, 'report:' . $date, $useAi ? 'ai' : 'rules', $req->ip);
        $reports = $this->reports;
        return Response::json($reports->get($date), 202)->afterSend(static function () use ($reports, $date, $useAi): void {
            $reports->run($date, $useAi);
        });
    }

    private function dateParam(mixed $d): string
    {
        $date = is_string($d) && $d !== '' ? $d : (new \DateTimeImmutable('@' . intdiv(Clock::nowMs(), 1000)))->setTimezone($this->stats->tz())->format('Y-m-d');
        $this->reports->validateDate($date);
        return $date;
    }
}
