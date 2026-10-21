#!/system/bin/sh
# SMS Web Gateway - Root Watchdog (main loop)
#
# Contract: docs/ROOT_INTERFACE.md (single source of truth).
# - Only reads local files (control, DE status.json) and sends ONE kind of
#   command to the APK: an explicit WATCHDOG_RECOVER broadcast.
# - No network access, no remote commands, no system settings changes.
# - POSIX sh / busybox ash / mksh compatible. No bash, jq, python.
#
# Test overrides (SMSGATE_*) are honoured ONLY when SMSGATE_TEST=1.

LC_ALL=C
export LC_ALL
umask 077

WD_VERSION="1.0.0"
DEFAULT_PKG="com.smswebgateway.android"
RECOVER_ACTION="com.smswebgateway.android.action.WATCHDOG_RECOVER"
MAX_LOG_BYTES=262144
STALE_HEARTBEAT=120
STALE_SERVER=600
MIN_INTERVAL=30
MAX_INTERVAL=300
DEFAULT_INTERVAL=60
DEFAULT_MAXF=5
MAX_MAXF=20
NET_DOWN_MIN_SLEEP=60

case $0 in
  */*) SELF_DIR=${0%/*} ;;
  *) SELF_DIR=. ;;
esac

ROOT_DIR=/data/adb/smsgate
MOD_DIR=$SELF_DIR
DE_DIR_OVERRIDE=""
FAKE_AM=""
FAKE_GETPROP=""
FAKE_PIDOF=""
FAKE_IP=""
FAKE_DUMPSYS=""
NOW_OVERRIDE=""
ONESHOT=0

if [ "${SMSGATE_TEST:-}" = "1" ]; then
  FAKE_DUMPSYS=${SMSGATE_FAKE_DUMPSYS:-}
  ROOT_DIR=${SMSGATE_ROOT_DIR:-$ROOT_DIR}
  MOD_DIR=${SMSGATE_MODDIR:-$MOD_DIR}
  DE_DIR_OVERRIDE=${SMSGATE_DE_DIR:-}
  FAKE_AM=${SMSGATE_FAKE_AM:-}
  FAKE_GETPROP=${SMSGATE_FAKE_GETPROP:-}
  FAKE_PIDOF=${SMSGATE_FAKE_PIDOF:-}
  FAKE_IP=${SMSGATE_FAKE_IP:-}
  NOW_OVERRIDE=${SMSGATE_NOW:-}
  ONESHOT=${SMSGATE_ONESHOT:-0}
fi

CONTROL_FILE=$ROOT_DIR/control
STATUS_OUT=$ROOT_DIR/watchdog.status
LOG_FILE=$ROOT_DIR/watchdog.log
LOCK_FILE=$ROOT_DIR/watchdog.lock

# ---- runtime state ----
NOW=0
STATE=running
FAILURES=0
RECOVERY_COUNT=0
LAST_RECOVERY_TS=0
LAST_NOTE=""
LAST_CFG=""
LOCK_OWNED=0
STARTED=0
CLEANED=0
SLEEP_PID=""
NET_BACKOFF=0
NAP_FAILS=0
ENABLED=0
CFG_REASON=""
INTERVAL=$DEFAULT_INTERVAL
MAXF=$DEFAULT_MAXF
PKG=$DEFAULT_PKG
STATUS_JSON=""

# ---------------------------------------------------------------- helpers

now_epoch() {
  if [ -n "$NOW_OVERRIDE" ]; then
    echo "$NOW_OVERRIDE"
  else
    date +%s
  fi
}

# Non-negative integer from a string, else 0.
to_num() {
  _tn=$1
  case $_tn in
    '' | *[!0-9]*)
      echo 0
      return 0
      ;;
  esac
  if [ ${#_tn} -gt 12 ]; then
    echo 0
    return 0
  fi
  while [ ${#_tn} -gt 1 ] && [ "${_tn#0}" != "$_tn" ]; do
    _tn=${_tn#0}
  done
  echo "$_tn"
}

# Seconds from a possibly millisecond epoch value.
to_sec() {
  _ts=$(to_num "$1")
  if [ "$_ts" -gt 99999999999 ]; then
    _ts=$((_ts / 1000))
  fi
  echo "$_ts"
}

is_uint() {
  case $1 in
    '' | *[!0-9]*) return 1 ;;
  esac
  return 0
}

# Whitelist: ^[A-Za-z0-9._]+$
valid_token() {
  case $1 in
    '' | *[!A-Za-z0-9._]*) return 1 ;;
  esac
  return 0
}

# Package name: whitelist plus no path-like tricks.
valid_pkg() {
  valid_token "$1" || return 1
  case $1 in
    .* | *..* | *.) return 1 ;;
  esac
  [ ${#1} -le 255 ]
}

# Pure-shell epoch -> ISO8601 (UTC); no dependency on date -d.
iso8601() {
  _t=$(to_num "$1")
  _days=$((_t / 86400))
  _rem=$((_t % 86400))
  _z=$((_days + 719468))
  _era=$((_z / 146097))
  _doe=$((_z - _era * 146097))
  _yoe=$(((_doe - _doe / 1460 + _doe / 36524 - _doe / 146096) / 365))
  _y=$((_yoe + _era * 400))
  _doy=$((_doe - (365 * _yoe + _yoe / 4 - _yoe / 100)))
  _mp=$(((5 * _doy + 2) / 153))
  _d=$((_doy - (153 * _mp + 2) / 5 + 1))
  if [ "$_mp" -lt 10 ]; then
    _m=$((_mp + 3))
  else
    _m=$((_mp - 9))
  fi
  if [ "$_m" -le 2 ]; then
    _y=$((_y + 1))
  fi
  printf '%04d-%02d-%02dT%02d:%02d:%02dZ' "$_y" "$_m" "$_d" \
    $((_rem / 3600)) $(((_rem % 3600) / 60)) $((_rem % 60))
}

# log_line LEVEL ACTION DETAIL   (details are internal constants only)
log_line() {
  [ -d "$ROOT_DIR" ] || return 0
  if [ -f "$LOG_FILE" ]; then
    _sz=$(wc -c < "$LOG_FILE" 2>/dev/null)
    case $_sz in
      '' | *[!0-9\ ]*) _sz=0 ;;
    esac
    _sz=$((_sz + 0))
    if [ "$_sz" -ge "$MAX_LOG_BYTES" ]; then
      mv -f "$LOG_FILE" "$LOG_FILE.1" 2>/dev/null
    fi
  fi
  printf '%s %s %s %s\n' "$(iso8601 "$NOW")" "$1" "$2" "$3" >> "$LOG_FILE" 2>/dev/null
}

# Log only when the message differs from the previous one (anti-spam).
note() {
  _nk="$1|$2|$3"
  if [ "$_nk" != "$LAST_NOTE" ]; then
    LAST_NOTE=$_nk
    log_line "$1" "$2" "$3"
  fi
}

kv_get() { # file key
  [ -f "$1" ] || return 0
  head -c 4096 "$1" 2>/dev/null | tr -d '\r' | grep "^$2=" | tail -n 1 | cut -d= -f2- |
    sed 's/^[[:space:]]*//; s/[[:space:]]*$//'
}

