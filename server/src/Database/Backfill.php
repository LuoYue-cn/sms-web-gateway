<?php
declare(strict_types=1);

namespace SmsGate\Database;

use SmsGate\Support\SmsClassifier;
use SmsGate\Support\VerificationCode;

/**
 * 给还没按当前规则处理过的收件短信补上验证码与类别。
 * 升级后第一次请求自动跑（每次最多 $budget 行，剩余的下次请求继续）；bin/backfill_codes.php 可一次跑完或强制重扫。
 */
final class Backfill
{
    /** @return int 本次处理的行数 */
    public static function codes(Database $db, int $budget = 20000): int
    {
        $ver = VerificationCode::VERSION;
        if ($db->value("SELECT 1 FROM messages WHERE direction = 'incoming' AND code_ver < ? LIMIT 1", [$ver]) === null) {
            return 0;
        }
        $done = 0;
        while ($done < $budget) {
            $n = $db->transaction(function () use ($db, $ver): int {
                $rows = $db->all("SELECT id, conversation_id, content FROM messages WHERE direction = 'incoming' AND code_ver < ? ORDER BY id LIMIT 1000", [$ver]);
                $touched = [];
                foreach ($rows as $r) {
                    $code = VerificationCode::extract((string) $r['content']);
                    $db->exec('UPDATE messages SET code = ?, category = ?, code_ver = ? WHERE id = ?', [
                        $code, SmsClassifier::category((string) $r['content'], $code), $ver, $r['id'],
                    ]);
                    $touched[(int) $r['conversation_id']] = true;
                }
                foreach (array_keys($touched) as $cid) {
                    $db->exec(
                        'UPDATE conversations SET last_code = (SELECT code FROM messages WHERE conversation_id = ? ORDER BY timestamp DESC, id DESC LIMIT 1) WHERE id = ?',
                        [$cid, $cid]
                    );
                }
                return count($rows);
            });
            if ($n === 0) {
                break;
            }
            $done += $n;
        }
        return $done;
    }

    /** 规则大改后强制重扫全部收件短信 */
    public static function reset(Database $db): void
    {
        $db->exec("UPDATE messages SET code_ver = 0 WHERE direction = 'incoming'");
    }
}
