<?php
declare(strict_types=1);

namespace SmsGate;

use SmsGate\Controllers\AdminController;
use SmsGate\Controllers\DeviceController;
use SmsGate\Controllers\InsightsController;
use SmsGate\Controllers\InstallController;
use SmsGate\Database\Backfill;
use SmsGate\Database\Database;
use SmsGate\Database\Migrator;
use SmsGate\Http\Request;
use SmsGate\Http\Response;
use SmsGate\Http\Router;
use SmsGate\Repositories\AuditRepository;
use SmsGate\Repositories\DeviceRepository;
use SmsGate\Repositories\EventRepository;
use SmsGate\Repositories\MessageRepository;
use SmsGate\Repositories\SettingsRepository;
use SmsGate\Repositories\TaskRepository;
use SmsGate\Repositories\UserRepository;
use SmsGate\Services\AiClient;
use SmsGate\Services\AuthService;
use SmsGate\Services\DeviceService;
use SmsGate\Services\EventBus;
use SmsGate\Services\InstallService;
use SmsGate\Services\ReportService;
use SmsGate\Services\SendService;
use SmsGate\Services\StatsService;
use SmsGate\Support\ApiException;
use SmsGate\Support\Config;
use SmsGate\Support\RateLimiter;
use SmsGate\Support\Validator;

/** Composition root + request pipeline: route -> authenticate by route type -> CSRF -> controller. */
final class Kernel
{
    private const API = '/api/v1';
    private const DEVICE_REQUESTS_PER_MINUTE = 600;

    private ?Database $db = null;
    private Router $router;
    private InstallService $install;
    /** @var array<string,object> */
    private array $svc = [];

    public function __construct(private Config $config)
    {
        $this->install = new InstallService($config);
        $this->router = new Router();
        $this->routes();
    }

    public function db(): Database
    {
        if ($this->db === null) {
            $this->db = new Database($this->config->dbPath());
            // Idempotent; lets an upgraded image apply new migrations on first request.
            (new Migrator($this->db, $this->config->migrationsDir))->migrate();
            // 升级后给历史短信补验证码（有上限，剩余的下次请求继续）；无待处理行时只是一次索引探测
            Backfill::codes($this->db);
        }
        return $this->db;
    }

    public function handle(Request $req): Response
    {
        try {
            if (strncmp($req->path, self::API . '/', strlen(self::API) + 1) !== 0) {
                throw ApiException::notFoundRoute();
            }
            $route = $this->router->match($req->method, substr($req->path, strlen(self::API)));
            if ($route['auth'] !== 'public' || !in_array($req->path, [self::API . '/install', self::API . '/install/status'], true)) {
                if (!$this->install->isInstalled()) {
                    throw new ApiException(503, 'not_installed', '系统尚未安装，请先访问 /install');
                }
            }
            $this->authenticate($req, $route['auth']);
            $response = ($route['handler'])($req, $route['params']);
        } catch (ApiException $e) {
            $response = Response::error($e->status, $e->errorCode, $e->getMessage(), $e->details);
            if (isset($e->details['retry_after'])) {
                $response->headers['Retry-After'] = (string) $e->details['retry_after'];
            }
        } catch (\Throwable $e) {
            error_log('[smsgate] ' . get_class($e) . ': ' . $e->getMessage() . ' @ ' . $e->getFile() . ':' . $e->getLine());
            $response = Response::error(500, 'internal_error', '服务器内部错误');
        }
        $response->headers += [
            'X-Content-Type-Options' => 'nosniff',
            'Referrer-Policy' => 'same-origin',
            'X-Frame-Options' => 'DENY',
        ];
        if (!isset($response->headers['Cache-Control'])) {
            $response->headers['Cache-Control'] = 'no-store';
        }
        return $response;
    }

    /**
     * Credential types are strictly separated: an admin session is never accepted on a device
     * route and a device token is never accepted on an admin route.
     */
    private function authenticate(Request $req, string $type): void
    {
        if ($type === 'public') {
            return;
        }
        if ($type === 'device') {
            $res = $this->devices()->authenticate($req->bearer());
            if ($res['state'] === 'revoked') {
                throw new ApiException(401, 'device_revoked', '设备已被移除或令牌已被吊销');
            }
            if ($res['state'] !== 'ok') {
                throw new ApiException(401, 'invalid_token', '设备令牌无效');
            }
            $req->attributes['device'] = $res['device'];
            if (!str_ends_with($req->path, '/tasks/poll')) {
                $this->limiter()->hit('dev:' . $res['device']['id'], self::DEVICE_REQUESTS_PER_MINUTE, 60);
            }
            return;
        }
        $auth = $this->auth();
        $user = $auth->currentUser($req, str_ends_with($req->path, '/events'));
        if ($user === null) {
            throw ApiException::unauthenticated();
        }
        if ($req->method !== 'GET' && $req->method !== 'HEAD') {
            $auth->verifyCsrf($req);
        }
        $req->attributes['user'] = $user;
    }

