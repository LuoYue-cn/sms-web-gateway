#!/bin/sh
# Unit tests for the SMS Web Gateway root watchdog (host-side, fake am/getprop/pidof/ip).
# Usage: sh tests/run_tests.sh
# Runs the whole suite once per available shell (dash, busybox ash, sh).

HERE=$(cd "$(dirname "$0")" && pwd)
MODDIR=$(dirname "$HERE")
WD=$MODDIR/watchdog.sh
PASS=0
FAIL=0
CUR_SHELL=""

ok() { PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"; }
bad() { FAIL=$((FAIL + 1)); printf '  FAIL %s\n' "$1"; }

assert_eq() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (expected '$2', got '$3')"; fi
}
assert_contains() { # name file pattern
  if grep -q -- "$3" "$2" 2> /dev/null; then ok "$1"; else bad "$1 (pattern '$3' not in $2)"; fi
}
assert_not_contains() {
  if grep -q -- "$3" "$2" 2> /dev/null; then bad "$1 (pattern '$3' found in $2)"; else ok "$1"; fi
}

# ------------------------------------------------------------ fixtures
setup() {
  T=$(mktemp -d /tmp/smsgate-test.XXXXXX)
  mkdir -p "$T/root" "$T/de" "$T/props" "$T/bin"
  : > "$T/am.log"
  cat > "$T/bin/am" << 'EOF'
#!/bin/sh
printf '%s\n' "$*" >> "$SMSGATE_TEST_DIR/am.log"
exit 0
EOF
  cat > "$T/bin/getprop" << 'EOF'
#!/bin/sh
f="$SMSGATE_TEST_DIR/props/$1"
[ -f "$f" ] && cat "$f"
exit 0
EOF
  cat > "$T/bin/pidof" << 'EOF'
#!/bin/sh
[ -f "$SMSGATE_TEST_DIR/proc_alive" ]
EOF
  cat > "$T/bin/ip" << 'EOF'
#!/bin/sh
if [ -f "$SMSGATE_TEST_DIR/net_down" ]; then
  echo "10.0.0.0/24 dev wlan0 scope link"
else
  echo "default via 10.0.0.1 dev wlan0"
fi
exit 0
EOF
  cat > "$T/bin/dumpsys" << 'EOF'
#!/bin/sh
# Mimics `dumpsys package <pkg>`: the stopped flag comes from $T/stopped (true|false|nodata).
v=false
[ -f "$SMSGATE_TEST_DIR/stopped" ] && v=$(cat "$SMSGATE_TEST_DIR/stopped")
if [ "$v" = nodata ]; then
  echo "Unable to find package"
  exit 0
fi
echo "    User 0: ceDataInode=1 installed=true hidden=false suspended=false stopped=$v notLaunched=false enabled=0"
exit 0
EOF
  chmod +x "$T/bin/"*
  export SMSGATE_FAKE_DUMPSYS="$T/bin/dumpsys"
  export SMSGATE_TEST=1
  export SMSGATE_TEST_DIR="$T"
  export SMSGATE_ROOT_DIR="$T/root"
  export SMSGATE_DE_DIR="$T/de"
  export SMSGATE_MODDIR="$T/mod"
  export SMSGATE_FAKE_AM="$T/bin/am"
  export SMSGATE_FAKE_GETPROP="$T/bin/getprop"
  export SMSGATE_FAKE_PIDOF="$T/bin/pidof"
  export SMSGATE_FAKE_IP="$T/bin/ip"
  mkdir -p "$T/mod"
  echo 1 > "$T/props/sys.boot_completed"
  echo true > "$T/props/sys.user.0.ce_available"
  touch "$T/proc_alive"
  set_control 1 60 5 com.smswebgateway.android
}

teardown() {
  [ -n "$T" ] && rm -rf "$T"
  T=""
}

