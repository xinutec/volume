#!/usr/bin/env bash
# Drive the JBL app through its controls, for a capture. Prints the timeline.
#
# The method is `docs/captures.md`: every change is followed immediately by its
# inverse, so the differing bytes are the field and nothing is left moved. Each line
# printed here becomes a row of the timeline that labels the capture — without it a
# snoop log is a haystack.
#
# ⚠ **Three faults in the first version of this, all found by reading its own log**:
#
#   1. It tapped switches by vertical overlap with no regard for clipping, so a row
#      half under the header could hand back the NEIGHBOUR's switch — the wrong
#      setting moving while the log printed the right label. `scripts/tap.sh` now
#      refuses a clipped row outright.
#   2. It re-scrolled from the top before every tap, so three buttons on ONE row got
#      tapped at three different offsets and each action carried ~20 s of scrolling
#      into its capture window.
#   3. It aborted on a missing label with `set -e` and no restore, so a failure
#      between a change and its inverse would leave the setting moved — the one
#      thing the script exists to prevent.
#
# So: every toggle is now VERIFIED by reading the switch back, and anything left
# changed is named at exit. A run that does not print "all restored" left something.
#
# ⚠ Deliberately NOT here: Max Volume Limiter and LE Audio. The limiter is hearing
# protection and is driven by hand with the restore verified; LE Audio re-negotiates
# the link, so it goes last, after everything that needs the link to stay up.
#
# Usage: scripts/drive-jbl.sh 2>&1 | tee /tmp/jbl-timeline.txt
# ⚠ Strict mode AND a no-abort wrapper, because the two pull opposite ways here: a
# script that exits on the first failure can exit BETWEEN a change and its inverse,
# leaving a setting moved. `step` is where that is resolved — a failed step is
# reported and the run continues to the restores, and `on_exit` names anything still
# outstanding. Dropping `set -e` to get that was the wrong trade: it also hid typos.
set -euo pipefail
cd "$(dirname "$0")/.." || exit 1

readonly WAIT=${WAIT:-3}
readonly TAP=scripts/tap.sh

# Anything toggled and not yet toggled back. Printed at exit, however we exit.
declare -a OUTSTANDING=()

say() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"; }