    private function routes(): void
    {
        $r = $this->router;
        $inst = fn() => new InstallController($this->install);
        $r->add('GET', '/install/status', fn($q, $p) => $inst()->status($q), 'public');
        $r->add('POST', '/install', fn($q, $p) => $inst()->install($q), 'public');

        $r->add('POST', '/auth/login', fn($q, $p) => $this->login($q), 'public');
        $a = fn() => $this->admin();
        $r->add('POST', '/auth/logout', fn($q, $p) => $a()->logout($q), 'admin');
        $r->add('GET', '/auth/me', fn($q, $p) => $a()->me($q), 'admin');
        $r->add('POST', '/auth/password', fn($q, $p) => $a()->changePassword($q), 'admin');

        $r->add('GET', '/devices', fn($q, $p) => $a()->devices($q), 'admin');
        $r->add('POST', '/devices/pairing', fn($q, $p) => $a()->createPairing($q), 'admin');
        $r->add('PATCH', '/devices/{id}', fn($q, $p) => $a()->renameDevice($q, $p), 'admin');
        $r->add('DELETE', '/devices/{id}', fn($q, $p) => $a()->deleteDevice($q, $p), 'admin');

        $r->add('GET', '/conversations', fn($q, $p) => $a()->conversations($q), 'admin');
        $r->add('GET', '/conversations/{id}/messages', fn($q, $p) => $a()->conversationMessages($q, $p), 'admin');
        $r->add('POST', '/conversations/{id}/read', fn($q, $p) => $a()->markRead($q, $p), 'admin');
        $r->add('POST', '/conversations/{id}/pin', fn($q, $p) => $a()->pin($q, $p), 'admin');
        $r->add('DELETE', '/conversations/{id}', fn($q, $p) => $a()->deleteConversation($q, $p), 'admin');

        $r->add('GET', '/messages', fn($q, $p) => $a()->messagesList($q), 'admin');
        $r->add('GET', '/messages/search', fn($q, $p) => $a()->search($q), 'admin');
        $r->add('POST', '/messages/send', fn($q, $p) => $a()->send($q), 'admin');
        $r->add('POST', '/tasks/{task_id}/resolve', fn($q, $p) => $a()->resolveTask($q, $p), 'admin');
        $r->add('GET', '/export/messages', fn($q, $p) => $a()->export($q), 'admin');

        $r->add('GET', '/settings', fn($q, $p) => $a()->getSettings($q), 'admin');
        $r->add('PUT', '/settings', fn($q, $p) => $a()->putSettings($q), 'admin');
        $r->add('POST', '/settings/emergency-pause', fn($q, $p) => $a()->emergencyPause($q), 'admin');
        $r->add('GET', '/audit-logs', fn($q, $p) => $a()->auditLogs($q), 'admin');
        $r->add('GET', '/events', fn($q, $p) => $a()->events($q), 'admin');

        $i = fn() => $this->insights();
        $r->add('GET', '/codes/recent', fn($q, $p) => $i()->recentCodes($q), 'admin');
        $r->add('GET', '/stats', fn($q, $p) => $i()->stats($q), 'admin');
        $r->add('GET', '/stats/health', fn($q, $p) => $i()->health($q), 'admin');
        $r->add('GET', '/ai/config', fn($q, $p) => $i()->getAi($q), 'admin');
        $r->add('PUT', '/ai/config', fn($q, $p) => $i()->putAi($q), 'admin');
        $r->add('POST', '/ai/test', fn($q, $p) => $i()->testAi($q), 'admin');
        $r->add('POST', '/ai/preview', fn($q, $p) => $i()->previewAi($q), 'admin');
        $r->add('GET', '/reports', fn($q, $p) => $i()->listReports($q), 'admin');
        $r->add('POST', '/reports/generate', fn($q, $p) => $i()->generate($q), 'admin');
        $r->add('GET', '/reports/{date}', fn($q, $p) => $i()->getReport($q, $p), 'admin');
        $r->add('DELETE', '/reports/{date}', fn($q, $p) => $i()->deleteReport($q, $p), 'admin');

        $d = fn() => $this->deviceCtl();
        $r->add('POST', '/device/pair', fn($q, $p) => $d()->pair($q), 'public');
        $r->add('POST', '/device/token/rotate', fn($q, $p) => $d()->rotate($q), 'device');
        $r->add('POST', '/device/heartbeat', fn($q, $p) => $d()->heartbeat($q), 'device');
        $r->add('POST', '/device/messages/sync', fn($q, $p) => $d()->sync($q), 'device');
        $r->add('POST', '/device/messages/status', fn($q, $p) => $d()->status($q), 'device');
        $r->add('GET', '/device/tasks/poll', fn($q, $p) => $d()->poll($q), 'device');
        $r->add('POST', '/device/tasks/claim', fn($q, $p) => $d()->claim($q), 'device');
        $r->add('POST', '/device/tasks/result', fn($q, $p) => $d()->result($q), 'device');
        $r->add('POST', '/device/sync/progress', fn($q, $p) => $d()->saveProgress($q), 'device');
        $r->add('GET', '/device/sync/progress', fn($q, $p) => $d()->progress($q), 'device');
    }