ctl_get() {
  kv_get "$CONTROL_FILE" "$1"
}

getprop_val() {
  if [ -n "$FAKE_GETPROP" ]; then
    "$FAKE_GETPROP" "$1" 2>/dev/null | tr -d '\r\n '
  else
    getprop "$1" 2>/dev/null | tr -d '\r\n '
  fi
}

# JSON (flat, conservative): value of "key" -> stdout (quotes stripped).
jget() {
  printf '%s\n' "$STATUS_JSON" |
    grep -o "\"$1\"[[:space:]]*:[[:space:]]*[^,}]*" | head -n 1 |
    sed 's/^[^:]*:[[:space:]]*//; s/[[:space:]]*$//; s/^"//; s/"$//'
}

cooldown_for() { # failures so far -> seconds to wait before next attempt
  case $1 in
    0) echo 0 ;;
    1) echo 10 ;;
    2) echo 30 ;;
    3) echo 60 ;;
    *) echo 300 ;;
  esac
}

idle_state() {
  if [ "$FAILURES" -ge "$MAXF" ]; then
    STATE=protected
  else
    STATE=running
  fi
}

write_status() { # $1 = pid value
  _wtmp="$STATUS_OUT.tmp.$$"
  {
    printf 'pid=%s\n' "$1"
    printf 'ts=%s\n' "$NOW"
    printf 'state=%s\n' "$STATE"
    printf 'failures=%s\n' "$FAILURES"
    printf 'recovery_count=%s\n' "$RECOVERY_COUNT"
    printf 'last_recovery_ts=%s\n' "$LAST_RECOVERY_TS"
  } > "$_wtmp" 2>/dev/null && mv -f "$_wtmp" "$STATUS_OUT" 2>/dev/null
  chmod 600 "$STATUS_OUT" 2>/dev/null
  return 0
}

