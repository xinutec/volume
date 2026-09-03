# Sony WH-1000XM4

## STATE — read this first; everything below is the evidence for it

⚠ **The sections are in DISCOVERY order and later ones correct earlier ones.** Two
headings asserted what their own bodies retracted until 2026-08-23. Trust this table and
the dated section it points at; treat anything undated as older than everything dated.

| | frame | where |
| --- | --- | --- |
| ANC / ambient | `66 02` / `68 02 <on> 02 <nc> 01 <AsmId> <amb>` | ✅ driven |
| Focus on Voice | the `AsmId` byte above | ✅ driven ⚠ **ambient mode only** — ⚠⚠ the app now labels it **`Voice passthrough`**, colliding with Speak-to-Chat's own row of that name |
| EQ preset | `56 01` / `58 01 <preset> 00` | ✅ driven |
| EQ band levels | `58 01 **ff** <count> <levels>` | ✅ driven 2026-08-24, six sliders on the card — ⚠ `ff` UNSPECIFIED, never the slot's id |
| Sound Quality | `e6 01` / `e8 01 00 <v>` | ✅ driven |
| Auto power off | `f6 04` / `f8 04 01 <v> 00` | ✅ driven, **complete** — `f0 04` declares 2 |
| DSEE Extreme | `e6 02` / `e8 02 00 <v>` | ✅ driven |
| Pause on removal | `f6 03` / `f8 03 00 <v>` | ✅ driven |
| Speak-to-Chat | `f6 05` / `f8 05 **01** <v>` | ✅ driven ⚠ **reads and writes different type tables** |
| Speak-to-Chat detail | `fa 05` / `fc 05 00 <s> <f> <t>` | ✅ driven 2026-08-24 — sensitivity · voice focus · mode-out |
| Battery | `10 00` / `11 00 <pct> <chg>` | ✅ read, on the card |
| Power off | `22 00 01` | ✅ driven 2026-08-24 — confirmed dialog; the link drops and the card goes |
| BLE setup | `1c 00` / `1c 01` | 👁 read — identifiers, ⚠ **values withheld, public repo** |
| Concierge data | `28 00` / `29 00 <JSON>` | ⛔ diagnostics for the vendor, like `c1` |
| Codec | `18 00` / `19 00 <AudioCodec>` | ✅ read, on the card — ⚠ negotiated, not settable |
| Upscaling effect | `14 00` / `15 00 02 00` | 👁 read; ⚠ **not shown** — it is not the DSEE switch |
| Touch sensor control panel | `d6 d1` / `d8 d1 01 <v>` | ✅ driven 2026-08-24 |
| Voice guidance | `46 01 01` / `48 01 01 <v>` on type **`0e`** | ✅ driven 2026-08-24 — ⚠ **second command table**; `48` is VPT on table 1 |
| Multipoint | `d6 d2` | ⛔ device refuses everyone, its own app too |
| [CUSTOM] button | `f6 06` / `f8 06 01 <v>` | ✅ **solved** — needs `94 01 00` first, then answer the `99` alert |
| Adaptive Sound Control | `70 01` → supported | ⛔ app-side — ✅ **confirmed by capture 2026-08-24**: off writes an ordinary `68 02`, on writes nothing |
| NC Optimizer | `82`–`87` OPT `01`, control `00` CANCEL · `01` START | ⛔ **identified, never driven** — it plays test tones into worn headphones |
| Volume `a1`, firmware `30`, telemetry `c1` | | ⛔ excluded by rule, not by the device |

⚠ **THE FRAME TYPE BYTE SELECTS THE COMMAND TABLE.** `0c` = table1, `0e` = table2, and
the ranges overlap: `40`–`49` is VPT on one and VOICE_GUIDANCE on the other. `probe.sh`
has `SONY_TABLE2=1`; default stays `0c`.

⚠ **Decoded is not reachable.** Battery was decoded and cross-checked on 2026-08-16 and
sat unused for a week because no driver method existed. Three separate features have now
been "known" and invisible. A ✅ above means driven **and** on the card.

⚠⚠ **This table was INCOMPLETE until 2026-09-01, and the cause is NOT that anybody missed
the feature.** `NC Optimizer` was identified AND consciously excluded on 2026-08-24 — it is
in task #1097's "6 excluded, each for its own reason", with the right reason. What went
wrong is that **this table only carried rows for things with a driver or a read; the
exclusions lived in the task and never crossed over.** So the doc and the task disagreed
for a week, each internally consistent, and only a survey against the app's own screens
made the disagreement visible.

⚠ **The fix is the row above, not a resolution to be careful.** An excluded feature needs a
line here saying it is excluded, or the summary silently means "everything we chose to
implement" while reading as "everything the device has". The parity table below is built
the other way round — off the app's screens rather than off the wire — which is why both
exist.

## ⚠ What Sound Connect has, and what we have — 2026-09-01

Every row of the vendor app's own screens, read by walking `All device settings` and each
of its six categories to the end. ⚠ **Read-only: only category headers and the settings
entry were tapped, never a control.**

✅ **The three sub-screens are now enumerated too — 2026-09-03, and none holds anything
undriven.** This was the standing gap in the survey's scope; it is closed.

- **`Ambient Sound Control`** — a master on/off switch, the NC↔Ambient `slider`, the
  Focus-on-Voice checkbox, and an "Add shortcut to the top screen" switch that is app-side.
  ⚠ It also carries a `caution_text`, "Set automatically by Adaptive Sound Control", so the
  mode has an owner other than whoever last wrote it.
- **`Equalizer`** — a preset carousel (`horizontal_slider`, a custom view drawing no text),
  `CLEAR BASS`, the five band handles, an `Edit` button onto the same six values, and the
  same app-side shortcut switch.
- **`Speak-to-Chat`** — its on/off, `Voice Detect Sensitivity`, `Voice passthrough`, `Time
  until the mode closes`, the shortcut switch, and an **`Experience it`** trial link. ⛔ That
  link starts a demo of a worn-headphone feature and is now in `drive_sony.py`'s FORBIDDEN.

✅ **The EQ render corroborates `eqlevels`' argument order from the app's own screen.** The
graph reads CLEAR BASS `+3` with bands 400 `0`, 1k `0`, 2.5k `+2`, 6.3k `+4`, 16k `+6` —
which is `probe.sh`'s documented example `eqlevels=3,0,0,2,4,6` exactly, clear bass first.
⚠ The band values are read off HANDLE POSITIONS against the `+10`/`0`/`-10` axis labels, so
they are a render measurement, not a frame.

| the app's row | wire | us |
| --- | --- | --- |
| battery, on the device card | ✅ `10 00` | ✅ read |
| codec — "Connected via LDAC" | ✅ `18 00` | ✅ read — ⚠ negotiated, not settable |
| ⏻ Turn off audio device | ✅ `22 00 01` | ✅ driven |
| Ambient Sound Control — NC · Ambient · Off | ✅ `66 02` / `68 02` | ✅ r/w |
| **NC Optimizer** — atmospheric pressure, wearing condition | ✅ `82`–`87` OPT `01` | ⛔ **identified, never driven** — see below |
| Speak-to-Chat | ✅ `f6 05`, detail `fa 05` | ✅ r/w, both |
| Equalizer — Custom 2 | ✅ `56 01` / `58 01` | ✅ r/w, preset and bands |
| **Find Your Equalizer** | ⚪ unestablished | ⚪ not opened — see below |
| **360 Reality Audio Setup** | ⚪ unestablished | ⚪ not opened — account-gated |
| DSEE Extreme | ✅ `e6 02` / `e8 02` | ✅ r/w |
| Sound Quality Mode | ✅ `e6 01` / `e8 01` | ✅ r/w |
| Connect to 2 devices simultaneously | ✅ `d6 d2` | ⛔ device refuses everyone, its own app too |
| Touch sensor control panel | ✅ `d6 d1` / `d8 d1` | ✅ r/w |
| Change function of [CUSTOM] button | ✅ `f6 06` / `f8 06` | ✅ r/w — needs `94 01 00`, then answer the `99` alert |
| Pause when headphones are removed | ✅ `f6 03` / `f8 03` | ✅ r/w |
| Automatic Power Off | ✅ `f6 04` / `f8 04` | ✅ r/w, complete |
| Notification & Voice Guide — On/Language | ✅ `46 01 01` on type `0e` | ✅ r/w |
| Download software automatically · Version 3.0.1 | ⛔ firmware | ⛔ excluded by rule, not by the device |
| Adaptive Sound Control (shortcut) | ✅ none — app-side, confirmed by capture | ⚪ correctly absent |

**Every row is placed.** Three carry something other than a plain ✅, and each for a stated
reason rather than for want of effort:

⛔ **`NC Optimizer` is identified and deliberately not driven.** `82`–`87` OPT with
`01` NC_OPTIMIZER and control `00` CANCEL / `01` START has been decoded since 2026-08-24.
It **plays test tones into headphones that must be worn** while it measures wearing
condition and atmospheric pressure. That is the same class as the JBL's Personi-Fi hearing
test and it gets the same treatment: the frame is recorded, and nothing here starts it.
⚠ It is also the row that proves the point above — decoded on the wire, absent from the
STATE table for a week, and only a survey of the app's screens surfaced it.

⚪ **`Find Your Equalizer` and `360 Reality Audio Setup` were not opened**, so their wire
status is unestablished rather than absent. `360 Reality Audio Setup` is account-gated and
a login prompt is a hard stop here. `Find Your Equalizer` is a guided listening test, which
by the reasoning above is not something to start blind. ⚠ **"Not opened" is not "app-side"**
— saying which one it is needs the capture nobody has taken.

---

## The original capture — EQ, auto-off, multipoint

Decoded from a snoop capture on 2026-08-16 of Sony Headphones Connect
(`com.sony.songpal.mdr`) driven through each setting, with the change and its
inverse performed so the differing byte is the field.

Capture: `~/.cache/volume-captures/2026-08-16-sony/` (kept outside the repo).
Framing is the one in `docs/protocols.md`: `3e | type | seq | len(4 BE) | payload
| sum | 3c`, every DATA frame acked, sequence byte alternating.

⚠ **Payloads below are the part between `len` and `sum`.** The `3e0c00…` /
`3e0c01…` prefix is framing, and the bare `3e01xx00000000…3c` frames are acks —
not commands. Reading an ack as a reply is the mistake this repo has already made
three times.

⚠ **Direction is NOT in the frame — take it from the capture.** The byte after `3e`
is the type and the next is the sequence; neither says who spoke. Add
`-e hci_h4.direction` to the `tshark` fields: **`0x00` is sent, `0x01` is
received**. Inferring direction from the opcode instead got the multipoint pair
backwards here, and a driver built on that would have sent the device its own
notification.

