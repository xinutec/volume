#!/usr/bin/env bash
# Drive the #783 RFCOMM probe from the terminal.
#
#   ./probe.sh install            build, install, grant BLUETOOTH_CONNECT
#   ./probe.sh list               bonded devices + the control channel each advertises
#   ./probe.sh send <mac> <uuid> <payload-hex> [type] [seq]
#   ./probe.sh raw  <mac> <uuid> <payload-hex>      bytes verbatim, no framing
#   ./probe.sh free               force-stop every vendor app holding a channel
#
# ⚠ Hearing safety: probe with reads. Never use a volume command as the proof, and
# restore any level touched for a test in the same step.
set -euo pipefail

PKG=org.xinutec.volume
ACT="$PKG/.MainActivity"
DEV="${VOLUME_ADB_DEVICE:-10.100.0.12:5555}"   # Pixel 9 over the VPN
# adb comes from the nix profile (~/.nix-profile/bin/adb) — there is no
# ~/Library/Android/sdk on this Mac, and ANDROID_HOME is unset outside the devshell.
ADB=("${VOLUME_ADB:-adb}" -s "$DEV")
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The vendor apps hold the RFCOMM channel exclusively. A connect failure while one
# of them is running says nothing about the protocol, so make that state explicit
# rather than letting it be diagnosed twice.
VENDOR_APPS=(com.sony.songpal.mdr com.bose.bosemusic com.bose.monet jbl.stc.com
             com.harman.ble.jbllink com.jlab.app)

# Print what the app logged for this run only. -T reads from the tail, so an
# earlier run's output cannot be mistaken for this one's.
watch_log() {
  "${ADB[@]}" logcat -T 1 -s volume-probe:I &
  local pid=$!
  sleep "${1:-6}"
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}

case "${1:-list}" in
  install)
    # deploy.sh owns build + install + grant, and selects the phone by model.
    # Kept as one implementation so this script and the gate cannot disagree about
    # what "installed" means.
    (cd "$here" && nix develop ~/Code/recall#android --command ./deploy.sh)
    ;;
  free)
    for app in "${VENDOR_APPS[@]}"; do "${ADB[@]}" shell am force-stop "$app"; done
    echo "force-stopped ${#VENDOR_APPS[@]} vendor apps"
    ;;
  list)
    "${ADB[@]}" shell am start -n "$ACT" --es op list >/dev/null
    watch_log 4
    ;;
  send|raw)
    mac="${2:?mac}"; uuid="${3:?uuid}"; payload="${4:-}"
    # An empty `--es payload ""` is dropped by the shell before `am` sees it, and am
    # then consumes the NEXT flag as the value and dies. Omit the extra instead —
    # the activity defaults it to empty, which is what a connect-only probe wants.
    args=(--es op send --es mac "$mac" --es uuid "$uuid")
    [ -n "$payload" ] && args+=(--es payload "$payload")
    [ "$1" = raw ] && args+=(--ez raw true)
    [ -n "${5:-}" ] && args+=(--es type "$5")
    [ -n "${6:-}" ] && args+=(--es seq "$6")
    "${ADB[@]}" shell am start -n "$ACT" "${args[@]}" >/dev/null
    watch_log 8
    ;;
  *)
    sed -n '2,12p' "${BASH_SOURCE[0]}" >&2
    exit 2
    ;;
esac
