<?php
declare(strict_types=1);

// Usage: php server/bin/reset.php --yes
// The ONLY supported way to re-run the install wizard. Deletes ALL data (a backup is kept first).
require __DIR__ . '/../src/autoload.php';

use SmsGate\Support\Config;

if (PHP_SAPI !== 'cli') {
    exit(1);
}
if (!in_array('--yes', $argv, true)) {
    fwrite(STDERR, "这将删除所有数据（短信、设备、管理员）。确认请追加 --yes。\n");
    exit(2);
}
$config = Config::fromEnv();
$dir = $config->dataDir;
if (!is_dir($dir)) {
    echo "数据目录不存在，无需重置。\n";
    exit(0);
}
$backup = $dir . '/backup-before-reset-' . gmdate('Ymd-His');
mkdir($backup, 0770, true);
foreach (glob($dir . '/sms_gateway.sqlite*') ?: [] as $f) {
    copy($f, $backup . '/' . basename($f));
    unlink($f);
}
@unlink($config->lockPath());
@unlink($dir . '/install.partial');
foreach (glob($config->sessionPath() . '/sess_*') ?: [] as $f) {
    @unlink($f);
}
echo "已重置。旧数据库备份在 $backup\n访问 /install 重新安装。\n";