**Cross-check against the SDK table** (`docs/protocols.md`): subsystems run in
blocks of ten, `…8` = `SET_PARAM` and `…9` = `NTFY_PARAM`. That predicts `58`/`59`
for EQ in the `50` EQEBB block and `f8`/`f9` for auto-off in the `f0` SYSTEM block —
which is exactly what the wire shows. Two independent routes to the same answer.

## Equalizer — block `0x50` (EQEBB), type `01`

    → 56 01                             GET_PARAM
    ← 57 01 <preset> 06 <6 levels>      RET_PARAM
    → 58 01 <preset> 00                 SET_PARAM
    ← (ack) then 59 01 <preset> 06 <6 levels>    NTFY_PARAM, unsolicited
    → 5a 01                             request the band table
    ← 5b 01 06 10 00 01 01 01 90 01 03 e8 01 09 c4 01 18 9c 01 3d 2e 80

`52 01` → `53 01 00` is the status, asked once on connecting; the `52 00` in
`docs/protocols.md` is the same command with a different type byte and is not
interchangeable with it.

⚠ `5a` is a REQUEST the phone sends, not another notification — the app asks for the
band table after every preset change. Directions verified from the capture.

**`56`/`57` were predicted from the SDK's blocks of ten and then found on the wire**,
once, at 10:58:21 when the app connected: `→ 56 01` drew
`← 57 01 a2 06 0d 0a 0a 0c 0e 10`. So the read command is measured, not inferred —
and its first level, `0d` = +3, is the CLEAR BASS +3 the owner had actually set.

⚠ **A SET's own reply carries the resulting state.** The device acks, then sends an
unsolicited `59` with the whole state, in the same window. That is a state report,
not a confirmation — it becomes evidence only by comparing its preset with the one
asked for, because a device that ignored the write reports the *old* preset there.

⚠ **The trailing `00` of a SET is a level COUNT**, sitting where `59`'s `06` sits. The
shape guessed here — `58 01 <preset> 06 <6 levels>` — was **wrong in one byte and cost a
day**: a levels write carries `ff`, never the slot's id. See the EQ band levels section.

Observed presets: `a0`, `a1`, `a2` (Customs), `17`, `16`. The menu holds more and
nothing captured enumerates them.

**Six levels, not five.** `06` is the count, then one byte per band. The first is
CLEAR BASS and the remaining five are the graphic bands. ⚠ **Levels are offset by
10**: `0a` is 0 dB, so the range −10…+10 maps to `00`…`14`. A flat preset reads
`0a 0a 0a 0a 0a 0a`; one measured preset read `00 0e 0d 0b 0c 00`.

✅ **Driven on hardware 2026-08-16 evening.** `56 01` read back
`preset=a2, levels=[3, 0, 0, 2, 4, 6]` — byte-identical to the morning capture's
`57 01 a2 06 0d 0a 0a 0c 0e 10`, reached by this repo's own driver rather than by
replay. Presets `a1` and `a2` were then each written and confirmed by read-back, and
`a2` restored. `a1` reads flat, `[0, 0, 0, 0, 0, 0]`.

The `5b` frame carries the band centre frequencies as 16-bit values, and they match
the app's own axis labels **exactly**:

    01 90 → 400 Hz    03 e8 → 1 kHz    09 c4 → 2.5 kHz
    18 9c → 6.3 kHz   3e 80 → 16000 Hz

⚠ **On the wire that last one reads `3d 2e`, and it is an ESCAPED `3e`.** Taken
literally it decodes to 15662 — wrong, and close enough to the app's "16k" label to
pass for a rounding. This frame is also what settles the framing itself: it is the
only captured frame containing a marker byte, its declared length of 21 matches only
after unescaping 22 bytes, and its checksum `54` holds only over the unescaped body.
Length counts the unescaped payload; the sum is taken before escaping.

✅ **And that is now proven on hardware**, not just in a replay: asking the XM4 for
its band table returns `[400, 1000, 2500, 6300, 16000]`. A wrong unescape would put
15662 at the end, so the reading is what the escape rules predict.

## Automatic power off — block `f0` (SYSTEM), type `04`

    → f6 04              GET_PARAM
    ← f7 04 01 10 00     RET_PARAM — read at connect, 10:58:22
    → f8 04 01 11 00     "Do not turn off"
    ← f9 04 01 11 00
    → f8 04 01 10 00     "Off when headphones are removed"
    ← f9 04 01 10 00

One byte, `10` vs `11`, and its notify **does** echo the value set — so unlike
multipoint, this one is confirmable from its own reply. ✅ **Driven on hardware
2026-08-16 evening**, both directions, each confirmed by read-back and restored.
✅ **And these are ALL the values, settled 2026-08-24 by asking rather than inferring**:

    → f0 04        ← f1 04 02 10 11     two elements, and these two

`AutoPowerOffElementId` has six — `00` 5 min, `01` 30 min, `02` 60 min, `03` 180 min, `10`
when removed, `11` disable. **The XM4 declares only the last two.** So the timed encodings
are in Sony's enum and not on this unit, and this row is complete rather than partial. ⚠ It
had been carried as "2 of 6, menu offers 2" — an inference from the vendor app's UI, where
one capability read was available the whole time.

## Multipoint — block `d0`, type `d2`

    → d6 d2              GET_PARAM
    ← d7 d2 01 <on>      RET_PARAM — read at connect, 10:58:23, value 00
    → d8 d2 01 01        SET_PARAM

⚠ **THE XM4 REFUSES TO ENABLE MULTIPOINT.** Driven on hardware 2026-08-16 evening:

    → d8 d2 01 01        SET_PARAM, multipoint on
    ← (ack) then d9 d2 01 00     NTFY_PARAM — still OFF

and a following `d6 d2` reads `d7 d2 01 00`. The device acknowledges the frame and
reports the unchanged state. ⚠ **This is not a fault in the frame.** Sony Headphones
Connect gets the same answer: its switch flips on and immediately back, both when
driven over adb and when pressed by hand. So `d8 d2 01 01` is what the app sends and
what the app fails with.

Not the codec, though the app's screen invites that guess. It was refused with Sound
Quality Mode on **both** settings — re-tested over RFCOMM, because the first attempt
at the second mode was a tap that missed the switch and sent nothing while the screen
looked like a refusal (`docs/captures.md`). The app's own words are that LDAC "is not
possible" *during* multipoint — a consequence, not a precondition. The cause is
unknown and is not worth another hypothesis without evidence.

⚠ **A multipoint reply is NOT always about a different setting.** An earlier reading
of the morning capture said it always is — `d8 d2 01 01` there drew `99 01 06 01`, a
`90`-block notification, and a `90`-block write drew `d9 d2 01 00`. Today the same
SET drew `d9 d2 01 00`, its **own** opcode. The reply names whichever parameter the
device considers changed, so it is sometimes this one and sometimes not. Either way
the consequence for code is unchanged: **read back with `d6 d2`**, never trust the
reply, which is why [`setMultipoint`] always does a real read.

⚠ **`d8 d2 01 00` has still never been sent.** Multipoint has never been on for this
code to turn off. That `00` is this field's off value is known only because the GET
and the notification both report it.

⚠ The `90` block answers **no reads at all**: `90 01`, `92 01`, `94 01`, `96 01`,
`96 02` and `96 06` each drew a bare ack and no DATA frame. A hand-built
`98 01 06 01` — guessed from the notification's shape — also drew only an ack and
changed nothing. The block's payload shape is **not** established; do not copy that
guess out of this paragraph.

## ✅ THE DEVICE NAMES ITS OWN GENERAL SETTINGS, and there are exactly two

`d0 <GsInquiredType>` answers with the setting's own key. ⚠ **`GsInquiredType` is `d1`,
`d2`, `d3` — not `01`, `02`, `03`.** Asking `d0 01` gets a bare ack, which reads as
"unsupported" and is really "no such type".

    → d0 d1   ← d1 d1 02 13 "TOUCH_PANEL_SETTING" 00 01 00
    → d0 d2   ← d1 d2 02 12 "MULTIPOINT_SETTING" 1a "MULTIPOINT_SETTING_SUMMARY" 01 00
    → d0 d3   ← (nothing — GENERAL_SETTING3 does not exist on this unit)

The shape is `<GsStringFormat 02 ENUM_NAME> <len><titleKey> <len><summaryKey> <GsSettingType
01 BOOLEAN_TYPE> <00>`. GENERAL_SETTING1 has no summary, hence its `00`.

✅ **`d1` is the touch panel and it TAKES WRITES**, driven 2026-08-24 through the probe and
then through the driver:

    → d6 d1        ← d7 d1 01 00     off
    → d8 d1 01 01  ← d9 d1 01 01     on, and an independent d6 d1 agrees
    → d8 d1 01 00  ← d9 d1 01 00     restored

⚠ **This is the same frame family as multipoint and the opposite outcome.** `d8 d1` is
accepted; `d8 d2` is refused for everyone including Sony's app. **So the `d8` family is not
blanket-refused, and a refusal is per setting, not per peer.** That is a real narrowing of
#965: this repo can write a GENERAL_SETTING the vendor app can write.

⚠ **No `99` alert was involved**, even though the app's string table has
`ENABLE_TOUCH_PANEL_AND_RECONNECTION_CONFIRMATION` next to this setting. So an alert-shaped
name in the resources does not mean the device gates the write behind one.

**What it means**, in Sony's own words: "Turning on this function allows you to use the
headphones to control playback, adjust volume, receive/end phone calls, and more."
`TouchSensorControlPanel_title` is "Touch sensor control panel". So **on is enabled**, and
this pair reads off.

## Everything the app asked, on connecting

The whole capture's DATA frames, deduplicated by their first two payload bytes.
⚠ **A map of where to look, not a decode** — only the rows above have been read.

```
36 02..0b  device info strings   37 02 "HP002"  37 03 "MDRID294301"  37 04 "CE7"
                                 37 06 "0000000000502474"  37 0b "8BD1C6930CD0F12E"
                                 37 07 cb   37 08 14   37 09 25   37 0a 14
42 01 / 43 01   01 00            46 01 05 → 47 01 05 25 14 14 10 <16 hex chars>
52/53 01  EQ status              56/57/58/59/5a/5b 01   EQ            ← decoded
62/63 02  NCASM status           66/67/68 02            ANC           ← driven
82/83 01, 86/87 01               a2/a3 01, a5 01 (×54), a6/a7/a9 01 20 12 (×110)
94 01 00, 98/99 01 06            multipoint's other half             ← above
c4 01 00 → c9 01 …               JSON log upload: {"v":"M6","logs":[{"key":…
d2/d3 d1, d2/d3 d2  status       d6/d7/d8/d9 d1, d2     multipoint    ← decoded
e2/e3 01, e2/e3 02, e6/e7 01,02  AUDIO block
f2/f3 03..06  status             f6/f7 03..06, f8/f9 04  SYSTEM       ← auto-off
fa 05 → fb 05 00 00 00 01
```

