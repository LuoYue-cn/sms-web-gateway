<?php
declare(strict_types=1);

namespace SmsGate\Services;

use SmsGate\Database\Database;
use SmsGate\Repositories\SettingsRepository;
use SmsGate\Support\ApiException;
use SmsGate\Support\Clock;
use SmsGate\Support\SmsClassifier;

/**
 * 统计查询。全部是 SQL 聚合；按站点时区分桶的做法是把时间范围按「UTC 偏移不变」切成段，
 * 每段用 strftime(ts/1000 + 偏移) 分组，这样跨夏令时也是准确的。
 */
final class StatsService
{
    public const MAX_DAYS = 366;

    public function __construct(private Database $db, private SettingsRepository $settings)
    {
    }

    public function tz(): \DateTimeZone
    {
        try {
            return new \DateTimeZone($this->settings->get('timezone') ?: 'UTC');
        } catch (\Exception) {
            return new \DateTimeZone('UTC');
        }
    }

    public function today(): string
    {
        return (new \DateTimeImmutable('@' . intdiv(Clock::nowMs(), 1000)))->setTimezone($this->tz())->format('Y-m-d');
    }

    /** @return array{from:string,to:string} */
    public function resolveRange(string $range, string $from, string $to): array
    {
        $today = $this->today();
        switch ($range) {
            case '7d':
                return ['from' => self::addDays($today, -6), 'to' => $today];
            case '30d':
                return ['from' => self::addDays($today, -29), 'to' => $today];
            case '90d':
                return ['from' => self::addDays($today, -89), 'to' => $today];
            case 'custom':
                if (!self::isDate($from) || !self::isDate($to)) {
                    throw ApiException::invalid(['from' => '自定义范围需要 YYYY-MM-DD 格式的 from 和 to']);
                }
                if ($to > $today) {
                    $to = $today;
                }
                if ($from > $to) {
                    throw ApiException::invalid(['from' => '开始日期不能晚于结束日期']);
                }
                if (self::daysBetween($from, $to) + 1 > self::MAX_DAYS) {
                    throw ApiException::invalid(['from' => '范围不能超过 ' . self::MAX_DAYS . ' 天']);
                }
                return ['from' => $from, 'to' => $to];
            default:
                return ['from' => $today, 'to' => $today];
        }
    }

    public static function isDate(string $s): bool
    {
        if (preg_match('/^\d{4}-\d{2}-\d{2}$/', $s) !== 1) {
            return false;
        }
        $d = \DateTimeImmutable::createFromFormat('!Y-m-d', $s, new \DateTimeZone('UTC'));
        return $d !== false && $d->format('Y-m-d') === $s;
    }

    public static function addDays(string $date, int $n): string
    {
        return (new \DateTimeImmutable($date, new \DateTimeZone('UTC')))->modify(($n >= 0 ? '+' : '') . $n . ' day')->format('Y-m-d');
    }

    public static function daysBetween(string $from, string $to): int
    {
        $a = new \DateTimeImmutable($from, new \DateTimeZone('UTC'));
        $b = new \DateTimeImmutable($to, new \DateTimeZone('UTC'));
        return (int) $a->diff($b)->format('%r%a');
    }

    /** @return array{0:int,1:int} [起始毫秒, 结束毫秒)，按站点时区的整天 */
    public function bounds(string $from, string $to): array
    {
        $tz = $this->tz();
        $s = (new \DateTimeImmutable($from . ' 00:00:00', $tz))->getTimestamp() * 1000;
        $e = (new \DateTimeImmutable(self::addDays($to, 1) . ' 00:00:00', $tz))->getTimestamp() * 1000;
        return [$s, $e];
    }

    /** @return list<array{0:int,1:int,2:int}> [起, 止, UTC 偏移秒] */
    private function segments(int $fromMs, int $toMs): array
    {
        $tz = $this->tz();
        $fromS = intdiv($fromMs, 1000);
        $toS = intdiv($toMs, 1000);
        $tr = $tz->getTransitions($fromS, $toS);
        if (!is_array($tr) || $tr === []) {
            return [[$fromMs, $toMs, 0]];
        }
        $segs = [];
        $n = count($tr);
        for ($i = 0; $i < $n; $i++) {
            $a = $i === 0 ? $fromMs : $tr[$i]['ts'] * 1000;
            $b = $i + 1 < $n ? $tr[$i + 1]['ts'] * 1000 : $toMs;
            if ($b > $a) {
                $segs[] = [$a, min($b, $toMs), (int) $tr[$i]['offset']];
            }
        }
        return $segs ?: [[$fromMs, $toMs, 0]];
    }

