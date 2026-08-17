# Captures — what was done, and when

A snoop capture is only as good as the log of what was done to produce it. These
timelines are the labels for the frames; without them a capture is a haystack.

Captures live in `~/.cache/volume-captures/` — outside the repo, because they are
megabytes and carry the phone's whole radio traffic for the period, not just ours.

## Method that works

### Before capturing — a capture is an hour, these are minutes

- ⚠ **To learn whether a device has a READ at all, make the vendor app tell you.**
  Set the value from this repo's probe, launch the vendor app **cold**, and see what
  its UI draws: the state you just set means it read the device, so a read exists and
  the capture of that launch contains it. Stale means it is remembering. Do it **both
  ways round** — one direction can agree by luck. This found the JLab's read after
  "the app appears to track mode locally" had gone untested, and wrong, for months.
- **Read the status surface first** — reads are safe on worn headphones.
- ⚠ **Check a status field TRACKS the setting before believing it is the read.** The
  JBL's `34` was written down as "EQ preset"; selecting JAZZ left it at `00`.

### Guessing — shape yes, values no

⚠ **Frame SHAPE can be guessed from a status reply; VALUE bytes cannot.** The JBL's
auto-off reads `aa 22 04 33 <en> <min> <?>`, so `aa 33 03 <en> <min> <?>` worked first
try — every vendor here mirrors its getter. Values have failed every time: Sony's mode
bytes, the JLab's `03`, the Bose slot order. **One bounded guess with a read-back, then
capture** — `aa 40 01 01` drawing nothing is the signal to stop, not to try `aa 40 02`.

### Driving the vendor app

1. **Each change followed immediately by its inverse.** The differing bytes are the
   field.
2. **Connection-disturbing settings LAST** — Sony multipoint, and ⚠ the [CUSTOM]
   button, whose own dialog says "Reconnects to the headphones".
3. **Confirm the app is still connected AFTER the actions.** It renders a change it
   has not delivered exactly like one it has.
4. ⚠ **Take tap coordinates from `uiautomator dump` bounds**, not a screenshot's
   proportions: a tap on the row instead of the switch opens a detail screen and
   leaves the toggle looking refused.
5. ⚠ **A toggle that springs back cannot tell you whether anything was sent.** Prefer
   RFCOMM/GATT, where the probe echoes `[n] → <bytes>`.

### Pulling and believing it

6. **Wait ~3 minutes.** The snoop log flushes lazily; mtime is later than its last
   frame, so a truncated log looks complete. **Check the last frame's timestamp.**
7. ⚠ **An empty window is evidence.** 425 frames of `HCI_EVT`/`HCI_CMD` and no ACL
   means nothing was sent — not that the capture missed it.
8. ⚠ **A reply's checksum need not follow the request's rule.** The JLab's are exactly
   2 less than its requests' sum-mod-256. Leave a rule you cannot derive unchecked.

### Extracting

    unzip -o -q bugreport-*.zip 'FS/data/misc/bluetooth/logs/*' -d extracted
    mergecap -w merged.pcap btsnoop_hci.log.last btsnoop_hci.log
    tshark -r merged.pcap -Y 'btrfcomm && data.len > 4' \
      -T fields -e frame.time -e hci_h4.direction -e data.data

⚠ `mergecap`/`tshark` need `nix shell nixpkgs#wireshark-cli`.
⚠ **`-e hci_h4.direction`** — `0x00` sent, `0x01` received. Sony frames carry no
direction, and inferring it from the opcode got a command pair backwards.
⚠ **The field name differs by dissector**: `data.data` for Sony/Bose RFCOMM, but
**`btspp.data`** for the JLab (`data` is empty there) and `btatt.value` for the JBL's
GATT. A filter that returns 0 rows usually means the wrong field, not a quiet device.
⚠ Bare `3e01xx00000000…3c` frames are **acks**, not replies.

## 2026-08-17 — JBL Tour One M2 (`~/.cache/volume-captures/2026-08-17-jbl-spatial/`)

Driven by `scripts/drive_jbl.py`. Two bugreports, merged with the previous
`btsnoop_hci.log.last` each time, so the window runs 09:20 → 11:42 and includes the
*previous* evening's spatial run — which is what let it be re-judged rather than
re-argued.