⚠ `a9 01 20 12` arrives **110 times** and `a5 01 00 03` 54 times, unasked. Anything
that reads a Sony session has to expect unsolicited traffic between its question and
its answer — which is exactly why `SonyEq.state` refuses a frame that is not its own
opcode rather than decoding whatever turned up.

## Sound Quality Mode — block `e0` (AUDIO), type `01`

    → e6 01              GET_PARAM
    ← e7 01 00 00        RET_PARAM — "Prioritize Sound Quality", i.e. LDAC
    → e8 01 00 01        SET_PARAM — "Priority on Stable Connection"
    ← e9 01 00 01        NTFY_PARAM, echoing the value

✅ **Decoded and driven the same evening**, both directions, each confirmed by its own
echo and by `e6 01`, and restored. Found by accident: changing it was a step in
testing multipoint, and the frames were in the capture.

⚠ **The third byte is `00` here and `01` in every other setting on this page.**
Auto-off sends `f8 04 01 <v> 00`, multipoint `d8 d2 01 <v>`, the button
`f8 06 01 <v>`; this one sends `e8 01 00 <v>`. Whatever that byte is, it is not one
thing — carried verbatim rather than tidied into a "count".

Changing it renegotiates the codec: the link drops and comes back, and the vendor app
warns "Reconnects to the audio device" first. Treat it as connection-disturbing.

## The [CUSTOM] button — block `f0` (SYSTEM), type `06`

This is what #955 went looking for, after the morning's attempt sent nothing at all.

    → f0 06              GET_CAPABILITY
    ← f1 06 01 02 01 00 03 00 02 00 01 21 02 31 03 00 31 01 33 22 32 32 01 00 34
    → f6 06              GET_PARAM
    ← f7 06 01 00        RET_PARAM — one key, set to 00 AMBIENT_SOUND_CONTROL
    → f8 06 01 31        SET_PARAM — one key, to 31 GOOGLE_ASSISTANT
    ← 99 01 02 01        ALERT_NTFY · FIXED_MESSAGE · 02 DISCONNECT_CAUSED_BY_CHANGING_KEY_ASSIGN
                         · 01 AlertActionType.POSITIVE_NEGATIVE
    → 98 01 02 01        ALERT_SET · same message · 01 AlertAction.POSITIVE
    ← f9 06 01 31        NTFY_PARAM — only now

⚠ **The last byte of `99` and of `98` are different enums that happen to share `01`.**
The notify carries `AlertActionType` (whether the dialog has two buttons); the set
carries `AlertAction` (which button). Reading them as one type would make
`98 01 02 00` — answering *no* — look like a malformed frame rather than a refusal.

### The payload grammar, from Sony's own parsers

`SYSTEM_SET_PARAM` does not write the inquired type itself; its payload object does.
For ASSIGNABLE_SETTINGS the whole frame is a **positional list, one preset per key**:

    f8 06 <numKeys> <preset> × numKeys

so `01` is a count, not a key index, and `f7 06 01 00` says *this device has exactly
one assignable key*. The capability reply nests three levels deep:

    f1 06 <numKeys>
      per key:    <key> <keyType> <defaultPreset> <numPresets>
      per preset: <preset> <numActions>
      per action: <action> <function>

Which decodes the XM4's 23 bytes exactly, with nothing left over:

    01                    one key
      02 CUSTOM_KEY  01 BUTTON  00 default=AMBIENT_SOUND_CONTROL  03 presets
        00 AMBIENT_SOUND_CONTROL  2  00 SINGLE_TAP→01 NC_ASM_OFF
                                     21 LONG_PRESS_THEN_ACTIVATE→02 NC_OPTIMIZER
        31 GOOGLE_ASSISTANT       3  00 SINGLE_TAP→31 GET_YOUR_NOTIFICATION
                                     01 DOUBLE_TAP→33 STOP_GA
                                     22 LONG_PRESS_DURING_ACTIVATION→32 TALK_TO_GA
        32 AMAZON_ALEXA           1  00 SINGLE_TAP→34 VOICE_INPUT_CANCEL_AA

## ✅ SOLVED 2026-08-24 — THE DEVICE MUST BE ASKED FOR ALERTS FIRST

**`94 01 00` — ALERT_SET_STATUS · FIXED_MESSAGE · ENABLE.** One frame, never sent from here,
and without it the XM4 raises no alert; without the alert the write never commits. Nothing
to do with being a second-class peer, and nothing to do with the assistant being
unprovisioned.

    → 94 01 00        the subscription. No reply — fire and forget.
    → f6 06         ← f7 06 01 00
    → f8 06 01 31   ← 99 01 02 01    the alert, for the first time from this repo
    → 98 01 02 00   ← f9 06 01 00    answered NEGATIVE: declined
    → f6 06         ← f7 06 01 00    unchanged, as intended

⚠ **The alert fires only on a REAL change.** With the subscription in place,
`f8 06 01 00` — the value already set — draws no `99` and no `f9`. So a no-op write is
silent for a second reason, and silence from this type still cannot be read as refusal.

⚠ **`98 01 02 00` is NEGATIVE and is the safe way to test this.** It proves the mechanism
without changing the button and without the reconnect that
`DISCONNECT_CAUSED_BY_CHANGING_KEY_ASSIGN` promises.

✅ **PROVEN END TO END, same day.** The button was driven to `31` GOOGLE_ASSISTANT and back
to `00`, each commit confirmed by an independent `f6 06` after the link returned:

    → 94 01 00
    → f8 06 01 31   ← 99 01 02 01
    → 98 01 02 01   ✗ Broken pipe — and that is the SUCCESS path, see below
    …link drops, device reconnects…
    → f6 06         ← f7 06 01 31    committed

⚠ **ANSWERING POSITIVE KILLS THE SOCKET, AND A DRIVER MUST NOT READ THAT AS FAILURE.** The
device commits and immediately reconnects — `DISCONNECT_CAUSED_BY_CHANGING_KEY_ASSIGN` is
not a warning about some later consequence, it is what happens next. The `98` write itself
reports `Broken pipe` because our socket dies underneath it. The bytes still landed.

⚠ **The NEGATIVE run is the control for this**, and it behaves completely differently: no
disconnect, no broken pipe, an orderly `f9 06 01 00`, and the value unchanged. So the
answer byte is what decides, not a timeout — which was the alternative reading of a commit
that arrived alongside a dropped link.

### ⚠ Two dead ends recorded so nobody re-walks them

- **"`90 01` ALERT_GET_CAPABILITY is never answered"** was published as the lead and is
  worthless: `ALERT_GET_CAPABILITY` exists **only in the two `Command` enums** — no payload
  class builds it, so the vendor app never sends `90` either. An unanswered frame that
  nobody sends is not evidence about us.
- **"a no-op `f8 06 01 00` is silent, and auto power off's no-op notifies, so the write path
  is broken whatever the value"** — the comparison was not a control. Auto power off
  notifies *directly*; the button notifies only *after an alert*. Two different mechanisms,
  so the pair says nothing. The conclusion drawn from it was superseded, not confirmed.

**What this means for the app**: the [CUSTOM] button is writable, but committing one needs
the owner's answer to a dialog — the device is asking a real question and a reconnect
follows a yes. A switch that silently answers POSITIVE would be putting words in the owner's
mouth about their own audio link.

⚠ **This is a DIFFERENT failure from multipoint**, and collapsing them would throw
away the only clue. Multipoint is refused for everyone, including Sony's own app.
The button is refused only for us — so there is something about the app's session
that this probe does not reproduce, and *that* is the thing to look for. The reads
(`f0 06`, `f2 06`, `f6 06`) all answer us fine.


## Method notes that cost something

⚠ **The snoop log flushes lazily.** The morning bugreport's log ended at 11:06:44
though its mtime was 11:10 — the last minutes were still in memory. Wait a few
minutes before pulling, and check the LAST FRAME's timestamp against what you did;
size and mtime both lie.

⚠ **A settings app renders changes it has not delivered.** The morning's button
attempt produced 425 frames in its window, **every one `HCI_EVT` or `HCI_CMD`, no ACL
data at all** — the app changed its own UI having lost the link. Confirm the app is
still connected *immediately before each action*, not just at the start.

⚠ **A UI toggle that springs back cannot tell you whether anything was sent.** The
evening run wrote up a multipoint retry as "refused" when the tap had missed the
switch entirely. Take the coordinates from `uiautomator dump` bounds, and prefer
RFCOMM — the probe echoes every byte it sends, so "not asked" can never read as "asked
and refused".

## ✅ The SDK names the whole XM4 surface — 2026-08-23, offline, no hardware

`apktool d com.sony.songpal.mdr` was already on disk (`~/.cache/volume-apks/sony-smali`)
and nobody had read its enums. `scripts/smali_enum.py` does, and it turns most of the
`?` rows on this page into named commands without a capture, a device, or a write.

⚠ **"Each a plain enum whose ordinal IS its byte" — `docs/protocols.md` said that, and
it is wrong past the first eight entries.** These enums carry the protocol byte as a
*separate constructor argument*:

    invoke-direct {v0, v1, v2, v3}, …Command;-><init>(Ljava/lang/String;IB)V
                       ^name ^ordinal ^the byte

`CONNECT_*` are ordinals 0–7 and bytes `00`–`07`, so counting agrees — and then
`GET_TEST` is ordinal 8 and byte `0f`. This is the third time in this repo that an
ordinal has been taken for a wire value; the other two are the JBL's gesture tables.

### ✅ The XM4 speaks **v1/table1**, and that is measured rather than assumed

The APK ships `mdr/{v1,v2}/{table1,table2}`, with different meanings for the same
bytes. Every frame this repo has captured lands on **v1**:

| observed | v1/table1 | v2/table1 would say |
| --- | --- | --- |
| `f6 04` auto power off | `04` AUTO_POWER_OFF | `04` VOICE_ASSISTANT_SETTINGS |
| `f6 06` the [CUSTOM] button | `06` ASSIGNABLE_SETTINGS | `06` WEARING_STATUS_DETECTOR |
| `56 01` EQ | `01` PRESET_EQ | `01` EBB |
| `e6 01` sound quality | `01` CONNECTION_MODE | `01` UPSCALING |
| `66 02` ANC | `02` NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE | `02` NC_MODE_SWITCH_AND_ASM_ON_OFF |