    /**
     * @param array<string,mixed> $opt device_id?:int
     * @return array<string,mixed>
     */
    public function report(string $from, string $to, ?int $deviceId): array
    {
        [$s, $e] = $this->bounds($from, $to);
        $days = self::daysBetween($from, $to) + 1;
        $prevTo = self::addDays($from, -1);
        $prevFrom = self::addDays($prevTo, -($days - 1));
        [$ps, $pe] = $this->bounds($prevFrom, $prevTo);

        $cur = $this->totals($s, $e, $deviceId);
        $prev = $this->totals($ps, $pe, $deviceId);

        return [
            'range' => ['from' => $from, 'to' => $to, 'days' => $days, 'timezone' => $this->tz()->getName(), 'prev_from' => $prevFrom, 'prev_to' => $prevTo],
            'overview' => ['current' => $cur, 'previous' => $prev],
            'daily' => $this->daily($from, $to, $s, $e, $deviceId),
            'heatmap' => $this->heatmap($s, $e, $deviceId),
            'categories' => $this->categories($s, $e, $deviceId),
            'devices' => $this->byDevice($s, $e, $deviceId),
            'send_quality' => $this->sendQuality($s, $e, $deviceId),
            'top_senders' => $this->topSenders($s, $e, $deviceId, 10),
        ];
    }

    /** @return array{incoming:int,outgoing:int,codes:int,senders:int} */
    private function totals(int $s, int $e, ?int $dev): array
    {
        [$dw, $dp] = self::devWhere($dev);
        $r = $this->db->all(
            "SELECT direction, COUNT(*) n, SUM(CASE WHEN code IS NOT NULL THEN 1 ELSE 0 END) c, COUNT(DISTINCT phone_number) p
             FROM messages WHERE timestamp >= ? AND timestamp < ? $dw GROUP BY direction",
            array_merge([$s, $e], $dp)
        );
        $out = ['incoming' => 0, 'outgoing' => 0, 'codes' => 0, 'senders' => 0];
        foreach ($r as $row) {
            $out[$row['direction']] = (int) $row['n'];
            if ($row['direction'] === 'incoming') {
                $out['codes'] = (int) $row['c'];
                $out['senders'] = (int) $row['p'];
            }
        }
        return $out;
    }

    /** @return list<array{date:string,incoming:int,outgoing:int,codes:int}> */
    private function daily(string $from, string $to, int $s, int $e, ?int $dev): array
    {
        [$dw, $dp] = self::devWhere($dev);
        $map = [];
        for ($d = $from; $d <= $to; $d = self::addDays($d, 1)) {
            $map[$d] = ['date' => $d, 'incoming' => 0, 'outgoing' => 0, 'codes' => 0];
        }
        foreach ($this->segments($s, $e) as [$a, $b, $off]) {
            $rows = $this->db->all(
                "SELECT strftime('%Y-%m-%d', timestamp/1000 + $off, 'unixepoch') d, direction, COUNT(*) n,
                        SUM(CASE WHEN code IS NOT NULL THEN 1 ELSE 0 END) c
                 FROM messages WHERE timestamp >= ? AND timestamp < ? $dw GROUP BY d, direction",
                array_merge([$a, $b], $dp)
            );
            foreach ($rows as $r) {
                if (!isset($map[$r['d']])) {
                    continue;
                }
                $map[$r['d']][$r['direction']] += (int) $r['n'];
                if ($r['direction'] === 'incoming') {
                    $map[$r['d']]['codes'] += (int) $r['c'];
                }
            }
        }
        return array_values($map);
    }

