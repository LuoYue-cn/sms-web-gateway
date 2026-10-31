<?php
declare(strict_types=1);

namespace SmsGate\Support;

/** 基于规则的短信归类、发件标签提取与号码脱敏。不联网，结果稳定可测。 */
final class SmsClassifier
{
    public const CATEGORIES = ['verification', 'bank', 'logistics', 'carrier', 'marketing', 'other'];

    public const LABELS = [
        'verification' => '验证码',
        'bank' => '银行/支付',
        'logistics' => '快递物流',
        'carrier' => '运营商',
        'marketing' => '营销推广',
        'other' => '其它通知',
    ];

    private const MARKETING_STRONG = '/退订|回[复覆]?\s*(?:T|TD|N|0000)\b|拒收|不再接收/iu';
    private const LOGISTICS = '/快递|取件|取货|包裹|驿站|菜鸟|顺丰|中通|圆通|韵达|申通|极兔|邮政|EMS|京东物流|丰巢|派送|派件|签收|运单|物流/iu';
    private const BANK = '/银行|信用卡|储蓄卡|借记卡|尾号\s*\d{3,4}|账户|余额|支出|收入|入账|转账|还款|账单|交易|消费|支付宝|微信支付|花呗|借呗|云闪付|信用额度|分期/u';
    private const CARRIER = '/话费|流量|套餐|中国移动|中国联通|中国电信|10086|10010|10000|宽带|充值|欠费|停机/u';
    private const MARKETING_WEAK = '/优惠|折扣|促销|活动|领取|红包|福利|会员|抢购|立减|优惠券|大促|特惠|秒杀|限时|免费|赢取|中奖/u';

    public static function category(string $text, ?string $code): string
    {
        if ($code !== null && $code !== '') {
            return 'verification';
        }
        if (preg_match(self::MARKETING_STRONG, $text) === 1) {
            return 'marketing';
        }
        if (preg_match(self::LOGISTICS, $text) === 1) {
            return 'logistics';
        }
        if (preg_match(self::CARRIER, $text) === 1) {
            return 'carrier';
        }
        if (preg_match(self::BANK, $text) === 1) {
            return 'bank';
        }
        if (preg_match(self::MARKETING_WEAK, $text) === 1) {
            return 'marketing';
        }
        return 'other';
    }

    /** 短信里的签名，如「【京东】」。没有则返回空串。 */
    public static function senderTag(string $text): string
    {
        return preg_match('/[【\[]([^】\]\n]{1,16})[】\]]/u', $text, $m) === 1 ? trim($m[1]) : '';
    }

    /** 138****8000；短号码和字母发件人原样返回。 */
    public static function maskAddress(string $address): string
    {
        $a = trim($address);
        if (preg_match('/^\+?[0-9][0-9 \-]{6,}$/', $a) !== 1) {
            return $a;
        }
        $digits = preg_replace('/[^0-9+]/', '', $a) ?? $a;
        if (strlen($digits) < 8) {
            return $a;
        }
        return substr($digits, 0, 3) . '****' . substr($digits, -4);
    }
}