set_control() { # enabled interval max_failures pkg [extra line]
  {
    printf 'enabled=%s\n' "$1"
    printf 'interval=%s\n' "$2"
    printf 'max_failures=%s\n' "$3"
    printf 'pkg=%s\n' "$4"
    [ -n "$5" ] && printf '%s\n' "$5"
  } > "$T/root/control"
}

# mk_status ts service_running bound user_stopped network_ok last_server_ok_at
mk_status() {
  cat > "$T/de/status.json" << EOF
{
  "pkg": "com.smswebgateway.android",
  "ts": $1,
  "service_running": $2,
  "service_started_at": 1759990000,
  "last_sync_ok_at": $6,
  "last_server_ok_at": $6,
  "network_ok": $5,
  "bound": $3,
  "user_stopped": $4,
  "sending_task_ids": [],
  "protection_state": "none"
}
EOF
}

# run one watchdog cycle at fake time $1 ; sets RC
run_once() {
  SMSGATE_NOW=$1 SMSGATE_ONESHOT=1 $CUR_SHELL "$WD" > "$T/stdout" 2>&1
  RC=$?
}

am_count() { wc -l < "$T/am.log" | tr -d ' '; }
st() { grep "^$1=" "$T/root/watchdog.status" 2> /dev/null | tail -n 1 | cut -d= -f2-; }

NOW0=1760000000

# --------------------------------------------------------------- tests
t_healthy() {
  echo "[healthy -> no recovery]"
  setup
  mk_status "$NOW0" true true false true "$NOW0"
  run_once "$NOW0"
  assert_eq "exit code 0" 0 "$RC"
  assert_eq "no broadcast" 0 "$(am_count)"
  assert_eq "state=running" running "$(st state)"
  assert_eq "failures=0" 0 "$(st failures)"
  assert_contains "status has pid" "$T/root/watchdog.status" '^pid=[0-9][0-9]*$'
  assert_contains "status has ts" "$T/root/watchdog.status" "^ts=$NOW0\$"
  # compact one-line JSON must parse too
  printf '{"pkg":"com.smswebgateway.android","ts":%s,"service_running":true,"last_server_ok_at":%s,"network_ok":true,"bound":true,"user_stopped":false}' \
    "$NOW0" "$NOW0" > "$T/de/status.json"
  run_once "$NOW0"
  assert_eq "compact json healthy: no broadcast" 0 "$(am_count)"
  teardown
}

t_stale_status() {
  echo "[stale status.json -> recovery]"
  setup
  mk_status $((NOW0 - 200)) true true false true $((NOW0 - 200))
  run_once "$NOW0"
  assert_eq "one broadcast" 1 "$(am_count)"
  assert_contains "action" "$T/am.log" 'broadcast --user 0 -a com.smswebgateway.android.action.WATCHDOG_RECOVER'
  assert_contains "explicit component" "$T/am.log" '-n com.smswebgateway.android/.keepalive.RecoverReceiver'
  assert_contains "reason" "$T/am.log" '--es reason heartbeat_stale'
  assert_contains "attempt" "$T/am.log" '--ei attempt 1'
  assert_eq "state=cooldown" cooldown "$(st state)"
  assert_eq "recovery_count=1" 1 "$(st recovery_count)"
  assert_eq "last_recovery_ts" "$NOW0" "$(st last_recovery_ts)"
  assert_contains "log has recover line" "$T/root/watchdog.log" ' INFO recover reason=heartbeat_stale attempt=1'
  # process exists but heartbeat stale is still unhealthy
  teardown
  setup
  mk_status $((NOW0 - 500)) true true false true $((NOW0 - 10))
  [ -f "$T/proc_alive" ] && ok "(process alive in fixture)"
  run_once "$NOW0"
  assert_eq "process alive but stale heartbeat -> recovered" 1 "$(am_count)"
  teardown
  # missing status.json
  setup
  run_once "$NOW0"
  assert_eq "status.json missing -> recovered" 1 "$(am_count)"
  assert_contains "reason status_missing" "$T/am.log" 'reason status_missing'
  teardown
  # fresh heartbeat but service not running
  setup
  mk_status "$NOW0" false true false true "$NOW0"
  run_once "$NOW0"
  assert_contains "service_running=false -> recovered" "$T/am.log" 'reason service_not_running'
  teardown
  # fresh heartbeat, service_running=true but process gone
  setup
  rm -f "$T/proc_alive"
  mk_status "$NOW0" true true false true "$NOW0"
  run_once "$NOW0"
  assert_contains "process missing -> recovered" "$T/am.log" 'reason process_missing'
  teardown
  # sync stuck: server contact too old while network ok
  setup
  mk_status "$NOW0" true true false true $((NOW0 - 700))
  run_once "$NOW0"
  assert_contains "server stale -> recovered" "$T/am.log" 'reason server_stale'
  teardown
  # same age but network_ok=false -> not a stuck sync
  setup
  mk_status "$NOW0" true true false false $((NOW0 - 700))
  run_once "$NOW0"
  assert_eq "server old but network down -> no recovery" 0 "$(am_count)"
  teardown
}

