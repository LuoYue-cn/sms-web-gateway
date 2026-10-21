<?php
declare(strict_types=1);

// Usage: php server/bin/migrate.php   — apply pending database migrations.
require __DIR__ . '/../src/autoload.php';

use SmsGate\Database\Database;
use SmsGate\Database\Migrator;
use SmsGate\Support\Config;

$config = Config::fromEnv();
$db = new Database($config->dbPath());
$applied = (new Migrator($db, $config->migrationsDir))->migrate();
echo $applied === [] ? "数据库已是最新。\n" : '已应用迁移: ' . implode(', ', $applied) . "\n";
