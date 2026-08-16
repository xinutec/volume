#!/usr/bin/env bash
# Screenshot just the Volume app, which lives in one half of a split screen.
#
# The phone is set up with Volume above and the agent console below, so the app
# is permanently on screen and a deploy updates it in place. That removes the two
# things that kept spoiling captures:
#
#   ⚠ `am start` on an already-resumed activity does NOT run `onStart`, so
#     relaunching "to be sure" silently skips the very refresh being measured.
#   ⚠ `uiautomator dump` returns whatever is FOCUSED, which in split screen is
#     usually the other half.
#
# So this never launches anything and never touches the semantics tree. It asks
# the window manager where the app actually is and crops to that — which means it
# keeps working when the split is dragged to a different ratio.
#
# Usage: scripts/shot.sh [out.png]
set -euo pipefail

readonly OUT=${1:-/tmp/volume-shot.png}
readonly ACTIVITY=org.xinutec.volume/org.xinutec.volume.VolumeActivity

# The window's own frame, not the task's and not a guessed fraction.
#
# ⚠ `dumpsys` is read into a variable FIRST rather than piped. A pipeline ending in
# an early-exiting `awk`/`head` kills the producer with SIGPIPE, which `pipefail`
# turns into a failure of the whole script — intermittently, depending on how much
# output raced through before the reader left. This script did exactly that.
windows=$(adb shell dumpsys window windows | tr -d '\r')
frame=$(
    awk -v a="$ACTIVITY" '$0 ~ "Window #.*" a {f=1} f && /Frames:/ {print; exit}' <<<"$windows" |
        grep -oE 'frame=\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]'
)
if [[ -z $frame ]]; then
    echo "no window for $ACTIVITY — is it on screen?" >&2
    exit 1
fi
nums=$(echo "$frame" | grep -oE '[0-9]+')
read -r x0 y0 x1 y1 <<<"$(echo "$nums" | paste -sd' ' -)"
w=$((x1 - x0))
h=$((y1 - y0))

adb exec-out screencap -p > "$OUT"
# ⚠ NOT `sips`. Its `--cropToHeightWidth` crops from the CENTRE, and its
# `--cropOffset` is measured from the centre too — so the obvious `--cropOffset
# 0 0` silently yields the middle band. That cost two wrong captures and an
# accusation that `dumpsys` was reporting stale bounds, when the bounds were
# right and the crop was wrong. ImageMagick's +x+y is from the top-left, plainly.
nix shell nixpkgs#imagemagick --command \
    magick "$OUT" -crop "${w}x${h}+${x0}+${y0}" +repage "$OUT"

echo "$OUT (${w}x${h} at +${x0}+${y0})"