t_cooldown_sequence() {
  echo "[cooldown 10/30/60/300 then protected]"
  setup
  set_control 1 60 5 com.smswebgateway.android
  mk_status $((NOW0 - 1000)) true true false true $((NOW0 - 1000))
  t=$NOW0
  run_once "$t"; assert_eq "t+0 attempt 1" 1 "$(am_count)"
  run_once $((t + 9)); assert_eq "t+9 still cooling (10s)" 1 "$(am_count)"
  assert_eq "state cooldown" cooldown "$(st state)"
  t=$((t + 10))
  run_once "$t"; assert_eq "t+10 attempt 2" 2 "$(am_count)"
  assert_contains "attempt 2 arg" "$T/am.log" '--ei attempt 2'
  run_once $((t + 29)); assert_eq "+29 cooling (30s)" 2 "$(am_count)"
  t=$((t + 30))
  run_once "$t"; assert_eq "+30 attempt 3" 3 "$(am_count)"
  run_once $((t + 59)); assert_eq "+59 cooling (60s)" 3 "$(am_count)"
  t=$((t + 60))
  run_once "$t"; assert_eq "+60 attempt 4" 4 "$(am_count)"
  run_once $((t + 299)); assert_eq "+299 cooling (300s)" 4 "$(am_count)"
  t=$((t + 300))
  run_once "$t"; assert_eq "+300 attempt 5" 5 "$(am_count)"
  assert_contains "attempt 5 arg" "$T/am.log" '--ei attempt 5'
  assert_eq "failures=5" 5 "$(st failures)"
  run_once $((t + 300)); assert_eq "max_failures reached: no 6th attempt" 5 "$(am_count)"
  assert_eq "state=protected" protected "$(st state)"
  run_once $((t + 301)); assert_eq "protected stays quiet" 5 "$(am_count)"
  assert_contains "log mentions protected" "$T/root/watchdog.log" ' WARN protected '
  # auto-release when app reports a healthy status
  mk_status $((t + 400)) true true false true $((t + 400))
  run_once $((t + 400))
  assert_eq "healthy again -> state running" running "$(st state)"
  assert_eq "failures cleared" 0 "$(st failures)"
  assert_eq "no extra broadcast" 5 "$(am_count)"
  teardown

  echo "[protected released by reset_protection]"
  setup
  set_control 1 60 2 com.smswebgateway.android
  mk_status $((NOW0 - 1000)) true true false true $((NOW0 - 1000))
  run_once "$NOW0"
  run_once $((NOW0 + 10))
  run_once $((NOW0 + 50))
  assert_eq "2 attempts then protected" 2 "$(am_count)"
  assert_eq "protected" protected "$(st state)"
  set_control 1 60 2 com.smswebgateway.android "reset_protection=$((NOW0 + 60))"
  run_once $((NOW0 + 61))
  assert_eq "reset_protection allows a new attempt" 3 "$(am_count)"
  teardown
}

