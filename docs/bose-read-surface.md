# The Bose surface

`./probe.sh sweep <mac> <spp-uuid> bose <blocks> <fns>` walks `[b][f] 01 00`.
Operator `01` is Get, so a sweep is read-only and safe on worn headphones.
Swept against the QC45 (304 packets, ~2 min) and the QC35, 2026-08-15.

⚠ **Sweep past block `12`.** The first sweep ran `00-12` and missed `1f`, where
the QC45 keeps ANC. Blocks `0a`–`0d` answer "block not supported", which reads
like the end of the map and is not.

## The error codes are the map

| Reply | Meaning |
| --- | --- |
| `04 01 03` | block not supported |
| `04 01 04` | function not supported |
| `04 01 05` | ⚠ **NOT "a Set" — see the 2026-08-26 correction at the end of this page.** It marks a Start/Processing/Result *transaction*, and operator `05` reads it. |
| `04 01 01` | bad/missing argument (pinned by `1f 06` Get with no index) |

⚠ **RETRACTED 2026-08-26 — `04 01 05` does NOT locate write commands**; several of
these are readable transactions (`01 01`, `00 04`, `15 01` all stream data to operator
`05`). The list below is kept because it is a true record of which functions answered
`04 01 05`, but it classifies nothing. ⚠ **`04 07` CLEAR_DEVICE_LIST is in it — never
send it with any operator but `01`.** On the QC45:
`01 01  02 01  03 03  03 05  03 08  03 09  03 0b  03 0c  04 07  05 02  05 06
06 01  07 01  07 05  07 0b`.

## ✅ ANC

```
QC45   1f 03 05 02 <slot> 01     slot 0=Quiet 1=Aware 2=Home 3=unnamed
QC35   01 06 02 01 <value>       00 Off · 01 High · 03 Low   (2nd reply byte 0b is constant)
```
⚠ **The QC35 names were WRONG in this repo until 2026-08-26** — see "Bose Connect is
the only oracle" at the end of this page. This line recorded the value *set*,
`00 / 01 / 03`, and never which was which, so the driver's invented meanings had
nothing here to contradict them.

Both driven; headphones announced each mode aloud. `01 05` on the QC45 reads the
active level (`0b <level> 03`, Quiet=`00` Aware=`0a`) and is **read-only** — three
writes there were refused `04 01 05`. The QC45 has 11 levels (`0b`), Quiet/Aware
being the ends; the QC35 has three states at `01 06`, a function the QC45 reports
unsupported. **Same framing, different tables.**

`1f 01 05 00` dumps the whole mode table in one reply (all four slots with names
and levels) and shows `05` Start → `07` Processing → `06` Result in one exchange.
`1f 07` lists slots `00 01 02 03`; `1f 06 01 01 <idx>` reads one.

### ⚠ Three mistakes this cost

1. **The same packet was tried and dismissed as inert** — sent while the QC45 was
   *already* in that mode, so correct behaviour read as failure. **Always drive a
   setting to a value it does not hold.**
2. The payload is `<slot> 01`, not `01 <slot>`. The one captured example had `01`
   in both bytes, hiding the order.
3. The connect handshake sends `1f 03 05 02 **00** 01`; a selection sends
   `… **01** 01`. **A packet lifted from a handshake is not a command.**

Found by capturing one *isolated* action and decoding the window — earlier
captures covered periods where several things happened, and every packet inferred
from them was plausible and inert.

## QC45 reads

