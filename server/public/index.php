<?php
declare(strict_types=1);

require __DIR__ . '/../src/autoload.php';

use SmsGate\Http\Request;
use SmsGate\Http\Response;
use SmsGate\Kernel;
use SmsGate\Support\Config;

$config = Config::fromEnv();
$rawPath = rawurldecode((string) parse_url((string) ($_SERVER['REQUEST_URI'] ?? '/'), PHP_URL_PATH));
if (strncmp($rawPath, '/index.php/', 11) === 0) {
    $path = substr($rawPath, 10);
} elseif ($rawPath === '/index.php') {
    $path = '/';
} else {
    $path = $rawPath;
}

// 非 /api/ 请求：提供前端静态资源或单页应用入口 index.html
if (strncmp($path, '/api/', 5) !== 0) {
    // 兜底：即使 Web 服务器没有任何拒绝规则，这些路径也绝不能被读取（数据库、会话、安装锁、源码、迁移、CLI）。
    // 只有在 Nginx 直接把文件当静态资源输出时这里才拦不住，所以安装向导还会再检测一次（见 InstallService）。
    if (preg_match('#^/(data|src|migrations|bin|tests|\.git|\.env)(/|$)#i', $path) === 1
        || preg_match('#\.(sqlite3?|db|sql|lock|log|bak|ini|md)$#i', $path) === 1) {
        http_response_code(404);
        header('Content-Type: text/plain; charset=utf-8');
        echo "Not Found\n";
        return;
    }
    $webDir = is_file(__DIR__ . '/index.html') ? __DIR__ : $config->webRoot;
    $root = realpath($webDir);
    $file = ($root !== false && $path !== '/' && $path !== '') ? realpath($root . $path) : false;
    if ($root !== false && $file !== false && is_file($file) && strncmp($file, $root . DIRECTORY_SEPARATOR, strlen($root) + 1) === 0) {
        $ext = strtolower(pathinfo($file, PATHINFO_EXTENSION));
        if (in_array($ext, ['php', 'lock', 'sqlite', 'db', 'sql', 'sh', 'env'], true)) {
            http_response_code(403);
            return;
        }
        $types = ['js' => 'text/javascript', 'css' => 'text/css', 'svg' => 'image/svg+xml', 'png' => 'image/png', 'ico' => 'image/x-icon', 'json' => 'application/json', 'woff2' => 'font/woff2', 'html' => 'text/html; charset=utf-8'];
        header('Content-Type: ' . ($types[$ext] ?? 'application/octet-stream'));
        readfile($file);
        return;
    }
    $index = $root !== false ? $root . '/index.html' : '';
    if ($index !== '' && is_file($index)) {
        header('Content-Type: text/html; charset=utf-8');
        header('Cache-Control: no-cache');
        readfile($index);
        return;
    }
    http_response_code(404);
    header('Content-Type: text/plain; charset=utf-8');
    echo "前端尚未构建或未找到 index.html。\n";
    return;
}

// 数据库、会话、安装锁等新建文件默认 0640（目录 0750），共享主机上的其他用户读不到
umask(0027);
ini_set('display_errors', '0');
set_time_limit(0);
ignore_user_abort(false);

try {
    $kernel = new Kernel($config);
    $kernel->handle(Request::fromGlobals($config->trustProxy, $config->trustedProxies))->send();
} catch (Throwable $e) {
    error_log('[smsgate] fatal: ' . $e->getMessage() . ' @ ' . $e->getFile() . ':' . $e->getLine());
    (Response::error(500, 'internal_error', '服务器内部错误'))->send();
}
