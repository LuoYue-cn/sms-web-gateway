<?php
declare(strict_types=1);

namespace SmsGate\Support;

use SmsGate\Database\Database;

/** Fixed-window counter stored in SQLite (shared by all PHP workers). */
final class RateLimiter
{
    public function __construct(private Database $db)
    {
    }

    /** Throws 429 when `$limit` hits per `$windowSec` are exceeded for `$bucket`. */
    public function hit(string $bucket, int $limit, int $windowSec = 60): void
    {
        $nowSec = intdiv(Clock::nowMs(), 1000);
        $window = intdiv($nowSec, $windowSec);
        $count = $this->db->transaction(function () use ($bucket, $window): int {
            $this->db->exec(
                'INSERT INTO rate_limits (bucket, window, count) VALUES (?, ?, 1) ON CONFLICT(bucket, window) DO UPDATE SET count = count + 1',
                [$bucket, $window]
            );
            return (int) $this->db->value('SELECT count FROM rate_limits WHERE bucket = ? AND window = ?', [$bucket, $window]);
        });
        if (random_int(1, 300) === 1) {
            $this->db->exec('DELETE FROM rate_limits WHERE window < ?', [$window - 2]);
        }
        if ($count > $limit) {
            $retry = max(1, ($window + 1) * $windowSec - $nowSec);
            throw new ApiException(429, 'rate_limited', '请求过于频繁，请稍后再试', ['retry_after' => $retry]);
        }
    }
}