# ------------------------------------------------------- lock / lifecycle

lock_pid_alive() { # $1 = pid from lock file
  _lp=$1
  is_uint "$_lp" || return 1
  [ "$_lp" != "$$" ] || return 1
  [ -d "/proc/$_lp" ] || return 1
  if [ -r "/proc/$_lp/cmdline" ]; then
    tr '\0' ' ' < "/proc/$_lp/cmdline" 2>/dev/null | grep -q 'watchdog\.sh' || return 1
  fi
  return 0
}

acquire_lock() {
  _try=0
  while [ "$_try" -lt 2 ]; do
    if (
      set -C
      printf '%s\n' "$$" > "$LOCK_FILE"
    ) 2>/dev/null; then
      LOCK_OWNED=1
      return 0
    fi
    _lpid=$(head -n 1 "$LOCK_FILE" 2>/dev/null | tr -d '\r\n ')
    if lock_pid_alive "$_lpid"; then
      return 1
    fi
    rm -f "$LOCK_FILE"
    _try=$((_try + 1))
  done
  return 1
}

cleanup() {
  [ "$CLEANED" = 1 ] && return 0
  CLEANED=1
  # Only our own sleep child is ever signalled.
  if [ -n "$SLEEP_PID" ]; then
    kill "$SLEEP_PID" 2>/dev/null
    SLEEP_PID=""
  fi
  if [ "$LOCK_OWNED" = 1 ]; then
    if [ "$STARTED" = 1 ] && [ "$ONESHOT" != 1 ]; then
      NOW=$(now_epoch)
      log_line INFO stop "pid=$$"
      write_status 0
    fi
    _cur=$(head -n 1 "$LOCK_FILE" 2>/dev/null | tr -d '\r\n ')
    if [ "$_cur" = "$$" ]; then
      rm -f "$LOCK_FILE"
    fi
  fi
  return 0
}

load_persisted() {
  [ -f "$STATUS_OUT" ] || return 0
  RECOVERY_COUNT=$(to_num "$(kv_get "$STATUS_OUT" recovery_count)")
  LAST_RECOVERY_TS=$(to_num "$(kv_get "$STATUS_OUT" last_recovery_ts)")
  _pts=$(to_num "$(kv_get "$STATUS_OUT" ts)")
  _pf=$(to_num "$(kv_get "$STATUS_OUT" failures)")
  _age=$((NOW - _pts))
  # Only trust failure counters from a recent run (watchdog restart), so a
  # crash loop cannot bypass protection but a reboot starts fresh.
  if [ "$_pts" -gt 0 ] && [ "$_age" -ge 0 ] && [ "$_age" -le 900 ]; then
    FAILURES=$_pf
  fi
}

module_disabled() {
  [ -e "$MOD_DIR/disable" ] || [ -e "$MOD_DIR/remove" ]
}

# ------------------------------------------------------------ config read

read_control() {
  ENABLED=0
  CFG_REASON=""
  INTERVAL=$DEFAULT_INTERVAL
  MAXF=$DEFAULT_MAXF
  PKG=$DEFAULT_PKG
  if [ ! -f "$CONTROL_FILE" ]; then
    CFG_REASON=control_missing
    return 0
  fi
  _e=$(ctl_get enabled)
  if [ "$_e" = "1" ]; then
    ENABLED=1
  else
    CFG_REASON=enabled_0
  fi
  _i=$(ctl_get interval)
  if is_uint "$_i"; then
    INTERVAL=$(to_num "$_i")
    if [ "$INTERVAL" -lt "$MIN_INTERVAL" ]; then
      INTERVAL=$MIN_INTERVAL
    elif [ "$INTERVAL" -gt "$MAX_INTERVAL" ]; then
      INTERVAL=$MAX_INTERVAL
    fi
  fi
  _m=$(ctl_get max_failures)
  if is_uint "$_m"; then
    MAXF=$(to_num "$_m")
    if [ "$MAXF" -lt 1 ]; then
      MAXF=$DEFAULT_MAXF
    elif [ "$MAXF" -gt "$MAX_MAXF" ]; then
      MAXF=$MAX_MAXF
    fi
  fi
  _p=$(ctl_get pkg)
  if [ -n "$_p" ]; then
    if valid_pkg "$_p"; then
      PKG=$_p
    else
      ENABLED=0
      CFG_REASON=invalid_pkg
    fi
  fi
  return 0
}

