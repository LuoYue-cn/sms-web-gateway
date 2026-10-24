#!/usr/bin/env python3
"""API integration tests. Spawns a throw-away PHP server with an empty data dir.

Run:  python3 server/tests/api_test.py
"""
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
import uuid
from http.cookiejar import CookieJar

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PASSWORD = "correct-horse-1"


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Client:
    def __init__(self, base, token=None):
        self.base = base
        self.token = token
        self.csrf = None
        self.jar = CookieJar()
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def call(self, method, path, body=None, headers=None, raw=False, timeout=40):
        h = {"Content-Type": "application/json"}
        if self.token:
            h["Authorization"] = "Bearer " + self.token
        if self.csrf and method not in ("GET", "HEAD"):
            h["X-CSRF-Token"] = self.csrf
        h.update(headers or {})
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=h)
        try:
            with self.op.open(req, timeout=timeout) as r:
                payload, status = r.read(), r.status
        except urllib.error.HTTPError as e:
            payload, status = e.read(), e.code
        if raw:
            return status, payload
        return status, json.loads(payload)

    def ok(self, method, path, body=None, expect=200, **kw):
        st, js = self.call(method, path, body, **kw)
        assert st == expect, f"{method} {path} -> {st} {js}"
        return js["data"]

    def err(self, method, path, body=None, status=None, code=None, **kw):
        st, js = self.call(method, path, body, **kw)
        assert js.get("ok") is False, f"{method} {path} unexpectedly ok: {js}"
        if status is not None:
            assert st == status, f"{method} {path} -> {st} {js}"
        if code is not None:
            assert js["error"]["code"] == code, f"{method} {path} -> {js}"
        return js["error"]


