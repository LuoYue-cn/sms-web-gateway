<?php
declare(strict_types=1);

namespace SmsGate\Services;

use SmsGate\Database\Database;
use SmsGate\Http\Request;
use SmsGate\Repositories\AuditRepository;
use SmsGate\Repositories\SettingsRepository;
use SmsGate\Repositories\UserRepository;
use SmsGate\Support\ApiException;
use SmsGate\Support\Clock;
use SmsGate\Support\Id;

/** Admin authentication: password check, login throttling, PHP session + CSRF token. */
final class AuthService
{
    public const MAX_FAILURES = 5;
    /** 同一用户名在所有来源合计的失败上限。分布式撞库时每个 IP 只试几次，逐 IP 限流拦不住。 */
    public const MAX_FAILURES_PER_USER = 20;
    public const FAILURE_WINDOW_MS = 900_000;
    public const SESSION_IDLE_MS = 12 * 3600_000;
    public const SESSION_ABSOLUTE_MS = 7 * 86400_000;
    public const MIN_PASSWORD = 10;

    public function __construct(
        private Database $db,
        private UserRepository $users,
        private AuditRepository $audit,
        private SettingsRepository $settings,
        private string $sessionPath,
    ) {
    }

    public function startSession(Request $req): void
    {
        if (session_status() === PHP_SESSION_ACTIVE) {
            return;
        }
        if (!is_dir($this->sessionPath)) {
            @mkdir($this->sessionPath, 0770, true);
        }
        session_save_path($this->sessionPath);
        session_name('sms_session');
        session_set_cookie_params([
            'lifetime' => 0, 'path' => '/', 'secure' => $req->secure, 'httponly' => true, 'samesite' => 'Lax',
        ]);
        ini_set('session.use_strict_mode', '1');
        ini_set('session.use_only_cookies', '1');
        ini_set('session.gc_maxlifetime', (string) (self::SESSION_ABSOLUTE_MS / 1000));
        session_start();
    }

    /** Read the logged-in user (or null) and release the session lock. */
    public function currentUser(Request $req, bool $releaseLock = false): ?array
    {
        $this->startSession($req);
        $uid = $_SESSION['uid'] ?? null;
        $user = null;
        if (is_int($uid)) {
            $now = Clock::nowMs();
            $u = $this->users->byId($uid);
            $valid = $u !== null
                && (int) ($_SESSION['epoch'] ?? -1) === (int) $u['session_epoch']
                && $now - (int) ($_SESSION['last'] ?? 0) <= self::SESSION_IDLE_MS
                && $now - (int) ($_SESSION['created'] ?? 0) <= self::SESSION_ABSOLUTE_MS;
            if ($valid) {
                $_SESSION['last'] = $now;
                $user = $u;
            } else {
                $_SESSION = [];
            }
        }
        if ($releaseLock) {
            session_write_close();
        }
        return $user;
    }

    public function csrfToken(): string
    {
        return (string) ($_SESSION['csrf'] ?? '');
    }

    public function verifyCsrf(Request $req): void
    {
        $sent = $req->header('x-csrf-token');
        $have = $this->csrfToken();
        if ($have === '' || $sent === '' || !hash_equals($have, $sent)) {
            throw new ApiException(403, 'csrf_failed', 'CSRF 校验失败，请刷新页面后重试');
        }
    }