| time | action | on the wire |
| --- | --- | --- |
| 09:30:27 | (prev. session) tap mode label `Movie` | nothing |
| 09:30:35 | (prev. session) Spatial Sound on | `aa 9d 03 00 01 01` — mode `01`, Music |
| 10:40:01 | vendor app cold launch | 30+ getters, paired with replies |
| 10:47:03 | tap mode LABEL `Movie` | nothing |
| 10:49:06 | tap mode TILE `Movie` | `aa 9d 03 00 01 02` |
| 11:11:28/35/43 | pick Movie, Game, Music | `aa 9d 03 00 01 02` / `…03` / `…01` |
| 11:11:53 | Spatial Sound off | `aa 9d 03 00 00 01` |
| 11:31:31 | pick `Video Mode` | `aa 81 08 c5 00 2e 00 50 00 ff ff` |
| 11:36:22 | Smart A/V switch off | `aa 81 08 00 01 35 00 e6 00 ff ff` |
| 11:37:41 | pick `Audio Mode` | `aa 81 08 00 01 35 00 96 00 ff ff` |
| 11:34–11:35 | intended VoiceAware Low/High/Mid | ⚠ `aa 9f` — **Smart Talk**, see below |

✅ **The pair at 10:47 and 10:49 is the point of the capture.** Same intended action,
two tap targets, thirty seconds apart: the label sends nothing, the tile sends the
write. A finding that had been published from the silent half is retracted in
`docs/protocols.md`.

⚠ **The capture caught the driver pressing the wrong control while reporting success.**
`drive_jbl.py` retargets a segmented label onto its tile by matching x-bounds; every
card in this app uses the same three columns, and VoiceAware is a gradient bar with no
tiles at all, so `Low` matched *Smart Talk's* tile one card above. The log said
`tapped Low`; the wire said `aa 9f 03 00 01 05`. Smart Talk was left switched on at 15 s
and had to be put back to off / 5 s — its resting value being known only because this
same capture had recorded `aa 9f 03 02 00 05` at 09:20 and again at 10:40.

⚠ A tap that lands is not a tap that lands on the right thing, and only the capture can
tell the difference.

### 12:50 — Smart A/V, at the third attempt

| time | action | on the wire |
| --- | --- | --- |
| 12:50:01 | pick Video Mode, switch on | `aa 81 08 c5 00 2e 00 50 00 ff ff` |
| 12:50:40 | switch off, **Video still selected** | `aa 81 08 00 01 35 00 e6 00 ff ff` |
| 12:51:25 | switch on, Video still selected | `aa 81 08 c5 00 2e 00 50 00 ff ff` |
| 12:52:03 | pick Audio Mode | `aa 81 08 00 01 35 00 96 00 ff ff` |

✅ Settles that there is no enable byte: off is its own frame, sent while Video is lit.

⚠ **Two earlier attempts produced the same wrong answer and looked fine.** Both times
the Video pick missed its tile and sent nothing, so the switch was flipped while the
mode was still Audio — and the Audio-off frame that came back was a real frame, from a
real write, that answered a question nobody had asked. The tell was in the capture, not
the log: no frame at the moment the pick was logged.

⚠ **A diagnostic that fires.** Checking where a label resolves meant `--tap`-ing it,
so verifying the targeting across eight controls wrote eight settings and left Spatial
Sound on Game and Smart Talk on 20 s, both switched on. `--where` now answers the same
question and taps nothing.

### 12:13 — the level, by hand

| time | action | on the wire |
| --- | --- | --- |
| 12:13:39 | Pippijn drags the VoiceAware bar | `aa 98 03 00 02 01` |
| 12:13:41 | …to Low | `aa 98 03 00 01 01` |
| 12:13:45 | …to High | `aa 98 03 00 03 01` |
| 12:13:46 | …back to Mid | `aa 98 03 00 02 01` |

✅ **Two automated attempts failed at this and a person did it in ten seconds.** The bar
takes a drag; `drive_jbl` has taps. The first attempt tapped inert labels, the second
pressed a different feature's control, and both produced logs that read as successful
runs. Worth remembering before building a third gesture into the driver: the question
was cheap to answer and expensive to automate.

⚠ The drags come in bursts — a slider emits while it travels, so `01` repeats four
times. The level is the byte that varies and the run is labelled by what was done, not
by counting frames.

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

⚠ This capture could not answer the EQ's range — presets were pressed, no slider
dragged. ✅ Settled that evening on hardware instead: the device declares `f6 0a` as
each band's min/max, and both ends were driven and read back (`docs/bose-settings.md`).

## 2026-08-16 evening — JLab JBuds Sport ANC 4 (`…/2026-08-16-jlab/`)