    /** 收到短信按「星期（周一在前）× 小时」计数。 @return list<list<int>> 7×24 */
    private function heatmap(int $s, int $e, ?int $dev): array
    {
        [$dw, $dp] = self::devWhere($dev);
        $grid = array_fill(0, 7, array_fill(0, 24, 0));
        foreach ($this->segments($s, $e) as [$a, $b, $off]) {
            $rows = $this->db->all(
                "SELECT strftime('%w', timestamp/1000 + $off, 'unixepoch') w, strftime('%H', timestamp/1000 + $off, 'unixepoch') h, COUNT(*) n
                 FROM messages WHERE direction = 'incoming' AND timestamp >= ? AND timestamp < ? $dw GROUP BY w, h",
                array_merge([$a, $b], $dp)
            );
            foreach ($rows as $r) {
                $grid[((int) $r['w'] + 6) % 7][(int) $r['h']] += (int) $r['n'];
            }
        }
        return $grid;
    }

    /** @return array<string,int> */
    private function categories(int $s, int $e, ?int $dev): array
    {
        [$dw, $dp] = self::devWhere($dev);
        $rows = $this->db->all(
            "SELECT COALESCE(category, 'other') c, COUNT(*) n FROM messages WHERE direction = 'incoming' AND timestamp >= ? AND timestamp < ? $dw GROUP BY c",
            array_merge([$s, $e], $dp)
        );
        $out = array_fill_keys(SmsClassifier::CATEGORIES, 0);
        foreach ($rows as $r) {
            $key = isset($out[$r['c']]) ? $r['c'] : 'other';
            $out[$key] += (int) $r['n'];
        }
        return $out;
    }

    /** @return list<array<string,mixed>> */
    private function byDevice(int $s, int $e, ?int $dev): array
    {
        [$dw, $dp] = self::devWhere($dev);
        $rows = $this->db->all(
            "SELECT m.device_id, m.sim_slot, m.direction, COUNT(*) n, SUM(CASE WHEN m.code IS NOT NULL THEN 1 ELSE 0 END) c
             FROM messages m WHERE m.timestamp >= ? AND m.timestamp < ? $dw GROUP BY m.device_id, m.sim_slot, m.direction",
            array_merge([$s, $e], $dp)
        );
        $names = [];
        foreach ($this->db->all('SELECT id, device_name FROM devices') as $d) {
            $names[(int) $d['id']] = (string) $d['device_name'];
        }
        $map = [];
        foreach ($rows as $r) {
            $k = $r['device_id'] . ':' . ($r['sim_slot'] ?? 'x');
            $map[$k] ??= [
                'device_id' => (int) $r['device_id'], 'device_name' => $names[(int) $r['device_id']] ?? ('设备 #' . $r['device_id']),
                'sim_slot' => $r['sim_slot'] === null ? null : (int) $r['sim_slot'], 'incoming' => 0, 'outgoing' => 0, 'codes' => 0,
            ];
            $map[$k][$r['direction']] += (int) $r['n'];
            if ($r['direction'] === 'incoming') {
                $map[$k]['codes'] += (int) $r['c'];
            }
        }
        $list = array_values($map);
        usort($list, fn($a, $b) => ($b['incoming'] + $b['outgoing']) <=> ($a['incoming'] + $a['outgoing']));
        return $list;
    }

    /** @return array<string,mixed> */
    private function sendQuality(int $s, int $e, ?int $dev): array
    {
        $dw = $dev === null ? '' : ' AND device_id = ?';
        $dp = $dev === null ? [] : [$dev];
        $statuses = ['queued' => 0, 'claimed' => 0, 'sending' => 0, 'sent' => 0, 'delivered' => 0, 'failed' => 0, 'unknown' => 0];
        foreach ($this->db->all("SELECT status, COUNT(*) n FROM send_tasks WHERE created_at >= ? AND created_at < ? $dw GROUP BY status", array_merge([$s, $e], $dp)) as $r) {
            if (isset($statuses[$r['status']])) {
                $statuses[$r['status']] = (int) $r['n'];
            }
        }
        $errors = [];
        foreach ($this->db->all(
            "SELECT COALESCE(error_code, 'unspecified') c, status, COUNT(*) n FROM send_tasks
             WHERE created_at >= ? AND created_at < ? AND status IN ('failed','unknown') $dw GROUP BY c, status ORDER BY n DESC LIMIT 10",
            array_merge([$s, $e], $dp)
        ) as $r) {
            $errors[] = ['code' => $r['c'], 'status' => $r['status'], 'count' => (int) $r['n']];
        }
        $total = array_sum($statuses);
        $settled = $statuses['sent'] + $statuses['delivered'] + $statuses['failed'] + $statuses['unknown'];
        return [
            'statuses' => $statuses,
            'total' => $total,
            'success_rate' => $settled > 0 ? round(($statuses['sent'] + $statuses['delivered']) / $settled * 100, 1) : null,
            'errors' => $errors,
        ];
    }

