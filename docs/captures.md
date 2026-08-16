# Captures — what was done, and when

A snoop capture is only as good as the log of what was done to produce it. These
timelines are the labels for the frames; without them a capture is a haystack.

Captures live in `~/.cache/volume-captures/` — outside the repo, because they are
megabytes and carry the phone's whole radio traffic for the period, not just ours.

## Method that works

1. **Each change immediately followed by its inverse.** The differing bytes between
   two known states are the field. A single change tells you far less.
2. **Connection-disturbing settings LAST** (multipoint). On the Sony it renegotiated
   the link mid-session and everything after it was never sent.
3. **Confirm the vendor app is still connected AFTER the actions**, not just before.
   A settings app renders a change it has not delivered exactly like one it has.
4. **Wait ~3 minutes before pulling the bugreport.** The snoop log flushes lazily;
   its mtime is later than its last frame, so a truncated log looks complete.
5. **Check the last frame's timestamp against this log** before believing a capture.

## 2026-08-16 — Sony WH-1000XM4 (`~/.cache/volume-captures/2026-08-16-sony/`)

Decoded in `docs/sony-settings.md`. Sony Headphones Connect, driven over adb.

| time | action |
|---|---|
| 11:01:48–11:02:26 | EQ preset stepped back ×5 |
| 11:02:35–11:03:15 | stepped forward ×5, restored to Custom 2 / CLEAR BASS +3 |
| 11:05:03 | auto power off → "Do not turn off" |
| 11:05:10 | restored → "Off when headphones are removed" |
| 11:06:32 / 11:06:42 | multipoint on, then off (restored) |
| 11:07:56 / 11:08:23 | ⚠ CUSTOM button → Digital assistant, then back — **NOT SENT** |

⚠ The last row produced **no ACL traffic at all**: the multipoint toggle at 11:06:42
had dropped the link, and the app rendered the change anyway. See #955.

## 2026-08-16 — Bose QC45 (`~/.cache/volume-captures/2026-08-16-bose/`)

Bose Music (`com.bose.bosemusic`), driven over adb. ⚠ Not yet decoded.

| time | action |
|---|---|
| 11:23:24 | app connected to the QC45 (90% battery) |
| 11:25:31 | EQ → **Bass Boost** |
| 11:25:39 | EQ → **Treble Boost** |
| 11:25:48 | EQ → **Reset**, back to Bass 0 / Mid 0 / Treble 0 |
| 11:26:37 | Action button shortcut → **Spotify** |
| 11:26:45 | restored → **Hear Battery Level** |
| 11:27:34 / 11:27:44 | multipoint toggled, then restored |

The EQ screen is Bass/Mid/Treble sliders plus four preset buttons (Bass Boost, Bass
Reducer, Treble Boost, Treble Reducer) — so the presets are almost certainly a
three-value write, not an opaque preset id like Sony's.

Link confirmed still up after the last action, and the app still showed the QC45 —
so unlike the Sony run, everything here should be on the wire.

## Extracting

    unzip -o -q bugreport-*.zip 'FS/data/misc/bluetooth/logs/*' -d extracted
    mergecap -w merged.pcap btsnoop_hci.log.last btsnoop_hci.log
    tshark -r merged.pcap -Y 'btrfcomm && data.len > 4' \
      -T fields -e frame.time -e data.data

⚠ `mergecap`/`tshark` are not on the Mac's PATH: `nix shell nixpkgs#wireshark-cli`.
⚠ Filter by ACL handle, not address — the dissector does not resolve BD_ADDRs.
⚠ Bare `3e01xx00000000…3c` frames are **acks**, not replies.