Taken to find a read (#939), by the cold-launch test at the top of this file rather
than by driving the app's settings.

| time | action |
|---|---|
| 19:51:13 | **our probe** set ANC → Be Aware, with `com.jlab.app` not running |
| 19:51:56 | JBL… JLab app launched **cold**; its UI drew **Be Aware** |
| 19:53:24 | our probe set → Noise Cancelling On |
| 19:53:54 | app launched cold again; its UI drew **Noise Cancelling On** |

So the app reads the device rather than remembering, and the read is in each launch:
`c0 ff 00 44 00 00 01 00 04` at 19:52:02 and 19:54:00, differing only in the mode
byte. Decoded in `docs/protocols.md`.

## 2026-08-16 evening — JBL Tour One M2 (`…/2026-08-16-jbl/`)

⚠ **GATT, not RFCOMM** — filter `btatt.value`, not `data.data`.

| time | action |
|---|---|
| 20:36:45 | Equalizer → **JAZZ** in the JBL app |
| 20:37:52 | **Auto Power Off** toggled on |

The EQ went out at 20:36:48 as `aa a2 74 …` — a ten-band curve of float pairs, which
is why the `34` status never moved. ⚠ **The EQ's prior state was not recorded before
changing it**; it was recovered from an `aa a2 02 01 ff` read earlier in the same
capture (20:26:55, all gains 0.0) and restored to that. Read the value first next
time.

⚠ **And that restore was incomplete, which showed 45 minutes later.** The gains went
back to zero but the *table id* did not — the frame carries both, and the id was left
at the slot the app's JAZZ write had used. It surfaced at 21:22 when the settings
screen read the curve and said "custom" over ten zeroes, which is what a state neither
named curve produces looks like; tapping Flat put it back to `00`. Comparing the whole
frame is what caught it. Comparing the part you were thinking about would not have.

## 2026-08-16 evening — JBL feature sweep (`…/2026-08-16-jbl-features{,-2}/`)

Driving the vendor app through its own rows to answer "is all of it understood?".
`scripts/drive_jbl.py` prints the timeline; `/tmp/jbl-timeline*.txt` are the two runs.

| time | action |
|---|---|
| 21:44–21:49 | ASC master, Low Volume Dynamic EQ, Spatial Sound, Smart Talk, VoiceAware |
| 22:04–22:13 | Smart Audio & Video, Auto Play & Pause, L/R balance, Auto Power Off, Spatial again |
| 22:14:25 | our app force-stopped → the vendor app reconnected and swept 40 getters |

Decoded in `docs/protocols.md`. ⚠ **Three faults in the driving script, all found by
reading its own output rather than by it failing loudly**:

⚠ **A tap on a row clipped by the header can hit the NEIGHBOUR's switch.** The switch
belonging to a label was found by vertical overlap, and a clipped band overlaps the
next row. The wrong setting moves while the log prints the label you asked for.
The driver now refuses a clipped row outright.

⚠ **The vendor app cannot reach the headphones while OUR app holds the link.** JBL
control is LE GATT and takes one client; with Volume connected the app greys out,
says "Loading…", and every tap lands on a dead UI. **Nothing reaches the wire, which
in the capture is indistinguishable from a control the app keeps to itself** — the
exact false conclusion "an empty window is evidence" invites when the link was never
checked. The driver now force-stops our app first and waits for the app to render
a battery percentage, which it only does when it has the device.

⚠ **A run that aborts between a change and its inverse leaves the setting changed.**
One did: Smart Audio & Video was left off, and only a switch-read-back check caught
it. Every toggle is now verified by reading the switch, and anything outstanding is
named at exit.

⚠ **The driver never checked WHICH APP was in front, and a whole run drove the agent
console.** `uiautomator dump` returns the FOCUSED window; the vendor app had not been
launched; and the readiness check — "is a battery percentage on screen" — matched a
different app entirely. Ten minutes of swiping someone else's transcript, and the log
said "app is connected" throughout. ⚠ **A precondition that can pass for the wrong
reason is worse than no precondition**, because it also silences the question.

That was the fourth missing check in a row, and the driver is now typed Python under
`mypy --strict` in the gate rather than shell: it had grown a state machine, retry
loops, restore tracking and an embedded Python XML parser, and shell has no gate — so
each missing precondition arrived without anything going red. It asserts the focused
package before every dump and every tap.

⚠ **`adb bugreport` leaves a wedged `dumpstate` if its shell is killed**, and every
later bugreport is refused with "Failed to connect to dumpstatez service". It cannot
be killed from `adb shell` — `setprop ctl.stop dumpstatez` clears it. Worth knowing
before blaming the host: nothing on the Mac was wrong.
