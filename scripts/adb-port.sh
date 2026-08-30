#!/usr/bin/env bash
# Find the phone's adb listener after a reboot.
#
# `adb tcpip 5555` does not survive a reboot. Its replacement — wireless
# debugging — picks a RANDOM port each boot, and Android only opens it while
# the phone is on WiFi. Once open the VPN carries it, so this scans the VPN
# address rather than the LAN one.
#
# Reading the port off Settings > Developer options > Wireless debugging is
# faster when someone is holding the phone. This is for when nobody is.
set -euo pipefail

HOST="${1:-10.100.0.12}"
LO="${2:-1024}"
HI="${3:-65535}"
CHUNK=1000

for start in $(seq "$LO" "$CHUNK" "$HI"); do
  end=$((start + CHUNK - 1))
  [ "$end" -gt "$HI" ] && end=$HI
  for p in $(seq "$start" "$end"); do
    (nc -z -G 1 -w 1 "$HOST" "$p" 2>/dev/null && echo "$p") &
  done
  # ⚠ `|| true` is REQUIRED, not defensive. A closed port makes its `nc` exit nonzero,
  # `wait` then reports the last child's status, and under `set -e` the scan aborts on
  # the first CLOSED port — which is almost always port 1024. The failure mode is an
  # empty result that looks exactly like "no listener", i.e. the scan silently answers
  # the opposite of the truth.
  wait || true
done | sort -n