```
00 00/00 01  "1.1.0"        00 05  "1.0.6-80+f5f219b"    00 06  own BD_ADDR
00 07  "084896T50188177AE"  00 0a  "SOR"                 01 02  device name
01 05  0b <anc> 03          01 07  f60a0000/0001/0002    01 09  80 09 03 00 01 40 08 …
01 0b  01 02 0f             02 02  5a ff ff 00  ← battery 90%
02 0d/02 0e  ASCII CSV ~180 B, per-cell charge stats
03 04  ff 00000000 "0.0.0"  03 0d  00 01 07 ff           04 01  00 00 03
04 04  01 <phone MAC>  ← paired list   04 09  <phone MAC>  ← active  (MULTIPOINT)
05 01  00 02 01 <phone>     05 03  01 ff       05 04  01 ff ff     05 05  20 0b
09 02  00 00 00 00 00       1f 00  "1.0.0"     1f 02  02 02 00 00 00 09
1f 05  01                   1f 08  04 07       17 0b  00
19 xx  telemetry block (19 02 → 0f 71, 19 03 → 00 50 53 73, 19 0c → 50)
```

⚠ **`05 07` and `05 0d` are live telemetry** — they move on their own (`9b→96`,
`97→92`). Never a fingerprint, and noise in any diff.

⚠ **Take the baseline TWICE.** The ANC diff moved three fields; `05 04` and
`08 07` looked equally convincing and had *already* differed between two
pre-change sweeps. A second baseline costs a minute and turns a guess into an
elimination.

## QC35 reads

Differs from the QC45 — battery moves, ANC moves:
```
00 01  "1.0.4"   00 05  "4.8.1"   00 06  own BD_ADDR
00 07  "077061Z93573967AZ"        01 02  "Pippijn Bose QC35"
01 04  3c  ⚠ NOT battery — see below
01 06  01 0b  ← ANC       01 03  a1 00 04 cf de     01 09  10 04 02 07
02 02  46        03 01  01      03 04  ff 00000000 "0.0.0"
04 04/04 09  paired + active     05 01/05 03/05 04/05 05/05 07  as QC45
09 02  21 00 3e
```

## Not yet

✅ **QC35 block `1f` does NOT exist** — settled 2026-08-25, see the end of this page.

EQ (`01 07`), multipoint (`01 0a`) and the Action button (`01 09`) are **decoded** —
`docs/bose-settings.md`, from the 2026-08-16 capture rather than from this sweep.
⚠ One thing that leaves open about the list above: `01 0a` answers a Get but is not
among the readable functions recorded here. ✅ Auto-off is no longer among them —
it is `01 04`, see the correction at the end of this page.

A long sweep ends in `Broken pipe` around block `0d`; the device closes after a
run of unsupported blocks. Range-limit rather than sweeping to `12` blindly.

## ⚠ `01 04` is the STANDBY TIMER, not the battery — 2026-08-23

The QC35 list above read `01 04  3c` as "battery 60%", and noted that the QC45 keeps
its battery at `02 02` instead. Bose Connect's own SDK names both, unobfuscated
(`docs/bose-settings.md`): `02 02` is `STATUS/BATTERY_LEVEL` on **both** devices, and
`01 04` is `SETTINGS/STANDBY_TIMER` — so `3c` is **sixty minutes**, Bose Connect's
default auto-power-off, and this page's own QC35 line `02 02  46` is the battery it
was looking for.

⚠ **0x3c being a believable battery percentage is the whole reason the wrong reading
stood**, next to a device where the battery really did move. The tell was available:
the QC45 answers `02 02  5a ff ff 00` and the QC35 `02 02  46`, so neither of them
lacks the function that `01 04` was invented to replace.

⚠ **Inference, not measurement.** Read `01 04` and `02 02` on the QC35 and compare
each against Bose Connect's screens. That settles #966 for both devices in one pass —
the auto power off that "is not on the QC45's device page at all".

## ✅ The QC35 answers step 0 — 2026-08-25, on hardware

`00 02` ALL_FUNCTION_BLOCKS is the device listing its own blocks, and it answers:

    → 00 02 01 00        ← 00 02 03 03  21 03 3f

⚠ **The three bytes are a bitmask, big-endian across the payload and LSB-first
within it** — bit *n* is block *n*, so `21 03 3f` reads `00 01 02 03 04 05 08 09
10 15`. Read the other way round it is a plausible-looking list of block numbers
that happens to be wrong, which is the same trap `01 04` fell into on this page.