    private function login(Request $req): Response
    {
        $v = new Validator($req->json());
        $u = $v->string('username', 1, 64);
        $p = $v->string('password', 1, 200, true, false);
        $v->check();
        $this->limiter()->hit('login-ip:' . $req->ip, 60, 60);
        $user = $this->auth()->login($req, $u, $p);
        return Response::json($this->auth()->sessionPayload($user));
    }

    // ---- lazy service graph --------------------------------------------

    private function once(string $k, callable $f): object
    {
        return $this->svc[$k] ??= $f();
    }

    private function settings(): SettingsRepository
    {
        return $this->once('settings', fn() => new SettingsRepository($this->db()));
    }

    private function audit(): AuditRepository
    {
        return $this->once('audit', fn() => new AuditRepository($this->db()));
    }

    private function devices(): DeviceRepository
    {
        return $this->once('devices', fn() => new DeviceRepository($this->db(), $this->settings()));
    }

    private function messages(): MessageRepository
    {
        return $this->once('messages', fn() => new MessageRepository($this->db()));
    }

    private function tasks(): TaskRepository
    {
        return $this->once('tasks', fn() => new TaskRepository($this->db()));
    }

    private function events(): EventRepository
    {
        return $this->once('events', fn() => new EventRepository($this->db()));
    }

    private function limiter(): RateLimiter
    {
        return $this->once('limiter', fn() => new RateLimiter($this->db()));
    }

    private function bus(): EventBus
    {
        return $this->once('bus', fn() => new EventBus($this->events(), $this->messages(), $this->devices(), $this->settings()));
    }

    private function auth(): AuthService
    {
        return $this->once('auth', fn() => new AuthService($this->db(), new UserRepository($this->db()), $this->audit(), $this->settings(), $this->config->sessionPath()));
    }

    private function admin(): AdminController
    {
        return $this->once('admin', fn() => new AdminController(
            $this->auth(), $this->devices(), $this->messages(), $this->settings(), $this->audit(), $this->events(), $this->bus(),
            new SendService($this->db(), $this->tasks(), $this->messages(), $this->devices(), $this->settings(), $this->audit(), $this->limiter(), $this->bus())
        ));
    }

    public function stats(): StatsService
    {
        return $this->once('stats', fn() => new StatsService($this->db(), $this->settings()));
    }

    public function reports(): ReportService
    {
        return $this->once('reports', fn() => new ReportService($this->db(), $this->settings(), $this->stats(), new AiClient($this->settings())));
    }

    private function insights(): InsightsController
    {
        return $this->once('insights', fn() => new InsightsController(
            $this->db(), $this->settings(), $this->audit(), $this->stats(), $this->reports(), new AiClient($this->settings()), $this->limiter()
        ));
    }

    private function deviceCtl(): DeviceController
    {
        return $this->once('devctl', fn() => new DeviceController(
            new DeviceService($this->db(), $this->devices(), $this->messages(), $this->tasks(), $this->settings(), $this->audit(), $this->bus())
        ));
    }
}
