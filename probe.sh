#!/usr/bin/env bash
# Drive the #783 RFCOMM probe from the terminal.
#
#   ./probe.sh install            build, install, grant BLUETOOTH_CONNECT
#   ./probe.sh list               bonded devices + the control channel each advertises
#   ./probe.sh send <mac> <uuid> <payload-hex> [type] [seq]
#   ./probe.sh raw  <mac> <uuid> <payload-hex>      bytes verbatim, no framing
#   ./probe.sh scan                                 what is advertising over LE
#   ./probe.sh gatt <name> <hex,hex> [service]      BLE — the JBL's control path
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
  sweep)
    # Walk a protocol's read surface. GET-shaped packets only — see Sweep.kt.
    mac="${2:?mac}"; uuid="${3:?uuid}"; proto="${4:?proto: bose|fastpair}"
    args=(--es op sweep --es mac "$mac" --es uuid "$uuid" --es proto "$proto")
    [ -n "${5:-}" ] && args+=(--es blocks "$5")
    [ -n "${6:-}" ] && args+=(--es fns "$6")
    "${ADB[@]}" shell am start -n "$ACT" "${args[@]}" >/dev/null
    watch_log "${SWEEP_WAIT:-180}"
    ;;
  seq)
    # The WRITE tool: several packets down ONE socket, in order. Bose edits are
    # transactional (an operator-05 Start, then the change), so they cannot be
    # expressed with `send`, which opens a fresh socket per packet.
    mac="${2:?mac}"; uuid="${3:?uuid}"; packets="${4:?comma-separated hex}"
    "${ADB[@]}" shell am start -n "$ACT" --es op seq \
      --es mac "$mac" --es uuid "$uuid" --es packets "$packets" >/dev/null
    watch_log "${SEQ_WAIT:-30}"
    ;;
  scan)
    "${ADB[@]}" shell am start -n "$ACT" --es op scan >/dev/null
    watch_log 14
    ;;
  gatt)
    # The LE write path, addressed by advertised NAME: the LE address rotates, so a
    # literal one goes stale and fails slowly rather than saying "no such device".
    who="${2:?device name substring, e.g. 'JBL TOUR', or a MAC}"
    packets="${3:?comma-separated hex}"
    # A MAC is accepted for the case where the address is known-current (a fresh
    # scan, a capture); anything else is treated as a name to resolve.
    if [[ "$who" =~ ^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$ ]]; then
      args=(--es op gatt --es mac "$who" --es packets "$packets")
    else
      # ⚠ Quoted for the DEVICE's shell too. `adb shell` joins its argv into one
      # command line and re-splits it there, so a name with a space arrives as two
      # arguments and `am` reads the second as the next flag.
      args=(--es op gatt --es name "'$who'" --es packets "$packets")
    fi
    [ -n "${4:-}" ] && args+=(--es service "$4")
    "${ADB[@]}" shell am start -n "$ACT" "${args[@]}" >/dev/null
    watch_log "${GATT_WAIT:-40}"
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