**Confirmed by discrimination, 5/5, rather than by the arithmetic looking neat.**
The error taxonomy at the top of this page separates a *block* refusal from a
*function* refusal, so the mask makes a falsifiable prediction for any block:

| asked | mask says | answered | |
| --- | --- | --- | --- |
| `06 01` | absent | `04 01 03` block not supported | ✅ |
| `07 01` | absent | `04 01 03` block not supported | ✅ |
| `0c 01` | absent | `04 01 03` block not supported | ✅ |
| `10 01` | present | `04 01 05` function exists, not gettable | ✅ |
| `15 01` | present | `04 01 05` function exists, not gettable | ✅ |

The two present ones are the load-bearing half: a block that does not exist cannot
refuse at the *function* level. So the QC35 really does carry `10` VPA and `15`
AUGMENTED_REALITY — Bose AR was a QC35 II feature, which is an outside agreement
this file did not arrange.

⚠ **And `07` CONTROL is absent, so `07 02` CHIRP does not exist here.** It is on the
QC45's list of rows worth trying; on this device there is nothing to try.
⚠ **`0d` DATA_COLLECTION is absent too** — the telemetry block nothing should read
is not present to read.

### ⚠ `00 04` GET_ALL_FUNCTIONS is NOT the cheapest first read

    → 00 04 01 00        ← 00 04 04 01 05      function exists, not gettable

It is a Set or an action on this device, not a query. `00 02` is the read that
answers "what does this unit have", and it is the one to ask first.

### The reads, and three absences worth as much as the data

| frame | answer | |
| --- | --- | --- |
| `01 02` PRODUCT_NAME | `00` + `"Pippijn Bose QC35"` | |
| `01 03` VOICE_PROMPTS | `a1 00 04 cf de` | decoded below |
| `01 04` STANDBY_TIMER | `3c` | 60, and the unit is minutes — below |
| `01 06` ANR | `01 0b` | ANC state `01`; `0b` constant, as before |
| `01 0b` SIDETONE | `01 02 0f` | ⚠ **byte-identical to the QC45's** |
| `02 02` BATTERY_LEVEL | `64` | 100% |
| `04 04` LIST_DEVICES | `01` + one BD_ADDR | count, then the phone |
| `05 01` SOURCE | `00 02 01` + that same BD_ADDR | |
| `01 08` ALERTS | **nothing at all** | 3/3, below |
| `01 15` IMU_VOLUME_CONTROL | `04 01 04` | function not supported |
| `02 03` AUX_CABLE_DETECTION | `04 01 04` | ⚠ **not supported — and this unit HAS the socket** |
| `02 05` CHARGER_DETECT | `04 01 04` | function not supported |

⚠ **`02 03` is the one to take seriously.** The QC35 has an aux socket, which is why
AUX_CABLE_DETECTION was on the list of rows worth asking for — and the device says
the function is not there. **A feature the hardware plainly has is not evidence that
the protocol exposes it**, and the reverse inference had already been written down
as a reason to ask.

⚠ **SILENCE IS A THIRD OUTCOME, and two functions have it** — see the block `01`
sweep below. `01 08` ALERTS and `01 07` BASS_CONTROL answer nothing at all: not `04`
Error, not a Status. A driver that waits for an answer hangs on them rather than
failing, and **an error-code map cannot see them** — this page locates functions by
which error they return, and these return none.

⚠ Block `02` STATUS therefore has exactly one gettable function on this device,
`02 02`. The QC45's richer `02 0d`/`02 0e` per-cell charge stats are not here either.

### ✅ Voice prompts — `01 03`, decoded from the parser, not guessed

`SettingsBmapPacketParser` (`com.bose.monet`, unobfuscated) reads the payload as:

    byte 0   bits 0–4  language, via VoicePromptLanguage.getByValue
             bit  5    voicePromptsEnabled
             bit  7    a second flag the SDK exposes only as `c()`
    bytes 1–4          big-endian int, a bitmask of SUPPORTED languages,
                       bit n = the language whose wire value is n

