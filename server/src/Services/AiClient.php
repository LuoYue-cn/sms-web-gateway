<?php
declare(strict_types=1);

namespace SmsGate\Services;

use SmsGate\Repositories\SettingsRepository;

/**
 * OpenAI 兼容的 /chat/completions 客户端（OpenAI、DeepSeek、通义、智谱、Ollama 等都用这个协议）。
 * API Key 优先取环境变量 SMSGATE_AI_API_KEY（不进数据库），否则取设置里的值。Key 绝不出现在错误信息里。
 */
final class AiClient
{
    public const TIMEOUT_S = 60;
    public const MAX_ATTEMPTS = 3;

    public function __construct(private SettingsRepository $settings)
    {
    }

    public static function envKey(): string
    {
        $v = getenv('SMSGATE_AI_API_KEY');
        if (($v === false || $v === '') && isset($_SERVER['SMSGATE_AI_API_KEY']) && is_string($_SERVER['SMSGATE_AI_API_KEY'])) {
            $v = $_SERVER['SMSGATE_AI_API_KEY'];
        }
        return is_string($v) ? trim($v) : '';
    }

    public function apiKey(): string
    {
        $e = self::envKey();
        return $e !== '' ? $e : $this->settings->get('ai_api_key');
    }

    public function ready(): bool
    {
        return $this->settings->bool('ai_enabled') && $this->endpoint() !== '' && $this->settings->get('ai_model') !== '';
    }

    public function endpoint(): string
    {
        $b = rtrim(trim($this->settings->get('ai_base_url')), '/');
        if ($b === '') {
            return '';
        }
        $b = (string) preg_replace('#/chat/completions$#i', '', $b);
        return $b . '/chat/completions';
    }

    /**
     * @param list<array{role:string,content:string}> $messages
     * @return array{content:string,prompt_tokens:int,completion_tokens:int,model:string}
     */
    public function chat(array $messages, int $maxTokens = 1500, int $attempts = self::MAX_ATTEMPTS, float $temperature = 0.3): array
    {
        $url = $this->endpoint();
        if ($url === '' || $this->settings->get('ai_model') === '') {
            throw new AiException('尚未配置 AI 服务地址或模型名称');
        }
        if (preg_match('#^https?://#i', $url) !== 1) {
            throw new AiException('AI 服务地址必须以 http:// 或 https:// 开头');
        }
        $payload = json_encode([
            'model' => $this->settings->get('ai_model'),
            'messages' => $messages,
            'temperature' => $temperature,
            'max_tokens' => $maxTokens,
            'stream' => false,
        ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE);

        $last = '';
        for ($i = 1; $i <= $attempts; $i++) {
            [$status, $body, $netErr] = $this->post($url, (string) $payload);
            if ($status >= 200 && $status < 300) {
                return $this->parse($body);
            }
            $last = $netErr !== '' ? $netErr : $this->httpError($status, $body);
            // 4xx（除 408/429）是配置或请求本身的问题，重试没有意义
            if ($status >= 400 && $status < 500 && $status !== 408 && $status !== 429) {
                break;
            }
            if ($i < $attempts) {
                sleep($i * 2);
            }
        }
        throw new AiException($this->scrub($last));
    }

    /** @return array{0:int,1:string,2:string} status, body, network error */
    private function post(string $url, string $payload): array
    {
        $headers = ['Content-Type: application/json', 'Accept: application/json'];
        $key = $this->apiKey();
        if ($key !== '') {
            $headers[] = 'Authorization: Bearer ' . $key;
        }
        if (function_exists('curl_init')) {
            $ch = curl_init($url);
            curl_setopt_array($ch, [
                CURLOPT_POST => true, CURLOPT_POSTFIELDS => $payload, CURLOPT_HTTPHEADER => $headers,
                CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => self::TIMEOUT_S, CURLOPT_CONNECTTIMEOUT => 10,
                CURLOPT_FOLLOWLOCATION => false, CURLOPT_PROTOCOLS => CURLPROTO_HTTP | CURLPROTO_HTTPS,
            ]);
            $body = curl_exec($ch);
            $status = (int) curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
            $err = $body === false ? curl_error($ch) : '';
            curl_close($ch);
            return [$status, is_string($body) ? $body : '', $err !== '' ? '连接 AI 服务失败：' . $err : ''];
        }
        $ctx = stream_context_create(['http' => [
            'method' => 'POST', 'header' => implode("\r\n", $headers), 'content' => $payload,
            'timeout' => self::TIMEOUT_S, 'ignore_errors' => true, 'follow_location' => 0,
        ]]);
        $body = @file_get_contents($url, false, $ctx);
        if ($body === false) {
            return [0, '', '连接 AI 服务失败（网络不通、证书无效，或 PHP 未启用 openssl / allow_url_fopen）'];
        }
        $status = 0;
        foreach ($http_response_header ?? [] as $h) {
            if (preg_match('#^HTTP/\S+\s+(\d{3})#', $h, $m) === 1) {
                $status = (int) $m[1];
            }
        }
        return [$status, $body, ''];
    }

    /** @return array{content:string,prompt_tokens:int,completion_tokens:int,model:string} */
    private function parse(string $body): array
    {
        $j = json_decode($body, true);
        $content = is_array($j) ? ($j['choices'][0]['message']['content'] ?? null) : null;
        if (!is_string($content) || trim($content) === '') {
            throw new AiException('AI 服务返回了无法识别的内容（请确认地址是 OpenAI 兼容的 /v1 接口）');
        }
        return [
            'content' => $content,
            'prompt_tokens' => (int) ($j['usage']['prompt_tokens'] ?? 0),
            'completion_tokens' => (int) ($j['usage']['completion_tokens'] ?? 0),
            'model' => is_string($j['model'] ?? null) ? $j['model'] : $this->settings->get('ai_model'),
        ];
    }

    private function httpError(int $status, string $body): string
    {
        $j = json_decode($body, true);
        $msg = is_array($j) ? ($j['error']['message'] ?? $j['message'] ?? '') : '';
        $hint = match (true) {
            $status === 401, $status === 403 => '（API Key 无效或没有权限）',
            $status === 404 => '（地址或模型名称不对）',
            $status === 429 => '（请求过于频繁或额度用完）',
            default => '',
        };
        return 'AI 服务返回 HTTP ' . $status . $hint . (is_string($msg) && $msg !== '' ? '：' . mb_substr($msg, 0, 200) : '');
    }

    private function scrub(string $s): string
    {
        $k = $this->apiKey();
        return $k !== '' ? str_replace($k, '***', $s) : $s;
    }
}
