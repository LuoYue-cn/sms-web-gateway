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
        return Response::json(['installed' => false, 'checks' => $this->install->checks()]);
    }

    public function install(Request $req): Response
    {
        $this->install->install($req->json());
        return Response::json(['installed' => true], 201);
    }
}
