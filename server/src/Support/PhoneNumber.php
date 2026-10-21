<?php
declare(strict_types=1);

namespace SmsGate\Support;

/** Phone number validation and canonical keys. No country is assumed. */
final class PhoneNumber
{
    /** Normalise a number meant for SENDING. Returns null if invalid. */
    public static function normalizeForSend(string $raw): ?string
    {
        $n = preg_replace('/[\s\-().]/u', '', trim($raw)) ?? '';
        return preg_match('/^\+?[0-9]{3,20}$/', $n) === 1 ? $n : null;
    }

    /** True if the raw string looks like several recipients. */
    public static function looksLikeMultiple(string $raw): bool
    {
        return preg_match('/[,;，；、\r\n]/u', $raw) === 1;
    }

    /** Normalise a stored/incoming address (may be an alphanumeric sender id). */
    public static function normalizeAddress(string $raw): string
    {
        $raw = trim($raw);
        $n = preg_replace('/[\s\-().]/u', '', $raw) ?? '';
        if ($n !== '' && preg_match('/^\+?[0-9]+$/', $n) === 1) {
            return mb_substr($n, 0, 32);
        }
        return mb_substr($raw, 0, 32);
    }

    /**
     * Key used to merge "13800138000" and "+8613800138000" into one conversation:
     * the last 9 digits for numeric numbers of 10+ digits, otherwise the lowercased address.
     */
    public static function key(string $address): string
    {
        $a = self::normalizeAddress($address);
        if (preg_match('/^\+?([0-9]+)$/', $a, $m) === 1) {
            return strlen($m[1]) >= 10 ? substr($m[1], -9) : $m[1];
        }
        return mb_strtolower($a);
    }

    /** @return list<string> subset of ['international','premium_suspect'] */
    public static function warnings(string $normalized, string $homeCountryCode): array
    {
        $w = [];
        if ($normalized[0] === '+') {
            if ($homeCountryCode === '' || strncmp($normalized, $homeCountryCode, strlen($homeCountryCode)) !== 0) {
                $w[] = 'international';
            }
        } else {
            $len = strlen($normalized);
            if ($len >= 4 && $len <= 6) {
                $w[] = 'premium_suspect';
            } elseif ($len >= 7 && preg_match('/^(400|900|106)/', $normalized) === 1) {
                $w[] = 'premium_suspect';
            }
        }
        return $w;
    }
}