# --------------------------------------------------------- system probes

ce_unlocked() {
  [ "$(getprop_val sys.user.0.ce_available)" = "true" ]
}

proc_state() { # yes | no | unknown
  if [ -n "$FAKE_PIDOF" ]; then
    if "$FAKE_PIDOF" "$PKG" > /dev/null 2>&1; then echo yes; else echo no; fi
    return 0
  fi
  if command -v pidof > /dev/null 2>&1; then
    if pidof "$PKG" > /dev/null 2>&1; then echo yes; else echo no; fi
    return 0
  fi
  if [ ! -d /proc/self ]; then
    echo unknown
    return 0
  fi
  for _pd in /proc/[0-9]*; do
    _c=$(tr '\0' '\n' < "$_pd/cmdline" 2>/dev/null | head -n 1)
    if [ "$_c" = "$PKG" ]; then
      echo yes
      return 0
    fi
  done
  echo no
}

# Read-only query of the package manager's "stopped" flag: yes | no | unknown.
# An explicit-component broadcast DOES start a package the user stopped from Settings (and clears
# that flag), so the watchdog must never send one while the package is in the stopped state.
pkg_stopped() {
  _dsys=dumpsys
  if [ -n "$FAKE_DUMPSYS" ]; then
    _dsys=$FAKE_DUMPSYS
  fi
  if command -v timeout > /dev/null 2>&1; then
    _po=$(timeout 10 "$_dsys" package "$PKG" 2> /dev/null)
  else
    _po=$("$_dsys" package "$PKG" 2> /dev/null)
  fi
  _pl=$(printf '%s\n' "$_po" | grep 'User 0:.*stopped=' | head -n 1)
  case $_pl in
    *stopped=true*) echo yes ;;
    *stopped=false*) echo no ;;
    *) echo unknown ;;
  esac
}

# Local routing-table check only (no traffic is generated). up|down|unknown
net_state() {
  if [ -n "$FAKE_IP" ]; then
    _ipc=$FAKE_IP
  elif command -v ip > /dev/null 2>&1; then
    _ipc=ip
  else
    echo unknown
    return 0
  fi
  _o4=$("$_ipc" route show table all 2> /dev/null)
  _rc4=$?
  _o6=$("$_ipc" -6 route show table all 2> /dev/null)
  if printf '%s\n%s\n' "$_o4" "$_o6" | grep -q '^default'; then
    echo up
    return 0
  fi
  if [ "$_rc4" -ne 0 ] && [ -z "$_o4" ]; then
    echo unknown
    return 0
  fi
  echo down
}

# ------------------------------------------------------------- recovery

# do_recover REASON ATTEMPT -> sets REC_RC
do_recover() {
  REC_RC=1
  valid_token "$1" || return 1
  is_uint "$2" || return 1
  valid_pkg "$PKG" || return 1
  _am=am
  if [ -n "$FAKE_AM" ]; then
    _am=$FAKE_AM
  fi
  if command -v timeout > /dev/null 2>&1; then
    timeout 15 "$_am" broadcast --user 0 \
      -a "$RECOVER_ACTION" \
      -n "$PKG/.keepalive.RecoverReceiver" \
      --es reason "$1" --ei attempt "$2" > /dev/null 2>&1
  else
    "$_am" broadcast --user 0 \
      -a "$RECOVER_ACTION" \
      -n "$PKG/.keepalive.RecoverReceiver" \
      --es reason "$1" --ei attempt "$2" > /dev/null 2>&1
  fi
  REC_RC=$?
  return 0
}

# --------------------------------------------------------------- one cycle