So `a1 00 04 cf de` is **on**, **US_ENGLISH**, and thirteen languages offered:
US English, French, Italian, German, Mexican Spanish, Brazilian Portuguese,
Mandarin, Korean, Russian, Polish, Dutch, Japanese, Swedish.

⚠ **UK English is bit 0 and is NOT set**, while US English is — as is Mexican
Spanish without European Spanish. The pairs that look like they must travel
together do not, so the language list has to be read bit by bit.

⚠ **The bit numbering comes from `BitSetUtil.b(int)`**, which shifts right one bit
at a time — LSB first. It is not the ordinal: `adjustedOrdinal()` subtracts the two
sentinel entries (`UNKNOWN`, `INVALID`) that lead the enum, and only then does the
index agree with the wire value.

### ✅ #966 — the standby timer is measured now, not inferred

The correction above was labelled "inference, not measurement" and asked for one
comparison. Two independent things now say the same:

**The unit is named in the SDK.** `StandbyTimerEvent.getMinutes()` returns an int,
and the parser fills it from `payload[0] & 0xff`. `3c` is sixty minutes.

**The two fields moved apart.** `02 02` read `46` (70) on 2026-08-15 and `64` (100)
today, the headphones having been charged in between, while `01 04` read `3c` on
both days. ⚠ **A field that moves with the battery and a field that does not are
not the same quantity** — which is the observation the original mis-labelling
lacked, and it costs nothing but a second visit on a different day.

⚠ **`01 04` carries TWO different messages**, and the parser tells them apart by
length, not by content:

    payload length ≥ 2 and payload[1] & 1  →  AutoPowerDownEvent(payload[0] != 0)
    otherwise                              →  StandbyTimerEvent(payload[0] minutes)

The QC35 answers one byte, so this is the timer. ⚠ A reader that took `payload[0]`
and stopped would report the boolean form as a **1-minute** standby timer.

✅ **And it was driven, which settles it without the vendor app.** `01 04` is
*writable*, and a battery level is not:

    → 01 04 01 00          ← 01 04 03 01 3c     60 minutes
    → 01 04 02 01 14       ← 01 04 03 01 14     set 20, SET_GET echoes the new state
    → 01 04 01 00          ← 01 04 03 01 14     and a separate Get agrees
    → 01 04 02 01 3c       ← 01 04 03 01 3c     restored
    → 01 04 01 00          ← 01 04 03 01 3c     confirmed restored

⚠ **The read-back is a second Get, not the SET_GET's own echo.** An echo is the
device repeating what it was told; only an independent read says the value stuck.

⚠ **The device was left at 60 minutes, where it was found.**

⚠ **`01 04 02 14` is not that frame** — it is operator `02` with a *length* of 0x14
and no payload, and the device answers `04 01 01` bad/missing argument. The value
goes in a payload byte after the length, and a 5-byte Bose write collapses to a
plausible-looking 4-byte one the moment the length is forgotten. Second thing to
pin `04 01 01` in this file, after `1f 06` Get with no index.

### The rest of the QC35's surface — same sitting

| frame | answer | |
| --- | --- | --- |
| `00 03` PRODUCT_ID_VARIANT | `40 20 01` | |
| `00 0b` COMPONENT_DEVICES | `04 01 04` | not supported |
| `01 09` BUTTONS | `10 04 02 07` | as the 08-15 sweep, still undecoded |
| `01 0a` MULTIPOINT | `04 01 04` | ⚠ **not supported — the QC45 HAS this** |
| `04 05` INFO | BD_ADDR + `03 02 03` + the phone's name | argument required |
| `04 06` EXTENDED_INFO | BD_ADDR + `07 07` | argument required |
| `04 0c` ROUTING | `04 01 04` | not supported |
| `05 06` NOW_PLAYING | `04 01 05` | exists, not gettable |

⚠ **`04 05`/`04 06` take the DEVICE'S BD_ADDR, not an index into `04 04`'s list.**
Both answer `04 01 01` bad/missing argument to a bare Get and to a one-byte index;
they answer to the six-byte address that `04 04` just handed back. So the paired list
is not an array you can subscript — it is a set of keys.

