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
# ⚠ Ops run in ProbeService, NOT the activity — an activity can be told to start and
# never receive the intent (#967). Set VOLUME_PROBE_ACTIVITY=1 to route to the screen
# instead, which is the only way to watch a run on the phone itself.
#
# ⚠ Hearing safety: probe with reads. Never use a volume command as the proof, and
# restore any level touched for a test in the same step.
set -euo pipefail

PKG=org.xinutec.volume
ACT="$PKG/.MainActivity"
SVC="$PKG/.ProbeService"
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

# The id THIS invocation stamps on its op. The app prints it as its first line, so
# the log can be checked for it rather than read hopefully — see check_ran.
RUN_ID=""

# Device-clock timestamp taken just BEFORE the op is sent, so the check can ask for
# exactly this run's lines afterwards.
#
# ⚠ **The live stream cannot be the thing that is checked.** `logcat -T 1` starts
# AFTER `am start` and takes a moment to attach, while the app prints its run id
# immediately — so the id lands in the gap and a perfectly good run looks skipped.
# Measured 2026-08-24: that race refused fifteen consecutive healthy runs and
# produced "nothing was delivered" for `list`, whose whole output is instant. It read
# exactly like the bug it was written to catch. A timestamped re-read has no window.
RUN_SINCE=""

# Print what the app logged for this run only. -T reads from the tail, so an
# earlier run's output cannot be mistaken for this one's.
watch_log() {
  local live="${TMPDIR:-/tmp}/probe-live.$$"
  "${ADB[@]}" logcat -T 1 -s volume-probe:I > >(tee "$live") &
  local pid=$!
  sleep "${1:-6}"
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  # ⚠ **The live stream misses whatever the app printed before logcat attached**, which
  # is anything instant: a refusal, a bad-argument complaint, `list`. On 2026-08-24 that
  # swallowed a ⛔ REFUSED and the run read as though it had sent an UNPAIR and got no
  # answer — thirty seconds of checking whether the headphones were still bonded. So the
  # authoritative window is re-read and anything the stream did not show is printed.
  local dump missed
  dump=$("${ADB[@]}" logcat -d -t "$RUN_SINCE" -s volume-probe:I 2>/dev/null || true)
  # ⚠ `grep -vxF -f`, not `comm`: comm needs its inputs SORTED, which reorders the
  # transcript — the first version printed a refusal's three lines in the wrong order,
  # so the consequence read before the command it belonged to.
  missed=$(grep -vxF -f "$live" <<<"$dump" 2>/dev/null || true)
  if [ -n "${missed//[[:space:]]/}" ]; then
    echo "--- printed before the log attached:"
    echo "$missed"
  fi
  rm -f "$live"
  check_ran
}

# ⚠ **#967: `am start` can deliver NOTHING, silently.** The activity is resumed
# instead ("its current task has been brought to the front"), `onNewIntent` never
# fires, and the run produces no output at all — or re-runs the previous extras.
# Measured twice before this check existed, and the unique `-d` does not prevent it.
#
# So the app stamps every run with the id it was given, and this refuses to let a run
# that did not happen pass for one that did. **Loud is the whole point**: the failure
# this guards is a transcript that reads perfectly and describes a different run.
#
# ⚠ Re-reads the log by TIMESTAMP rather than trusting the live stream — see
# [RUN_SINCE]. A check with a race of its own is worse than no check, because it
# teaches you to ignore it.
check_ran() {
  [ -n "$RUN_ID" ] || return 0
  local log
  log=$("${ADB[@]}" logcat -d -t "$RUN_SINCE" -s volume-probe:I 2>/dev/null || true)
  case "$log" in
    *"run $RUN_ID"*) return 0 ;;
  esac
  echo >&2
  if [ -n "$log" ]; then
    echo "⚠ #967: the log above is NOT this run — it carries no 'run $RUN_ID'." >&2
    echo "  The op was not delivered and you are reading an EARLIER run's output." >&2
  else
    echo "⚠ #967: nothing was delivered — no 'run $RUN_ID' and no output at all." >&2
  fi
  echo "  Retry. If it persists, 'adb shell am force-stop $PKG' fixes delivery," >&2
  echo "  at the cost of tearing the app out of a split screen." >&2
  return 3
}