    /** @return list<array<string,mixed>> */
    public function topSenders(int $s, int $e, ?int $dev, int $limit): array
    {
        [$dw, $dp] = self::devWhere($dev);
        $rows = $this->db->all(
            "SELECT phone_number, MAX(contact_name) contact, COUNT(*) n, SUM(CASE WHEN code IS NOT NULL THEN 1 ELSE 0 END) c, MAX(COALESCE(category, 'other')) cat
             FROM messages WHERE direction = 'incoming' AND timestamp >= ? AND timestamp < ? $dw
             GROUP BY phone_number ORDER BY n DESC, phone_number LIMIT " . (int) $limit,
            array_merge([$s, $e], $dp)
        );
        return array_map(static fn($r) => [
            // 完整号码不出现在统计里：有联系人名用名字，否则中间打码
            'label' => trim((string) $r['contact']) !== '' ? trim((string) $r['contact']) : SmsClassifier::maskAddress((string) $r['phone_number']),
            'count' => (int) $r['n'],
            'codes' => (int) $r['c'],
        ], $rows);
    }

    /**
     * 最近 48 小时的设备健康：每小时平均电量与在线率（心跳数 / 应有心跳数）。心跳只保留 7 天。
     *
     * @return array<string,mixed>
     */
    public function health(?int $deviceId): array
    {
        $now = Clock::nowMs();
        $hours = 48;
        $firstH = intdiv($now, 3_600_000) - ($hours - 1);
        $interval = max(10, $this->settings->int('heartbeat_interval_s'));
        $expected = 3600 / $interval;
        $dw = $deviceId === null ? '' : ' AND device_id = ?';
        $rows = $this->db->all(
            "SELECT device_id, ts/3600000 h, AVG(battery_level) b, COUNT(*) n, MAX(charging) ch FROM device_heartbeats WHERE ts >= ? $dw GROUP BY device_id, h",
            array_merge([$firstH * 3_600_000], $deviceId === null ? [] : [$deviceId])
        );
        $by = [];
        foreach ($rows as $r) {
            $by[(int) $r['device_id']][(int) $r['h']] = $r;
        }
        $devWhere = $deviceId === null ? 'WHERE revoked_at IS NULL' : 'WHERE id = ' . (int) $deviceId;
        $out = [];
        foreach ($this->db->all("SELECT id, device_name, created_at FROM devices $devWhere ORDER BY id") as $d) {
            $id = (int) $d['id'];
            $startH = max($firstH, intdiv((int) $d['created_at'], 3_600_000));
            $points = [];
            $sum = 0.0;
            $cnt = 0;
            for ($h = $startH; $h < $firstH + $hours; $h++) {
                $r = $by[$id][$h] ?? null;
                // 当前这个小时还没过完，按已过去的比例折算，避免显示成「掉线」
                $exp = $h === $firstH + $hours - 1 ? max(1.0, $expected * (($now % 3_600_000) / 3_600_000)) : $expected;
                $pct = $r === null ? 0 : (int) min(100, round((int) $r['n'] / $exp * 100));
                $points[] = [
                    't' => $h * 3_600_000,
                    'battery' => $r === null || $r['b'] === null ? null : (int) round((float) $r['b']),
                    'online_pct' => $pct,
                    'charging' => $r !== null && (int) $r['ch'] === 1,
                ];
                if ($h >= $firstH + $hours - 24) {
                    $sum += $pct;
                    $cnt++;
                }
            }
            $out[] = [
                'device_id' => $id, 'device_name' => (string) $d['device_name'], 'points' => $points,
                'uptime_24h' => $cnt > 0 ? round($sum / $cnt, 1) : null,
            ];
        }
        return ['hours' => $hours, 'devices' => $out];
    }

    /** @return array{0:string,1:list<mixed>} */
    private static function devWhere(?int $dev): array
    {
        return $dev === null ? ['', []] : [' AND device_id = ?', [$dev]];
    }
}