t_not_bound_user_stopped() {
  echo "[bound=false / user_stopped=true -> never recover]"
  setup
  mk_status $((NOW0 - 1000)) false false false true $((NOW0 - 1000))
  run_once "$NOW0"
  assert_eq "bound=false: no broadcast" 0 "$(am_count)"
  assert_contains "logged skip" "$T/root/watchdog.log" 'skip not_bound'
  teardown
  setup
  mk_status $((NOW0 - 1000)) false true true true $((NOW0 - 1000))
  run_once "$NOW0"
  assert_eq "user_stopped=true: no broadcast" 0 "$(am_count)"
  assert_contains "logged skip" "$T/root/watchdog.log" 'skip user_stopped'
  teardown
  setup
  rm -f "$T/proc_alive"
  mk_status "$NOW0" false false false true "$NOW0"
  run_once "$NOW0"
  assert_eq "bound=false, service down, proc gone: still no broadcast" 0 "$(am_count)"
  teardown
}

t_force_stopped() {
  echo "[user force-stopped the app in system settings -> never revive it]"
  setup
  rm -f "$T/proc_alive"
  echo true > "$T/stopped"
  mk_status $((NOW0 - 1000)) false true false true $((NOW0 - 1000))
  run_once "$NOW0"
  assert_eq "force-stopped, unhealthy: no broadcast" 0 "$(am_count)"
  assert_contains "logged force_stopped" "$T/root/watchdog.log" 'force_stopped'
  assert_eq "force-stop does not count as a failure" 0 "$(st failures)"
  run_once $((NOW0 + 700))
  run_once $((NOW0 + 1400))
  assert_eq "still no broadcast after many cycles" 0 "$(am_count)"
  teardown
  setup
  rm -f "$T/proc_alive"
  echo true > "$T/stopped"
  rm -f "$T/de/status.json"
  run_once "$NOW0"
  assert_eq "force-stopped, status.json missing: no broadcast" 0 "$(am_count)"
  teardown
  setup
  rm -f "$T/proc_alive"
  echo nodata > "$T/stopped"
  mk_status $((NOW0 - 1000)) false true false true $((NOW0 - 1000))
  run_once "$NOW0"
  assert_eq "stopped state unknown: fail safe, no broadcast" 0 "$(am_count)"
  assert_contains "logged unknown" "$T/root/watchdog.log" 'stopped_state_unknown'
  teardown
  setup
  rm -f "$T/proc_alive"
  echo false > "$T/stopped"
  mk_status $((NOW0 - 1000)) false true false true $((NOW0 - 1000))
  run_once "$NOW0"
  assert_eq "not stopped + unhealthy: recovers (control case)" 1 "$(am_count)"
  teardown
}

t_locked_user() {
  echo "[user not unlocked / boot not completed -> no recovery]"
  setup
  echo false > "$T/props/sys.user.0.ce_available"
  mk_status $((NOW0 - 1000)) false true false true $((NOW0 - 1000))
  run_once "$NOW0"
  assert_eq "ce_available=false: no broadcast" 0 "$(am_count)"
  assert_contains "logged wait_unlock" "$T/root/watchdog.log" 'wait_unlock'
  rm -f "$T/props/sys.user.0.ce_available"
  run_once $((NOW0 + 100))
  assert_eq "ce_available unset: no broadcast" 0 "$(am_count)"
  teardown
  setup
  echo 0 > "$T/props/sys.boot_completed"
  mk_status $((NOW0 - 1000)) false true false true $((NOW0 - 1000))
  run_once "$NOW0"
  assert_eq "boot not completed: no broadcast" 0 "$(am_count)"
  teardown
}