# ⚠ Start the activity so THIS run's extras are the ones handled — and see check_ran
# above, which is what actually establishes that they were.
#
# `am start` compares intents with `filterEquals` — action, data, type, component,
# categories — and **extras are not part of it**. Two ops differing only in their
# `--es` values are therefore the same intent: the activity is resumed rather than
# re-delivered, and `handle(getIntent())` re-runs the OLD extras. That is not a
# display bug, it silently REPEATS THE PREVIOUS WRITE. A unique `-d` makes
# `filterEquals` false and mostly fixes it; it has been measured to recur anyway.
start_op() {
  RUN_ID="$RANDOM$$"
  # ⚠ The DEVICE's clock, in logcat's own format: the check reads back from here, and
  # a host timestamp would be wrong by whatever the two clocks disagree by.
  #
  # ⚠ **Quoted for the PHONE's shell, not this one.** The format string contains a
  # space, and `adb shell` joins its argv and lets the device re-split it — so the
  # unquoted form reaches `date` as two arguments and dies with "Max 1 argument",
  # leaving this empty. Same hazard as hex_arg below, and it hid for a whole session
  # because an empty `-t` makes logcat dump everything, which still CONTAINS the id.
  RUN_SINCE=$("${ADB[@]}" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
  # ⚠ A SERVICE, not the activity. `am start` on an activity that is already alive
  # reports success and delivers nothing; `onStartCommand` is called for every start.
  # #967. `$ACT` is kept for `--activity`, which is still the way to watch a run on
  # the phone's own screen.
  if [ -n "${VOLUME_PROBE_ACTIVITY:-}" ]; then
    "${ADB[@]}" shell am start -n "$ACT" -d "probe://run/$RUN_ID" --es run "$RUN_ID" "$@" \
      >/dev/null
  else
    "${ADB[@]}" shell am start-foreground-service -n "$SVC" --es run "$RUN_ID" "$@" \
      >/dev/null
  fi
}

# Normalise a hex argument, and refuse anything that is not one.
#
# ⚠ **`adb shell` runs a shell ON THE PHONE, and it re-splits on spaces.** So a payload
# written the way this repo writes bytes everywhere else — `f8 04 01 11 00` — arrives as
# `f8`, and the rest is dropped. That is not a no-op: on 2026-08-24 it framed and SENT
# `f8 04 01 11`, a four-byte SYSTEM_SET_PARAM where five were meant. It happened to be
# harmless; a truncated payload landing on a different command is how `aa 95` — Bose's
# factory reset — gets sent by somebody who typed something else.
#
# ⚠ **It fails as success**: `am` reports the intent delivered and the run prints a tidy
# transcript of the wrong packet. Nothing downstream can catch it, because what the app
# receives IS a well-formed frame. So it has to be caught here, before the boundary.
#
# Spaces are stripped rather than rejected — `Hex.parse` ignores them too, so they are
# never significant and refusing them would only make this tool disagree with the docs it
# is used from. Everything else is refused loudly. #1132.
hex_arg() {
  local raw="${1//[[:space:]]/}" part
  [ -n "$raw" ] || { echo "empty hex payload" >&2; exit 2; }
  for part in ${raw//,/ }; do
    case "$part" in
      "" | *[!0-9a-fA-F]*)
        echo "not hex: '$part' (in '$1')" >&2
        exit 2
        ;;
    esac
    [ $((${#part} % 2)) -eq 0 ] || {
      echo "hex needs an even number of digits: '$part'" >&2
      exit 2
    }
  done
  printf '%s' "$raw"
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
    # ⚠ 4s was too short and the failure looks like nothing happened: `am` prints
    # "intent has been delivered", the op runs, and the log window shuts before the
    # first line lands. Twice on 2026-08-23 this read as a broken op. `list` walks
    # every bonded device's UUIDs, so it is the slowest of the read-only ops, not the
    # fastest — 12 was still short, and it truncates mid-device rather than failing.
    start_op --es op list
    watch_log "${LIST_WAIT:-20}"
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
    mac="${2:?mac}"; uuid="${3:?uuid}"; packets="$(hex_arg "${4:?comma-separated hex}")"
    args=(--es op seq --es mac "$mac" --es uuid "$uuid" --es packets "$packets")
    # SONY_SEQ=1 frames each payload and acks the device's data frames — its PARAM
    # reads only answer inside a session that does both.
    [ -n "${SONY_SEQ:-}" ] && args+=(--ez sony true)
    # ⚠ SONY_TABLE2=1 sends DATA_MDR_NO2 (0e) instead of DATA_MDR (0c), which selects
    # Sony's SECOND command table. The ranges overlap and mean different things: table1
    # `40`-`49` is VPT, table2 `40`-`49` is VOICE_GUIDANCE. Getting this wrong sends a
    # sound-field write where a voice-prompt write was meant, with no error either way.
    [ -n "${SONY_TABLE2:-}" ] && args+=(--ez table2 true)
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
    packets="$(hex_arg "${3:?comma-separated hex}")"
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
    #   ./probe.sh settings XM4 eqlevels=3,0,0,2,4,6   dB per band, clear bass FIRST
    #   ./probe.sh settings XM4 autooff=never       never | when_removed
    #   ./probe.sh settings Bose eq=8,0,0           Bose bass,mid,treble in dB
    #   ./probe.sh settings Bose button=spotify     hear_battery_level | spotify
    #   ./probe.sh settings XM4 multipoint=on
    #   ./probe.sh settings XM4 dsee=on            DSEE Extreme
    #   ./probe.sh settings XM4 pause=off          pause when removed
    #   ./probe.sh settings XM4 chat=on            Speak-to-Chat
    #   ./probe.sh settings XM4 voice=on           Focus on Voice ⚠ ambient mode only
    who="${2:?device name substring}"
    args=(--es op settings --es device "'$who'")
    shift 2
    for kv in "$@"; do
      # Split on the FIRST = only: a Bose eq value is itself comma-separated and a
      # future value could hold one too.
      key="${kv%%=*}"; val="${kv#*=}"
      case "$key" in
        eq|eqlevels|multipoint|autooff|button|quality|dsee|pause|chat|voice|touch)
          args+=(--es "$key" "$val") ;;
        *) echo "unknown setting '$key' — eq, eqlevels, multipoint, autooff, button,\
 quality, dsee, pause, chat, voice, touch" >&2; exit 2 ;;
      esac
    done
    start_op "${args[@]}"
    watch_log "${SETTINGS_WAIT:-60}"
    ;;
  send|raw)
    mac="${2:?mac}"; uuid="${3:?uuid}"
    # ⚠ `if`, not `[ … ] && …`: under `set -e` a false test as the whole command exits
    # the script, and a payload-less `send` is the documented connect-only probe.
    payload=""
    if [ -n "${4:-}" ]; then payload="$(hex_arg "$4")"; fi
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
