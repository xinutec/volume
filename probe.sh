#!/usr/bin/env bash
# Drive the #783 RFCOMM probe from the terminal.
#
#   ./probe.sh install            build, install, grant BLUETOOTH_CONNECT
#   ./probe.sh list               bonded devices + the control channel each advertises
#   ./probe.sh send <mac> <uuid> <payload-hex> [type] [seq]
#   ./probe.sh raw  <mac> <uuid> <payload-hex>      bytes verbatim, no framing
#   ./probe.sh scan                                 what is advertising over LE
#   ./probe.sh gattmap <name>                       every GATT service + property
#   ./probe.sh gatt <name> <hex,hex> [service]      BLE — the JBL's control path
#   ./probe.sh anc  <device> [mode]                 read, or set, the ANC mode
#   ./probe.sh settings <device> [k=v …]            EQ, multipoint, auto-off, button
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

# ⚠ Start the activity so THIS run's extras are the ones handled.
#
# `am start` compares intents with `filterEquals` — action, data, type, component,
# categories — and **extras are not part of it**. Two ops differing only in their
# `--es` values are therefore the same intent: the activity is resumed rather than
# re-delivered ("its current task has been brought to the front"), and
# `handle(getIntent())` re-runs the OLD extras. That is not a display bug, it
# silently REPEATS THE PREVIOUS WRITE. Caught when a read-only `settings XM4`
# printed "→ multipoint on", having re-sent the write from the run before it.
#
# ⚠ **A unique `-d` mostly fixes it, and `am force-stop` is NOT the answer** — that
# also works but tears the activity out of a split-screen pair and off whatever
# Pippijn had on screen (see the doc's "do not drive the phone while he is using
# it"). A URI nobody reads makes `filterEquals` false, so `singleTask` delivers
# `onNewIntent` and the task stack is left alone.
#
# ⚠ **MOSTLY. It recurred once WITH the unique `-d` in place**, on 2026-08-16 at
# 18:32:53: a run asking for `0000,e8010001,…` executed the packet list from 18:01
# instead, five for five. No explanation was found. So do not treat the `-d` as a
# guarantee — **read the echoed `[n] →` bytes in the log and check they are the ones
# you asked for.** That is what caught it both times, and it is the only check here
# that does not depend on understanding the cause.
start_op() {
  "${ADB[@]}" shell am start -n "$ACT" -d "probe://run/$RANDOM$$" "$@" >/dev/null
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
    start_op --es op list
    watch_log 4
    ;;
  sweep)
    # Walk a protocol's read surface. GET-shaped packets only — see Sweep.kt.
    mac="${2:?mac}"; uuid="${3:?uuid}"; proto="${4:?proto: bose|fastpair}"
    args=(--es op sweep --es mac "$mac" --es uuid "$uuid" --es proto "$proto")
    [ -n "${5:-}" ] && args+=(--es blocks "$5")
    [ -n "${6:-}" ] && args+=(--es fns "$6")
    start_op "${args[@]}"
    watch_log "${SWEEP_WAIT:-180}"
    ;;
  seq)
    # The WRITE tool: several packets down ONE socket, in order. The Bose ANC write
    # is transactional (an operator-05 Start, then the change), so it cannot be
    # expressed with `send`, which opens a fresh socket per packet. ⚠ Only that one
    # is — Bose EQ, multipoint and the Action button each took a plain Set.
    mac="${2:?mac}"; uuid="${3:?uuid}"; packets="${4:?comma-separated hex}"
    args=(--es op seq --es mac "$mac" --es uuid "$uuid" --es packets "$packets")
    # SONY_SEQ=1 frames each payload and acks the device's data frames — its PARAM
    # reads only answer inside a session that does both.
    [ -n "${SONY_SEQ:-}" ] && args+=(--ez sony true)
    start_op "${args[@]}"
    watch_log "${SEQ_WAIT:-30}"
    ;;
  scan)
    start_op --es op scan
    watch_log 14
    ;;
  gattmap)
    # What a device's GATT actually offers. Cheaper than guessing which of a chip
    # vendor's published UUID pairs this particular model implements.
    who="${2:?device name substring}"
    start_op --es op gattmap --es name "'$who'"
    watch_log "${GATT_WAIT:-60}"
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
    start_op "${args[@]}"
    watch_log "${GATT_WAIT:-40}"
    ;;
  anc)
    # The whole stack: resolve a bonded name to a driver, open its channel, read
    # the mode, optionally set one. With no mode it only reads.
    who="${2:?device name substring}"
    args=(--es op anc --es device "'$who'")
    [ -n "${3:-}" ] && args+=(--es mode "$3")
    start_op "${args[@]}"
    watch_log "${ANC_WAIT:-40}"
    ;;
  settings)
    # Everything decoded that is not ANC. ⚠ With no k=v arguments this READS only —
    # which is the right first move against a driver nothing has ever sent.
    #
    #   ./probe.sh settings XM4                     read
    #   ./probe.sh settings XM4 eq=a1               Sony preset id, hex
    #   ./probe.sh settings XM4 autooff=never       never | when_removed
    #   ./probe.sh settings Bose eq=8,0,0           Bose bass,mid,treble in dB
    #   ./probe.sh settings Bose button=spotify     hear_battery_level | spotify
    #   ./probe.sh settings XM4 multipoint=on
    #   ./probe.sh settings XM4 dsee=on            DSEE Extreme
    #   ./probe.sh settings XM4 pause=off          pause when removed
    #   ./probe.sh settings XM4 chat=on            Speak-to-Chat
    who="${2:?device name substring}"
    args=(--es op settings --es device "'$who'")
    shift 2
    for kv in "$@"; do
      # Split on the FIRST = only: a Bose eq value is itself comma-separated and a
      # future value could hold one too.
      key="${kv%%=*}"; val="${kv#*=}"
      case "$key" in
        eq|multipoint|autooff|button|quality|dsee|pause|chat)
          args+=(--es "$key" "$val") ;;
        *) echo "unknown setting '$key' — eq, multipoint, autooff, button, quality,\
 dsee, pause, chat" >&2; exit 2 ;;
      esac
    done
    start_op "${args[@]}"
    watch_log "${SETTINGS_WAIT:-60}"
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
    start_op "${args[@]}"
    watch_log 8
    ;;
  *)
    sed -n '2,13p' "${BASH_SOURCE[0]}" >&2
    exit 2
    ;;
esac
