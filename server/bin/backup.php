<?php
declare(strict_types=1);

// Usage: php server/bin/backup.php [dest.sqlite]   |   php server/bin/backup.php --restore src.sqlite --yes
// Uses SQLite's online backup (VACUUM INTO), safe while the server is running.
require __DIR__ . '/../src/autoload.php';

use SmsGate\Support\Config;

$config = Config::fromEnv();
$args = array_slice($argv, 1);
$restoreIdx = array_search('--restore', $args, true);

if ($restoreIdx !== false) {
    $src = $args[$restoreIdx + 1] ?? '';
    if (!is_file($src) || !in_array('--yes', $args, true)) {
        fwrite(STDERR, "用法: backup.php --restore <备份文件> --yes（会覆盖当前数据库，请先停止服务）\n");
        exit(2);
    }
    $check = new PDO('sqlite:' . $src);
    if ($check->query('PRAGMA integrity_check')->fetchColumn() !== 'ok') {
        fwrite(STDERR, "备份文件完整性检查失败，已中止。\n");
        exit(3);
    }
    $check = null;
    foreach (glob($config->dbPath() . '*') ?: [] as $f) {
        unlink($f);
    }
    copy($src, $config->dbPath());
    echo "已从 $src 恢复。\n";
    exit(0);
}

$dest = $args[0] ?? $config->dataDir . '/backups/sms_gateway-' . gmdate('Ymd-His') . '.sqlite';
if (!is_dir(dirname($dest))) {
    mkdir(dirname($dest), 0770, true);
}
if (file_exists($dest)) {
    fwrite(STDERR, "目标已存在: $dest\n");
    exit(2);
}
$pdo = new PDO('sqlite:' . $config->dbPath());
$pdo->exec('VACUUM INTO ' . $pdo->quote($dest));
echo "备份完成: $dest\n";
