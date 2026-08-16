# Captures — what was done, and when

A snoop capture is only as good as the log of what was done to produce it. These
timelines are the labels for the frames; without them a capture is a haystack.

Captures live in `~/.cache/volume-captures/` — outside the repo, because they are
megabytes and carry the phone's whole radio traffic for the period, not just ours.

## Method that works

### Before capturing anything

**Ask whether the thing you want even exists.** A capture is an hour; these are
minutes.

- ⚠ **To find out whether a device has a READ at all, make the vendor app tell
  you.** Set the value from *this* repo's probe, then launch the vendor app **cold**
  and look at what its UI draws. If it shows the state you just set, it read the
  device and a read command exists — go and capture the launch, the frames are in
  it. If it shows something stale, it is remembering. This found the JLab's read on
  2026-08-16 after months of "the app appears to track mode locally", which was
  never tested and was wrong. Do it **both ways round**: one direction can agree by
  luck.
- **Read the status surface first.** `aa 21 01 3x` on the JBL, a Bose sweep, a Sony
  `f0`/`f2`/`f6` block — reads are safe on worn headphones and cost nothing.
- ⚠ **Check that a status field actually TRACKS the setting before believing it is
  the read.** The JBL's `34` was written down as "EQ preset"; selecting JAZZ in the
  app left it at `00`. A field that does not move when the thing moves is a field
  about something else.

### Guessing — where it works and where it has always failed

⚠ **Frame SHAPE can be guessed from a status reply. VALUE bytes cannot.**

- Shape: the JBL's auto-off status reads `aa 22 04 33 <en> <min> <?>`, so
  `aa 33 03 <en> <min> <?>` was tried and worked first time. Sony/Bose/JLab all
  follow the same "the setter mirrors the getter" habit.
- Values: guessing has failed **every** time it has been tried here — Sony's mode
  bytes, the JLab's `03`, the Bose slot order. Capture them.
- **Allow yourself ONE bounded guess with a read-back, then stop.** `aa 40 01 01`
  for the JBL's EQ preset drew nothing and moved nothing; that is the signal to go
  and capture rather than to try `aa 40 02 01`.

### Driving the vendor app

1. **Each change immediately followed by its inverse.** The differing bytes between
   two known states are the field. A single change tells you far less.
2. **Connection-disturbing settings LAST.** On the Sony, multipoint renegotiated the
   link mid-session and everything after it was never sent — and ⚠ the [CUSTOM]
   button change is connection-disturbing too, which its own dialog says
   ("Reconnects to the headphones") and which is the better explanation for that
   capture's missing frames.
3. **Confirm the vendor app is still connected AFTER the actions**, not just before.
   A settings app renders a change it has not delivered exactly like one it has.
4. ⚠ **Take tap coordinates from `uiautomator dump` bounds, never from a
   screenshot's proportions.** A tap that lands on the row instead of the switch
   opens a detail screen, and the toggle you were watching keeps its old value —
   which is indistinguishable from a refusal.
5. ⚠ **A UI toggle that springs back cannot tell you whether anything was sent.**
   Prefer driving over RFCOMM/GATT, where the probe echoes `[n] → <bytes>` and
   "never asked" cannot read as "asked and refused".

### Pulling and believing it

6. **Wait ~3 minutes before pulling the bugreport.** The snoop log flushes lazily;
   its mtime is later than its last frame, so a truncated log looks complete.
7. **Check the last frame's timestamp against this log** before believing a capture.
8. ⚠ **An empty window is evidence too.** 425 frames of `HCI_EVT`/`HCI_CMD` and no
   ACL means nothing was sent — the app changed its own UI over a dead link. Do not
   read it as "the capture missed it".
9. ⚠ **A reply's checksum need not follow the request's rule.** The JLab's replies
   come out exactly 2 less than the sum-mod-256 its requests use. Leave a rule you
   cannot derive unchecked rather than "fixing" the frames to fit it.

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

## 2026-08-16 evening — Sony WH-1000XM4 (`~/.cache/volume-captures/2026-08-16-sony-2/`)

The re-run #955 asked for, plus the multipoint question this repo could not answer
from the wire alone. Sony Headphones Connect, driven over adb by tapping bounds read
from `uiautomator dump` rather than fixed coordinates.

