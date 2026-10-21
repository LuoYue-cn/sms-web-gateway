<?php
declare(strict_types=1);

namespace SmsGate\Database;

use SmsGate\Support\Clock;

/** Applies `NNN_name.sql` files in order, once each, each in its own transaction. */
final class Migrator
{
    public function __construct(private Database $db, private string $dir)
    {
    }

    /** @return list<string> names of migrations applied by this call */
    public function migrate(): array
    {
        $this->db->exec('CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY, applied_at INTEGER NOT NULL)');
        $files = glob(rtrim($this->dir, '/') . '/*.sql') ?: [];
        sort($files);
        $applied = array_column($this->db->all('SELECT name FROM schema_migrations'), 'name');
        $done = [];
        foreach ($files as $file) {
            $name = basename($file);
            if (in_array($name, $applied, true)) {
                continue;
            }
            $sql = file_get_contents($file);
            if ($sql === false) {
                throw new \RuntimeException('无法读取迁移文件: ' . $name);
            }
            $this->db->pdo()->exec('BEGIN IMMEDIATE');
            try {
                $this->db->pdo()->exec($sql);
                $this->db->run('INSERT INTO schema_migrations (name, applied_at) VALUES (?, ?)', [$name, Clock::nowMs()]);
                $this->db->pdo()->exec('COMMIT');
            } catch (\Throwable $e) {
                if ($this->db->pdo()->inTransaction()) {
                    $this->db->pdo()->exec('ROLLBACK');
                }
                throw new \RuntimeException('迁移失败 ' . $name . ': ' . $e->getMessage(), 0, $e);
            }
            $done[] = $name;
        }
        return $done;
    }
}
