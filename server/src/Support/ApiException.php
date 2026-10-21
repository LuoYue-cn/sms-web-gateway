<?php
declare(strict_types=1);

namespace SmsGate\Support;

use RuntimeException;

/** An error that maps directly onto the documented JSON error envelope. */
final class ApiException extends RuntimeException
{
    /** @param array<string,mixed> $details */
    public function __construct(
        public readonly int $status,
        public readonly string $errorCode,
        string $message,
        public readonly array $details = [],
    ) {
        parent::__construct($message);
    }

    /** @param array<string,string> $fields */
    public static function invalid(array $fields, string $message = '请求参数不合法'): self
    {
        return new self(400, 'invalid_request', $message, ['fields' => $fields]);
    }

    public static function notFound(string $what = '资源'): self
    {
        return new self(404, 'not_found', $what . '不存在');
    }

    public static function notFoundRoute(): self
    {
        return new self(404, 'not_found', '接口不存在');
    }

    public static function unauthenticated(): self
    {
        return new self(401, 'unauthenticated', '请先登录');
    }
}