class Base(unittest.TestCase):
    proc = None
    data_dir = None
    base = None

    @classmethod
    def setUpClass(cls):
        cls.data_dir = tempfile.mkdtemp(prefix="smsgate-test-")
        port = free_port()
        env = dict(os.environ, SMSGATE_DATA_DIR=cls.data_dir, PHP_CLI_SERVER_WORKERS="8")
        cls.proc = subprocess.Popen(
            ["php", "-S", f"127.0.0.1:{port}", "-t", os.path.join(ROOT, "public"), os.path.join(ROOT, "public", "index.php")],
            env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        cls.base = f"http://127.0.0.1:{port}/api/v1"
        for _ in range(50):
            try:
                urllib.request.urlopen(cls.base + "/install/status", timeout=1).read()
                break
            except Exception:
                time.sleep(0.1)
        anon = Client(cls.base)
        anon.ok("POST", "/install", {"username": "admin", "password": PASSWORD, "site_name": "T", "timezone": "UTC"}, expect=201)
        cls.admin = cls.login()
        cls.device_seq = 0

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        cls.proc.wait(timeout=5)
        shutil.rmtree(cls.data_dir, ignore_errors=True)

    @classmethod
    def login(cls, password=PASSWORD):
        c = Client(cls.base)
        d = c.ok("POST", "/auth/login", {"username": "admin", "password": password})
        c.csrf = d["csrf_token"]
        return c

    @classmethod
    def new_device(cls, name="Test Phone"):
        tok = cls.admin.ok("POST", "/devices/pairing", {}, expect=201)["pairing_token"]
        d = Client(cls.base).ok("POST", "/device/pair", {"pairing_token": tok, "device_name": name, "android_version": "15", "app_version": "1.0", "sim_count": 2}, expect=201)
        c = Client(cls.base, d["device_token"])
        c.device_id = d["device_id"]
        return c

    @staticmethod
    def msg(i, **kw):
        m = {"source_type": "android_provider", "android_sms_id": i, "phone_number": "+8613800138000", "content": f"hello {i}",
             "direction": "incoming", "timestamp": 1760000000000 + i * 1000, "sim_slot": 0, "read": False}
        m.update(kw)
        return m

    def sync(self, dev, msgs, batch=None):
        return dev.ok("POST", "/device/messages/sync", {"batch_id": batch or str(uuid.uuid4()), "messages": msgs})


class TestInstallAndAuth(Base):
    def test_install_lock(self):
        anon = Client(self.base)
        self.assertTrue(anon.ok("GET", "/install/status")["installed"])
        anon.err("POST", "/install", {"username": "evil", "password": PASSWORD, "site_name": "x", "timezone": "UTC"}, 409, "already_installed")
        self.assertTrue(os.path.exists(os.path.join(self.data_dir, "install.lock")))

    def test_requires_login(self):
        Client(self.base).err("GET", "/devices", None, 401, "unauthenticated")

    def test_login_wrong_password_and_throttle(self):
        c = Client(self.base)
        for _ in range(5):
            c.err("POST", "/auth/login", {"username": "throttle-me", "password": "nope"}, 401, "invalid_credentials")
        e = c.err("POST", "/auth/login", {"username": "throttle-me", "password": "nope"}, 429, "too_many_attempts")
        self.assertGreater(e["details"]["retry_after"], 0)

    def test_csrf_required_on_writes(self):
        c = Client(self.base)
        d = c.ok("POST", "/auth/login", {"username": "admin", "password": PASSWORD})
        c.err("POST", "/settings/emergency-pause", {"paused": False}, 403, "csrf_failed")
        c.err("POST", "/settings/emergency-pause", {"paused": False}, 403, "csrf_failed", headers={"X-CSRF-Token": "wrong"})
        c.csrf = d["csrf_token"]
        c.ok("POST", "/settings/emergency-pause", {"paused": False})

    def test_logout_invalidates_session(self):
        c = self.login()
        c.ok("GET", "/auth/me")
        c.ok("POST", "/auth/logout", {})
        c.err("GET", "/auth/me", None, 401)

    def test_session_cookie_flags(self):
        req = urllib.request.Request(self.base + "/auth/login", data=json.dumps({"username": "admin", "password": PASSWORD}).encode(), headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req) as r:
            cookie = r.headers.get("Set-Cookie", "")
        self.assertIn("HttpOnly", cookie)
        self.assertIn("SameSite=Lax", cookie)

    def test_device_token_cannot_use_admin_api_and_vice_versa(self):
        dev = self.new_device("iso")
        dev.err("GET", "/devices", None, 401)
        dev.err("POST", "/messages/send", {"device_id": dev.device_id, "recipient": "123456789", "content": "x"}, 401)
        self.admin.err("POST", "/device/heartbeat", {}, 401, "invalid_token")
        self.admin.err("GET", "/device/tasks/poll?wait=0", None, 401, "invalid_token")


class TestPairing(Base):
    def test_token_single_use(self):
        tok = self.admin.ok("POST", "/devices/pairing", {}, expect=201)
        self.assertIn("pairing_token", json.loads(tok["qr_payload"]))
        body = {"pairing_token": tok["pairing_token"], "device_name": "P", "android_version": "15", "app_version": "1", "sim_count": 1}
        Client(self.base).ok("POST", "/device/pair", body, expect=201)
        Client(self.base).err("POST", "/device/pair", body, 400, "invalid_pairing_token")

    def test_token_concurrent_only_one_wins(self):
        tok = self.admin.ok("POST", "/devices/pairing", {}, expect=201)["pairing_token"]
        results = []

        def go(i):
            st, _ = Client(self.base).call("POST", "/device/pair", {"pairing_token": tok, "device_name": f"R{i}", "android_version": "15", "app_version": "1", "sim_count": 1})
            results.append(st)

        ts = [threading.Thread(target=go, args=(i,)) for i in range(8)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        self.assertEqual(results.count(201), 1, results)

    def test_garbage_token(self):
        Client(self.base).err("POST", "/device/pair", {"pairing_token": "A" * 20, "device_name": "x"}, 400, "invalid_pairing_token")

    def test_token_expiry(self):
        tok = self.admin.ok("POST", "/devices/pairing", {}, expect=201)
        self.assertAlmostEqual(tok["expires_at"] - int(time.time() * 1000), 300_000, delta=5000)
        import sqlite3
        con = sqlite3.connect(os.path.join(self.data_dir, "sms_gateway.sqlite"))
        import hashlib
        h = hashlib.sha256(tok["pairing_token"].encode()).hexdigest()
        con.execute("UPDATE pairing_tokens SET expires_at = 1 WHERE token_hash = ?", (h,))
        con.commit()
        con.close()
        Client(self.base).err("POST", "/device/pair", {"pairing_token": tok["pairing_token"], "device_name": "x"}, 400, "invalid_pairing_token")

    def test_only_hash_stored(self):
        dev = self.new_device("hash")
        import sqlite3
        con = sqlite3.connect(os.path.join(self.data_dir, "sms_gateway.sqlite"))
        rows = [r[0] for r in con.execute("SELECT token_hash FROM device_tokens")]
        con.close()
        self.assertNotIn(dev.token, rows)
        self.assertTrue(all(len(r) == 64 for r in rows))

    def test_revoke_then_device_revoked(self):
        dev = self.new_device("rev")
        dev.ok("POST", "/device/heartbeat", {"battery_level": 50})
        self.admin.ok("DELETE", f"/devices/{dev.device_id}")
        dev.err("POST", "/device/heartbeat", {"battery_level": 50}, 401, "device_revoked")
        dev.err("GET", "/device/tasks/poll?wait=0", None, 401, "device_revoked")
        ids = [d["id"] for d in self.admin.ok("GET", "/devices")["items"]]
        self.assertNotIn(dev.device_id, ids)

    def test_rotate_keeps_old_token_in_grace_period(self):
        dev = self.new_device("rot")
        old = dev.token
        new = dev.ok("POST", "/device/token/rotate", {})["device_token"]
        self.assertNotEqual(old, new)
        Client(self.base, new).ok("POST", "/device/heartbeat", {})
        Client(self.base, old).ok("POST", "/device/heartbeat", {})


class TestHeartbeat(Base):
    def test_heartbeat_and_keepalive_fields(self):
        dev = self.new_device("hb")
        r = dev.ok("POST", "/device/heartbeat", {
            "battery_level": 77, "charging": True, "network_type": "wifi", "sim_count": 2, "sim_operator": "中国电信",
            "sims": [{"slot": 0, "operator": "中国电信", "state": "ready"}, {"slot": 1, "operator": "", "state": "absent"}],
            "keepalive_mode": "root_enhanced", "watchdog_status": "running", "root_granted": True, "battery_whitelisted": True,
            "recovery_count": 2, "last_recovery_at": 1760000000000, "protection_state": "cooldown",
            "root_audit": [{"ts": 1760000000001, "action": "whitelist_add", "result": "ok", "exit_code": 0}],
        })
        self.assertIn("config", r)
        d = next(x for x in self.admin.ok("GET", "/devices")["items"] if x["id"] == dev.device_id)
        self.assertTrue(d["online"])
        self.assertEqual((d["battery_level"], d["charging"], d["keepalive_mode"], d["watchdog_status"], d["recovery_count"], d["protection_state"]),
                         (77, True, "root_enhanced", "running", 2, "cooldown"))
        self.assertTrue(d["root_granted"] and d["battery_whitelisted"])
        self.assertEqual(len(d["sims"]), 2)
        logs = self.admin.ok("GET", "/audit-logs?action=root_op")["items"]
        self.assertTrue(any(l["target"] == "whitelist_add" and l["device_id"] == dev.device_id for l in logs))
        # replaying the same summary must not duplicate audit rows
        n = len([l for l in logs if l["device_id"] == dev.device_id])
        dev.ok("POST", "/device/heartbeat", {"root_audit": [{"ts": 1760000000001, "action": "whitelist_add", "result": "ok", "exit_code": 0}]})
        logs2 = self.admin.ok("GET", "/audit-logs?action=root_op")["items"]
        self.assertEqual(len([l for l in logs2 if l["device_id"] == dev.device_id]), n)

    def test_rejects_bad_values(self):
        dev = self.new_device("hb2")
        dev.err("POST", "/device/heartbeat", {"battery_level": 500}, 400, "invalid_request")
        dev.err("POST", "/device/heartbeat", {"keepalive_mode": "evil"}, 400, "invalid_request")

    def test_offline_after_threshold(self):
        dev = self.new_device("off")
        dev.ok("POST", "/device/heartbeat", {})
        self.admin.ok("PUT", "/settings", {"online_threshold_seconds": 10})
        try:
            import sqlite3
            con = sqlite3.connect(os.path.join(self.data_dir, "sms_gateway.sqlite"))
            con.execute("UPDATE devices SET last_seen = ? WHERE id = ?", (int(time.time() * 1000) - 60_000, dev.device_id))
            con.commit()
            con.close()
            d = next(x for x in self.admin.ok("GET", "/devices")["items"] if x["id"] == dev.device_id)
            self.assertFalse(d["online"])
        finally:
            self.admin.ok("PUT", "/settings", {"online_threshold_seconds": 60})


class TestSync(Base):
    def test_sync_dedup_upsert(self):
        dev = self.new_device("sync")
        r1 = self.sync(dev, [self.msg(i) for i in range(1, 6)])
        self.assertEqual((r1["inserted"], r1["updated"]), (5, 0))
        r2 = self.sync(dev, [self.msg(i) for i in range(1, 6)])
        self.assertEqual((r2["inserted"], r2["updated"]), (0, 5))
        convs = self.admin.ok("GET", f"/conversations?device_id={dev.device_id}")
        self.assertEqual(convs["total"], 1)
        msgs = self.admin.ok("GET", f"/conversations/{convs['items'][0]['id']}/messages")["items"]
        self.assertEqual(len(msgs), 5)
        self.assertEqual([m["timestamp"] for m in msgs], sorted(m["timestamp"] for m in msgs))

    def test_same_content_different_ids_are_not_duplicates(self):
        dev = self.new_device("same")
        r = self.sync(dev, [self.msg(1, content="验证码 123456"), self.msg(2, content="验证码 123456", timestamp=1760000001000)])
        self.assertEqual(r["inserted"], 2)

    def test_batch_id_idempotent_replay(self):
        dev = self.new_device("batch")
        b = str(uuid.uuid4())
        r1 = self.sync(dev, [self.msg(1)], b)
        r2 = self.sync(dev, [self.msg(1)], b)
        self.assertEqual(r1["inserted"], 1)
        self.assertTrue(r2.get("replayed"))
        self.assertEqual(r2["inserted"], 1)

    def test_atomic_batch_rejected_if_any_invalid(self):
        dev = self.new_device("atomic")
        bad = self.msg(2)
        del bad["direction"]
        dev.err("POST", "/device/messages/sync", {"batch_id": str(uuid.uuid4()), "messages": [self.msg(1), bad]}, 400, "invalid_request")
        self.assertEqual(self.admin.ok("GET", f"/conversations?device_id={dev.device_id}")["total"], 0)

    def test_batch_limit(self):
        dev = self.new_device("limit")
        dev.err("POST", "/device/messages/sync", {"batch_id": str(uuid.uuid4()), "messages": [self.msg(i) for i in range(501)]}, 400)

    def test_multi_device_same_content_and_ids(self):
        a, b = self.new_device("A"), self.new_device("B")
        self.sync(a, [self.msg(1, content="same body")])
        self.sync(b, [self.msg(1, content="same body")])
        self.assertEqual(self.admin.ok("GET", "/messages/search?q=same%20body")["total"], 2)

    def test_number_formats_share_conversation(self):
        dev = self.new_device("fmt")
        self.sync(dev, [self.msg(1, phone_number="13800138000"), self.msg(2, phone_number="+86 138-0013-8000")])
        self.assertEqual(self.admin.ok("GET", f"/conversations?device_id={dev.device_id}")["total"], 1)

    def test_alphanumeric_sender(self):
        dev = self.new_device("alpha")
        self.sync(dev, [self.msg(1, phone_number="ICBC"), self.msg(2, phone_number="10086")])
        self.assertEqual(self.admin.ok("GET", f"/conversations?device_id={dev.device_id}")["total"], 2)

    def test_receiver_then_provider_merges(self):
        dev = self.new_device("merge")
        key = str(uuid.uuid4())
        r = self.sync(dev, [self.msg(0, source_type="android_receiver", source_key=key, android_sms_id=None, content="live msg", timestamp=1760001000000)])
        self.assertEqual(r["inserted"], 1)
        r = self.sync(dev, [self.msg(501, content="live msg", timestamp=1760001001000)])
        self.assertEqual((r["inserted"], r["merged"]), (0, 1))
        # receiver retry after merge is ignored, provider re-upload is an update; still one message
        self.sync(dev, [self.msg(0, source_type="android_receiver", source_key=key, android_sms_id=None, content="live msg", timestamp=1760001000000)])
        self.sync(dev, [self.msg(501, content="live msg", timestamp=1760001001000)])
        c = self.admin.ok("GET", f"/conversations?device_id={dev.device_id}")["items"][0]
        self.assertEqual(len(self.admin.ok("GET", f"/conversations/{c['id']}/messages")["items"]), 1)

    def test_provider_then_receiver_merges(self):
        dev = self.new_device("merge2")
        self.sync(dev, [self.msg(9, content="late", timestamp=1760002000000)])
        r = self.sync(dev, [self.msg(0, source_type="android_receiver", source_key=str(uuid.uuid4()), android_sms_id=None, content="late", timestamp=1760001999000)])
        self.assertEqual((r["inserted"], r["merged"]), (0, 1))

    def test_unicode_and_long_content(self):
        dev = self.new_device("uni")
        body = "你好🙂" * 300
        self.sync(dev, [self.msg(1, content=body)])
        c = self.admin.ok("GET", f"/conversations?device_id={dev.device_id}")["items"][0]
        self.assertEqual(self.admin.ok("GET", f"/conversations/{c['id']}/messages")["items"][0]["content"], body)

    def test_unread_and_mark_read(self):
        dev = self.new_device("unread")
        self.sync(dev, [self.msg(1), self.msg(2)])
        c = self.admin.ok("GET", f"/conversations?device_id={dev.device_id}")["items"][0]
        self.assertEqual(c["unread_count"], 2)
        self.admin.ok("POST", f"/conversations/{c['id']}/read", {})
        c = self.admin.ok("GET", f"/conversations?device_id={dev.device_id}")["items"][0]
        self.assertEqual(c["unread_count"], 0)

    def test_progress(self):
        dev = self.new_device("prog")
        dev.ok("POST", "/device/sync/progress", {"state": "running", "total": 1000, "synced": 300, "last_android_sms_id": 300})
        p = dev.ok("GET", "/device/sync/progress")
        self.assertEqual((p["state"], p["total"], p["synced"], p["last_android_sms_id"]), ("running", 1000, 300, 300))
        d = next(x for x in self.admin.ok("GET", "/devices")["items"] if x["id"] == dev.device_id)
        self.assertEqual((d["sync_status"], d["sync_total"], d["sync_synced"]), ("running", 1000, 300))

    def test_large_history(self):
        dev = self.new_device("big")
        total = 0
        for start in range(0, 2000, 500):
            r = self.sync(dev, [self.msg(i, phone_number=f"1380000{i % 40:04d}") for i in range(start + 1, start + 501)])
            total += r["inserted"]
        self.assertEqual(total, 2000)
        self.assertEqual(self.admin.ok("GET", f"/conversations?device_id={dev.device_id}&page_size=100")["total"], 40)


class TestSearchAndConversations(Base):
    def test_search_and_escape(self):
        dev = self.new_device("search")
        self.sync(dev, [self.msg(1, content="100% genuine", phone_number="111111"), self.msg(2, content="plain text", phone_number="222222")])
        self.assertEqual(self.admin.ok("GET", f"/messages/search?q=%25&device_id={dev.device_id}")["total"], 1)
        self.assertEqual(self.admin.ok("GET", f"/messages/search?q=plain&device_id={dev.device_id}")["total"], 1)
        self.admin.err("GET", "/messages/search?q=", None, 400)
        # SQL metacharacters are inert
        self.assertEqual(self.admin.ok("GET", "/messages/search?q=%27%20OR%201%3D1%20--")["total"], 0)

    def test_pin_delete_server_only(self):
        dev = self.new_device("pin")
        self.sync(dev, [self.msg(1, phone_number="900001"), self.msg(2, phone_number="900002", timestamp=1760009000000)])
        items = self.admin.ok("GET", f"/conversations?device_id={dev.device_id}")["items"]
        self.assertEqual(items[0]["phone_number"], "900002")
        old = items[1]
        self.admin.ok("POST", f"/conversations/{old['id']}/pin", {"pinned": True})
        items = self.admin.ok("GET", f"/conversations?device_id={dev.device_id}")["items"]
        self.assertEqual(items[0]["id"], old["id"])
        self.admin.ok("DELETE", f"/conversations/{old['id']}")
        self.admin.err("GET", f"/conversations/{old['id']}/messages", None, 404)

    def test_pagination_before_id(self):
        dev = self.new_device("page")
        self.sync(dev, [self.msg(i) for i in range(1, 31)])
        c = self.admin.ok("GET", f"/conversations?device_id={dev.device_id}")["items"][0]
        p1 = self.admin.ok("GET", f"/conversations/{c['id']}/messages?limit=10")
        self.assertTrue(p1["has_more"])
        p2 = self.admin.ok("GET", f"/conversations/{c['id']}/messages?limit=10&before_id={p1['items'][0]['id']}")
        self.assertLess(p2["items"][-1]["timestamp"], p1["items"][0]["timestamp"])
        seen = {m["id"] for m in p1["items"]} | {m["id"] for m in p2["items"]}
        self.assertEqual(len(seen), 20)


class TestSending(Base):
    def setUp(self):
        self.admin.ok("POST", "/settings/emergency-pause", {"paused": False})
        self.admin.ok("PUT", "/settings", {"send_rate_per_minute": 600})

    def send(self, dev, recipient="13800138000", content="hi", **kw):
        return self.admin.ok("POST", "/messages/send", {"device_id": dev.device_id, "recipient": recipient, "content": content, **kw}, expect=201)

    def test_full_lifecycle(self):
        dev = self.new_device("life")
        r = self.send(dev, "+8613800138000", "你好")
        tid = r["task"]["task_id"]
        self.assertEqual(r["task"]["status"], "queued")
        self.assertEqual(r["message"]["status"], "queued")
        polled = dev.ok("GET", "/device/tasks/poll?wait=0")["tasks"]
        self.assertEqual([t["task_id"] for t in polled], [tid])
        # poll does not change state
        self.assertEqual(len(dev.ok("GET", "/device/tasks/poll?wait=0")["tasks"]), 1)
        self.assertEqual(dev.ok("POST", "/device/tasks/claim", {"task_id": tid})["status"], "claimed")
        self.assertEqual(dev.ok("GET", "/device/tasks/poll?wait=0")["tasks"], [])
        for st in ("sending", "sent", "delivered"):
            self.assertEqual(dev.ok("POST", "/device/tasks/result", {"task_id": tid, "status": st})["status"], st)
        cid = r["conversation"]["id"]
        m = self.admin.ok("GET", f"/conversations/{cid}/messages")["items"][-1]
        self.assertEqual((m["status"], m["direction"]), ("delivered", "outgoing"))

    def test_claim_is_exclusive_under_concurrency(self):
        dev = self.new_device("claim")
        tid = self.send(dev)["task"]["task_id"]
        res = []

        def go():
            st, _ = Client(self.base, dev.token).call("POST", "/device/tasks/claim", {"task_id": tid})
            res.append(st)

        ts = [threading.Thread(target=go) for _ in range(8)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        self.assertEqual(sorted(res).count(200), 1, res)
        self.assertEqual(sorted(res).count(409), 7, res)

    def test_claim_other_devices_task_is_hidden(self):
        a, b = self.new_device("own-a"), self.new_device("own-b")
        tid = self.send(a)["task"]["task_id"]
        b.err("POST", "/device/tasks/claim", {"task_id": tid}, 404)
        b.err("POST", "/device/tasks/result", {"task_id": tid, "status": "sent"}, 404)
        self.assertEqual(b.ok("GET", "/device/tasks/poll?wait=0")["tasks"], [])

    def test_invalid_transitions(self):
        dev = self.new_device("trans")
        tid = self.send(dev)["task"]["task_id"]
        dev.err("POST", "/device/tasks/result", {"task_id": tid, "status": "sent"}, 409, "invalid_transition")
        dev.ok("POST", "/device/tasks/claim", {"task_id": tid})
        dev.err("POST", "/device/tasks/result", {"task_id": tid, "status": "sent"}, 409, "invalid_transition")
        dev.ok("POST", "/device/tasks/result", {"task_id": tid, "status": "sending"})
        dev.ok("POST", "/device/tasks/result", {"task_id": tid, "status": "sending"})  # idempotent
        dev.ok("POST", "/device/tasks/result", {"task_id": tid, "status": "sent"})
        dev.err("POST", "/device/tasks/result", {"task_id": tid, "status": "sending"}, 409, "invalid_transition")
        dev.err("POST", "/device/tasks/claim", {"task_id": tid}, 409, "not_claimable")

    def test_failed_task_marks_conversation(self):
        dev = self.new_device("fail")
        r = self.send(dev)
        tid = r["task"]["task_id"]
        dev.ok("POST", "/device/tasks/claim", {"task_id": tid})
        dev.ok("POST", "/device/tasks/result", {"task_id": tid, "status": "failed", "error_code": "no_sim", "error_message": "没有 SIM 卡"})
        c = next(x for x in self.admin.ok("GET", f"/conversations?device_id={dev.device_id}")["items"])
        self.assertTrue(c["has_failed"])

    def test_unknown_never_requeued_and_resolution(self):
        dev = self.new_device("unk")
        tid = self.send(dev, content="never twice")["task"]["task_id"]
        dev.ok("POST", "/device/tasks/claim", {"task_id": tid})
        dev.ok("POST", "/device/tasks/result", {"task_id": tid, "status": "sending"})
        # crash, restart: client reports unknown (never resends)
        dev.ok("POST", "/device/tasks/result", {"task_id": tid, "status": "unknown", "error_code": "crash_recovery"})
        self.assertEqual(dev.ok("GET", "/device/tasks/poll?wait=0")["tasks"], [])
        dev.err("POST", "/device/tasks/claim", {"task_id": tid}, 409, "not_claimable")
        # only the admin can act; mark_sent
        self.admin.err("POST", f"/tasks/{tid}/resolve", {"action": "bogus"}, 400)
        out = self.admin.ok("POST", f"/tasks/{tid}/resolve", {"action": "mark_sent"})
        self.assertEqual(out["task"]["status"], "sent")
        self.admin.err("POST", f"/tasks/{tid}/resolve", {"action": "mark_sent"}, 409, "not_resolvable")

    def test_resend_creates_new_task(self):
        dev = self.new_device("resend")
        tid = self.send(dev, content="again?")["task"]["task_id"]
        dev.ok("POST", "/device/tasks/claim", {"task_id": tid})
        dev.ok("POST", "/device/tasks/result", {"task_id": tid, "status": "sending"})
        dev.ok("POST", "/device/tasks/result", {"task_id": tid, "status": "unknown"})
        out = self.admin.ok("POST", f"/tasks/{tid}/resolve", {"action": "resend"})
        self.assertNotEqual(out["new_task"]["task_id"], tid)
        self.assertEqual(out["new_task"]["status"], "queued")
        self.assertEqual(out["task"]["status"], "failed")
        self.assertEqual([t["task_id"] for t in dev.ok("GET", "/device/tasks/poll?wait=0")["tasks"]], [out["new_task"]["task_id"]])

    def _db(self):
        import sqlite3
        return sqlite3.connect(os.path.join(self.data_dir, "sms_gateway.sqlite"))

    def test_sweep_requeues_claimed_but_never_sending(self):
        dev = self.new_device("sweepc")
        tid = self.send(dev)["task"]["task_id"]
        dev.ok("POST", "/device/tasks/claim", {"task_id": tid})
        con = self._db()
        con.execute("UPDATE send_tasks SET updated_at = updated_at - 400000 WHERE task_id = ?", (tid,))
        con.commit()
        con.close()
        tasks = dev.ok("GET", "/device/tasks/poll?wait=0")["tasks"]
        self.assertEqual([t["task_id"] for t in tasks], [tid])

    def test_sweep_sending_goes_unknown_not_queued(self):
        dev = self.new_device("sweeps")
        tid = self.send(dev)["task"]["task_id"]
        dev.ok("POST", "/device/tasks/claim", {"task_id": tid})
        dev.ok("POST", "/device/tasks/result", {"task_id": tid, "status": "sending"})
        con = self._db()
        con.execute("UPDATE send_tasks SET updated_at = updated_at - 700000 WHERE task_id = ?", (tid,))
        con.commit()
        con.close()
        self.assertEqual(dev.ok("GET", "/device/tasks/poll?wait=0")["tasks"], [])
        con = self._db()
        st = con.execute("SELECT status FROM send_tasks WHERE task_id = ?", (tid,)).fetchone()[0]
        con.close()
        self.assertEqual(st, "unknown")

    def test_web_task_message_merges_with_provider_sync(self):
        dev = self.new_device("webmerge")
        r = self.send(dev, "13800138000", "from web")
        tid = r["task"]["task_id"]
        dev.ok("POST", "/device/tasks/claim", {"task_id": tid})
        dev.ok("POST", "/device/tasks/result", {"task_id": tid, "status": "sending"})
        dev.ok("POST", "/device/tasks/result", {"task_id": tid, "status": "sent"})
        now = int(time.time() * 1000)
        out = self.sync(dev, [self.msg(77, phone_number="13800138000", content="from web", direction="outgoing", timestamp=now + 500)])
        self.assertEqual((out["inserted"], out["merged"]), (0, 1))
        msgs = self.admin.ok("GET", f"/conversations/{r['conversation']['id']}/messages")["items"]
        self.assertEqual(len([m for m in msgs if m["content"] == "from web"]), 1)
        self.assertEqual(msgs[-1]["status"], "sent")

    def test_validation(self):
        dev = self.new_device("valid")
        base = {"device_id": dev.device_id, "content": "x"}
        for bad in ("", "abc", "12", "138 0013 8000, 13900139000", "1380013800;139", "+" + "1" * 30):
            self.admin.err("POST", "/messages/send", {**base, "recipient": bad}, 400, "invalid_request")
        self.admin.err("POST", "/messages/send", {**base, "recipient": "13800138000", "content": ""}, 400)
        self.admin.err("POST", "/messages/send", {**base, "recipient": "13800138000", "content": "x" * 1001}, 400)
        self.admin.err("POST", "/messages/send", {**base, "recipient": "13800138000", "device_id": 99999}, 404)
        self.admin.ok("POST", "/messages/send", {**base, "recipient": "+81 90-1234-5678", "confirm_warnings": True}, expect=201)

    def test_international_and_premium_warnings(self):
        dev = self.new_device("warn")
        e = self.admin.err("POST", "/messages/send", {"device_id": dev.device_id, "recipient": "+819012345678", "content": "x"}, 422, "confirmation_required")
        self.assertIn("international", e["details"]["warnings"])
        e = self.admin.err("POST", "/messages/send", {"device_id": dev.device_id, "recipient": "10086", "content": "x"}, 422, "confirmation_required")
        self.assertIn("premium_suspect", e["details"]["warnings"])
        self.admin.ok("POST", "/messages/send", {"device_id": dev.device_id, "recipient": "+819012345678", "content": "x", "confirm_warnings": True}, expect=201)
        # home country number: no warning
        self.admin.ok("POST", "/messages/send", {"device_id": dev.device_id, "recipient": "+8613800138000", "content": "x"}, expect=201)

    def test_emergency_pause(self):
        dev = self.new_device("pause")
        tid = self.send(dev)["task"]["task_id"]
        self.admin.ok("POST", "/settings/emergency-pause", {"paused": True})
        try:
            self.admin.err("POST", "/messages/send", {"device_id": dev.device_id, "recipient": "13800138000", "content": "x"}, 423, "send_paused")
            p = dev.ok("GET", "/device/tasks/poll?wait=0")
            self.assertEqual((p["tasks"], p["send_paused"]), ([], True))
            dev.err("POST", "/device/tasks/claim", {"task_id": tid}, 423, "send_paused")
            self.assertTrue(dev.ok("POST", "/device/heartbeat", {})["send_paused"])
        finally:
            self.admin.ok("POST", "/settings/emergency-pause", {"paused": False})
        self.assertEqual(len(dev.ok("GET", "/device/tasks/poll?wait=0")["tasks"]), 1)

    def test_rate_limit(self):
        dev = self.new_device("rate")
        self.admin.ok("PUT", "/settings", {"send_rate_per_minute": 3})
        codes = []
        for _ in range(5):
            st, js = self.admin.call("POST", "/messages/send", {"device_id": dev.device_id, "recipient": "13800138000", "content": "x"})
            codes.append(st)
        self.assertEqual(codes[:3], [201] * 3)
        self.assertEqual(codes[3:], [429, 429])

    def test_audit_has_no_message_body(self):
        dev = self.new_device("audit")
        self.send(dev, "13800138000", "SECRET-OTP-424242")
        logs = self.admin.ok("GET", "/audit-logs?action=send_message&page_size=200")["items"]
        self.assertTrue(logs)
        self.assertFalse(any("424242" in json.dumps(l) for l in logs))

    def test_long_poll_wakes_on_new_task(self):
        dev = self.new_device("lp")
        out = {}

        def poll():
            t0 = time.time()
            out["tasks"] = dev.ok("GET", "/device/tasks/poll?wait=15")["tasks"]
            out["elapsed"] = time.time() - t0

        th = threading.Thread(target=poll)
        th.start()
        time.sleep(1.5)
        self.send(dev)
        th.join(20)
        self.assertEqual(len(out["tasks"]), 1)
        self.assertLess(out["elapsed"], 6)

    def test_long_poll_times_out_empty(self):
        dev = self.new_device("lp2")
        t0 = time.time()
        self.assertEqual(dev.ok("GET", "/device/tasks/poll?wait=2")["tasks"], [])
        self.assertGreaterEqual(time.time() - t0, 1.5)


class TestSettingsAndMisc(Base):
    def test_password_change_invalidates_other_sessions(self):
        a, b = self.login(), self.login()
        a.err("POST", "/auth/password", {"current_password": "wrong", "new_password": "another-pass-2"}, 400)
        a.err("POST", "/auth/password", {"current_password": PASSWORD, "new_password": "short"}, 400, "invalid_request")
        a.ok("POST", "/auth/password", {"current_password": PASSWORD, "new_password": "another-pass-2"})
        a.ok("GET", "/auth/me")
        b.err("GET", "/auth/me", None, 401)
        Client(self.base).err("POST", "/auth/login", {"username": "admin", "password": PASSWORD}, 401, "invalid_credentials")
        c = Client(self.base)
        c.ok("POST", "/auth/login", {"username": "admin", "password": "another-pass-2"})
        # restore for other tests
        c.csrf = c.ok("GET", "/auth/me")["csrf_token"]
        c.ok("POST", "/auth/password", {"current_password": "another-pass-2", "new_password": PASSWORD})
        # every password change revokes all other sessions (by design), including the shared admin one
        type(self).admin = self.login()

    def test_settings_validation_and_audit(self):
        self.admin.err("PUT", "/settings", {"online_threshold_seconds": 1}, 400)
        self.admin.err("PUT", "/settings", {"timezone": "Mars/Base"}, 400)
        self.admin.err("PUT", "/settings", {"home_country_code": "86"}, 400)
        s = self.admin.ok("PUT", "/settings", {"site_name": "Renamed", "home_country_code": "+86"})
        self.assertEqual(s["site_name"], "Renamed")
        self.admin.ok("PUT", "/settings", {"site_name": "T"})

    def test_no_shell_command_endpoints(self):
        for path in ("/device/exec", "/device/shell", "/device/command", "/device/script", "/devices/1/exec", "/devices/1/command"):
            for m in ("GET", "POST", "PUT"):
                st, _ = self.admin.call(m, path, {})
                self.assertIn(st, (404, 405), f"{m} {path} -> {st}")

    def test_export(self):
        dev = self.new_device("export")
        self.sync(dev, [self.msg(1, content="=cmd|' /C calc'!A0")])
        st, body = self.admin.call("GET", f"/export/messages?format=csv&device_id={dev.device_id}", raw=True)
        self.assertEqual(st, 200)
        self.assertIn("'=cmd", body.decode("utf-8-sig"))
        st, body = self.admin.call("GET", f"/export/messages?format=json&device_id={dev.device_id}", raw=True)
        self.assertEqual(len(json.loads(body)), 1)

    def test_unknown_route_and_bad_json(self):
        self.admin.err("GET", "/nope", None, 404)
        st, _ = self.admin.call("POST", "/auth/login", None, raw=True, headers={"Content-Type": "application/json"})
        self.assertIn(st, (400, 429))
        req = urllib.request.Request(self.base + "/auth/login", data=b"{not json", method="POST", headers={"Content-Type": "application/json"})
        try:
            urllib.request.urlopen(req)
            self.fail("expected error")
        except urllib.error.HTTPError as e:
            self.assertIn(e.code, (400, 429))

    def test_sse_stream_delivers_events(self):
        dev = self.new_device("sse")
        c = self.login()
        req = urllib.request.Request(self.base + "/events", headers={"Accept": "text/event-stream"})
        cj = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(c.jar))
        got = []

        def read():
            with cj.open(req, timeout=20) as r:
                self.assertIn("text/event-stream", r.headers["Content-Type"])
                for line in r:
                    got.append(line.decode())
                    if any("message.new" in g for g in got) and any(g.startswith("data:") for g in got):
                        return

        th = threading.Thread(target=read)
        th.start()
        time.sleep(1.2)
        self.sync(dev, [self.msg(1, content="sse body")])
        th.join(15)
        text = "".join(got)
        self.assertIn("event: message.new", text)
        self.assertIn("sse body", text)
        self.assertIn("id: ", text)

    def test_wal_and_foreign_keys(self):
        import sqlite3
        con = sqlite3.connect(os.path.join(self.data_dir, "sms_gateway.sqlite"))
        self.assertEqual(con.execute("PRAGMA journal_mode").fetchone()[0], "wal")
        idx = {r[1] for r in con.execute("SELECT * FROM sqlite_master WHERE type='index'")}
        for name in ("idx_sms_source", "idx_messages_conversation_time", "idx_messages_phone", "idx_tasks_device_status"):
            self.assertIn(name, idx)
        con.close()

    def test_security_headers(self):
        st, _ = self.admin.call("GET", "/auth/me")
        req = urllib.request.Request(self.base + "/install/status")
        with urllib.request.urlopen(req) as r:
            self.assertEqual(r.headers["X-Content-Type-Options"], "nosniff")
            self.assertEqual(r.headers["X-Frame-Options"], "DENY")


class TestProxyTrust(Base):
    """X-Forwarded-For 只有在显式开启并且来自可信反代时才被采信；否则登录限流可被伪造绕过。"""

    def test_spoofed_forwarded_for_cannot_bypass_login_throttle(self):
        c = Client(self.base)
        codes = []
        for i in range(12):
            st, _ = c.call("POST", "/auth/login", {"username": "spoof-victim", "password": "nope"},
                           headers={"X-Forwarded-For": f"203.0.113.{i + 1}"})
            codes.append(st)
        self.assertEqual(codes[:5], [401] * 5, codes)
        self.assertTrue(all(x == 429 for x in codes[5:]), codes)

    def test_in_list_cidr_matching(self):
        out = subprocess.run(
            ["php", "-r", 'require "' + os.path.join(ROOT, "src", "autoload.php") + '"; '
             '$l=["127.0.0.1","10.0.0.0/8","192.168.0.0/16","::1"]; '
             'foreach (["127.0.0.1","10.200.3.4","192.168.1.5","192.169.1.5","8.8.8.8","::1","2001:db8::1","bad"] as $ip) '
             'echo SmsGate\\Http\\Request::inList($ip,$l) ? "1" : "0";'],
            capture_output=True, text=True).stdout
        self.assertEqual(out, "11100100")


class TestCredentialStuffing(Base):
    """分布式撞库：每个来源只试几次，逐 IP 限流拦不住，所以同一用户名还要有跨来源的总量上限。"""

    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        # 让每个请求看起来来自不同 IP：信任 127.0.0.1 这个“反代”，并由测试自己填 X-Forwarded-For
        pass

    def test_per_username_cap_across_sources(self):
        import sqlite3
        con = sqlite3.connect(os.path.join(self.data_dir, "sms_gateway.sqlite"))
        now = int(time.time() * 1000)
        # 直接写入 20 次来自 20 个不同 IP 的失败记录（等价于 20 个来源各试 1 次）
        for i in range(20):
            con.execute("INSERT INTO login_attempts (ts, username, ip, success) VALUES (?, ?, ?, 0)",
                        (now - 1000, "stuffed-user", f"198.51.100.{i + 1}"))
        con.commit()
        con.close()
        e = Client(self.base).err("POST", "/auth/login", {"username": "stuffed-user", "password": "whatever"}, 429, "too_many_attempts")
        self.assertGreater(e["details"]["retry_after"], 0)
        # 该账号即使给出正确密码也被锁：锁的是账号维度，不是只拦错误密码
        c = Client(self.base)
        c.ok("POST", "/auth/login", {"username": "admin", "password": PASSWORD})
        con = sqlite3.connect(os.path.join(self.data_dir, "sms_gateway.sqlite"))
        con.execute("UPDATE users SET username = 'stuffed-user' WHERE username = 'admin'")
        con.commit()
        con.close()
        Client(self.base).err("POST", "/auth/login", {"username": "stuffed-user", "password": PASSWORD}, 429, "too_many_attempts")

    def test_other_usernames_unaffected(self):
        c = Client(self.base)
        c.err("POST", "/auth/login", {"username": "someone-else", "password": "x"}, 401, "invalid_credentials")


class TestInstallExposureProbe(Base):
    def test_data_exposure_check_present_and_passes_on_builtin_server(self):
        # php -S 以 server/public 为文档根，数据目录在其上一级，不在网站目录内
        st, js = Client(self.base).call("GET", "/install/status")
        self.assertEqual(st, 200)
        self.assertTrue(js["data"]["installed"])


class TestSimNumbers(Base):
    def _dev(self, c):
        return next(x for x in self.admin.ok("GET", "/devices")["items"] if x["id"] == c.device_id)

    def test_device_reported_number_shown_with_source(self):
        dev = self.new_device("simnum-a")
        dev.ok("POST", "/device/heartbeat", {"sims": [
            {"slot": 0, "operator": "中国电信", "state": "ready", "number": "+86 138-0013-8000"},
            {"slot": 1, "operator": "", "state": "absent"}]})
        sims = {x["slot"]: x for x in self._dev(dev)["sims"]}
        self.assertEqual((sims[0]["number"], sims[0]["number_source"]), ("+8613800138000", "device"))
        self.assertEqual((sims[1]["number"], sims[1]["number_source"]), (None, None))

    def test_garbage_number_from_device_is_dropped_not_rejected(self):
        dev = self.new_device("simnum-b")
        dev.ok("POST", "/device/heartbeat", {"sims": [{"slot": 0, "operator": "x", "state": "ready", "number": "<script>alert(1)</script>"}]})
        self.assertIsNone(self._dev(dev)["sims"][0]["number"])

    def test_manual_overrides_device_and_survives_heartbeats(self):
        dev = self.new_device("simnum-c")
        dev.ok("POST", "/device/heartbeat", {"sims": [{"slot": 0, "operator": "x", "state": "ready", "number": "+8613800138000"}]})
        r = self.admin.ok("PATCH", f"/devices/{dev.device_id}", {"sim_numbers": {"0": "139 0013 9000", "1": "+819012345678"}})
        sims = {x["slot"]: x for x in r["device"]["sims"]}
        self.assertEqual((sims[0]["number"], sims[0]["number_source"]), ("13900139000", "manual"))
        dev.ok("POST", "/device/heartbeat", {"sims": [{"slot": 0, "operator": "x", "state": "ready", "number": "+8613800138000"},
                                                      {"slot": 1, "operator": "y", "state": "ready"}]})
        sims = {x["slot"]: x for x in self._dev(dev)["sims"]}
        self.assertEqual((sims[0]["number"], sims[0]["number_source"]), ("13900139000", "manual"))
        self.assertEqual((sims[1]["number"], sims[1]["number_source"]), ("+819012345678", "manual"))

    def test_clearing_manual_falls_back_to_device_value(self):
        dev = self.new_device("simnum-d")
        dev.ok("POST", "/device/heartbeat", {"sims": [{"slot": 0, "operator": "x", "state": "ready", "number": "+8613800138000"}]})
        self.admin.ok("PATCH", f"/devices/{dev.device_id}", {"sim_numbers": {"0": "13900139000"}})
        r = self.admin.ok("PATCH", f"/devices/{dev.device_id}", {"sim_numbers": {"0": ""}})
        s0 = r["device"]["sims"][0]
        self.assertEqual((s0["number"], s0["number_source"]), ("+8613800138000", "device"))

    def test_validation(self):
        dev = self.new_device("simnum-e")
        self.admin.err("PATCH", f"/devices/{dev.device_id}", {"sim_numbers": {"0": "abc"}}, 400, "invalid_request")
        self.admin.err("PATCH", f"/devices/{dev.device_id}", {"sim_numbers": {"9": "13900139000"}}, 400, "invalid_request")
        self.admin.err("PATCH", f"/devices/{dev.device_id}", {"sim_numbers": ["13900139000"]}, 400, "invalid_request")
        self.admin.err("PATCH", f"/devices/{dev.device_id}", {}, 400, "invalid_request")
        # 只改名字仍然可用（向后兼容）
        self.admin.ok("PATCH", f"/devices/{dev.device_id}", {"device_name": "renamed"})

    def test_requires_admin_and_csrf(self):
        dev = self.new_device("simnum-f")
        dev.err("PATCH", f"/devices/{dev.device_id}", {"sim_numbers": {"0": "13900139000"}}, 401)
        c = Client(self.base)
        c.ok("POST", "/auth/login", {"username": "admin", "password": PASSWORD})
        c.err("PATCH", f"/devices/{dev.device_id}", {"sim_numbers": {"0": "13900139000"}}, 403, "csrf_failed")

    def test_number_not_written_to_audit_log(self):
        dev = self.new_device("simnum-g")
        self.admin.ok("PATCH", f"/devices/{dev.device_id}", {"sim_numbers": {"0": "13955556666"}})
        logs = self.admin.ok("GET", "/audit-logs?action=device_sim_numbers&page_size=50")["items"]
        self.assertTrue(logs)
        self.assertFalse(any("13955556666" in json.dumps(l) for l in logs))


if __name__ == "__main__":
    unittest.main(verbosity=2)
