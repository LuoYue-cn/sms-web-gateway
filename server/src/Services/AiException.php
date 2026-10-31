<?php
declare(strict_types=1);

namespace SmsGate\Services;

/** AI 服务调用失败。消息可直接展示给管理员（已去除 API Key）。 */
final class AiException extends \RuntimeException
{
}
