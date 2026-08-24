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

# ⚠ **Only if it is not already here.** Connecting unconditionally adds a SECOND
# transport for the same phone when it is reachable on the LAN too, and every plain
# `adb` in this repo then dies with "more than one device" — including probe.sh's,
# which is how this was found on 2026-08-24.
find_serial() {
  "$ADB" devices -l | awk -v m="model:$MODEL" '$0 ~ m && $2 == "device" { print $1; exit }'
}
serial=$(find_serial)
if [ -z "$serial" ]; then
  "$ADB" connect "$ADDR:5555" >/dev/null 2>&1 || true
  serial=$(find_serial)
fi
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
# dev-lint: android-deploy allow=launch,fresh-launch
#
# ⚠ **Waived deliberately, not because the rule is noise.** Its two invariants exist
# so a deploy cannot appear to succeed while the OLD build is still on screen — `am
# start` on an existing task resumes it rather than recreating it. That hazard does
# not arise here: `install -r` above kills the running process, so whatever comes
# back is necessarily the new build. What `-S` would additionally buy is a reset to
# a freshly-created activity, and that is the precise thing we must NOT do — see
# below.
#
# ⚠ **No `am start` by default, and never `-S`.** The phone keeps Volume in a split
# screen with the agent console below it, and `am start` re-creates the task in
# FULLSCREEN — which throws the console out of the split and costs Pippijn a manual
# rebuild of his layout. `install -r` already kills the running process, so the old
# build cannot survive on screen; the system relaunches the activity in place, in
# its own half, with the new code. That is the whole reason `-S` was here, and it is
# handled without it.
#
# Pass --start when the app genuinely needs foregrounding, knowing it costs the
# split. probe.sh starts the probe by name when it wants it.
if [[ ${1:-} == --start ]]; then
    "$ADB" -s "$serial" shell am start -n "$PKG/.VolumeActivity" >/dev/null
    echo "installed and FOREGROUNDED on $serial (this drops the split screen)"
else
    echo "installed on $serial — it relaunches in place; --start to foreground it"
fi
