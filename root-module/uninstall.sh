#!/system/bin/sh
# Stop the watchdog and remove only the files this module created.
# Does NOT touch any system setting, package or other app.
DATA_DIR=/data/adb/smsgate
if [ "${SMSGATE_TEST:-}" = "1" ]; then
  DATA_DIR=${SMSGATE_ROOT_DIR:-$DATA_DIR}
fi
LOCK=$DATA_DIR/watchdog.lock

if [ -f "$LOCK" ]; then
  pid=$(head -n 1 "$LOCK" 2>/dev/null | tr -d '\r\n ')
  case $pid in
    '' | *[!0-9]*) ;;
    *)
      # Only signal our own watchdog (verified by cmdline), SIGTERM -> clean exit.
      if [ -d "/proc/$pid" ] && tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q 'watchdog\.sh'; then
        kill -TERM "$pid" 2>/dev/null
        n=0
        while [ -d "/proc/$pid" ] && [ "$n" -lt 10 ]; do
          sleep 1
          n=$((n + 1))
        done
      fi
      ;;
  esac
fi

rm -f "$DATA_DIR/watchdog.lock" "$DATA_DIR/watchdog.status" \
  "$DATA_DIR/watchdog.log" "$DATA_DIR/watchdog.log.1" 2>/dev/null
rm -f "$DATA_DIR"/watchdog.status.tmp.* 2>/dev/null
# 'control' belongs to the app; leave it. Remove the dir only if now empty.
rmdir "$DATA_DIR" 2>/dev/null
exit 0
