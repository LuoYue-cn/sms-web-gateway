<?php
declare(strict_types=1);

namespace SmsGate\Http;

final class Response
{
    /** @param array<string,string> $headers */
    public function __construct(
        public readonly int $status = 200,
        public readonly string $body = '',
        public array $headers = [],
        public readonly ?\Closure $stream = null,
    ) {
    }

    public static function json(mixed $data, int $status = 200): self
    {
        return new self($status, self::encode(['ok' => true, 'data' => $data]), ['Content-Type' => 'application/json; charset=utf-8']);
    }

    /** @param array<string,mixed> $details */
    public static function error(int $status, string $code, string $message, array $details = []): self
    {
        $err = ['code' => $code, 'message' => $message];
        if ($details !== []) {
            $err['details'] = $details;
        }
        return new self($status, self::encode(['ok' => false, 'error' => $err]), ['Content-Type' => 'application/json; charset=utf-8']);
    }

    /** @param array<string,string> $headers */
    public static function stream(\Closure $writer, array $headers): self
    {
        return new self(200, '', $headers, $writer);
    }

    public static function encode(mixed $v): string
    {
        $out = json_encode($v, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE | JSON_PRESERVE_ZERO_FRACTION);
        return $out === false ? '{"ok":false,"error":{"code":"encode_failed","message":"响应编码失败"}}' : $out;
    }

    public function send(): void
    {
        http_response_code($this->status);
        foreach ($this->headers as $k => $v) {
            header($k . ': ' . $v);
        }
        if ($this->stream !== null) {
            ($this->stream)();
            return;
        }
        echo $this->body;
    }
}
