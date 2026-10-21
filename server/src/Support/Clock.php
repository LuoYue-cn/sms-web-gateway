<?php
declare(strict_types=1);

namespace SmsGate\Support;

/** Time source in Unix milliseconds (UTC). Overridable for tests. */
final class Clock
{
    private static ?int $fixed = null;

    public static function nowMs(): int
    {
        return self::$fixed ?? (int) floor(microtime(true) * 1000);
    }

    public static function setFixed(?int $ms): void
    {
        self::$fixed = $ms;
    }

    public static function advance(int $ms): void
    {
        self::$fixed = self::nowMs() + $ms;
    }
}