    /** @return array<string,mixed> */
    public function login(Request $req, string $username, string $password): array
    {
        $this->startSession($req);
        $now = Clock::nowMs();
        $key = mb_strtolower($username);
        $fails = (int) $this->db->value(
            'SELECT COUNT(*) FROM login_attempts WHERE username = ? AND ip = ? AND success = 0 AND ts > ?',
            [$key, $req->ip, $now - self::FAILURE_WINDOW_MS]
        );
        if ($fails >= self::MAX_FAILURES) {
            $oldest = (int) $this->db->value(
                'SELECT MIN(ts) FROM login_attempts WHERE username = ? AND ip = ? AND success = 0 AND ts > ?',
                [$key, $req->ip, $now - self::FAILURE_WINDOW_MS]
            );
            $retry = max(1, (int) ceil(($oldest + self::FAILURE_WINDOW_MS - $now) / 1000));
            throw new ApiException(429, 'too_many_attempts', '登录失败次数过多，请稍后再试', ['retry_after' => $retry]);
        }
        $userFails = (int) $this->db->value(
            'SELECT COUNT(*) FROM login_attempts WHERE username = ? AND success = 0 AND ts > ?',
            [$key, $now - self::FAILURE_WINDOW_MS]
        );
        if ($userFails >= self::MAX_FAILURES_PER_USER) {
            $oldest = (int) $this->db->value(
                'SELECT MIN(ts) FROM login_attempts WHERE username = ? AND success = 0 AND ts > ?',
                [$key, $now - self::FAILURE_WINDOW_MS]
            );
            $retry = max(1, (int) ceil(($oldest + self::FAILURE_WINDOW_MS - $now) / 1000));
            $this->audit->log('admin', $username, 'login_locked', null, '', 'too many failures across sources', $req->ip);
            throw new ApiException(429, 'too_many_attempts', '该账号登录失败次数过多，请稍后再试', ['retry_after' => $retry]);
        }
        $user = $this->users->byUsername($username);
        // Always run a hash comparison so response time does not reveal whether the user exists.
        $hash = $user['password_hash'] ?? '$2y$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234';
        $ok = password_verify($password, $hash) && $user !== null;
        $this->db->exec('INSERT INTO login_attempts (ts, username, ip, success) VALUES (?, ?, ?, ?)', [$now, $key, $req->ip, $ok ? 1 : 0]);
        if (!$ok) {
            $this->audit->log('admin', $username, 'login_failed', null, '', '', $req->ip);
            throw new ApiException(401, 'invalid_credentials', '用户名或密码错误');
        }
        $this->db->exec('DELETE FROM login_attempts WHERE username = ? AND ip = ?', [$key, $req->ip]);
        $this->db->exec('DELETE FROM login_attempts WHERE ts < ?', [$now - 86400_000]);
        if (password_needs_rehash($user['password_hash'], PASSWORD_DEFAULT)) {
            $this->users->setPassword((int) $user['id'], password_hash($password, PASSWORD_DEFAULT));
            $user = $this->users->byId((int) $user['id']) ?? $user;
        }
        session_regenerate_id(true);
        $_SESSION = ['uid' => (int) $user['id'], 'epoch' => (int) $user['session_epoch'], 'csrf' => Id::token(24), 'created' => $now, 'last' => $now];
        $this->audit->log('admin', $user['username'], 'login', null, '', '', $req->ip);
        return $user;
    }

    public function logout(Request $req): void
    {
        $this->startSession($req);
        $_SESSION = [];
        if (session_status() === PHP_SESSION_ACTIVE) {
            session_destroy();
        }
    }

    public function changePassword(Request $req, array $user, string $current, string $new): void
    {
        if (!password_verify($current, $user['password_hash'])) {
            throw new ApiException(400, 'invalid_credentials', '当前密码不正确');
        }
        self::assertStrong($new);
        $epoch = $this->users->setPassword((int) $user['id'], password_hash($new, PASSWORD_DEFAULT));
        $_SESSION['epoch'] = $epoch; // keep this session; all others are invalidated
        $this->audit->log('admin', $user['username'], 'password_change', null, '', '', $req->ip);
    }

    public static function assertStrong(string $password): void
    {
        if (mb_strlen($password) < self::MIN_PASSWORD) {
            throw ApiException::invalid(['password' => '密码至少 ' . self::MIN_PASSWORD . ' 位']);
        }
    }

    /** @return array<string,mixed> */
    public function sessionPayload(array $user): array
    {
        return [
            'user' => ['id' => (int) $user['id'], 'username' => $user['username']],
            'csrf_token' => $this->csrfToken(),
            'site_name' => $this->settings->get('site_name'),
            'timezone' => $this->settings->get('timezone'),
        ];
    }
}