⚠ **`01 0a` MULTIPOINT and `04 0c` ROUTING are both absent**, and `bose-settings.md`
drives multipoint on the QC45. ⚠ **Two devices of one brand, one framing, and this
function is on one of them only** — the third demonstration of that after `01 05`/`01 06`,
and the reason a Bose "feature table" cannot be written once and applied to both.

### ✅ Blocks `10` and `15` are exactly what the SDK says they are

Both swept `01`–`0f`, Get only, 15/15 answered:

```
10 01  04 01 05   GET_ALL, not gettable      15 01  04 01 05   GET_ALL, not gettable
10 02  7f 03      SUPPORTED_VPAS             15 02  00         AR_STREAMING_STATUS, off
10 03…0f  04 01 04  not supported            15 03…0f  04 01 04  not supported
```

`VoicePersonalAssistantPackets$FUNCTIONS` and `AugmentedRealityPackets$FUNCTIONS` each
declare four entries, two of them the `UNKNOWN`/`FUNCTION_BLOCK_INFO` sentinels — so the
SDK predicts **two** real functions per block and the device has exactly two. A sweep
that stops where the errors start would have found the same thing by luck; this one ran
to `0f` in both blocks and can say the absences are absences.

**`10 02` SUPPORTED_VPAS = `7f 03`**, read with the parser rather than guessed:

    byte 0  bits 0–6  the ACTIVE assistant, via VoicePersonalAssistant.getByValue
            bit  7    a flag the event carries as its second argument
    byte 1…           a bitmask of the SUPPORTED assistants, bit n = wire value n

`VoicePersonalAssistant` has three values — `00` GOOGLE_ASSISTANT, `01` ALEXA, `7f` NONE.
So this unit **supports Google Assistant and Alexa** (`03` = bits 0 and 1) and has
**none configured** (`7f`). ⚠ The active-assistant field is seven bits wide, not eight,
and `7f` is a real enum value rather than a "no answer" sentinel — a reader masking with
`0xff` gets a number that is in no table.

### ⚠ Block `01` swept in full — and the EQ is SILENT here

`01`, functions `01`–`16`, Get only: **20 answered, 2 silent**. The two are `01 07`
BASS_CONTROL and `01 08` ALERTS. Everything from `01 0c` up is `04 01 04` function not
supported, including `01 15` IMU_VOLUME_CONTROL.

✅ **The silence is the function's, not a dead socket** — the control that matters.
`01 07` was asked three times and `01 08` four, all silent; **`01 06` ANR asked
immediately after, on the same socket, answered normally**. Without that control the
whole finding would read as "the link dropped", which is what an empty reply looks like.

⚠ **`01 07` is the QC45's equaliser**, driven and read there (`bose-settings.md`). On the
QC35 it neither answers nor refuses. So the honest QC35 row for EQ is **unknown**, not
"absent" — and that is a different answer from `01 0a` MULTIPOINT, which says `04 01 04`
outright. ⚠ **A capability table built from this device must distinguish three states,
not two:** answered, refused, and never replied.

### ✅ `1f` is not on the QC35 — and the mask does not reach that far

    → 1f 03 01 00   ← 1f 03 04 01 03      block not supported
    → 1f 00 01 00   ← 1f 00 04 01 03
    → 1f 07 01 00   ← 1f 07 04 01 03
    → 17 00 01 00   ← 17 00 04 01 03
    → 19 02 01 00   ← 19 02 04 01 03
    → 01 06 01 00   ← 01 06 03 02 01 0b   the link, still alive

So the "may not exist — older model" above is resolved: the block where the QC45 keeps
its ANC is absent here, as are `17` and `19`, which the QC45 answers. The QC35's ANC
lives at `01 06` and nowhere else.