cycle() {
  NOW=$(now_epoch)
  NET_BACKOFF=0
  read_control

  _cfg="$ENABLED|$INTERVAL|$MAXF|$PKG"
  if [ "$_cfg" != "$LAST_CFG" ]; then
    LAST_CFG=$_cfg
    log_line INFO config "enabled=$ENABLED interval=$INTERVAL max_failures=$MAXF pkg=$PKG"
  fi

  if [ "$ENABLED" != 1 ]; then
    STATE=disabled
    FAILURES=0
    if [ "$CFG_REASON" = invalid_pkg ]; then
      note WARN idle "reason=invalid_pkg"
    else
      note INFO idle "reason=$CFG_REASON"
    fi
    write_status $$
    return 0
  fi

  # User-initiated protection reset (control: reset_protection=<ts>).
  _rp=$(to_sec "$(ctl_get reset_protection)")
  if [ "$_rp" -gt "$LAST_RECOVERY_TS" ] && [ "$FAILURES" -gt 0 ]; then
    FAILURES=0
    log_line INFO reset_protection "ts=$_rp"
  fi

  if [ "$(getprop_val sys.boot_completed)" != "1" ]; then
    idle_state
    note INFO wait_boot ""
    write_status $$
    return 0
  fi

  # Credential-encrypted storage not available: never touch CE data, never recover.
  if ! ce_unlocked; then
    idle_state
    note INFO wait_unlock "ce_not_available"
    write_status $$
    return 0
  fi

  if [ -n "$DE_DIR_OVERRIDE" ]; then
    _sdir=$DE_DIR_OVERRIDE
  else
    _sdir=/data/user_de/0/$PKG/files/keepalive
  fi
  if [ ! -d "$_sdir" ]; then
    idle_state
    note INFO skip "no_app_data"
    write_status $$
    return 0
  fi

  _sfile=$_sdir/status.json
  A_PRESENT=0
  A_TS=0
  A_SVC=""
  A_BOUND=""
  A_STOPPED=""
  A_NET=""
  A_SRV=0
  A_STARTED=0
  if [ -f "$_sfile" ]; then
    STATUS_JSON=$(head -c 16384 "$_sfile" 2>/dev/null | tr -d '\r')
    if [ -n "$STATUS_JSON" ]; then
      A_PRESENT=1
      A_TS=$(to_sec "$(jget ts)")
      A_SVC=$(jget service_running)
      A_BOUND=$(jget bound)
      A_STOPPED=$(jget user_stopped)
      A_NET=$(jget network_ok)
      A_SRV=$(to_sec "$(jget last_server_ok_at)")
      A_STARTED=$(to_sec "$(jget service_started_at)")
    fi
  fi

  # Never recover for an unbound app or after an explicit in-app stop.
  if [ "$A_PRESENT" = 1 ]; then
    if [ "$A_BOUND" = "false" ]; then
      FAILURES=0
      STATE=running
      note INFO skip "not_bound"
      write_status $$
      return 0
    fi
    if [ "$A_STOPPED" = "true" ]; then
      FAILURES=0
      STATE=running
      note INFO skip "user_stopped"
      write_status $$
      return 0
    fi
  fi

  # ---- health evaluation (process existence alone is never "healthy") ----
  PROC=$(proc_state)
  HEALTH=ok
  FRESH=0
  if [ "$A_PRESENT" != 1 ]; then
    HEALTH=status_missing
  else
    _age=$((NOW - A_TS))
    if [ "$_age" -lt 0 ]; then
      _age=0
    fi
    if [ "$A_TS" -eq 0 ] || [ "$_age" -gt "$STALE_HEARTBEAT" ]; then
      HEALTH=heartbeat_stale
    else
      FRESH=1
      if [ "$A_SVC" = "false" ] && [ "$A_BOUND" = "true" ]; then
        HEALTH=service_not_running
      elif [ "$A_SVC" = "true" ] && [ "$PROC" = "no" ]; then
        HEALTH=process_missing
      elif [ "$A_NET" = "true" ] && [ "$A_SVC" = "true" ]; then
        _ref=$A_SRV
        if [ "$_ref" -eq 0 ]; then
          _ref=$A_STARTED
        fi
        if [ "$_ref" -gt 0 ] && [ $((NOW - _ref)) -gt "$STALE_SERVER" ]; then
          HEALTH=server_stale
        fi
      fi
    fi
  fi

  if [ "$HEALTH" = ok ]; then
    if [ "$FAILURES" -gt 0 ] || [ "$STATE" = protected ]; then
      log_line INFO healthy "failures_cleared=$FAILURES"
    fi
    FAILURES=0
    STATE=running
    note INFO healthy "proc=$PROC"
    write_status $$
    return 0
  fi

  # ---- unhealthy: decide whether we may act ----
  # A user-initiated stop (Settings) must be respected: never revive the app. If the state cannot
  # be determined, fail safe and do nothing rather than risk bypassing the user's stop.
  _stopped=$(pkg_stopped)
  if [ "$_stopped" != no ]; then
    FAILURES=0
    STATE=running
    if [ "$_stopped" = yes ]; then
      note INFO skip "force_stopped reason=$HEALTH"
    else
      note WARN skip "stopped_state_unknown reason=$HEALTH"
    fi
    write_status $$
    return 0
  fi

  if [ "$FRESH" = 1 ]; then
    case $A_NET in
      true) _net=up ;;
      false) _net=down ;;
      *) _net=$(net_state) ;;
    esac
  else
    _net=$(net_state)
  fi
  if [ "$_net" = "down" ]; then
    idle_state
    NET_BACKOFF=1
    note WARN skip "net_down reason=$HEALTH"
    write_status $$
    return 0
  fi

  # Protection first: too many consecutive failed recoveries -> log only.
  if [ "$FAILURES" -ge "$MAXF" ]; then
    STATE=protected
    note WARN protected "reason=$HEALTH failures=$FAILURES max_failures=$MAXF"
    write_status $$
    return 0
  fi

  _wait=$(cooldown_for "$FAILURES")
  _since=$((NOW - LAST_RECOVERY_TS))
  if [ "$FAILURES" -gt 0 ] && [ "$_since" -lt "$_wait" ]; then
    STATE=cooldown
    note INFO cooldown "reason=$HEALTH failures=$FAILURES wait=${_wait}s"
    write_status $$
    return 0
  fi

  _attempt=$((FAILURES + 1))
  do_recover "$HEALTH" "$_attempt"
  FAILURES=$_attempt
  RECOVERY_COUNT=$((RECOVERY_COUNT + 1))
  LAST_RECOVERY_TS=$NOW
  STATE=cooldown
  LAST_NOTE=""
  log_line INFO recover "reason=$HEALTH attempt=$_attempt proc=$PROC net=$_net rc=$REC_RC"
  write_status $$
  return 0
}