✅ **And the clincher is the nine device-info strings**, which this page has carried
since 2026-08-16 as "`36 02..0b` device info strings" with no idea what they were. `36`
is `UPDT_GET_PARAM` and v1's `UpdateInquiredType` names every one:

```
37 02  CATEGORY_ID                   "HP002"
37 03  SERVICE_ID                    "MDRID294301"
37 04  NATION_CODE                   "CE7"
37 06  SERIAL_NUMBER                 "0000000000502474"
37 07  BLE_TX_POWER                  cb
37 08  BATTERY_POWER_THRESHOLD       14 = 20%
37 09  UPDATE_METHOD                 25
37 0a  BATTERY_POWER_THRESHOLD_FOR_INTERRUPTING_FW_UPDATE   14
37 0b  UNIQUE_ID_FOR_DEVICE_BINDING  "8BD1C6930CD0F12E"
```
v2's `UpdtInquiredType` has no `03` and no `0b` at all, so those two frames could not
have been produced by a v2 device. Nine values landing on nine names is the check that
makes the rest of the extraction trustworthy rather than hopeful.

### ✅ `FunctionType` — the device lists its own features (asked 16:43, see below)

`CONNECT_GET_SUPPORT_FUNCTION` is `06`, its reply `07`, and its payload is a list of
`FunctionType` bytes. The encoding is *block nibble | inquired type*, so every setting
this repo has decoded appears in it at exactly the two bytes it is driven with:

```
11 BATTERY_LEVEL       12 UPSCALING_INDICATOR   13 CODEC_INDICATOR    14 BLE_SETUP
15 LEFT_RIGHT_BATTERY_LEVEL   17 LEFT_RIGHT_CONNECTION_STATUS   18 CRADLE_BATTERY_LEVEL
21 POWER_OFF           22 CONCIERGE_DATA        23 TANDEM_KEEP_ALIVE  30 FW_UPDATE
38 PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT         39 VOICE_GUIDANCE
41 VPT                 42 SOUND_POSITION
51 PRESET_EQ           52 EBB                   53 PRESET_EQ_NONCUSTOMIZABLE
61 NOISE_CANCELLING    62 NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE     63 AMBIENT_SOUND_MODE
71 AUTO_NC_ASM         81 NC_OPTIMIZER          92 VIBRATOR_ALERT_NOTIFICATION
a1 PLAYBACK_CONTROLLER b1 TRAINING_MODE         c1 ACTION_LOG_NOTIFIER
d1 GENERAL_SETTING1    d2 GENERAL_SETTING2      d3 GENERAL_SETTING3    d4 GENERAL_SETTING4
e1 CONNECTION_MODE     e2 UPSCALING             f1 VIBRATOR           f2 POWER_SAVING_MODE
f3 CONTROL_BY_WEARING  f4 AUTO_POWER_OFF        f5 SMART_TALKING_MODE f6 ASSIGNABLE_SETTINGS
```
`62` is ANC, `51` the equaliser, `f4` auto power off, `f6` the button, `d2` multipoint —
all five already driven from this repo, at those bytes. So the table is cross-validated
five ways before it is used for anything new.

⚠ **This is the Sony answer to "what does this device actually have", and it is ONE
READ.** The JBL needed twenty-three rows read off a screen by hand (`docs/protocols.md`)
because its SDK has no such list; Sony's device declares it. Asking is safe — a Get on
an established session — and it replaces guessing about which of the rows below the XM4
supports. ⚠ **Nothing here has asked it yet**, and until it does, every row below is a
thing the *protocol* has, not a thing this *unit* has.

### ✅ The value enums, and the four questions they answer

⚠ Each of these is a claim about the vendor's APK. They are what makes a command
usable, and none is a measurement — see the caveat at the end.

**`EqPresetId` — the whole preset menu**, which this page said "the menu holds more and
nothing captured enumerates them":
```
00 OFF   01 ROCK   02 POP   03 JAZZ   04 DANCE   05 EDM   06 R_AND_B_HIP_HOP   07 ACOUSTIC
10 BRIGHT  11 EXCITED  12 MELLOW  13 RELAXED  14 VOCAL  15 TREBLE  16 BASS  17 SPEECH
a0 CUSTOM  a1..a5 USER_SETTING1..5     ff UNSPECIFIED    (08–0f, 18–1f reserved)
```
✅ All five ids ever observed land: `a0`/`a1`/`a2` are CUSTOM and two user slots, `16` is
BASS and `17` is SPEECH. **`setEqPreset(preset: Int)` can carry names now** instead of a
number the owner has to recognise.

**`AutoPowerOffElementId`** — this page says only `10`/`11` were exercised and that "a
timer encoding, if one exists, is unmeasured". It exists and it is named:
```
00 POWER_OFF_IN_5_MIN   01 …30_MIN   02 …60_MIN   03 …180_MIN
10 POWER_OFF_WHEN_REMOVED_FROM_EARS  11 POWER_OFF_DISABLE
```
✅ The two driven values are exactly the two the XM4's menu offered. ⚠ **That the other
four are in the enum is not evidence this unit takes them** — the menu did not offer
them, which is the device declining before anything is sent. One bounded write with a
read-back is what would settle it, and `06`/`07` above is the cheaper question.

**`AssignableSettingsPreset` — what the [CUSTOM] button can be.** This page refused to
read the capability reply's `21 31 33 22 32 32 34` as a list, on the grounds that doing
so would be inventing a structure. It was right to refuse, and here is the real list:
```
00 AMBIENT_SOUND_CONTROL   10 VOLUME_CONTROL   20 PLAYBACK_CONTROL
30 VOICE_RECOGNITION       31 GOOGLE_ASSISTANT 32 AMAZON_ALEXA   33 TENCENT_XIAOWEI
ff NO_FUNCTION
```
✅ `00` and `31` are the two this repo drove, and they are named exactly as the app's
screen names them.

⚠ **`10` VOLUME_CONTROL is in that list**, which makes Sony's button the second place in
this repo where a setting can bind a control to the volume — the JBL's three gesture
actions are the first. The rule is the same and it is not optional.

### ✅ The capability reply decodes exactly, and it is a NESTED list

`f1 06 01 02 01 00 03 00 02 00 01 21 02 31 03 00 31 01 33 22 32 32 01 00 34` — 23 bytes
this page called "plainly more values than two" and declined to parse. With
`AssignableSettingsKey` (`00` left, `01` right, `02` custom, `03` C), `…Action` and
`…Function` it reads straight through, and the length lands on the byte:

```
01                one assignable key
02                CUSTOM_KEY  ← the XM4's button, and the only key it has
01 00             ⚠ two bytes NOT attributed; carried, not explained
03                three presets are offered for it
  00 02  00 01  21 02          AMBIENT_SOUND_CONTROL: tap → NC_ASM_OFF,
                               long-press-then-activate → NC_OPTIMIZER
  31 03  00 31  01 33  22 32   GOOGLE_ASSISTANT: tap → GET_YOUR_NOTIFICATION,
                               double tap → STOP_GA, hold-during → TALK_TO_GA
  32 01  00 34                 AMAZON_ALEXA: tap → VOICE_INPUT_CANCEL_AA
```
✅ **1 + 1 + 2 + 1 + 6 + 8 + 4 = 23**, and every byte is a legal member of the enum its
position calls for. A wrong grouping would leave a remainder or an illegal value, and
this is the check that separates a decode from a story.

✅ **And it answers the hearing question before it was asked.** The XM4 offers exactly
three presets and VOLUME_CONTROL is not among them, so this unit's button cannot be
bound to the volume at all. ⚠ That is the DEVICE's answer, not a property of the enum —
so an editor must offer what `f0 06` returns, never what `AssignableSettingsPreset`
contains. Reading the enum as the menu is the same mistake as reading `values_Action` as
the JBL's action space, which cost two published corrections there.

### ✅ `99`/`98` are ALERT, which sharpens #965 considerably

The button write's unexplained exchange — the device sending `99 01 02 01`, the app
answering `98 01 02 01`, and only then `f9 06 01 31` — is the **ALERT block**:
`98` `ALERT_SET_PARAM`, `99` `ALERT_NTFY_PARAM`, and v1's `AlertInquiredType` `01` is
`FIXED_MESSAGE`. So the device is raising a **dialog** and the write commits when the
app answers it. That is the "Reconnects to the headphones" prompt, on the wire.

⚠ **And this repo never receives the `99` at all** — it sends the identical SET and gets
a bare ack. So the question in #965 is no longer "what does `98 01 02 01` do"; it is
**why the device raises no alert for us**. The obvious candidate is now named: the app
declares itself with `CONNECT_GET_SUPPORT_FUNCTION` (`06`) and this repo never has.
⚠ Labelled a hypothesis. The test is cheap and read-only up to the last frame: `00 00`,
then `02`, `04`, `06`, then the SET.

### The rows this names, and what has since been asked

⚠ **A wire identity is not a decode**, which is why this table was written. ⚠ **It is no
longer true that "none of them has been asked"** — six were driven or read on 2026-08-23
and are marked below. Anything still unmarked is a name, not a measurement.

| frame | feature | note |
| --- | --- | --- |
| `10`/`11` | COMMON_GET/RET_BATTERY_LEVEL | ✅ **done** — `SonyBattery`, on the card since 2026-08-23 |
| `18`/`19` | AUDIO_CODEC — `01` SBC `02` AAC `10` LDAC `20`/`21` aptX | 👁 read `19 00 10` = LDAC; ⚠ never cross-checked, the app shows a different field |
| `14`/`15` | UPSCALING_EFFECT — `00` OFF `01` VALID `02` INVALID | 👁 read; ⚠ a status, **not** the DSEE switch |
| `e6 02` | UPSCALING — `00` OFF `01` AUTO | ✅ **driven** — DSEE Extreme, `SonyDsee` |
| `24`/`25` | CONNECTION_STATUS | |
| `1c`/`1d` | BLUETOOTH_DEVICE_INFO | |
| `f6 03` | CONTROL_BY_WEARING — `00`/`01` | ✅ **driven** — `SonyPauseOnRemoval` |
| `f6 05` | SMART_TALKING_MODE — **Speak-to-Chat** | ✅ **driven**, ⚠ writes `f8 05 **01** <v>`, a different type table than it reads |
| `fa 05` | its sensitivity, voice focus and mode-out time | ✅ **driven** — one frame, three settings |
| `f6 02` | POWER_SAVING_MODE | |
| `f6 01` | VIBRATOR | |
| `70`/`71`/`74` | SENSE — `01` AUTO_NC_ASM | 👁 `70 01` → `71 01 01`; `74 01 00 01` starts sensing, ⛔ no off, #1113 |
| `82`–`87` | OPT — `01` NC_OPTIMIZER; control `00` CANCEL `01` START | ⚠ plays test tones |
| `46`–`49` | VPT `01`, SOUND_POSITION `02` | ⚠ **only on frame type `0c`.** On `0e` these bytes are VOICE_GUIDANCE — see the second-table section |
| `66 01`, `66 03` | NC alone, ambient alone | this repo drives `66 02` |
| `d6 d1` | GENERAL_SETTING1 | ✅ **driven** — names itself `TOUCH_PANEL_SETTING`; `d8 d1 01 <v>` takes |
| `d6 d3`, `d6 d4` | GENERAL_SETTING3 and 4 | ⚠ both absent from the 22; not on this unit. ⚠ `d4` was missing from this page until the 2026-08-23 audit |
| `22` | COMMON_SET_POWER_OFF | ⚠ ends the session, like the JBL's `aa 97 00` |
| `c4`/`c9` | LOG — ACTION_LOG_NOTIFIER | ⚠ telemetry, see below |

