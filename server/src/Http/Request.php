<?php
declare(strict_types=1);

namespace SmsGate\Http;

use SmsGate\Support\ApiException;

final class Request
{
    /** @var array<string,mixed>|null */
    private ?array $json = null;
    /** @var array<string,mixed> */
    public array $attributes = [];

    /**
     * @param array<string,string> $headers lower-cased header names
     * @param array<string,mixed> $query
     */
    public function __construct(
        public readonly string $method,
        public readonly string $path,
        public readonly array $query,
        public readonly array $headers,
        public readonly string $body,
        public readonly string $ip,
        public readonly bool $secure,
    ) {
    }

    public static function fromGlobals(bool $trustProxy): self
    {
        $uri = (string) ($_SERVER['REQUEST_URI'] ?? '/');
        $path = rawurldecode((string) parse_url($uri, PHP_URL_PATH));
        $headers = [];
        foreach ($_SERVER as $k => $v) {
            if (is_string($v) && strncmp($k, 'HTTP_', 5) === 0) {
                $headers[strtolower(str_replace('_', '-', substr($k, 5)))] = $v;
            }
        }
        if (isset($_SERVER['CONTENT_TYPE'])) {
            $headers['content-type'] = (string) $_SERVER['CONTENT_TYPE'];
        }
        $ip = (string) ($_SERVER['REMOTE_ADDR'] ?? '');
        $secure = !empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off';
        if ($trustProxy) {
            $xff = $headers['x-forwarded-for'] ?? '';
            if ($xff !== '') {
                $ip = trim(explode(',', $xff)[0]);
            }
            if (($headers['x-forwarded-proto'] ?? '') === 'https') {
                $secure = true;
            }
        }
        $body = (string) file_get_contents('php://input');
        return new self(strtoupper((string) ($_SERVER['REQUEST_METHOD'] ?? 'GET')), $path, $_GET, $headers, $body, $ip, $secure);
    }

    public function header(string $name): string
    {
        return $this->headers[strtolower($name)] ?? '';
    }

    public function bearer(): string
    {
        $h = $this->header('authorization');
        return stripos($h, 'bearer ') === 0 ? trim(substr($h, 7)) : '';
    }

    /** @return array<string,mixed> */
    public function json(): array
    {
        if ($this->json !== null) {
            return $this->json;
        }
        if (trim($this->body) === '') {
            return $this->json = [];
        }
        $data = json_decode($this->body, true);
        if (!is_array($data) || ($data !== [] && array_is_list($data))) {
            throw new ApiException(400, 'invalid_json', '请求体必须是 JSON 对象');
        }
        return $this->json = $data;
    }

    public function queryString(string $key, string $default = ''): string
    {
        $v = $this->query[$key] ?? $default;
        return is_string($v) ? $v : $default;
    }

    public function queryInt(string $key, int $default, int $min = PHP_INT_MIN, int $max = PHP_INT_MAX): int
    {
        $v = $this->query[$key] ?? null;
        if (!is_string($v) || !preg_match('/^-?\d+$/', $v)) {
            return $default;
        }
        return max($min, min($max, (int) $v));
    }
}