t_disabled() {
  echo "[enabled=0 / control missing -> no recovery]"
  setup
  set_control 0 60 5 com.smswebgateway.android
  mk_status $((NOW0 - 1000)) false true false true $((NOW0 - 1000))
  run_once "$NOW0"
  assert_eq "enabled=0: no broadcast" 0 "$(am_count)"
  assert_eq "state=disabled" disabled "$(st state)"
  set_control 1 60 5 com.smswebgateway.android
  run_once $((NOW0 + 5))
  assert_eq "re-enabled: recovers" 1 "$(am_count)"
  set_control 0 60 5 com.smswebgateway.android
  run_once $((NOW0 + 200))
  assert_eq "disabled again: no further broadcast" 1 "$(am_count)"
  assert_eq "state=disabled again" disabled "$(st state)"
  assert_eq "failures reset when disabled" 0 "$(st failures)"
  teardown
  setup
  rm -f "$T/root/control"
  mk_status $((NOW0 - 1000)) false true false true $((NOW0 - 1000))
  run_once "$NOW0"
  assert_eq "control missing: no broadcast" 0 "$(am_count)"
  assert_eq "control missing: state=disabled" disabled "$(st state)"
  teardown
  setup
  touch "$T/mod/disable"
  mk_status $((NOW0 - 1000)) false true false true $((NOW0 - 1000))
  run_once "$NOW0"
  assert_eq "module disable marker: no broadcast" 0 "$(am_count)"
  teardown
}

t_invalid_pkg() {
  echo "[invalid pkg rejected / config clamping]"
  for bad_pkg in 'com.evil;touch /tmp/x' 'a b' '$(id)' 'com.a/../b' '..' '`id`' 'com.a|b' "x'y"; do
    setup
    set_control 1 60 5 "$bad_pkg"
    mk_status $((NOW0 - 1000)) false true false true $((NOW0 - 1000))
    run_once "$NOW0"
    assert_eq "pkg '$bad_pkg': no broadcast" 0 "$(am_count)"
    assert_eq "pkg '$bad_pkg': state=disabled" disabled "$(st state)"
    teardown
  done
  setup
  set_control 1 60 5 "com.example.other_app1"
  mk_status $((NOW0 - 1000)) false true false true $((NOW0 - 1000))
  run_once "$NOW0"
  assert_contains "valid custom pkg used in -n" "$T/am.log" '-n com.example.other_app1/.keepalive.RecoverReceiver'
  teardown
  setup
  set_control 1 5 5 com.smswebgateway.android
  run_once "$NOW0"
  assert_contains "interval 5 -> clamped to 30" "$T/root/watchdog.log" 'interval=30 '
  set_control 1 9999 5 com.smswebgateway.android
  run_once $((NOW0 + 1))
  assert_contains "interval 9999 -> clamped to 300" "$T/root/watchdog.log" 'interval=300 '
  set_control 1 abc 5 com.smswebgateway.android
  run_once $((NOW0 + 2))
  assert_contains "interval junk -> default 60" "$T/root/watchdog.log" 'interval=60 '
  teardown
}

t_network_down() {
  echo "[network down -> no recovery]"
  setup
  mk_status "$NOW0" false true false false "$NOW0"
  run_once "$NOW0"
  assert_eq "status says network_ok=false: no broadcast" 0 "$(am_count)"
  assert_contains "logged net_down" "$T/root/watchdog.log" 'net_down'
  teardown
  setup
  touch "$T/net_down"
  mk_status $((NOW0 - 1000)) true true false true $((NOW0 - 1000))
  run_once "$NOW0"
  assert_eq "stale status + no default route: no broadcast" 0 "$(am_count)"
  rm -f "$T/net_down"
  run_once $((NOW0 + 1))
  assert_eq "network back: recovers" 1 "$(am_count)"
  teardown
}

