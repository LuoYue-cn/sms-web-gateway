<?php
declare(strict_types=1);

// Usage: php server/bin/backfill_codes.php [--all]
//   默认：只处理还没按当前规则识别过的短信（升级后服务端也会自动做，这里可一次跑完）。
//   --all：识别规则改动后，强制重扫全部收件短信。
require __DIR__ . '/../src/autoload.php';

use SmsGate\Database\Backfill;
use SmsGate\Database\Database;
use SmsGate\Database\Migrator;
use SmsGate\Support\Config;

$config = Config::fromEnv();
$db = new Database($config->dbPath());
(new Migrator($db, $config->migrationsDir))->migrate();
if (in_array('--all', $argv, true)) {
    Backfill::reset($db);
}
$n = Backfill::codes($db, PHP_INT_MAX);
$withCode = (int) $db->value("SELECT COUNT(*) FROM messages WHERE code IS NOT NULL");
echo "已处理 $n 条短信，当前共有 $withCode 条识别出验证码。\n";
