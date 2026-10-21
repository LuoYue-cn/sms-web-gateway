<?php
declare(strict_types=1);

namespace SmsGate\Http;

use SmsGate\Support\ApiException;

/**
 * Minimal router. Routes carry an `auth` tag ('public' | 'admin' | 'device') enforced by the kernel,
 * so a controller cannot accidentally be reachable with the wrong credential type.
 */
final class Router
{
    /** @var list<array{method:string,regex:string,handler:callable,auth:string}> */
    private array $routes = [];

    public function add(string $method, string $pattern, callable $handler, string $auth): void
    {
        $regex = '#^' . preg_replace('#\{(\w+)\}#', '(?P<$1>[^/]+)', $pattern) . '$#';
        $this->routes[] = ['method' => $method, 'regex' => $regex, 'handler' => $handler, 'auth' => $auth];
    }

    /** @return array{handler:callable,auth:string,params:array<string,string>} */
    public function match(string $method, string $path): array
    {
        $pathMatched = false;
        foreach ($this->routes as $r) {
            if (preg_match($r['regex'], $path, $m) === 1) {
                $pathMatched = true;
                if ($r['method'] === $method) {
                    $params = array_filter($m, 'is_string', ARRAY_FILTER_USE_KEY);
                    return ['handler' => $r['handler'], 'auth' => $r['auth'], 'params' => $params];
                }
            }
        }
        if ($pathMatched) {
            throw new ApiException(405, 'method_not_allowed', '不支持的请求方法');
        }
        throw new ApiException(404, 'not_found', '接口不存在');
    }
}
