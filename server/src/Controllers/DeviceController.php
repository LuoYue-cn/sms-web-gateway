<?php
declare(strict_types=1);

namespace SmsGate\Controllers;

use SmsGate\Http\Request;
use SmsGate\Http\Response;
use SmsGate\Services\DeviceService;

/** Bearer-token authenticated device endpoints. `$req->attributes['device']` is set by the kernel. */
final class DeviceController
{
    public function __construct(private DeviceService $svc)
    {
    }

    public function pair(Request $req): Response
    {
        return Response::json($this->svc->pair($req->json(), $req->ip), 201);
    }

    public function rotate(Request $req): Response
    {
        return Response::json($this->svc->rotate($this->id($req), $req->ip));
    }

    public function heartbeat(Request $req): Response
    {
        return Response::json($this->svc->heartbeat($this->id($req), $req->json(), $req->ip));
    }

    public function sync(Request $req): Response
    {
        return Response::json($this->svc->syncMessages($this->id($req), $req->json(), $req->ip));
    }

    public function status(Request $req): Response
    {
        return Response::json($this->svc->messageStatus($this->id($req), $req->json()));
    }

    public function poll(Request $req): Response
    {
        return Response::json($this->svc->poll($this->id($req), $req->queryInt('wait', 0, 0, DeviceService::POLL_MAX_WAIT)));
    }

    public function claim(Request $req): Response
    {
        return Response::json($this->svc->claim($this->id($req), $req->json())['task']);
    }

    public function result(Request $req): Response
    {
        return Response::json($this->svc->taskResult($this->id($req), $req->json())['task']);
    }

    public function saveProgress(Request $req): Response
    {
        return Response::json($this->svc->saveProgress($this->id($req), $req->json()) ?: new \stdClass());
    }

    public function progress(Request $req): Response
    {
        return Response::json($this->svc->progress($this->id($req)));
    }

    private function id(Request $req): int
    {
        return (int) $req->attributes['device']['id'];
    }
}