on_exit() {
    if ((${#OUTSTANDING[@]})); then
        say "!! LEFT CHANGED, restore by hand: ${OUTSTANDING[*]}"
    else
        say "=== all restored ==="
    fi
}
trap on_exit EXIT

# ⚠ **The vendor app cannot reach the headphones while OUR app holds the link.**
# The JBL's control channel is LE GATT and takes ONE client. With Volume connected,
# the vendor app greys out and says "Loading…", every tap lands on a dead UI, and
# NOTHING reaches the wire — which looks in the capture exactly like a control the
# app keeps to itself. That false reading is the whole reason this check exists.
#
# "Connected" is read off the app itself: it renders a battery percentage only when
# it has the device. Waiting for that is what makes a run's silence meaningful.
preflight() {
    say "preflight: releasing our own GATT link and waiting for the app to connect"
    adb shell am force-stop org.xinutec.volume >/dev/null 2>&1 || true
    local i
    for i in $(seq 1 40); do
        if $TAP --dump 2>/dev/null | grep -qE '^[0-9]+%$'; then
            say "preflight: app is connected"
            return 0
        fi
        sleep 3
    done
    say "!! preflight: the app never showed a battery reading — it is not connected"
    return 1
}

# Between groups: still connected? A mid-run drop turns every later group into
# "no traffic", and no traffic is a CONCLUSION in this work, not a shrug.
link_ok() {
    $TAP --dump 2>/dev/null | grep -qE '^[0-9]+%$'
}

# Scroll in small settled steps. ⚠ A big swipe FLINGS: it carries momentum past
# rows, which is how the first version scanned the whole page and missed a label
# that was plainly on it.
nudge() {
    adb shell input swipe 540 1500 540 "$1" 400
    sleep 1
}

to_top() {
    for _ in $(seq 1 12); do adb shell input swipe 540 700 540 1500 400; done
    sleep 1
}

# Put a label on screen AND clear of both edges, so a switch lookup cannot be
# ambiguous. Returns non-zero rather than guessing.
show() {
    local want=$1 i pass
    for pass in 1 2; do
        for i in $(seq 1 20); do
            # ⚠ `--find`, never `--switch` or a bare label: probing must not press.
            if $TAP --find "$want" >/dev/null 2>&1; then return 0; fi
            nudge 900
        done
        to_top
    done
    say "!! '$want' never came up clear of the edges"
    return 1
}

# Flip a switch and PROVE it flipped. The whole point: a tap that lands wrong, or on
# a control the app declines to move, is caught here rather than mislabelling frames.
flip() {
    local want=$1
    show "$want" || return 1
    local before after
    before=$($TAP --state "$want") || return 1
    $TAP --switch "$want" || return 1
    sleep "$WAIT"
    after=$($TAP --state "$want") || return 1
    if [[ $before == "$after" ]]; then
        say "!! '$want' did not move (still $after) — NOT counted as driven"
        return 1
    fi
    say "    '$want' $before -> $after"
    # Track it, or clear it if this was the restoring half of a pair.
    local i keep=()
    local found=0
    for i in "${OUTSTANDING[@]+"${OUTSTANDING[@]}"}"; do
        if [[ $i == "$want" && $found == 0 ]]; then found=1; else keep+=("$i"); fi
    done
    OUTSTANDING=("${keep[@]+"${keep[@]}"}")
    ((found)) || OUTSTANDING+=("$want")
    return 0
}

# Tap a plain label — a segmented option. No state to verify, so the capture is the
# only evidence; the timeline line is what makes it readable.
pick() {
    local want=$1
    show "$want" || return 1
    $TAP "$want" || return 1
    sleep "$WAIT"
}

# A toggle and its inverse, as one unit.
cycle() {
    flip "$1" && flip "$1"
}

# Run one step; report a failure and carry on, so later restores still happen.
step() {
    "$@" || say "!! step failed: $*"
}

# Each group is a function so a run can repeat just the part that came back empty.
# ⚠ That matters: a group that sent NOTHING is the ordinary outcome for a control
# the app keeps to itself, and it is indistinguishable from a tap that missed until
# it is retried on its own.
g_asc() {
    say "--- Ambient Sound Control master switch (found ON) ---"
    step cycle "Ambient Sound Control"
}

g_lvdeq() {
    say "--- Low Volume Dynamic EQ (found ON) ---"
    step cycle "Low Volume Dynamic EQ"
}

g_spatial() {
    say "--- Spatial Sound (found OFF, Music) ---"
    if flip "Spatial Sound"; then
        step pick "Movie"
        step pick "Game"
        step pick "Music"
        step flip "Spatial Sound"
    fi
}

g_smarttalk() {
    say "--- Smart Talk (found OFF, 5s) ---"
    if flip "Smart Talk"; then
        step pick "15s"
        step pick "20s"
        step pick "5s"
        step flip "Smart Talk"
    fi
}

g_voiceaware() {
    say "--- VoiceAware (found OFF, Mid) ---"
    step cycle "VoiceAware"
}

g_smartav() {
    say "--- Smart Audio & Video (found ON, Audio Mode) ---"
    step pick "Video Mode"
    step pick "Audio Mode"
    step cycle "Smart Audio & Video"
}

g_autoplay() {
    say "--- Auto Play & Pause (found ON) — tests whether 38 is this ---"
    step cycle "Auto Play & Pause"
}

g_balance() {
    say "--- Left / Right Sound Balance (found OFF, 0) ---"
    step cycle "Left / Right Sound Balance"
}

g_autooff() {
    say "--- Auto Power Off (found OFF, 30 min) — 1 hr and 2 hr test the unit ---"
    if flip "Auto Power Off"; then
        step pick "1 hr"
        step pick "2 hr"
        step pick "30 min"
        step flip "Auto Power Off"
    fi
}

readonly ALL=(asc lvdeq spatial smarttalk voiceaware smartav autoplay balance autooff)

say "=== JBL capture begins — every change is undone before the next starts ==="
preflight || exit 1
for g in "${@:-${ALL[@]}}"; do
    if ! link_ok; then
        say "!! link lost before '$g' — stopping rather than recording false silence"
        break
    fi
    "g_$g"
done
say "=== sequence complete ==="
