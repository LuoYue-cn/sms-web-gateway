#!/bin/sh
# Package the module into dist/smsgate-watchdog.zip (module files at the zip root).
# Also copies the zip to android/app/src/main/assets/root-module.zip when that directory exists.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
PROJECT=$(dirname "$HERE")
OUT_DIR=$PROJECT/dist
OUT=$OUT_DIR/smsgate-watchdog.zip
ASSETS=$PROJECT/android/app/src/main/assets

command -v zip > /dev/null 2>&1 || { echo "build.sh: 'zip' not found" >&2; exit 1; }

for f in module.prop service.sh watchdog.sh uninstall.sh customize.sh README.md; do
  [ -f "$HERE/$f" ] || { echo "build.sh: missing $f" >&2; exit 1; }
done

mkdir -p "$OUT_DIR"
rm -f "$OUT"
chmod 755 "$HERE/service.sh" "$HERE/watchdog.sh" "$HERE/uninstall.sh" "$HERE/customize.sh"
(
  cd "$HERE"
  zip -X -q "$OUT" module.prop service.sh watchdog.sh uninstall.sh customize.sh README.md
)
echo "built $OUT"

if [ -d "$ASSETS" ]; then
  cp -f "$OUT" "$ASSETS/root-module.zip"
  echo "copied to $ASSETS/root-module.zip"
fi
