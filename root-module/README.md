# SMS Web Gateway Watchdog (Magisk / KernelSU / KernelSU Next module)

Optional, off-by-default root module. It runs a small shell loop outside the app
process that watches the app's local heartbeat and, when the app is really
unhealthy, asks the app to restart its own sync service. Interface contract:
`docs/ROOT_INTERFACE.md`.

## What it does

- Every `interval` seconds (30-300, default 60) reads `/data/adb/smsgate/control`
  and `/data/user_de/0/com.smswebgateway.android/files/keepalive/status.json`.
- "Unhealthy" means: heartbeat `ts` older than 120 s (or file missing), or
  `service_running=false` while bound, or the service process is missing, or
  `network_ok=true` but `last_server_ok_at` older than 600 s. A running process
  alone is never treated as healthy.
- Recovery is a single explicit broadcast to the app's `RecoverReceiver`
  (`am broadcast --user 0 -a ...WATCHDOG_RECOVER -n .../.keepalive.RecoverReceiver`).
- Cool-down 10 s, 30 s, 60 s, then 300 s between attempts. After `max_failures`
  (default 5) consecutive failed recoveries it enters `protected` (log only) until
  the app reports healthy again or `reset_protection=<ts>` is written to `control`.
- Never acts when: `enabled` is not 1, `bound=false`, `user_stopped=true`, the boot
  is not completed, the user is not unlocked (`sys.user.0.ce_available`), the pkg
  value is invalid, or the network is down.
- Writes `watchdog.status` and `watchdog.log` (rotated at 256 KB) in `/data/adb/smsgate/`.

## What it never does

No network access, no remote commands, no changes to system settings, SELinux,
partitions or other apps, and no attempt to start or un-stop an app the user
stopped in system settings (the broadcast is simply not delivered then; this is
intended). Only the fixed recovery broadcast is ever sent.

## Install

1. Build: `sh root-module/build.sh` -> `dist/smsgate-watchdog.zip`
   (also copied to `android/app/src/main/assets/root-module.zip` if that directory exists).
2. Install the zip with the Magisk / KernelSU / KernelSU Next manager
   (Modules -> Install from storage), or let the app's "Install Root Watchdog" do it
   (extracts to `/data/adb/modules/smsgate_watchdog/`).
3. Reboot. `service.sh` waits for `sys.boot_completed=1`, then starts `watchdog.sh`
   (single instance guarded by `/data/adb/smsgate/watchdog.lock`).

## Enable / disable

The module idles (`state=disabled`) until `/data/adb/smsgate/control` contains `enabled=1`:

```
enabled=1
interval=60
max_failures=5
pkg=com.smswebgateway.android
```

Set `enabled=0` to stop all recovery immediately (no reboot needed).
`pkg` must match `^[A-Za-z0-9._]+$`, otherwise the watchdog stays disabled.

## Roll back

- Soft: set `enabled=0` in `control` (or turn off Root enhancement in the app).
- Disable: toggle the module off in the manager, or `touch /data/adb/modules/smsgate_watchdog/disable`; reboot.
- Remove: uninstall in the manager (or `touch .../remove`); `uninstall.sh` stops the
  daemon (SIGTERM, verified pid) and deletes only `watchdog.lock/status/log` it created.
  The `control` file belongs to the app and is left alone.
- Stop now: `kill -TERM $(cat /data/adb/smsgate/watchdog.lock)`.

## Tests (host, no device needed)

`sh root-module/tests/run_tests.sh` runs the suite under dash and busybox ash with
fake `am`/`getprop`/`pidof`/`ip`. Test path overrides (`SMSGATE_ROOT_DIR`, `SMSGATE_DE_DIR`,
`SMSGATE_FAKE_AM`, `SMSGATE_NOW`, `SMSGATE_ONESHOT`, ...) are honoured only when
`SMSGATE_TEST=1`, so they cannot be abused on a real device.
