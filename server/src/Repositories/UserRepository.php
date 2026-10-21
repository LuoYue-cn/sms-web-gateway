<?php
declare(strict_types=1);

namespace SmsGate\Repositories;

use SmsGate\Database\Database;
use SmsGate\Support\Clock;

final class UserRepository
{
    public function __construct(private Database $db)
    {
    }

    public function count(): int
    {
        return (int) $this->db->value('SELECT COUNT(*) FROM users');
    }

    /** @return array<string,mixed>|null */
    public function byUsername(string $username): ?array
    {
        return $this->db->one('SELECT * FROM users WHERE username = ? COLLATE NOCASE', [$username]);
    }

    /** @return array<string,mixed>|null */
    public function byId(int $id): ?array
    {
        return $this->db->one('SELECT * FROM users WHERE id = ?', [$id]);
    }

    public function create(string $username, string $passwordHash): int
    {
        $this->db->exec('INSERT INTO users (username, password_hash, role, created_at) VALUES (?, ?, ?, ?)', [$username, $passwordHash, 'admin', Clock::nowMs()]);
        return $this->db->lastId();
    }

    /** Bumps the session epoch so every other session of this user becomes invalid. */
    public function setPassword(int $id, string $hash): int
    {
        $this->db->exec('UPDATE users SET password_hash = ?, session_epoch = session_epoch + 1 WHERE id = ?', [$hash, $id]);
        return (int) $this->db->value('SELECT session_epoch FROM users WHERE id = ?', [$id]);
    }
}