nap() {
  _nsec=$INTERVAL
  if [ "$NET_BACKOFF" = 1 ] && [ "$_nsec" -lt "$NET_DOWN_MIN_SLEEP" ]; then
    _nsec=$NET_DOWN_MIN_SLEEP
  fi
  sleep "$_nsec" &
  SLEEP_PID=$!
  wait "$SLEEP_PID" 2> /dev/null
  _nrc=$?
  SLEEP_PID=""
  return "$_nrc"
}

# ------------------------------------------------------------------ main

mkdir -p "$ROOT_DIR" 2> /dev/null
chmod 700 "$ROOT_DIR" 2> /dev/null
NOW=$(now_epoch)

if ! acquire_lock; then
  log_line WARN already_running "pid=$$"
  exit 3
fi

trap 'exit 0' TERM INT HUP
trap cleanup EXIT

STARTED=1
load_persisted
log_line INFO start "pid=$$ version=$WD_VERSION oneshot=$ONESHOT"

while :; do
  if module_disabled; then
    NOW=$(now_epoch)
    STATE=disabled
    log_line INFO module_disabled ""
    break
  fi
  cycle
  if [ "$ONESHOT" = 1 ]; then
    break
  fi
  nap
  if [ $? -ne 0 ]; then
    # sleep interrupted/failed: tolerate a couple of times, never spin.
    NAP_FAILS=$((NAP_FAILS + 1))
    if [ "$NAP_FAILS" -ge 3 ]; then
      NOW=$(now_epoch)
      log_line ERROR sleep_failed ""
      exit 1
    fi
  else
    NAP_FAILS=0
  fi
done

exit 0
