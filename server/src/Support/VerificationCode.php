<?php
declare(strict_types=1);

namespace SmsGate\Support;

/**
 * 从短信正文提取验证码。思路沿用成熟的开源实现（XposedSmsCode）：
 * 先找「验证码 / code / OTP」之类的关键词，再在关键词附近挑候选串，按格式（6 位数字 > 4 位数字 > 其它）和距离排序。
 * 在它的基础上更保守：没有关键词一律不识别；金额、日期、时间、卡号、电话、订单号等一律排除。
 * 规则变化时递增 VERSION，bin/backfill_codes.php --all 会重新扫描历史短信。
 */
final class VerificationCode
{
    public const VERSION = 1;
    /** 候选串与关键词之间最多隔多少个字符 */
    private const NEAR = 30;
    private const FAR = 80;

    private const KW_CN = '验证码|校验码|检验码|确认码|激活码|动态码|安全码|登录码|登入码|认证码|识别码|授权码|'
        . '验证代码|校验代码|检验代码|激活代码|确认代码|动态代码|安全代码|短信口令|动态密码|动态口令|随机码|交易码|上网密码|'
        . '驗證碼|校驗碼|檢驗碼|確認碼|激活碼|動態碼|安全碼|登入碼|認證碼|識別碼|驗證代碼|校驗代碼|確認代碼|動態代碼';
    private const KW_EN = 'verification code|confirmation code|security code|authentication code|login code|sign-?in code|'
        . 'one[- ]time (?:password|passcode|code|pin)|passcode|otp|code|pin|kod|код|mã';
    /** 这些词紧挨在 code 前面时，说的是邮编、优惠码之类，不是验证码 */
    private const NOT_CODE_PREFIX = '/(?:zip|postal|post|promo|promotion|coupon|discount|area|country|dial|tracking|source|referral|invite)\s*$/i';

    /** 紧跟在数字后面表示「这是个数量」的单位 */
    private const UNIT_AFTER = '/^\s?(?:元|块|圆|分钟|分|秒|小时|时|天|日|月|年|周|%|％|折|次|条|个|人|笔|件|人民币|万|亿|rmb|usd|cny|yuan|mins?|minutes?|seconds?|secs?|hours?|hrs?|days?|gb|mb|kb|km|kg|am|pm)(?![A-Za-z])/iu';
    /** 数字前面的标签表示它是别的编号 */
    private const LABEL_BEFORE = '/(?:尾号|卡号|订单号?|单号|运单号?|电话|热线|客服|拨打|致电|编号|账号|账户|手机号|金额|余额|合计|共计|流水号?|身份证|工号|房号|车牌|order|tel|phone|call|card|account|ending(?: in)?)\s*[为是:：#]?\s*[A-Za-z]{0,2}$/iu';

    public static function extract(string $text): ?string
    {
        $text = trim(mb_substr($text, 0, 1000));
        if ($text === '' || !mb_check_encoding($text, 'UTF-8')) {
            return null;
        }
        // 「123 456」「123-456」这种分组写法合并成一个整体
        $text = (string) preg_replace('/(?<![0-9])([0-9]{3})[ \-]([0-9]{3})(?![0-9])/', '$1$2', $text);

        $keywords = self::keywords($text);
        if ($keywords === []) {
            return null;
        }
        $cjk = preg_match('/[\x{4e00}-\x{9fff}]/u', $text) === 1;
        $pattern = $cjk
            ? '/(?<![A-Za-z0-9])[A-Za-z0-9]{4,8}(?![A-Za-z0-9])/u'
            : '/(?<![0-9])[0-9]{4,8}(?![0-9])/u';
        if (preg_match_all($pattern, $text, $m, PREG_OFFSET_CAPTURE) < 1) {
            return null;
        }

        $cands = [];
        foreach ($m[0] as [$cand, $byteOff]) {
            if (preg_match('/[0-9]/', $cand) !== 1) {
                continue; // 纯字母不可能是验证码
            }
            if (self::looksLikeSomethingElse($text, $byteOff, $byteOff + strlen($cand))) {
                continue;
            }
            $start = mb_strlen(substr($text, 0, $byteOff));
            $cands[] = [$cand, $start, self::distance($start, $start + mb_strlen($cand), $keywords)];
        }
        // 先只看关键词附近的；都不在附近时，放宽到 FAR 并且只接受纯数字（英文短信里关键词常离验证码较远）
        foreach ([[self::NEAR, false], [self::FAR, true]] as [$limit, $digitsOnly]) {
            $best = null;
            foreach ($cands as [$cand, $start, $dist]) {
                if ($dist > $limit || ($digitsOnly && preg_match('/^[0-9]+$/', $cand) !== 1)) {
                    continue;
                }
                $key = [self::level($cand), -$dist, -$start];
                if ($best === null || $key > $best[0]) {
                    $best = [$key, $cand];
                }
            }
            if ($best !== null) {
                return $best[1];
            }
        }
        return null;
    }

    /** @return list<array{0:int,1:int}> 关键词的 [起, 止) 字符位置 */
    private static function keywords(string $text): array
    {
        $re = '/(?:' . self::KW_CN . ')|(?<![A-Za-z])(?:' . self::KW_EN . ')(?![A-Za-z])/iu';
        if (preg_match_all($re, $text, $m, PREG_OFFSET_CAPTURE) < 1) {
            return [];
        }
        $out = [];
        foreach ($m[0] as [$word, $byteOff]) {
            $before = substr($text, max(0, $byteOff - 16), min(16, $byteOff));
            if (preg_match('/^[A-Za-z]/', $word) === 1 && preg_match(self::NOT_CODE_PREFIX, $before) === 1) {
                continue;
            }
            $start = mb_strlen(substr($text, 0, $byteOff));
            $out[] = [$start, $start + mb_strlen($word)];
        }
        return $out;
    }

    /** @param list<array{0:int,1:int}> $keywords */
    private static function distance(int $start, int $end, array $keywords): int
    {
        $best = PHP_INT_MAX;
        foreach ($keywords as [$ks, $ke]) {
            $d = $start >= $ke ? $start - $ke : ($end <= $ks ? $ks - $end : 0);
            $best = min($best, $d);
        }
        return $best;
    }

    private static function level(string $c): int
    {
        if (preg_match('/^[0-9]{6}$/', $c) === 1) {
            return 4;
        }
        if (preg_match('/^[0-9]{4}$/', $c) === 1) {
            return 3;
        }
        return preg_match('/^[0-9]+$/', $c) === 1 ? 2 : 1;
    }

    /** 候选串的前后文表明它是金额、日期、时间、小数、电话、卡号后四位、订单号等。 */
    private static function looksLikeSomethingElse(string $text, int $byteStart, int $byteEnd): bool
    {
        $after = (string) mb_strcut($text, $byteEnd, 16, 'UTF-8');
        $before = (string) mb_strcut($text, max(0, $byteStart - 36), min(36, $byteStart), 'UTF-8');

        if (preg_match(self::UNIT_AFTER, $after) === 1) {
            return true;
        }
        // 小数 / 千分位 / 时间 / 日期 / 比分
        if (preg_match('/^(?:\.[0-9]|,[0-9]{3}(?![0-9])|[:：\/\-][0-9])/u', $after) === 1) {
            return true;
        }
        if (preg_match('/[0-9][.,:：\/\-]$|[¥￥$＄€£]\s*$|[\/@]$/u', $before) === 1) {
            return true;
        }
        return preg_match(self::LABEL_BEFORE, $before) === 1;
    }
}