✅ **`AsmId` names a byte this repo carries without understanding.** The ANC write is
`68 02 <on> 02 <nc> 01 00 <ambient>`, and this page says bytes 1, 3 and 4 "held `02 01
00` in all three states and are **not** identified". `AsmId` is `00 NORMAL` / `01 VOICE`,
which is the app's **Focus on Voice** switch — so byte 4 is very likely it, sitting at
its default. ⚠ **Likely, not measured**: nothing has moved it, and this page's own rule
is that a byte constant across every captured state is not attributed by a plausible
name. Flipping Focus on Voice in the vendor app for one capture settles it, and would
add the switch.

⚠ **`a0`–`a9` PLAY includes the volume, and `a8` is its SET.** The 110 unsolicited
`a9 01 20 12` frames in the 2026-08-16 capture are this block notifying. Whatever the
`20 12` is, `PLAY_SET_PARAM` is the one command family on this device that could raise a
level, and it is out of scope for the same reason the JBL's `56` VOLUME_CONTROL is.

⚠ **Sony's headphones keep usage telemetry too**, and the app uploads it: `c4 01 00` →
`c9 01 {"v":"M6","logs":[…]}`, already visible in the capture. The same note as the
JBL's `aa 13` — nothing here reads it, and nothing should start without a better reason
than the frame being understood.

⚠ **All of the above is the APK's word.** Every row is a claim about Sony's app until it
is met on the wire; `docs/captures.md` is why that distinction has its own paragraph.
The five cross-checks above are what make it worth acting on, not what make it true.

## ✅ THE XM4 LISTS ITS OWN FEATURES — driven 2026-08-23 16:43, and it changes the method

```
→ 06 00        CONNECT_GET_SUPPORT_FUNCTION, CommonCapabilityInquiredType FIXED_VALUE
← 07 00 16     71 62 f5 81 51 a1 e1 e2 d2 f6 d1 f4 f3 39 12 13 11 30 c1 14 22 21
```
`16` = 22 is a **count**, and exactly 22 bytes follow. Every one is a legal
`FunctionType`, so the whole frame is accounted for with no remainder:

```
71 AUTO_NC_ASM          62 NC_AND_AMBIENT_SOUND_MODE ✅driven   f5 SMART_TALKING_MODE
81 NC_OPTIMIZER         51 PRESET_EQ ✅driven                   a1 PLAYBACK_CONTROLLER ⚠volume
e1 CONNECTION_MODE ✅driven   e2 UPSCALING             d2 GENERAL_SETTING2 ⛔refused
f6 ASSIGNABLE_SETTINGS #965   d1 GENERAL_SETTING1      f4 AUTO_POWER_OFF ✅driven
f3 CONTROL_BY_WEARING   39 VOICE_GUIDANCE              12 UPSCALING_INDICATOR
13 CODEC_INDICATOR      11 BATTERY_LEVEL               30 FW_UPDATE ⚠out of scope
c1 ACTION_LOG_NOTIFIER ⚠telemetry   14 BLE_SETUP       22 CONCIERGE_DATA
21 POWER_OFF ⚠ends the session
```

✅ **This validates the offline extraction against hardware.** 22 of 22 land on names,
the declared count matches, and all five rows this repo already drives appear at exactly
the bytes they are driven with.

⚠ **The ABSENCES are worth as much as the entries, and they are the part a hand-built
list cannot give you.** Not present, therefore not on this unit: `41` VPT, `42`
SOUND_POSITION, `52` EBB, `f1` VIBRATOR, `f2` POWER_SAVING_MODE, `d3` GENERAL_SETTING3,
`61`/`63` NC-alone and ambient-alone, `92` VIBRATOR_ALERT, `b1` TRAINING_MODE, and the
earbud batteries `15`/`17`/`18`. Six of those were named in #1097 as things to go and
try. **One read retired them**, at no risk and no capture.

⚠ **This is what the JBL has no equivalent of.** That device's twenty-three rows were
counted off a screen by hand, which is why its Ambient Sound Control master switch hid
inside another row for a week (#1041). Ask the device, where the device will answer.

### ✅ Six rows read, and six confirmed against the vendor app's own screens

All reads, in one session, no writes:

| sent | reply | reads as | Sound Connect shows |
| --- | --- | --- | --- |
| `10 00` | `11 00 50 00` | battery 80%, not charging | **80%** ✅ |
| `18 00` | `19 00 10` | codec LDAC | ⚠ not displayed — see below |
| `14 00` | `15 00 02 00` | upscaling effect INVALID | (no row) |
| `e6 02` | `e7 02 00 00` | DSEE Extreme off | **Off** ✅ |
| `f6 03` | `f7 03 00 01` | pause when removed on | **On** ✅ |
| `f6 05` | `f7 05 00 00` | Speak-to-Chat off | **Off** ✅ |

And three already-known rows re-confirmed on the same screens: the equaliser reads `a2`,
which `EqPresetId` calls `USER_SETTING2` and the app calls **Custom 2**; Sound Quality
Mode is **Prioritize Sound Quality** (`ConnectionModeSettingValue 00`); the [CUSTOM]
button is **Ambient Sound Control** (`AssignableSettingsPreset 00`, the value #965 cannot
move); multipoint is **Off**.

⚠ **The codec is the one that is NOT confirmed**, and the distinction matters. The app
shows *Sound Quality Mode*, which is `e1` CONNECTION_MODE — a different field from `13`
CODEC_INDICATOR, which is what was actually negotiated. "Prioritize Sound Quality" is
**consistent** with LDAC and is not the same claim. Left as a decode.

⚠ **`15 00 02 00` reads INVALID rather than OFF** while `e6 02` says the setting is off,
and the app has no row for it. Two fields, and only one of them is the switch: a decoder
must not report "DSEE is off" from the effect status, which is about whether it is
*doing* anything right now.

⚠ **Nothing was written to reach any of this** — the vendor app was navigated by tapping
category headers only, never a control. ⚠ And the header labels are `clickable="false"`
with the tap landing on an enclosing container, which is the JBL's inert-label trap in a
second app; here the label sits *inside* the clickable row rather than beside it, so the
coordinates work. Check the geometry rather than assuming either shape.

⚠ **The app is called "Sound Connect" now**, not "Headphones Connect". The package is
unchanged (`com.sony.songpal.mdr`), so nothing in this repo breaks — but a future session
looking for the old name in the launcher will not find it.

## ✅ ALL THREE SWITCHES DRIVEN — 2026-08-23 17:20, Speak-to-Chat at 18:50

The writers that #1097 asked for, against the XM4, each read first and put back after.

| setting | frame | result |
| --- | --- | --- |
| **DSEE Extreme** `e2` UPSCALING | `e8 02 00 <v>` | ✅ **driven both ways**, restored to Off |
| **Pause when removed** `f3` CONTROL_BY_WEARING | `f8 03 00 <v>` | ✅ **driven both ways**, restored to On |
| **Speak-to-Chat** `f5` SMART_TALKING_MODE | `f8 05 01 <v>` | ✅ **driven both ways** (18:50, worn), restored to Off |

Pause-when-removed is the clean one, and its transcript is the whole proof in six frames:

```
[2] → f6 03        ← f7 03 00 01     On, the value found
[3] → f8 03 00 00  ← f9 03 00 00     write Off
[4] → f6 03        ← f7 03 00 00     ✅ Off, by an independent read
[5] → f8 03 00 01  ← f9 03 00 01     write On again
[6] → f6 03        ← f7 03 00 01     ✅ back where it started
```

✅ **The settingType byte is settled.** Every frame above lands on a named SDK enum —
`ControlByWearingSettingType.ON_OFF` = `00`, `ControlByWearingSettingValue.ON` = `01` —
and the device answered all of them. The same reading explains two settings that were
already driven and whose third byte differed: `SonySoundQuality` sends `00`
(`ConnectionModeSettingType.SOUND_CONNECTION`) and `SonyAutoOff` sends `01`
(`AutoPowerOffParameterType.ACTIVE_AND_SELECTIME_ID`, whose table has no `00` at all).
This page used to say only that the byte "is not the same thing in both". It is the same
kind of thing in both.

### ⚠ THE DEVICE VOLUNTEERS NOTIFICATIONS, AND IT COST A WORKING WRITE

`e8 02 00 01` was reported by the driver as **unverifiable**. It had worked. What happened
is that the XM4 answered the write with its `e9` NTFY_PARAM *and then* emitted `17`
COMMON_NTFY_UPSCALING_EFFECT — a second, unrequested frame — which arrived in the window
the next read was waiting in:

```
[3] → e8 02 00 00  ← e9 02 00 00                 the write, answered
[4] → e6 02        ← 17 00 02 00   ⚠ not an answer to anything asked
```

⚠ **That `17` is not noise, it is causally related**: changing the upscaling *setting*
makes the device announce the upscaling *effect*. Which is the same two-fields-one-switch
trap already noted for `15` — and here it does not merely mislead a reader, it displaces a
reply.

⚠ **On Speak-to-Chat it is worse: the session never recovers.** An `f5` SYSTEM_NTFY_STATUS
arrives early and from then on every exchange returns the previous one's answer —

```
[5] → f6 05        ← 11 00 50 00    [4]'s battery answer
[6] → f8 05 00 00  ← f7 05 00 00    [5]'s answer
[7] → 10 00        ← f9 05 01 00    [6]'s notify
```

— and a spacer read does **not** absorb it, which was the first thing tried.

### ✅ THE CAUSE: THE XM4 IS STOP-AND-WAIT, AND WE ACKED TOO LATE

**It withholds its next DATA frame until the current one is acknowledged.** Both the probe
and the driver acked only after the read window closed, so a window that opened with a
volunteered frame could not also contain the answer — the device was waiting on us. Meanwhile
it retransmitted the unacked frame every ~600 ms; an eight-packet run showed four to six
copies of every reply, which is how obvious this was once the raw bytes were read rather than
the decoded list.

⚠ **This supersedes "acking is not the cause", which was published twice.** That conclusion
came from acking *every* frame instead of only the returned one and seeing no change. The
count was never the variable; **when** was.

✅ **Fixed in the probe** (`Probe.readAcking`, acks mid-window) and driven: the same eight
packets, `00 00` through `f6 04`, each got its own answer and exactly one copy of each frame.

⛔ **Not fixed in the driver.** `exchangeFramed` acks after `Transport.exchange` returns, which
is the same defect; doing better needs a `Transport` that can hand frames back as they land.
It escapes instead by re-reading for an answer it can name — correct, one round trip late.

### ✅ SPEAK-TO-CHAT READS AND WRITES WITH DIFFERENT TYPE TABLES — 18:50

⚠ **This page said for an hour that the XM4 refused Speak-to-Chat, and that was wrong.**
It also carried a second wrong explanation on top of the first — that the setting needed
the headphones worn. Both are struck out below rather than deleted, because the way the
mistake survived is the reusable part.

```
→ f6 05        ← f7 05 00 00     SmartTalkingModeSettingType.ON_OFF        = 00
→ f8 05 01 01  ← ack             SmartTalkingModeParameterType.MODE_ON_OFF = 01
→ f6 05        ← f7 05 00 01     ✅ ON, by an independent read in a fresh session
→ f8 05 01 00                    ✅ restored, confirmed the same way
```

**The read's type byte and the write's type byte come from different enums.** Sony's app
has two payload classes for this one feature and no other: `ve0.c` parses a RET with
`SmartTalkingModeSettingType`, and `ve0.d` builds a SET with `SmartTalkingModeParameterType`,
wrapped by `qe0.r3`, which is `Command.SYSTEM_SET_PARAM`. Every other setting on this page
uses one table in both directions.

⚠ **`f8 05 00 01` is accepted, acked, and does nothing.** No error, no refusal — the write
just does not happen. That is the whole trap: an ack is not an outcome, and this device
will ack a frame it has no intention of acting on.

⚠ **The device named the right table and it was read as noise.** The bad SET drew
`f9 05 01 00` — a `01` in a slot where `00` had been sent. That transposition was written
up here as "the notify shape is unexplained, possibly a malformed echo". It was not
malformed. It was the answer.

~~The write is sent, the device acks it, and a read afterwards still says Off, so this
joins multipoint and the [CUSTOM] button.~~ **Struck 18:50.** It joins neither. Both of
those have the vendor app as a control — for multipoint the app fails too, for the
[CUSTOM] button the app succeeds where we do not. Speak-to-Chat had **no control at all**,
and was filed next to them on the strength of looking similar.

~~It may need the headphones worn, since it is a wearing-sensor feature.~~ **Struck 18:50
— tested and false.** Worn, `f8 05 00 01` still did nothing; worn, `f8 05 01 01` worked
immediately. The wearing hypothesis was plausible, cheap to test, and wrong, and testing
it is what turned up the real cause.

⚠ **The generalisation failed, not the byte.** `SonySwitch` was built from three settings
that all used one type byte in both directions, and the fourth was assumed to match. Three
agreeing samples are not a rule when the vendor SDK has a separate class saying otherwise —
and the SDK had been extracted and was sitting on disk when the assumption was made.

## ⚠ TABLE 2'S PERIPHERAL BLOCK — REACHED 2026-08-24, AND IT CAN UNPAIR

⛔ **DO NOT SWEEP THIS BLOCK.** `ConnectivityActionType` is `00 DISCONNECT · 01 CONNECT ·
02 UNPAIR`, and it is a parameter of `38` PERI_SET_PARAM. A write here can **unpair a
device** — the same class of hazard as Bose's `aa 95` factory reset, and the reason this
was explored with `30`/`32`/`36` reads only.

What the XM4 answers, on frame type `0e`:

    → 30 00   ← (nothing)     PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT
    → 30 01   ← 31 01 08 02 01   SOURCE_SWITCH_CONTROL — supported
    → 30 02   ← (nothing)     PAIRING_DEVICE_MANAGEMENT_WITH_BLUETOOTH_CLASS_OF_DEVICE
    → 30 03   ← (nothing)     MUSIC_HAND_OVER_SETTING
    → 32 01   ← 33 01 00 00   its status
    → 36 01   ← (nothing)     no GET_PARAM answer

✅ **One of four peripheral types exists on this device**, and it is source switching —
choosing which connected device the audio comes from.

⚠ **`08 02 01` and `00 00` are NOT decoded.** The obvious reading of `02` is the multipoint
device count and it would be a guess; Sony's parser for this payload was not found before
the search was called off. **Two undecoded byte strings are a better record than three
invented field names** — see the [CUSTOM] button's capability, which sat here as "more codes
than this enum names" for a week and then decoded exactly once the parser was read.

⚠ **Curious and unexplained**: this pair refuses multipoint outright, yet declares the
source-switch control that multipoint is for.

## ✅ EQ BAND LEVELS — THE PRESET BYTE MUST BE `ff`, AND THAT WAS THE WHOLE BUG

    → 58 01 ff 06 0d 0a 0a 0c 0e 05   ← (ack only)
    → 56 01                           ← 57 01 a2 06 0d 0a 0a 0c 0e 05   16k now −5
    → 58 01 ff 06 0d 0a 0a 0c 0e 10   ← (ack only)
    → 56 01                           ← 57 01 a2 06 0d 0a 0a 0c 0e 10   restored to +6

`ff` is `EqPresetId.UNSPECIFIED`. It means **leave the selection alone, these are the
levels** — nothing is stored under it, and `57 01` goes on reporting the real slot, so it
is a write-only sentinel rather than a preset.

⚠ **Sending the slot's own id instead is acked and silently dropped.** That is what this
repo did for a day: read `a2` out of `57 01`, put it back into `58 01`, and watch every
write vanish. Every other byte was already correct — type, count, offset encoding, band
order — so there was nothing to see. A write that is *dropped* looks exactly like a write
that is *unsupported*.

✅ **Three independent sources agree**, which is why this is not another theory:

- the wire, captured 2026-08-24 while a band was dragged in Sound Connect;
- the enum, where `ff` is the only non-slot name in `EqPresetId`;
- the writer, `l20/c.o()` — logging `"in sendEqBandSteps"` — which hardcodes
  `UNSPECIFIED` and **discards the `EqPresetId` it was passed**. The preset-change path
  in the same class passes a real id with an *empty* levels array.

So `EQEBB_SET_PARAM` has two uses that share a shape, and the count byte selects between
them:

| intent | frame |
| --- | --- |
| choose a preset | `58 01 <preset> 00` |
| move the bands | `58 01 ff <count> <levels…>` |

⚠ **A levels write draws no notify, only an ack** — unlike a preset change, whose
`59 01` carries the result. Sony's own app re-reads with `56 01` after every drag, and so
does this driver: the read-back is the only evidence a levels write has.

⚠ Levels are offset-encoded: the byte is `level + 0x0a`, so `0d 0a 0a 0c 0e 10` is
`[3, 0, 0, 2, 4, 6]` — six values for five bands, the first being clear bass.

⚠ **The slider emits while it travels.** One drag produced ten `58 01` frames in nine
seconds, each a waypoint; only the last is the setting. Counting frames would read a
single gesture as ten changes.

✅ **Driven by this repo 2026-08-24**, 16k `+6 → +5 → +6`, each direction confirmed
by read-back — so the fix is proven from our own frames, not only from Sony's.

⚠ **No frame declares a level range.** `-10…+10` is off the vendor app's own axis, so
refusing a value outside it is our guard, not the device's answer.

## ✅ SPEAK-TO-CHAT'S DETAIL SETTINGS — `fa`/`fc`, and the device names its own timings

The first `fa`/`fc` SYSTEM_*_EXTENDED_PARAM frames this repo has sent. They behave like the
ordinary param frames: the notify echoes the value, so a write is confirmable from its own
reply as well as by re-reading.

    → fa 05              ← fb 05 00 00 00 01   AUTO · voice focus off · MID
    → fc 05 00 01 00 01  ← fd 05 00 01 00 01   sensitivity HIGH
    → fc 05 00 02 01 02  ← fd 05 00 02 01 02   LOW · focus on · SLOW
    → fc 05 00 00 00 01  ← fd 05 00 00 00 01   restored

**Payload**: `<SmartTalkingModeDetailSettingType 00 TYPE_1> <DetectionSensitivity>
<CommonOnOffSettingValue> <ModeOutTime>`. The leading `00` is a payload selector, not a
setting — it has one legal value.

⚠ **THREE SETTINGS, ONE FRAME, AND NO FIELD SELECTOR.** Writing the sensitivity means
sending the voice focus and the mode-out time too. So they are modelled as a single
`ChatDetail` value rather than three switches — three separate setters would each have had
to invent the other two fields, and the caller changing one chip would silently rewrite the
other two.

⚠ **They take while Speak-to-Chat itself is OFF**, which was the case throughout the run
above. Unlike Focus on Voice, which is accepted and silently ignored outside ambient mode,
these are not gated on the feature being on. So a write here that appears to do nothing is
a real failure, not a mode problem.

### ✅ The seconds come from the device, not from a table here

`f0 05` → `f1 05 00 01 00 00 00 00 0f 1e 3c 00`, and Sony's capability parser reads it at
fixed offsets:

    [2] 00  SmartTalkingModeSettingType.ON_OFF
    [3] 01  PreviewType.SUPPORT
    [4] 00  DetailSettingType.TYPE_1
    [5] 00  DetectionSensitivityType.AUTO_HIGH_LOW
    [6] 00  VoiceFocusType.ON_OFF
    [7] 00  ModeOutTimeType.TYPE_1
    [8..] 0f 1e 3c 00   a four-int array, indexed by ModeOutTime's ordinal

So **FAST = 15 s, MID = 30 s, SLOW = 60 s, NONE = 0** — the device's own numbers, which is
why the card can print seconds instead of Sony's adjectives. ⚠ `0f 1e 3c` was spotted as
15/30/60 by eye first; that is a guess until the parser says where the array starts and how
long it is, and it does — `new-array` of 4, read from index 8.

## ✅ POWER OFF — driven 2026-08-24, and the only write with no reply

    → 22 00 01     COMMON_SET_POWER_OFF · FIXED_VALUE · USER_POWER_OFF

⚠ **There is no answer and there cannot be one.** The device acts and the link drops, so a
read-back is not unavailable, it is a contradiction. This is the one Sony write that
`Confirmation` does not apply to; calling it unverifiable would imply a check was tried.

**What confirms it is the radio.** Within seconds of the tap:

    VolumeLive: refresh: bonded=13 connected=0 listed=0
    dumpsys bluetooth_manager: ACL BR/EDR:N

and the card left the list, replaced by "No headphones switched on" — the right one of the
five [Emptiness] reasons, reached without anything special being written for this case.

⚠ **The screen confirms first, and the dialog names the COST rather than the action**:
"They can only be switched back on by hand, on the headphones." That someone is switching
their headphones off is obvious from the button; that this app cannot undo it is not.

⚠ `PowerOffSettingValue` also has `00 NO_USE`. It is the enum's absent value, not an "on" —
nothing switches a headphone on over a link that requires it to be on.

## ✅ THE LAST THREE UNASKED FUNCTIONS — read 2026-08-24, and none is a setting

`14` BLE_SETUP and `22` CONCIERGE_DATA were the only entries in the device's own
22-function list that nothing had ever asked. Both answer, and neither is something an
owner could change.

    → 1c 00     ← 1d 00 11 <17 ASCII bytes>    the pair's own Bluetooth address
    → 1c 01     ← 1d 01 08 <8 ASCII bytes>     a BLE hash
    → 28 00     ← 29 00 <JSON>                 {"formatVer":"BT02","di":"…"}

⚠ **The values are deliberately not written down. This repo is public**, and two of these
three are stable identifiers for Pippijn's headphones. The shapes are what a decoder
needs; the bytes would only be a way to recognise his hardware.

- **`14` BLE_SETUP** is `1c`/`1d` COMMON_*_BLUETOOTH_DEVICE_INFO, with
  `BluetoothDeviceInfoType` `00 BLUETOOTH_DEVICE_ADDRESS · 01 BLE_HASH_VALUE`. Both are
  ASCII, length-prefixed. Nothing to set, and the address is one Android already gives us
  from the bond — so there is no reason to read it at all.
- **`22` CONCIERGE_DATA** is `28`/`29`, and the payload is a JSON diagnostics blob for
  Sony's support flow. ⛔ **Same family as `c1` ACTION_LOG_NOTIFIER and excluded for the
  same reason**: it is data *about* the owner's usage, collected for the vendor, not a
  control. The `di` field is an opaque encoded run.

⚠ **`24`/`25` CONNECTION_STATUS answers although it is NOT in the 22.** `25 01 01 00`
decodes — via `se0/z`, which reads **two** `ConnectionStatus` values — as left
`01 CONNECTED`, right `00 NOT_CONNECTED`. It is an earbud question (the WF series connect
independently); a single-unit over-ear answers it truthfully and uselessly.

✅ **So the function list is about FEATURES, not about every command the device will
answer.** That is worth knowing before treating 22 as the whole surface: it is the whole
surface of *things an owner has*, which is the more useful denominator, but a command
absent from it may still reply.

## ⛔ ADAPTIVE SOUND CONTROL — ITS ON/OFF IS APP-SIDE, so there is none here (#1113)

The XM4's headline feature. ⚠ **Read the resolution at the end of this section first** —
the on/off turned out to live in the phone, so there is no device setting to find, and that
is different from the two wrong answers this section carried before it.

⚠ **It was headed "IS NOT A SETTING" and said the device blocked it. Both were wrong.**
What follows is what the SENSE block contains, which is true; the conclusion drawn from it
was not. The two corrections are kept because the way each survived is the reusable part.

```
→ 70 01     SENSE_GET_CAPABILITY, SenseInquiredType.AUTO_NC_ASM
← 71 01 01  ✅ supported, SenseTableType.TYPE1
```

**The SENSE block has three commands and that is all of them**: `70` GET_CAPABILITY,
`71` RET_CAPABILITY, `74` SET_STATUS. There is **no** `GET_PARAM`, no `RET_PARAM`, no
`SET_PARAM`, no `NTFY`. Compare the neighbouring blocks, which all have the full nine.

⚠ **So there is nothing to read.** Every driver in this repo confirms a write by reading
the value back, and that is not an implementation choice — it is the rule that caught the
XM4 refusing multipoint and caught Speak-to-Chat being written with the wrong byte. A
setting with no getter cannot be confirmed by this codebase's own standard.

⚠ **And there is nothing to switch off.** `SenseSettingControl` has exactly two entries,
`00 NO_USE` and `01 START`. The vendor app's whole Adaptive Sound Control class is one
method that sends the START and nothing else — no stop, no toggle, no query. Checked by
reading every method on it, not by grepping for "stop" and finding none.

So it is a **trigger**: "begin sensing now". Whatever holds the on/off state, it is not in
this block, and it is not in the 22 functions the device declares either — the only SENSE
entry there is `71 AUTO_NC_ASM`, which is this.

⚠ **Do not add a toggle backed by nothing.** A switch drawn from `74 01 01` alone could
not report its own state or be turned off. That is #1041 in reverse — there a real control
was missing from the UI, here an unbacked one would be added to it.

### ⚠ CORRECTION, same evening: "the device blocks it" was NOT established

The paragraph above says what the SENSE block contains, and that part stands. What was
written next to it — that Adaptive Sound Control is therefore *blocked by the device* —
does not follow, and it was published anyway. **Sony's app plainly does ASC on this
model.** An absence in one block is evidence about that block.

What the APK shows on a second look:

- `SenseSettingControl` has **two different tables**. v1, which the XM4 speaks: `00 NO_USE`,
  `01 START`. v2: `00 START_SETTING`, `01 END_SETTING`. The app class that sends END is on
  the **v2** path, so it is not the XM4's — but it does show START/END is a bracket around
  an edit, not an on/off, which is a different reading of `74` than "trigger".
- There is a whole app-side subsystem: `AscLocationPositionSelectFragment`,
  `ActivityRecognitionUiTab`, `AscSoundSettingsEditContract`, a places model. ASC on this
  app is substantially more than one frame.
- Sony's code uses Google's `DetectedActivity` **only for logging badges**, not for ASC. So
  the activity detection is not coming from the phone's GMS APIs.

⚠ **None of that said where the on/off lived, and neither did the original claim.** The
honest state that evening was *not found yet*, which is not the same as *refused* — the
distinction this page exists to keep. See multipoint, which **is** refused and has the
vendor app failing identically as its control.

⛔ **SUPERSEDED the next morning: it is not on the device at all.** Kept because the
distinction above is the reusable part, not because "not found yet" is still the state.
The answer is in the next subsection.

### ✅ RESOLVED FROM THE SDK, 2026-08-24: THE ON/OFF IS APP-SIDE

⚠ **First, this page had the frame wrong.** It said `74 01 01`, three bytes. The payload
writer emits **four**:

    74 <SenseInquiredType> <CommonStatus> <SenseSettingControl>
    74 01 AUTO_NC_ASM  ·  00 ENABLE  ·  01 START

⚠ **And `CommonStatus` is hardcoded in the constructor, not a parameter.** The payload class
takes `(SenseInquiredType, SenseSettingControl)` and assigns `CommonStatus.ENABLE` itself.
So the wire format has a byte for enable/disable and **the app can never set it to
disable** — `74 01 01 01` is expressible on the wire and is not a frame Sony's app can
build. Do not send it on the strength of the enum; nothing observed it.

**Where the state actually lives**: `AutoNcAsmPersistentDataFactory` persists ASC as
app-side JSON — `enabled`, `ncAsmEffect`, `ncAsmMode`, `ncValue`, `asmId`, `asmValue`,
`noiseAdaptiveOnOffValue`, `noiseAdaptiveSensitivity`. That is a per-activity preset table
with its own `enabled` flag, in the phone, not the headphones.

So the mechanism is: the app tells the device **once** to start sensing, then decides what
the setting should be and writes it with `68 02 …` — the ordinary NCASM_SET_PARAM this repo
already drives. ⚠ Confirmed by exclusion on the other side too: `NcAsmEffect` is
`00 OFF · 01 ON · 10 ADJUSTMENT_IN_PROGRESS · 11 ADJUSTMENT_COMPLETION`. **There is no
adaptive mode in the NCASM block**, so nothing else on the device could be holding it.

**What that means for this app: there is no toggle to mirror.** ASC is not a device setting
that could be read, written or confirmed. Supporting it would mean reimplementing Sony's
activity and place detection and driving the ANC writes ourselves — a different and much
larger feature than remote-controlling a headphone, and one this repo has not chosen.

### ✅ CONFIRMED BY CAPTURE, 2026-08-24 22:48 — and the two directions differ

The check this section asked for was taken. Sound Connect's own ASC switch, off then on:

    22:48:40.764 → 68 02 11 02 02 01 00 00   NCASM_SET_PARAM   ← the OFF tap
    22:48:40.819 ← 69 02 01 02 02 01 00 00   its notify
    ~22:48:52                                (the ON tap — NOTHING on the wire)

✅ **Turning ASC OFF sends an ordinary `68 02` NCASM_SET_PARAM**, the same frame this repo
already drives for the ANC chips. It is the app putting the manual mode back, not an ASC
command: there is no ASC-specific opcode in the window at all.

✅ **Turning ASC ON sends nothing.** ⚠ **And the link was up**, not merely quiet — the
next RFCOMM control frame is a DISC at 22:49:19, half a minute later, so the silence at
22:48:52 is an idle live channel rather than a dead one. That distinction is the whole
reason to check: "an empty window is evidence" only after the link is proven.

So the mechanism is exactly as the SDK described it. Switching ASC on tells the headphones
**nothing**; the app starts deciding for itself and writes ordinary NCASM frames when it
wants the mode changed. There is no device state to read, write or confirm.

⚠ **No `74` SENSE_SET_STATUS appeared either.** The "begin sensing" frame is not sent per
toggle — whatever triggers it, it is not this.

## ✅ GENERAL_SETTING1 IS THE TOUCH PANEL, and the device says so itself

Found while looking for where Adaptive Sound Control keeps its state. `d1` was an
untouched row named only `GENERAL_SETTING1`.

```
→ d0 d1     ← d1 d1 02 13 "TOUCH_PANEL_SETTING" 00 01 00
→ d0 d2     ← d1 d2 02 12 "MULTIPOINT_SETTING" 1a "MULTIPOINT_SETTING_SUMMARY" 01 00
→ d6 d1     ← d7 d1 01 00      false
→ d6 d2     ← d7 d2 01 00      false
```

✅ **The shape was decoded by comparison against a known value, not by guessing.** `d2` is
multipoint, which this repo has read many times and independently knows to be **off**; its
capability and its value frame have the identical structure to `d1`'s. So:

```
<cmd> <GsInquiredType> <GsStringFormat 02 ENUM_NAME> <len> <name> <len> <summary> <GsSettingType> <value>
```

The lengths check out — `12` = 18 = `len("MULTIPOINT_SETTING")`, `13` = 19 =
`len("TOUCH_PANEL_SETTING")`, `1a` = 26 for the summary — and `01` is
`GsSettingType.BOOLEAN_TYPE` in both. `d7 d2 01 00` reading false is the check that the
byte positions are right, because that answer is already known to be true.

⚠ **These settings are self-describing, which is new here.** Every other setting in this
file is a fixed byte with a meaning learned from a capture. `d1`/`d2`/`d3` carry their own
names, so a model that reports "GENERAL_SETTING1" is throwing away a string the device
sent. ⚠ `d3` GENERAL_SETTING3 is **not** on this unit — it is absent from the 22.

⚠ **Nothing has been written to `d1`, and it should not be assumed writable.** Its
neighbour `d2` is the one setting the XM4 flatly refuses, using the identical `d8 <type>
01 <v>` frame. Sharing a frame family with a refused setting is a reason to test, not a
reason to expect success.

⚠ **The touch panel currently reads OFF.** That is the owner's setting, whatever it is for,
and nothing here changed it.

## ✅ THE THREE UNIDENTIFIED BYTES IN THE ANC FRAME ARE NAMED

`68 02 <on> 02 <nc> 01 00 <ambient>` — this page has said since the 16th that "bytes 1, 3
and 4 held `02 01 00` in all three states and are not identified". Every one of them is an
enum in the SDK:

| byte | enum | value |
| --- | --- | --- |
| `02` after `<on>` | `NcAsmSettingType` | `02` DUAL_SINGLE_OFF |
| `<nc>` | `NcDualSingleValue` | `00` OFF · `01` SINGLE · `02` DUAL |
| `01` | `AsmSettingType` | `01` LEVEL_ADJUSTMENT |
| `00` | **`AsmId`** | `00` NORMAL · `01` VOICE |
| `<on>` | `NcAsmEffect` | `00` OFF · `01` ON |

✅ **`AsmId` is Focus on Voice, and this confirms the guess this page recorded as
unmeasured.** It sits at the position the earlier note suspected — "byte 4 is very likely
it, sitting at its default" — and it is `00` NORMAL because nothing has moved it.

### ✅ FOCUS ON VOICE DRIVEN — 18:05, worn

```
→ 68 02 01 02 00 01 00 14  ← 69 02 01 02 00 01 00 14   ambient, AsmId.NORMAL
→ 66 02                    ← 67 02 01 02 00 01 00 14   ✅ off
→ 68 02 01 02 00 01 01 14  ← 69 02 01 02 00 01 01 14   ambient, AsmId.VOICE
→ 66 02                    ← 67 02 01 02 00 01 01 14   ✅ on, by an independent read
```

⚠ **IT ONLY TAKES IN AMBIENT MODE, and the tidy-up is how that was found.** The restore
sent `68 02 01 02 02 01 00 00` — the exact frame the device had reported before the test,
`AsmId.NORMAL` included — and the read-back came back `67 02 01 02 02 01 01 00`. The mode
was restored; **the AsmId byte was not**. The device had accepted the frame, applied the
part of it that was about noise cancelling, and silently kept Focus on Voice on.

Getting back required routing through ambient: set `68 02 01 02 00 01 00 14`, *then* the
ANC frame. After that the read matched the original byte for byte.

⚠ **A trusted write would have left the owner's headphones changed** and reported success
while doing it. This is the third time today an XM4 write was accepted and ignored —
`f8 05 00 01` for Speak-to-Chat, `f8 06 01 31` for the [CUSTOM] button in #965, and now
this. **On this device an ack means the frame was well-formed and nothing more.**

`Drivers.SonyXm4.setFocusOnVoice` therefore refuses in ANC rather than sending a frame it
knows will be dropped, and returns the value that is actually there.

## ✅ BATTERY IS ON THE CARD — 2026-08-23 20:17

```
→ 10 00        BatteryInquiredType.BATTERY
← 11 00 46 00  0x46 = 70 %, BatteryChargingStatus.NOT_CHARGING
```

✅ **Every byte lands on a named enum**, so the scale is not inferred from one sample —
and it was checked twice against the vendor app: `50` = 80 % on the afternoon's reading
when Sound Connect showed 80, and `46` = 70 % this evening with the card agreeing.

⚠ **`00` BATTERY is the only cell this model has.** `01` LEFT_RIGHT_BATTERY and `02`
CRADLE_BATTERY belong to earbuds and a case, and the XM4 declares neither — `15`/`17`/`18`
are absent from the 22 functions it lists. Asking for them would be inventing cells.

⚠ **Unlike the JBL's, it must be ASKED.** `JblBattery` arrives unbidden every ten seconds;
nothing here has ever seen a `13` COMMON_NTFY_BATTERY_LEVEL from the XM4, so a card that
waited for one would sit blank.

⚠ **`f0` UNKNOWN decodes to null, not to "on battery".** `BatteryChargingStatus` has three
values and the third means the device does not know. Defaulting it to `false` would put a
confident percentage on screen on the strength of a shrug — the same rule `SonyAutoOff`
applies to an unrecognised value byte.

⚠ **This was decoded and confirmed on 2026-08-16 and sat unused for a week.** The read
worked, the cross-check passed, and no driver method existed — so the Sony card showed no
charge while the JBL's did. The same shape as #1041 and #1112: the wire was never the
problem.

## ✅ THERE IS A SECOND COMMAND TABLE, AND THE XM4 ANSWERS ON IT — 2026-08-23 20:40

Going after `39` VOICE_GUIDANCE turned up something larger than the feature.

⚠ **THE FRAME TYPE BYTE SELECTS WHICH COMMAND TABLE THE PAYLOAD MEANS.** `DataType`
`0c` is `DATA_MDR` = **table1**; `0e` is `DATA_MDR_NO2` = **table2**. Everything this repo
had ever sent was `0c`. `SonyFrame` has carried `TYPE_DATA_MDR_NO2` since it was written,
with a note wondering which one a given model answers — it answers **both**.

```
→ 3e 0e … 40 01     VOICE_GUIDANCE_GET_CAPABILITY   (type 0e = table2)
← 41 01 01 01 0f 01 02 03 04 05 06 07 08 09 0a 0b 0d 0f 10 f0
```

⚠ **THE RANGES OVERLAP AND MEAN DIFFERENT THINGS, WITH NOTHING IN THE PAYLOAD TO TELL
THEM APART.** In table1, `40`–`49` is **VPT** — a sound-field feature. In table2 the same
bytes are **VOICE_GUIDANCE**. So `48` is `VPT_SET_PARAM` on one table and
`VOICE_GUIDANCE_SET_PARAM` on the other, and nothing in the payload says which was meant.
`probe.sh` gained `SONY_TABLE2=1` with that warning attached; the default stays `0c`.

⚠ **On THIS unit the collision is inert, and the first version of this note overstated
it.** It said sending `48` as `0c` "would write a sound-field parameter". The XM4 does not
have VPT — `41` is among the absences listed earlier on this page — so the likeliest
outcome is that it is ignored. The hazard is real for a model that *does* have VPT, and it
is a reason to be deliberate about the type byte; it is not a near-miss that happened here.

✅ **This resolves a contradiction on this page.** The device declares `39` VOICE_GUIDANCE
in its table1 `FunctionType` list, and table1 has **no** voice-guidance commands at all.
The function list is the device's feature inventory; the commands for that feature live on
the other table. An absence in one table is not an absence on the device — the same
mistake shape as calling Adaptive Sound Control "device-blocked" earlier the same evening.

### What the XM4 says about voice guidance

| sent | reply | reads as |
| --- | --- | --- |
| `40 01` | `41 01 01 01 0f …` | supported; `SupportsSwitch.SUPPORT` |
| `42 01 01` | `43 01 01 00` | ⚠ status ON_OFF = `00` |
| `46 01 01` | `47 01 01 01` | param ON_OFF = `01` **ON** |
| `42 01 02` | `43 01 02 01` | language = `01` ENGLISH |
| `46 01 02` | `47 01 02 01` | language = `01` ENGLISH |

✅ **The language agrees on both commands and matches the app**, which shows English.
The capability's `0f` = 15 is followed by exactly fifteen bytes, so the count is a count.

⚠ **But they are NOT all languages, and calling them "fifteen languages" was wrong.**
`VoiceGuidanceLanguage` runs `00`–`0f`; the list ends `… 0d 0f 10 f0`, and **`10` and `f0`
are outside that enum entirely** — checked, and the enum is complete (16 declared, 16
read). So thirteen of the fifteen are languages and two are something else. What, is not
known: no enum in `table2/voiceguidance/param` has a `10`, and `f0` is the shape of an
"unknown" sentinel elsewhere in this SDK but is not one here.

✅ **SETTLED 2026-08-24 by moving it: `46`/`48` is the control.**

    → 48 01 01 00   ← 49 01 01 00   off
    → 46 01 01      ← 47 01 01 00   agrees
    → 48 01 01 01   ← 49 01 01 01   restored
    → 46 01 01      ← 47 01 01 01   agrees

`42`/`43` still reports `00` throughout and is **not** the switch. What it is remains
undecoded — the same shape as `15` UPSCALING_EFFECT reading INVALID while `e6 02` says the
setting is off, one block over. Two fields, one switch, and now it is known which.

⚠ **The enums are in `v1/table2`, and a `v2/table2` exists with the same class names and
DIFFERENT values.** `v2`'s `VoiceGuidanceStatusType` is `00 ON_OFF · 01 LANGUAGE`; `v1`'s
— the one this device speaks — is `01 ON_OFF · 02 LANGUAGE`. Reading the wrong package
makes a correct capture look like a misdecode, and nearly rewrote this table.

⚠ **Language (`02`) is deliberately not offered**: changing it would make the headphones
speak a language their owner did not ask for. The read is decoded; no write is exposed.

⚠ **Nothing has been written to table2.** The write is `44` SET_STATUS or `48` SET_PARAM
and which one takes has not been established. `48` is the byte that is VPT on the other
table, so it is exactly the one worth being careful with.
