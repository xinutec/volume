#!/usr/bin/env bash
# Build the probe and install it on the Pixel 9.
#
#   nix develop ~/Code/recall#android --command ./deploy.sh
#
# Run it from inside that devshell: adb comes from the shell's SDK
# ($ANDROID_HOME/platform-tools/adb), not from the ambient PATH, so the adb that
# installs is the one the SDK ships.
#
# Two-step device selection, because neither half is sufficient alone. The
# **address** is needed to bring the transport up over the VPN (`adb connect`;
# the phone listens on the persistent 5555 set once with `adb tcpip 5555`), and
# the VPN address is the stable one — the LAN lease drifts, which is why the
# govee deploy script still carries a dead 192.168.1.133. The **model** is what
# then picks the target, because a Pixel 5 is usually adb-connected at the same
# time and the Pixel 9 answers on two transports at once (VPN and mdns).
#
# Overridable: VOLUME_ADDR, VOLUME_MODEL.
set -euo pipefail
cd "$(dirname "$0")"

PKG=org.xinutec.volume
ADDR="${VOLUME_ADDR:-10.100.0.12}"
MODEL="${VOLUME_MODEL:-Pixel_9}"
ADB="$ANDROID_HOME/platform-tools/adb"

"$ADB" connect "$ADDR:5555" >/dev/null 2>&1 || true

serial=$("$ADB" devices -l | awk -v m="model:$MODEL" '$0 ~ m && $2 == "device" { print $1; exit }')
if [ -z "$serial" ]; then
  echo "no adb device with model:$MODEL — connected devices:" >&2
  "$ADB" devices -l >&2
  exit 1
fi

echo "building..."
./gradlew --console=plain -q :app:assembleDebug
APK="$PWD/app/build/outputs/apk/debug/app-debug.apk"

echo "=== $serial (model:$MODEL) ==="
"$ADB" -s "$serial" install -r "$APK"
# Pre-granted so the probe can be driven headlessly — it never shows a permission
# dialog, so without these a run fails with a SecurityException. CONNECT opens
# sockets to bonded devices; SCAN resolves the rotating LE address of a GATT
# control channel, which no bonded-device list can supply.
"$ADB" -s "$serial" shell pm grant "$PKG" android.permission.BLUETOOTH_CONNECT
"$ADB" -s "$serial" shell pm grant "$PKG" android.permission.BLUETOOTH_SCAN
# -S force-stops first. Without it the intent is delivered to the *existing* task
# (adb says "intent has been delivered to currently running top-most instance"),
# so a deploy can appear to succeed while the old build is still on screen.
#
# VolumeActivity, not MainActivity: the app is what a deploy should put on screen.
# probe.sh starts the probe by name when it wants it.
"$ADB" -s "$serial" shell am start -S -n "$PKG/.VolumeActivity" >/dev/null
echo "installed on $serial — drive it with ./probe.sh"