t_lock_and_signals() {
  echo "[lock file / duplicate instance / SIGTERM]"
  setup
  # stale lock (dead pid) is taken over
  echo 999999 > "$T/root/watchdog.lock"
  mk_status "$NOW0" true true false true "$NOW0"
  run_once "$NOW0"
  assert_eq "dead-pid lock replaced: exit 0" 0 "$RC"
  assert_eq "lock removed on exit" no "$([ -f "$T/root/watchdog.lock" ] && echo yes || echo no)"
  # lock pointing to a live but unrelated process is taken over
  sleep 30 &
  other=$!
  echo "$other" > "$T/root/watchdog.lock"
  run_once "$NOW0"
  assert_eq "live non-watchdog pid in lock: exit 0" 0 "$RC"
  kill "$other" 2> /dev/null
  wait "$other" 2> /dev/null
  rm -f "$T/root/watchdog.lock"

  # real long-running instance
  set_control 1 30 5 com.smswebgateway.android
  (
    unset SMSGATE_NOW SMSGATE_ONESHOT
    exec $CUR_SHELL "$WD" > "$T/bg.out" 2>&1
  ) &
  bg=$!
  n=0
  while [ ! -s "$T/root/watchdog.lock" ] && [ "$n" -lt 50 ]; do
    sleep 0.1
    n=$((n + 1))
  done
  assert_eq "lock holds bg pid" "$bg" "$(head -n 1 "$T/root/watchdog.lock" 2> /dev/null)"
  n=0
  while [ ! -s "$T/root/watchdog.status" ] && [ "$n" -lt 50 ]; do
    sleep 0.1
    n=$((n + 1))
  done
  assert_eq "status pid is bg pid" "$bg" "$(st pid)"
  # second instance must be refused
  before=$(am_count)
  run_once "$NOW0"
  assert_eq "second instance refused (exit 3)" 3 "$RC"
  assert_eq "second instance sent nothing" "$before" "$(am_count)"
  assert_eq "lock still owned by first" "$bg" "$(head -n 1 "$T/root/watchdog.lock" 2> /dev/null)"
  assert_contains "refusal logged" "$T/root/watchdog.log" 'already_running'
  # SIGTERM -> clean exit
  kill -TERM "$bg"
  n=0
  while kill -0 "$bg" 2> /dev/null && [ "$n" -lt 50 ]; do
    sleep 0.1
    n=$((n + 1))
  done
  if kill -0 "$bg" 2> /dev/null; then
    bad "SIGTERM: process exited"
    kill -KILL "$bg" 2> /dev/null
  else
    ok "SIGTERM: process exited"
  fi
  wait "$bg" 2> /dev/null
  assert_eq "SIGTERM exit status 0" 0 "$?"
  assert_eq "lock removed after SIGTERM" no "$([ -f "$T/root/watchdog.lock" ] && echo yes || echo no)"
  assert_contains "stop logged" "$T/root/watchdog.log" ' INFO stop '
  teardown
}

t_log() {
  echo "[log format & rotation]"
  setup
  mk_status $((NOW0 - 1000)) true true false true $((NOW0 - 1000))
  run_once "$NOW0"
  assert_contains "ISO8601 line format" "$T/root/watchdog.log" \
    '^2025-10-09T08:53:20Z \(INFO\|WARN\|ERROR\) [a-z_]* '
  # rotation
  i=0
  : > "$T/root/watchdog.log"
  while [ "$i" -lt 3000 ]; do
    echo "2025-10-09T08:53:20Z INFO filler 0123456789012345678901234567890123456789012345678901234567890123456789" >> "$T/root/watchdog.log"
    i=$((i + 1))
  done
  size_before=$(wc -c < "$T/root/watchdog.log")
  [ "$size_before" -gt 262144 ] && ok "fixture log > 256KB" || bad "fixture log > 256KB ($size_before)"
  run_once $((NOW0 + 1))
  assert_eq "rotated file exists" yes "$([ -f "$T/root/watchdog.log.1" ] && echo yes || echo no)"
  size_after=$(wc -c < "$T/root/watchdog.log")
  [ "$size_after" -lt 262144 ] && ok "new log below limit" || bad "new log below limit ($size_after)"
  teardown
}

