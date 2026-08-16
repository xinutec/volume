#!/usr/bin/env bash
# Watch Volume's list react to headphones coming and going.
#
# The instrument for the question "does the screen follow the radio, without
# anyone touching it" — which cannot be answered by reading the code, because the
# failure it is guarding against is a TIMING one: the A2DP and headset proxies
# populate a moment AFTER the ACL link comes up, so a list rebuilt on the ACL
# event alone sees nothing and is never corrected.
#
# It samples two things on the same clock and prints only when one of them
# changes: what the radio says is connected, and what the app is showing. The
# ordering and the gap between those two lines IS the measurement.
#
# ⚠ Reads the semantics tree, not pixels. That is the right instrument here —
# the question is whether a card exists, not what it looks like. Composition
# still needs a screenshot.
#
# Usage: scripts/watch-list.sh [seconds]   (default 180)
set -euo pipefail

readonly DEADLINE=${1:-180}
readonly PKG=org.xinutec.volume

# ⚠ Bounded, always: an unbounded poll against a device that never connects is a
# loop that has to be killed by hand, and the last two of those left the phone in
# a state nobody had asked for.
readonly START=$SECONDS

focus() {
    adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r'
}

# What the radio says. The per-profile connected counts, as one line.
radio() {
    adb shell dumpsys bluetooth_manager 2>/dev/null |
        grep -E 'Connected count' | tr -d '\r' | tr -s ' ' | paste -sd' ' -
}

# What the app is showing. Compose's semantics tree, text nodes only.
screen() {
    adb exec-out uiautomator dump /dev/tty 2>/dev/null |
        tr '<' '\n' | grep -o 'text="[^"]*"' | grep -v 'text=""' |
        sed 's/^text="//; s/"$//' | paste -sd' | ' -
}

if ! focus | grep -q "$PKG"; then
    echo "⚠ $PKG is not focused — a backgrounded app does not update, so this"
    echo "  would measure the wrong thing. Foreground it first:"
    echo "  adb shell am start -n $PKG/.VolumeActivity"
    focus
    exit 1
fi

echo "watching for ${DEADLINE}s — connect or disconnect a pair now"
echo

last_radio=""
last_screen=""
while ((SECONDS - START < DEADLINE)); do
    t=$((SECONDS - START))
    r=$(radio) || true
    s=$(screen) || true
    if [[ $r != "$last_radio" ]]; then
        printf '[%3ds] RADIO  %s\n' "$t" "$r"
        last_radio=$r
    fi
    if [[ $s != "$last_screen" ]]; then
        printf '[%3ds] SCREEN %s\n' "$t" "$s"
        last_screen=$s
    fi
done

echo
echo "--- what the stack logged (connection transitions only):"
adb shell dumpsys bluetooth_manager 2>/dev/null |
    grep -E 'connection state changed' | tail -20 | tr -d '\r'
