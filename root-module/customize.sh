#!/system/bin/sh
# Magisk / KernelSU installer hook: informational only, no dangerous actions.
SKIPUNZIP=0
ui_print "- SMS Web Gateway Watchdog"
ui_print "- Optional module. It only reads the app's local status file and"
ui_print "  sends a recovery broadcast to the app. No network, no system changes."
ui_print "- It stays idle until the app writes /data/adb/smsgate/control (enabled=1)."
ui_print "- Reboot (or enable in the app) to start the watchdog."
