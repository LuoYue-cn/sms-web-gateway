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

ini_set('display_errors', '0');
set_time_limit(0);
ignore_user_abort(false);

try {
    $kernel = new Kernel($config);
    $kernel->handle(Request::fromGlobals($config->trustProxy))->send();
} catch (Throwable $e) {
    error_log('[smsgate] fatal: ' . $e->getMessage() . ' @ ' . $e->getFile() . ':' . $e->getLine());
    (Response::error(500, 'internal_error', '服务器内部错误'))->send();
}
