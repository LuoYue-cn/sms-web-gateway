<?php
declare(strict_types=1);

// Usage: php server/tests/extractor_test.php
require __DIR__ . '/../src/autoload.php';

use SmsGate\Support\Redactor;
use SmsGate\Support\SmsClassifier;
use SmsGate\Support\VerificationCode;

/** [短信正文, 期望的验证码或 null] */
$codeCases = [
    // ---- 应识别 ----
    ['【京东】验证码 835261，5分钟内有效，请勿泄露给他人。', '835261'],
    ['【支付宝】验证码：4096。您正在登录，请勿转发。', '4096'],
    ['您的验证码是 123456，有效期10分钟。如非本人操作请忽略。', '123456'],
    ['【腾讯云】尊敬的用户，您的短信验证码为：908172，15分钟内有效。', '908172'],
    ['验证码 123 456，请在页面输入', '123456'],
    ['【美团】动态码 7719，您正在登录美团账号，请勿告诉他人', '7719'],
    ['【中国银行】您正在进行转账交易，动态密码 20481935，尾号1234账户，请勿泄露', '20481935'],
    ['【微信】微信验证码：582930。请勿转发，如非本人操作请忽略', '582930'],
    ['【淘宝】校验码 A8K2M9，用于找回密码', 'A8K2M9'],
    ['【携程】登录验证码：264810，工作人员不会索取。2024年6月1日有效', '264810'],
    ['Your verification code is 482915. Do not share it with anyone.', '482915'],
    ['G-482915 is your Google verification code.', '482915'],
    ['<#> 123456 is your Facebook code', '123456'],
    ['Your Uber code is 7731. Never share this code.', '7731'],
    ['Use code 998877 to sign in to Microsoft account.', '998877'],
    ['[Apple] Your Apple ID Code is: 394 028. Don\'t share it with anyone.', '394028'],
    ['Your OTP for txn of Rs.2,500.00 at AMAZON is 561209. Valid for 10 mins.', '561209'],
    ['Your one-time password is 20194832', '20194832'],
    ['【网易】您的验证码：6612（5分钟内有效），工作人员不会向您索要', '6612'],
    ['Ваш код подтверждения: 5521', '5521'],
    ['【顺丰】您的取件验证码 7392，请凭码取件', '7392'],
    ['验证码：8855\n有效期5分钟', '8855'],
    ['【招商银行】验证码 301847，您正在登录手机银行，付款 1,000.00元 不是您本人操作请忽略', '301847'],
    // ---- 不应识别 ----
    ['【中国移动】您的话费余额为 1234.56 元，本月流量已使用 2048 MB。', null],
    ['【中国银行】您尾号1234的账户于10月3日支出 5000.00 元，余额 12345.67 元。', null],
    ['【顺丰速运】您的快递单号 SF1234567890123 已签收，如有疑问请拨打 95338。', null],
    ['【菜鸟驿站】您的包裹已到，取件码 5-3-1042，请于18:30前取件。', null],
    ['下午3点会议改到 2024-06-01 15:30，会议室 4096 号', null],
    ['我的新号码是 13812345678，存一下', null],
    ['妈，我晚点到家，大概 8点左右，别等我吃饭', null],
    ['【某商城】双11大促，满 2000 减 300，优惠码 SAVE2024，回T退订', null],
    ['Your order #483920 has shipped. Track at example.com', null],
    ['Zip code 94043 is the headquarters location', null],
    ['验证码', null],
    ['', null],
    ['【建设银行】您尾号 5678 的信用卡账单日为 15 号，本期应还 3,280.50 元，请于 10月 28日前还款', null],
    ['温馨提示：本次活动有效期 30 天，共 1000 个名额', null],
];

$fail = 0;
foreach ($codeCases as $i => [$text, $want]) {
    $got = VerificationCode::extract($text);
    if ($got !== $want) {
        $fail++;
        fwrite(STDERR, sprintf("FAIL code #%d want=%s got=%s\n   %s\n", $i, var_export($want, true), var_export($got, true), $text));
    }
}

$catCases = [
    ['【京东】验证码 835261', '835261', 'verification'],
    ['【顺丰速运】您的快递已签收', null, 'logistics'],
    ['【中国银行】您尾号1234的账户支出 5000 元', null, 'bank'],
    ['【中国移动】您的话费余额不足，请及时充值', null, 'carrier'],
    ['【某商城】限时特惠 全场5折 回T退订', null, 'marketing'],
    ['晚上一起吃饭吗', null, 'other'],
];
foreach ($catCases as $i => [$text, $code, $want]) {
    $got = SmsClassifier::category($text, $code);
    if ($got !== $want) {
        $fail++;
        fwrite(STDERR, "FAIL category #$i want=$want got=$got\n   $text\n");
    }
}

$tagCases = [['【京东】验证码 1', '京东'], ['[Apple] code', 'Apple'], ['无签名', '']];
foreach ($tagCases as $i => [$text, $want]) {
    if (SmsClassifier::senderTag($text) !== $want) {
        $fail++;
        fwrite(STDERR, "FAIL tag #$i\n");
    }
}
foreach ([['13812345678', '138****5678'], ['+8613812345678', '+86****5678'], ['10086', '10086'], ['JD-SHOP', 'JD-SHOP']] as $i => [$in, $want]) {
    $got = SmsClassifier::maskAddress($in);
    if ($got !== $want) {
        $fail++;
        fwrite(STDERR, "FAIL mask #$i want=$want got=$got\n");
    }
}

$redactCases = [
    ['【中国银行】尾号1234账户支出 5000.00元，验证码 835261，电话13812345678', '835261', ['835261', '1234', '5000', '13812345678']],
    ['您的取件码 5-3-1042，订单 SF1234567890，详见 https://t.cn/abc', null, ['1042', 'SF1234567890', 'https://']],
    ['联系 foo@bar.com，身份证 110101199003071234', null, ['foo@bar.com', '110101199003071234']],
];
foreach ($redactCases as $i => [$text, $code, $mustNotContain]) {
    $out = Redactor::redact($text, $code);
    foreach ($mustNotContain as $bad) {
        if (str_contains($out, $bad)) {
            $fail++;
            fwrite(STDERR, "FAIL redact #$i still contains '$bad': $out\n");
        }
    }
}

if ($fail > 0) {
    fwrite(STDERR, "\n$fail case(s) failed\n");
    exit(1);
}
echo 'extractor tests: ' . (count($codeCases) + count($catCases) + count($tagCases) + 4 + count($redactCases)) . " groups OK\n";