⚠ **But only `17` was a prediction — `1f` and `19` were NOT.** `00 02` returned **three
bytes**, which describes blocks `00`–`17` and says nothing whatever about anything above
that. Bit 23 is clear, so `17` being absent is the mask's sixth confirmed call; `19` and
`1f` are simply off the end of it and their absence here is measured, not predicted.

⚠ **A short answer to `00 02` is not a claim that the high blocks are empty**, and
reading it as one would have "proved" the QC45 has no `1f` either. **The length of the
mask is itself information**, and it is the trap this file already fell into once by
sweeping to `12` and concluding the map ended.

✅ **A prediction to check when a QC45 is next powered on:** it reports block `1f`, which
no 24-bit mask can express, so its `00 02` must answer **four or more bytes**. If it
answers three, then either the mask is not what this page says it is, or the device does
not list `1f` in it — and either would matter more than the row it was asked for.

## ⚠ Bose Connect is the only oracle that could catch a scrambled mode table — 2026-08-26

`Drivers.BoseQc35` mapped `00` ANC · `01` AMBIENT · `03` OFF. Every one is wrong, and
the two that matter are inverted:

| Bose Connect | wire | this repo said |
| --- | --- | --- |
| **Off** | `00` | ANC |
| **High** | `01` | AMBIENT |
| **Low** | `03` | OFF |

So the app's *Off* chip turned cancelling **down**, and its *Noise cancelling* chip
turned it **off** — the one a person reaches for in a noisy place did the opposite,
which is a state somebody might answer by reaching for the volume.

⚠ **NOTHING INSIDE THIS REPO COULD HAVE FOUND IT.** `read` and `write` shared the one
table, so a write read back as the mode it had asked for; the card drew that mode;
`DriversTest` asserted the same three pairs. **Every check agreed with every other and
all of them were wrong together** — a closed loop is not evidence, however many members
it has.

**How it was actually found:** Bose Connect names the three states on screen. Selecting
each one there and reading `01 06` from this side gives a byte *labelled by someone
other than us*. Because the vendor app and this repo contend for the single SPP control
channel, that is one cycle per state — set in the app, force-stop it, read.

⚠ **The QC35 has no pass-through mode at all.** Its three rows are High, Low and Off;
`AMBIENT` was a mode the hardware does not have, and that should have looked wrong on
paper before anything was driven. `AncMode.ANC_LOW` now exists for the real third state,
and Low is *not* a kind of Ambient — nothing is passed through, the same cancelling is
turned down.

⚠ **A second oracle was in the room the whole time and went unused:** the headphones
announce the mode aloud when it changes. Whether they speak the level's *name* is
untested here — but "the device says something at this moment" was known and never
turned into a check.
## ✅ Bose Connect's settings screen names five more QC35 rows — 2026-08-26

Opening the vendor app's own Settings (gear, top right) lists what this unit exposes:
Name · Connections · Product Tour · Music Share · **Noise Cancellation: High** ·
**Action Button: Noise Cancellation** · **Self Voice: Medium** · **Standby Timer:
1 hour** · **Voice Prompts** · **Prompt Language: English (U.S.)**.

Every one of those is a label for a byte this repo had already read but could not name.

### ✅ #966, by the route the task actually asked for

**Standby Timer reads "1 hour"** on screen, and `01 04` answers `3c` = 60. That is the
vendor-screen comparison `bose-settings.md` asked for, and it is now the *third*
independent route to the same answer after the SDK's `getMinutes()` and the drive-and-
restore above. ⚠ Kept rather than dropped as redundant: the three agree, and a page that
records only the last one loses the fact that they were ever separate.

### ✅ Voice prompts, both halves

The screen shows prompts on and **English (U.S.)**. The decode of `01 03`'s `a1` said
bit 5 set (enabled) and low bits `01` = US_ENGLISH. ⚠ **Both halves of one byte confirmed
by one screen** — and the language mattering is what makes `01` rather than `00` the
right reading, since `00` is UK English and the two are one bit apart.

### ✅ Self Voice is `01 0b` SIDETONE — decoded and driven

