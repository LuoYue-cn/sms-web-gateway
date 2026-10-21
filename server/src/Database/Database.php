<?php
declare(strict_types=1);

namespace SmsGate\Database;

use PDO;
use PDOException;
use PDOStatement;
use SmsGate\Support\ApiException;

/** Thin PDO wrapper: WAL, foreign keys, prepared statements only, IMMEDIATE transactions. */
final class Database
{
    private PDO $pdo;
    private int $depth = 0;

    public function __construct(string $path)
    {
        $dir = dirname($path);
        if (!is_dir($dir) && !@mkdir($dir, 0775, true) && !is_dir($dir)) {
            throw new \RuntimeException('无法创建数据目录: ' . $dir);
        }
        $this->pdo = new PDO('sqlite:' . $path, null, null, [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES => false,
        ]);
        $this->pdo->exec('PRAGMA busy_timeout = 8000');
        $this->pdo->exec('PRAGMA foreign_keys = ON');
        if ($path !== ':memory:') {
            $this->pdo->exec('PRAGMA journal_mode = WAL');
        }
        $this->pdo->exec('PRAGMA synchronous = NORMAL');
        if ($path !== ':memory:') {
            // 大批量并发同步时 WAL 可能暂时涨到数百 MB（检查点追不上）；该上限让它在检查点完成后回落。
            $this->pdo->exec('PRAGMA journal_size_limit = 67108864');
        }
    }

    public function pdo(): PDO
    {
        return $this->pdo;
    }

    /** @param array<int|string,mixed> $params */
    public function run(string $sql, array $params = []): PDOStatement
    {
        try {
            $st = $this->pdo->prepare($sql);
            $st->execute($params);
            return $st;
        } catch (PDOException $e) {
            throw self::translate($e);
        }
    }

    /**
     * @param array<int|string,mixed> $params
     * @return array<string,mixed>|null
     */
    public function one(string $sql, array $params = []): ?array
    {
        $row = $this->run($sql, $params)->fetch();
        return $row === false ? null : $row;
    }

    /**
     * @param array<int|string,mixed> $params
     * @return list<array<string,mixed>>
     */
    public function all(string $sql, array $params = []): array
    {
        return $this->run($sql, $params)->fetchAll();
    }

    /** @param array<int|string,mixed> $params */
    public function value(string $sql, array $params = []): mixed
    {
        $v = $this->run($sql, $params)->fetchColumn();
        return $v === false ? null : $v;
    }

    /** @param array<int|string,mixed> $params */
    public function exec(string $sql, array $params = []): int
    {
        return $this->run($sql, $params)->rowCount();
    }

    public function lastId(): int
    {
        return (int) $this->pdo->lastInsertId();
    }

    /**
     * Run $fn in a write transaction (BEGIN IMMEDIATE, so concurrent writers queue on busy_timeout
     * instead of failing at commit). Nested calls join the outer transaction.
     *
     * @template T
     * @param callable():T $fn
     * @return T
     */
    public function transaction(callable $fn): mixed
    {
        if ($this->depth > 0) {
            $this->depth++;
            try {
                return $fn();
            } finally {
                $this->depth--;
            }
        }
        try {
            $this->pdo->exec('BEGIN IMMEDIATE');
        } catch (PDOException $e) {
            throw self::translate($e);
        }
        $this->depth = 1;
        try {
            $result = $fn();
            $this->pdo->exec('COMMIT');
            return $result;
        } catch (\Throwable $e) {
            try {
                if ($this->pdo->inTransaction()) {
                    $this->pdo->exec('ROLLBACK');
                }
            } catch (PDOException) {
                // keep the original failure
            }
            throw $e instanceof PDOException ? self::translate($e) : $e;
        } finally {
            $this->depth = 0;
        }
    }

    private static function translate(PDOException $e): \Throwable
    {
        $msg = $e->getMessage();
        if (stripos($msg, 'database is locked') !== false || stripos($msg, 'database table is locked') !== false) {
            return new ApiException(503, 'database_busy', '数据库繁忙，请稍后重试');
        }
        return $e;
    }
}