| time | action |
|---|---|
| 18:07:46 → 18:08:13 | [CUSTOM] button → **Digital assistant**: DONE, then OK |
| 18:09:11 → 18:09:26 | restored → **Ambient Sound Control**, same two taps |
| 18:10:58, 18:12:21 | multipoint ON ×2 — ⚠ **REFUSED**, the switch reverts itself |
| 18:12:52 | ⚠ Pippijn pressed the same switch **by hand** — same frame, same revert |
| 18:16:52 | Sound Quality Mode → **Priority on Stable Connection** — accepted |
| 18:17:54 | multipoint ON again — ⚠ **the tap missed; NOTHING was sent** |
| 18:19:58 | Sound Quality Mode → restored to **Prioritize Sound Quality** |

⚠ **The 18:17:54 row is why the capture is worth taking even when you watched the
screen.** It was written up as "refused again in stable-connection mode" from the
switch still reading Off. The capture has **no ACL traffic at all** in that window:
the tap landed on the row and opened the detail screen instead of hitting the switch,
so the screen was reporting the *old* refusal and the retry never happened. Refused
and never-asked look identical on a toggle that springs back. It was re-run properly
afterwards, over RFCOMM where the sent bytes are echoed — and *then* it was refused.

⚠ **The [CUSTOM] button change reconnects the headphones — the app says so in its own
confirmation dialog** ("Reconnects to the headphones. Change?"). That is a better
explanation for the morning capture's missing frames than the multipoint toggle it
was blamed on: the button change *itself* drops the link, so a capture that puts it
last loses whatever follows. Do it FIRST, and treat it as connection-disturbing.

⚠ **Multipoint could not be enabled at all** — by this repo's driver, by the vendor
app driven over adb, or by Pippijn's own finger. All three send the identical
`d8 d2 01 01` and get `d9 d2 01 00` back. So the frame is not wrong; the device is
refusing it. Not the codec either: re-tested over RFCOMM in **both** Sound Quality
Modes after the tap above was found to have sent nothing. Cause unknown.

**What this capture decoded** (`docs/sony-settings.md`): the [CUSTOM] button, which is
what #955 came for, and Sound Quality Mode, which was not being looked for at all and
turned up because changing it was a step in testing multipoint.

## 2026-08-16 — Bose QC45 (`~/.cache/volume-captures/2026-08-16-bose/`)

Bose Music (`com.bose.bosemusic`), driven over adb. Decoded in
`docs/bose-settings.md`.

| time | action |
|---|---|
| 11:23:24 | app connected to the QC45 (90% battery) |
| 11:25:31 | EQ → **Bass Boost** |
| 11:25:39 | EQ → **Treble Boost** |
| 11:25:48 | EQ → **Reset**, back to Bass 0 / Mid 0 / Treble 0 |
| 11:26:37 | Action button shortcut → **Spotify** |
| 11:26:45 | restored → **Hear Battery Level** |
| 11:27:34 / 11:27:44 | multipoint toggled, then restored |

Link confirmed still up after the last action, and the app still showed the QC45 —
so unlike the Sony run, everything here was on the wire. **All three settings
decoded**, each Set paired with the Status it drew: `docs/bose-settings.md`.

The one thing this capture cannot answer is the EQ's range. The presets were pressed
but no slider was dragged, so the `f6`/`0a` that lead every band group are still only
plausibly −10/+10.

## Extracting

    unzip -o -q bugreport-*.zip 'FS/data/misc/bluetooth/logs/*' -d extracted
    mergecap -w merged.pcap btsnoop_hci.log.last btsnoop_hci.log
    tshark -r merged.pcap -Y 'btrfcomm && data.len > 4' \
      -T fields -e frame.time -e data.data

⚠ `mergecap`/`tshark` are not on the Mac's PATH: `nix shell nixpkgs#wireshark-cli`.
⚠ Add `-e hci_h4.direction` — the Sony frame does NOT carry direction, and inferring
it from the opcode got a command pair backwards. `0x00` sent, `0x01` received.
⚠ Filter by ACL handle, not address — the dissector does not resolve BD_ADDRs.
⚠ Bare `3e01xx00000000…3c` frames are **acks**, not replies.