`SidetoneMode` is `00` OFF · `01` HIGH · `02` MEDIUM · `03` LOW, and the parser reads
`payload[0]` as a persist flag and **`payload[1]` as the level**.

    Medium (as found)   ← 01 0b 03 03 01 02 0f
    Low                 ← 01 0b 03 03 01 03 0f
    Medium (restored)   ← 01 0b 03 03 01 02 0f

Set from the vendor app each time and read from this side. Byte 1 tracks the label; byte
2 held `0f` throughout, which is four bits for four modes. ⚠ **`0f` is NOT asserted as
the supported-mode mask**: the SDK hands `payload[1…]` to `SupportedSidetoneModes`, which
would include the level byte, so either the offset is wrong for a 3-byte payload or the
field means something else. It is constant across two levels, and that is all that is
established.

⚠ **The QC45 answered `01 0b 01 02 0f` too** — byte-identical, so its Self Voice is also
Medium with the same trailing byte.

### `01 09` Action Button — one point, not driven

The screen says **Noise Cancellation**; `01 09` answers `10 04 02 07`, and
`ActionButtonMode` is `00` NOT_CONFIGURED · `01` VPA · `02` ANR · `03` BATTERY_LEVEL ·
`04` PLAY_PAUSE. Byte 2 is `02` = ANR, which matches. ⚠ **One agreement at one value is
not a decode** — that is exactly the evidence the QC35's ANC table had before it turned
out to be wrong at every value. Drive it to a second setting before believing the offset.

### ⚠ What the app does NOT offer, and the silent functions

**There is no equaliser and no alerts screen anywhere in Bose Connect for this unit** —
and `01 07` BASS_CONTROL and `01 08` ALERTS are the two functions that answer nothing at
all. The app not exposing them and the device not answering them agree.

⚠ **Agreement is not proof.** Silence is still not a refusal, and the honest QC35 rows
for EQ and alerts remain **unknown** rather than absent. What this adds is that nothing
in the vendor app contradicts the silence — where for `01 0a` MULTIPOINT the device says
`04 01 04` outright and the app correspondingly has no such row either.

## ⚠⚠ `04 01 05` DOES NOT MEAN "NOT GETTABLE" — it means "use Start" — 2026-08-26

Captured Bose Connect cold-launching onto the QC35 (`~/.cache/volume-captures/
2026-08-26-bose-connect/`, window 09:04:18–09:04:54). Its very first moves are
`00 01` BMAP_VERSION then **`00 02` ALL_FUNCTION_BLOCKS** — the capability read this
page arrived at independently. Then it does the thing this page had no idea about:

    → 01 01 05 00                     SETTINGS/GET_ALL, operator 05 START
    ← 01 01 07 00                     Processing
    ← 01 02 03 12 00 "Pippijn Bose QC35"
    ← 01 03 03 05 a1 00 04 cf de      voice prompts
    ← 01 04 03 01 3c                  standby timer
    ← 01 06 03 02 01 0b               ANR
    ← 01 09 03 04 10 04 02 07         action button
    ← 01 0b 03 03 01 02 0f            sidetone
    ← 01 01 06 00                     Result

**`01 01` answers `04 01 05` to a Get and streams the entire block to a Start.**
Reproduced from this side on four functions:

| asked | operator 01 Get | operator 05 Start |
| --- | --- | --- |
| `01 01` SETTINGS/GET_ALL | `04 01 05` | ✅ six settings + Result |
| `00 04` GET_ALL_FUNCTIONS | `04 01 05` | ✅ firmware, MAC, serial + Result |
| `15 01` AR/GET_ALL | `04 01 05` | ✅ `15 02` = `00` + Result |
| `07 01` CONTROL/GET_ALL | `04 01 03` | `04 01 03` — block really is absent |

⚠ **So the error table at the top of this page is WRONG about `04 01 05`, and the
"`04 01 05` locates write commands without sending a write" method built on it does
not hold.** Those functions are not Sets; they are *transactions*, and Start reads
them. The taxonomy is really:

    04 01 03   block not supported
    04 01 04   function not supported
    04 01 05   ⚠ NOT a Set — a Start/Processing/Result transaction. Ask with 05.
    04 01 01   bad or missing argument

