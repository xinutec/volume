#!/usr/bin/env bash
# Tap a control in the FOCUSED app by its label, or report that control's state.
#
# For driving a vendor app through a capture. `docs/captures.md` says to take tap
# coordinates from `uiautomator dump` bounds rather than from a screenshot's
# proportions, and this is that rule made executable — a tap on the row instead of
# the switch opens a detail screen and leaves the toggle looking refused, which is
# indistinguishable in the log from the device saying no.
#
# ⚠ `uiautomator dump` returns whatever is FOCUSED. That is correct here, where the
# vendor app is fullscreen, and wrong in split screen — see `scripts/shot.sh`.
#
# Usage:
#   scripts/tap.sh "Spatial Sound"           tap the node whose text is that
#   scripts/tap.sh --switch "Spatial Sound"  tap the SWITCH on that node's row
#   scripts/tap.sh --state  "Spatial Sound"  print that switch's checked value
#   scripts/tap.sh --find   "Spatial Sound"  is it on screen and clear of the edges?
#   scripts/tap.sh --dump                    print every label on screen
set -euo pipefail

readonly REMOTE=/sdcard/window_dump.xml
readonly LOCAL=${TMPDIR:-/tmp}/volume-ui-dump.xml

# ⚠ **A row too near an edge is REFUSED rather than tapped.** The switch belonging
# to a label is found by vertical overlap, and a row clipped by the header or the
# gesture bar has a clipped band — which can overlap the NEIGHBOURING row's switch.
# That is a silent wrong target: the wrong setting moves and the log still prints
# the label that was asked for. It nearly happened at y=295, just under the header.
readonly SAFE_TOP=380
readonly SAFE_BOTTOM=2200

# ⚠ The XML goes to a FILE and its path is passed as an argument. Feeding it on
# stdin cannot work here: `python3 -` already takes its program from stdin, so a
# second redirection silently wins and the parser sees an empty document. That cost
# one run, and the error it produces ("no element found") reads like the phone
# returned nothing rather than like a shell mistake.
dump() {
    for _ in 1 2 3; do
        adb shell uiautomator dump "$REMOTE" >/dev/null 2>&1 || true
        adb shell cat "$REMOTE" 2>/dev/null | tr -d '\r' > "$LOCAL"
        [[ -s $LOCAL ]] && return 0
        sleep 1
    done
    echo "uiautomator returned nothing three times" >&2
    return 1
}

case ${1:-} in
--dump)
    dump
    tr '>' '>\n' < "$LOCAL" | grep -oE 'text="[^"]+"' | sed 's/text="//;s/"$//' |
        grep -v '^$' | sort -u
    exit 0
    ;;
--switch) want=${2:?label}; mode=switch ;;
--state) want=${2:?label}; mode=state ;;
--find) want=${2:?label}; mode=find ;;
*) want=${1:?label}; mode=label ;;
esac

dump

out=$(python3 - "$LOCAL" "$want" "$mode" "$SAFE_TOP" "$SAFE_BOTTOM" <<'PY'
import re, sys, xml.etree.ElementTree as ET

path, want, mode, top, bottom = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5])
root = ET.parse(path).getroot()

def box(n):
    return tuple(map(int, re.findall(r'-?\d+', n.get('bounds'))))

band = None
for n in root.iter('node'):
    if n.get('text') == want:
        x0, y0, x1, y1 = box(n)
        if mode in ('label', 'find'):
            if y0 < top or y1 > bottom:
                print('CLIPPED', y0, y1)
                sys.exit(0)
            print('OK', (x0 + x1) // 2, (y0 + y1) // 2)
            sys.exit(0)
        band = (y0, y1)
        break

if band is None:
    print('ABSENT')
    sys.exit(0)

# ⚠ Refuse a clipped row: see SAFE_TOP/SAFE_BOTTOM above.
if band[0] < top or band[1] > bottom:
    print('CLIPPED', band[0], band[1])
    sys.exit(0)

best = None
for n in root.iter('node'):
    if n.get('checkable') != 'true':
        continue
    x0, y0, x1, y1 = box(n)
    if y1 < band[0] or y0 > band[1]:
        continue
    if best is None or x0 > best[0]:
        best = (x0, (x0 + x1) // 2, (y0 + y1) // 2, n.get('checked'))

if best is None:
    print('NOSWITCH')
elif mode == 'state':
    print('OK', best[3])
else:
    print('OK', best[1], best[2])
PY
)

status=${out%% *}
rest=${out#* }

case $status in
ABSENT) echo "'$want' is not on screen" >&2; exit 2 ;;
CLIPPED) echo "'$want' is clipped at the viewport edge ($rest) — scroll it clear first" >&2; exit 3 ;;
NOSWITCH) echo "'$want' has no switch on its row" >&2; exit 4 ;;
OK) ;;
*) echo "unexpected: $out" >&2; exit 5 ;;
esac

if [[ $mode == state ]]; then
    echo "$rest"
    exit 0
fi

# ⚠ `--find` must NEVER tap. It is the "can I act here yet?" probe, and an earlier
# draft answered it by calling the tapping path, which would have pressed every
# control it was merely looking for.
if [[ $mode == find ]]; then
    exit 0
fi

read -r x y <<<"$rest"
adb shell input tap "$x" "$y"
printf '%s  tapped %-34s at %s,%s\n' "$(date +%H:%M:%S)" "$want" "$x" "$y"