t_static() {
  echo "[static checks]"
  files="$MODDIR/watchdog.sh $MODDIR/service.sh $MODDIR/uninstall.sh $MODDIR/customize.sh $MODDIR/build.sh $MODDIR/README.md"
  # shellcheck disable=SC2086
  for pat in 'force-stop' 'pm disable' 'pm clear' 'pm uninstall' 'cmd package' 'setenforce' 'settings put' \
    'curl' 'wget' 'busybox nc' 'mount' 'eval ' 'am start' 'am startservice' 'killall' 'pkill' 'su -c'; do
    if grep -n -- "$pat" $files > /dev/null 2>&1; then
      bad "forbidden pattern absent: '$pat'"
      grep -n -- "$pat" $files
    else
      ok "forbidden pattern absent: '$pat'"
    fi
  done
  if grep -nwE 'nc|netcat|telnet|ssh|ftp|tftp' $files > /dev/null 2>&1; then
    bad "no network tools"
  else
    ok "no network tools"
  fi
  # kill: only on our own sleep child (watchdog) / verified watchdog pid (uninstall)
  unexpected=$(grep -nw 'kill' "$MODDIR/watchdog.sh" "$MODDIR/service.sh" "$MODDIR/uninstall.sh" "$MODDIR/customize.sh" |
    grep -v 'kill "\$SLEEP_PID"' | grep -v 'kill -TERM "\$pid"' | grep -v '^[^:]*:[0-9]*: *#')
  if [ -z "$unexpected" ]; then ok "kill only used on own sleep child / own watchdog pid"; else bad "unexpected kill: $unexpected"; fi

  for s in dash "busybox sh"; do
    command -v "${s%% *}" > /dev/null 2>&1 || continue
    for f in watchdog.sh service.sh uninstall.sh customize.sh build.sh tests/run_tests.sh; do
      if $s -n "$MODDIR/$f" 2> /dev/null; then ok "syntax ($s -n) $f"; else bad "syntax ($s -n) $f"; fi
    done
  done
  if command -v shellcheck > /dev/null 2>&1; then
    if shellcheck -s sh -e SC2086,SC2034,SC2154,SC2181 "$MODDIR/watchdog.sh" "$MODDIR/service.sh" "$MODDIR/uninstall.sh"; then
      ok "shellcheck"
    else
      bad "shellcheck"
    fi
  else
    echo "  note shellcheck not installed: only sh -n syntax checks were run"
  fi
  # module.prop
  for k in id name version versionCode author description; do
    grep -q "^$k=." "$MODDIR/module.prop" && ok "module.prop has $k" || bad "module.prop has $k"
  done
  assert_contains "module id" "$MODDIR/module.prop" '^id=smsgate_watchdog$'
}

run_suite() {
  CUR_SHELL=$1
  echo "=============== suite with: $CUR_SHELL ==============="
  t_healthy
  t_stale_status
  t_cooldown_sequence
  t_not_bound_user_stopped
  t_force_stopped
  t_locked_user
  t_disabled
  t_invalid_pkg
  t_network_down
  t_lock_and_signals
  t_log
}

SHELLS=""
command -v dash > /dev/null 2>&1 && SHELLS="dash"
command -v busybox > /dev/null 2>&1 && busybox sh -c : > /dev/null 2>&1 && SHELLS="$SHELLS|busybox sh"
[ -z "$SHELLS" ] && SHELLS="sh"

OLDIFS=$IFS
IFS='|'
for s in $SHELLS; do
  IFS=$OLDIFS
  run_suite "$s"
  IFS='|'
done
IFS=$OLDIFS

t_static

echo
echo "RESULT: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
