<?php
declare(strict_types=1);

namespace SmsGate\Support;

/** Collects field errors, then throws one `invalid_request` ApiException. */
final class Validator
{
    /** @var array<string,string> */
    private array $errors = [];

    /** @param array<string,mixed> $data */
    public function __construct(private array $data)
    {
    }

    public function string(string $key, int $min = 0, int $max = 255, bool $required = true, bool $trim = true): string
    {
        $v = $this->data[$key] ?? null;
        if ($v === null || $v === '') {
            if ($required && $min > 0) {
                $this->errors[$key] = '必填';
            }
            return '';
        }
        if (!is_string($v)) {
            $this->errors[$key] = '必须是字符串';
            return '';
        }
        if ($trim) {
            $v = trim($v);
        }
        if (!mb_check_encoding($v, 'UTF-8')) {
            $this->errors[$key] = '编码无效';
            return '';
        }
        $len = mb_strlen($v);
        if ($len < $min) {
            $this->errors[$key] = "长度不能少于 $min";
        } elseif ($len > $max) {
            $this->errors[$key] = "长度不能超过 $max";
        }
        return $v;
    }

    public function int(string $key, ?int $min = null, ?int $max = null, bool $required = true, ?int $default = null): ?int
    {
        $v = $this->data[$key] ?? null;
        if ($v === null) {
            if ($required) {
                $this->errors[$key] = '必填';
            }
            return $default;
        }
        if (is_string($v) && preg_match('/^-?\d{1,18}$/', $v) === 1) {
            $v = (int) $v;
        }
        if (!is_int($v)) {
            $this->errors[$key] = '必须是整数';
            return $default;
        }
        if (($min !== null && $v < $min) || ($max !== null && $v > $max)) {
            $this->errors[$key] = "必须在 $min 到 $max 之间";
            return $default;
        }
        return $v;
    }

    public function bool(string $key, ?bool $default = false): ?bool
    {
        $v = $this->data[$key] ?? null;
        if ($v === null) {
            return $default;
        }
        if (is_bool($v)) {
            return $v;
        }
        if ($v === 1 || $v === 0 || $v === '1' || $v === '0') {
            return (bool) (int) $v;
        }
        $this->errors[$key] = '必须是布尔值';
        return $default;
    }

    /** @param list<string> $allowed */
    public function enum(string $key, array $allowed, bool $required = true, ?string $default = null): ?string
    {
        $v = $this->data[$key] ?? null;
        if ($v === null) {
            if ($required) {
                $this->errors[$key] = '必填';
            }
            return $default;
        }
        if (!is_string($v) || !in_array($v, $allowed, true)) {
            $this->errors[$key] = '取值必须是 ' . implode(' / ', $allowed);
            return $default;
        }
        return $v;
    }

    public function fail(string $key, string $reason): void
    {
        $this->errors[$key] = $reason;
    }

    public function has(string $key): bool
    {
        return array_key_exists($key, $this->data) && $this->data[$key] !== null;
    }

    public function check(): void
    {
        if ($this->errors !== []) {
            throw ApiException::invalid($this->errors);
        }
    }
}
