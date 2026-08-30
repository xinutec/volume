#!/usr/bin/env bash
# One cold BMAP read, with BOTH clocks recorded (#1232).
#
# The question is whether a Bose answers a block-01 read in a virgin session,
# and the answer depends on two clocks that were conflated until 2026-08-29:
# time since the PHONE booted, and time since the HEADSET reconnected. A
# reading that records only one cannot be placed on the grid at all, so this
# records both BEFORE it sends anything.
#
# ⚠ The clocks come from a ROTATING buffer. `dumpsys bluetooth_manager`'s
# aclStateChangeCallback/encryptionChangeCallback history is shared across every
# device and ages out — measured 2026-08-30, present at 14:27 and gone by 18:09,
# with the headset's own connection unchanged. A blank therefore means "the log
# forgot", NOT "it never reconnected", and the two are opposite answers. Hence
# three sources and a loud marker rather than an empty string.
#
# Usage: cold-read.sh <mac> [suffix-for-dumpsys]
# dumpsys redacts the leading address bytes, so it is grepped by suffix.
set -euo pipefail
cd "$(dirname "$0")/.."

MAC="${1:?mac}"
SUFFIX="${2:-${MAC: -5}}"
SPP=00001101-0000-1000-8000-00805f9b34fb
found=0

# ⚠ The SAME selector probe.sh uses, or the clocks and the read can describe different
# phones. After a reboot the old `HOST:5555` entry lingers dead beside the new wireless
# -debugging port, and a bare `adb` then dies with "more than one device" — for the
# clocks only, which would print MISSING and read as "it never reconnected".
ADB=(adb)
[ -z "${VOLUME_ADB_DEVICE:-}" ] || ADB=(adb -s "$VOLUME_ADB_DEVICE")

clock() {
  local label="$1" pattern="$2" v
  v=$("${ADB[@]}" shell "dumpsys bluetooth_manager | grep -i '$SUFFIX' | grep -F -e '$pattern' | tail -1" \
      | tr -d '\r' | sed 's/^ *//')
  if [ -z "$v" ]; then
    printf '  %-14s ⚠ MISSING (log rotated — NOT evidence of no reconnect)\n' "$label"
  else
    printf '  %-14s %s\n' "$label" "$v"
    found=$((found + 1))
  fi
}

echo "=== clocks, read BEFORE the probe ==="
printf '  %-14s %s s\n' "boot age" "$("${ADB[@]}" shell 'cut -d. -f1 /proc/uptime' | tr -d '\r')"
printf '  %-14s %s\n' "phone now" "$("${ADB[@]}" shell "date '+%m-%d %H:%M:%S'" | tr -d '\r')"
clock "profile up" "STATE_CONNECTING -> STATE_CONNECTED"
clock "acl up"     "aclStateChangeCallback: State:Connected"
clock "encrypted"  "encryptionChangeCallback"
if [ "$found" -eq 0 ]; then
  echo
  echo "⛔ NO reconnect clock survived. The reading below lands on ONE axis only,"
  echo "   which is the defect that wasted 2026-08-29. Record it as unplaceable."
fi

echo
echo "=== the read: block 01 fn 06 GET — NOT block 00, which would cure it ==="
# ⚠ The HOST window only. The app-side wait stays at its 3000 ms default on purpose:
# every row already on the grid was measured at 3000 ms, and a longer wait would make
# this cell non-comparable with the rows it exists to sit beside. What gets widened is
# the logcat window, which is not part of the measurement — it only decides whether the
# verdict line is still being printed when the host stops listening.
#
# Read the VERDICT, not the echo:
#   ✗ no exchange          -> connect failed; the reading is INVALID, not silent
#   ✓ connected + ← nothing -> genuine SILENT
#   ✓ connected + ← N bytes -> answered
SEND_WAIT=30 ./probe.sh raw "$MAC" "$SPP" 01060100