⚠⚠ **AND THIS MAKES THE `04 07` HAZARD WORSE, NOT BETTER.** `04 07`
CLEAR_DEVICE_LIST is on the `04 01 05` list. It is now known that some members of
that list are readable transactions — which is exactly the reasoning that would
talk somebody into sending `04 07 05 00` to see what it returns. **DO NOT SEND
OPERATOR 05 TO BLOCK `04`.** The list can no longer be used to classify anything as
either safe or destructive, so `04 07` keeps its old status: unpairs everything,
never send it with any operator but `01`.

⚠ **Only `01 01` was observed being Started by the vendor app** (plus `05 02`,
`05 06` and `10 01` in the same capture). `00 04` and `15 01` were **extrapolated**
by this repo and happened to be benign reads. That extrapolation is exactly what
must not be repeated on block `04`.

### ✅ The silence is answered: the device does not have those functions

`01 01` GET_ALL enumerates **six** settings: `01 02`, `01 03`, `01 04`, `01 06`,
`01 09`, `01 0b`. **`01 07` BASS_CONTROL and `01 08` ALERTS are not among them.**

That is the device's own enumeration of its own block, so the earlier "unknown, and
silence is not a refusal" can be retired: **the QC35 has no equaliser and no alerts
function.** It also matches Bose Connect exposing neither. ⚠ The silence itself is
still unexplained — a function absent from GET_ALL might reasonably answer
`04 01 04` like every other absent function, and these two answer nothing at all.
What changed is that their *absence* is now established; the *silence* is not.

### ⚠ Bose batches packets, and nothing here splits them

The app writes **eight BMAP packets in one SPP write**:

    01 01 05 00 | 02 02 01 00 | 00 07 01 00 | 00 02 01 00 |
    04 04 01 00 | 00 0b 01 00 | 00 03 01 00 | 00 03 01 00

and the device batches replies the same way — one read carried `01 03`, `01 04`,
`01 06` and `01 09` glued together. **This is #1154's shape in the Bose protocol.**
Every Bose decoder here reads a fixed offset off the front of whatever
`Transport.exchange` returned, which is right only while each request draws exactly
one frame. The JBL has `Bes.frame` for this; Bose has no equivalent. One-packet-per-
exchange has hidden it so far.

### The rest of the connect, in order

`00 05` FIRMWARE_VERSION + `00 0a` HARDWARE_REVISION (batched, and `00 0a` answers
`04 01 04` — **not supported on the QC35**, though the QC45 answers `"SOR"`), then
GET_ALL, then `02 02` battery, `00 07` serial, `04 04` paired list, `00 03` variant,
`10 01` VPA Start, `04 05` INFO keyed by the phone's BD_ADDR. Later: `15 02` AR,
`01 02` name, `04 08` PAIRING_MODE, `05 01` SOURCE, `05 02` AUDIO GET_ALL by Start
(yielding `05 03`, `05 04`, `05 05` **VOLUME `19 12`**, `05 06`), and `05 06`
NOW_PLAYING by Start → `"Not Provided"`.

⚠ **Block `09` NOTIFICATION is never touched.** The app does not subscribe to
anything — it **polls**, asking `02 05` CHARGER_DETECT and `02 02` BATTERY_LEVEL a
few seconds apart, **each sent twice**. So there is no subscription mechanism to copy
here, and the repeated-send is the app's own behaviour rather than a retry after a
failure: `02 05` answers `04 01 04` both times and it asks again anyway.

⚠ **The tshark field for the QC35 is `btspp.data`, NOT `data.data`.** `captures.md`
says `data.data` for "Sony/Bose RFCOMM"; on this capture that filter returns 5 rows
and `btspp` returns 41. A filter returning almost nothing is the wrong field, not a
quiet device — which is the trap that page already warns about, in the entry that
gave the wrong field.
