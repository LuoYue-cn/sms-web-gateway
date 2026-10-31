<?php
declare(strict_types=1);

// Usage: php server/bin/daily_report.php [YYYY-MM-DD] [--no-ai]
//   不带日期：生成「昨天」（按站点时区）的日报。适合放进宝塔「计划任务」每天早上跑一次。
//   已有同日报告会被覆盖；AI 不可用时自动回退为规则模板。
require __DIR__ . '/../src/autoload.php';

use SmsGate\Kernel;
use SmsGate\Services\StatsService;
use SmsGate\Support\Config;

$config = Config::fromEnv();
$kernel = new Kernel($config);
$stats = $kernel->stats();
$reports = $kernel->reports();

$date = '';
foreach (array_slice($argv, 1) as $a) {
    if (StatsService::isDate($a)) {
        $date = $a;
    }
}
if ($date === '') {
    $date = StatsService::addDays($stats->today(), -1);
}
$useAi = !in_array('--no-ai', $argv, true);
$reports->validateDate($date);
if (!$reports->begin($date)) {
    fwrite(STDERR, "$date 的报告正在生成中，跳过。\n");
    exit(0);
}
$reports->run($date, $useAi);
$r = $reports->get($date);
echo "$date: {$r['status']} ({$r['source']})" . ($r['error'] !== '' ? " - {$r['error']}" : '') . "\n";
exit($r['status'] === 'done' ? 0 : 1);
