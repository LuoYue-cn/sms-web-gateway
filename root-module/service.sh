#!/system/bin/sh
# late_start service: wait for boot, then start the watchdog (single instance).
MODDIR=${0%/*}
DATA_DIR=/data/adb/smsgate
LOCK=$DATA_DIR/watchdog.lock

if [ "${SMSGATE_TEST:-}" = "1" ]; then
  DATA_DIR=${SMSGATE_ROOT_DIR:-$DATA_DIR}
  LOCK=$DATA_DIR/watchdog.lock
fi

# Respect the module being disabled/removed.
[ -e "$MODDIR/disable" ] && exit 0
[ -e "$MODDIR/remove" ] && exit 0

mkdir -p "$DATA_DIR" 2>/dev/null
chmod 700 "$DATA_DIR" 2>/dev/null

# Wait for the system to finish booting (bounded: ~10 minutes).
i=0
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
  i=$((i + 1))
  [ "$i" -gt 300 ] && exit 0
  sleep 2
done
sleep 5

# Do not start a second instance if the lock holder is alive.
if [ -f "$LOCK" ]; then
  pid=$(head -n 1 "$LOCK" 2>/dev/null | tr -d '\r\n ')
  case $pid in
    '' | *[!0-9]*) ;;
    *)
      if [ -d "/proc/$pid" ] && tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q 'watchdog\.sh'; then
        exit 0
      fi
      ;;
  esac
fi

nohup sh "$MODDIR/watchdog.sh" > /dev/null 2>&1 &
exit 0
