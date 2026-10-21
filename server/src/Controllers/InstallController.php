<?php
declare(strict_types=1);

namespace SmsGate\Controllers;

use SmsGate\Http\Request;
use SmsGate\Http\Response;
use SmsGate\Services\InstallService;

final class InstallController
{
    public function __construct(private InstallService $install)
    {
    }

    public function status(Request $req): Response
    {
        if ($this->install->isInstalled()) {
            return Response::json(['installed' => true]);
        }
        return Response::json(['installed' => false, 'checks' => $this->install->checks($this->host($req), $req->secure)]);
    }

    public function install(Request $req): Response
    {
        $this->install->install($req->json(), $this->host($req), $req->secure);
        return Response::json(['installed' => true], 201);
    }

    private function host(Request $req): ?string
    {
        $h = $req->header('host');
        // 只接受 host[:port]，避免把请求头当作任意 URL 去探测
        return preg_match('/^[A-Za-z0-9.\-\[\]:]+$/', $h) === 1 ? $h : null;
    }
}
