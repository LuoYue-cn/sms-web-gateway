<?php
declare(strict_types=1);

// SMS Web Gateway - 根目录容错入口
// 当在宝塔面板中未将「运行目录」切换为 /public 时，自动转入 public/index.php 处理
if (is_file(__DIR__ . '/public/index.php')) {
    require __DIR__ . '/public/index.php';
} else {
    http_response_code(500);
    header('Content-Type: text/plain; charset=utf-8');
    echo "缺少 public/index.php 文件，请检查部署完整性。\n";
}
