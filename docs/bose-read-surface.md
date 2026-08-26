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

⚠ **This is the 2026-08-15 SWEEP, kept as the record of what was seen then.** Several
readings on it were later corrected or explained, and the corrections are further down
rather than edited in — `01 04` is the standby timer, `01 06`'s values are Off/High/Low,
`04 04`'s leading byte is a connected bitmask, `09 02` is a subscription that is already
on. **Read the dated sections below before quoting anything here.**

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
| `04 04` LIST_DEVICES | `01` + one BD_ADDR | ⚠ **NOT a count** — see the correction below |
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

✅ **That prediction was checked on 2026-08-26 and held** — the QC45 answers **four**
bytes and bit `1f` is set. See "The QC45 answers the two predictions this page left
standing" at the end of this page. It was written as a falsifiable call in advance: three
bytes would have meant either that the mask is not what this page says it is, or that the
device does not list `1f` in it, and either would have mattered more than the row it was
asked for.

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

⚠ **SUPERSEDED the same day — they are ABSENT, not unknown.** This paragraph said the EQ
and alerts rows "remain unknown rather than absent", which was right until `01 01` GET_ALL
was asked: it enumerates six settings and neither is among them. See "The silence is
answered" below. What is still open is only *why* they answer nothing at all where every
other absent function answers `04 01 04`.

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

### ⚠ Bose batches packets — `BoseFrame.frames` splits them, since 2026-08-26

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

## ✅ `04 04`'s first byte is a CONNECTED BITMASK, not a count — 2026-08-26

Written up earlier the same day as "count, then the phone", from the only reading
available: one paired device answering `01`. ⚠ **With one entry a count and a bitmask
are the same byte**, which is why that stood.

Pairing a second device settled it. The Mac was paired from the Mac itself
(`blueutil --pair`), giving the QC35 two entries:

```
one device            ← 04 04 03 07  01  <phone>
two, both connected   ← 04 04 03 0d  03  <phone> <mac>
two, mac DISCONNECTED ← 04 04 03 0d  01  <phone> <mac>      ⚠ list unchanged, byte moved
```

**A count would have read `02` for two devices.** It read `03`, and then fell to `01`
when one of the two disconnected **while both remained in the list** — so byte 0 is a
bitmask over the list's own positions saying which are *connected*, and the list itself
holds what is *paired*.

⚠ **The disconnect is the load-bearing step.** With both connected, `03` is equally
consistent with "two slots occupied"; only breaking one link while the entry stayed
distinguishes connected from occupied. Pairing alone would have left a plausible wrong
reading in place — the same shape as `01 04` being read as a battery for a week.

`04 05` INFO agrees, per device: its first status byte after the address is `01` for the
connected Mac and `00` for the disconnected one. ⚠ The phone reads `03` there rather than
`01`, so that byte is **not** a plain boolean — three states are attested (`00`, `01`,
`03`) and nothing here establishes what distinguishes the last two. `04 06`
EXTENDED_INFO likewise differs per device (phone `07 07`, Mac `02 02`) and is undecoded.

⚠ **BD_ADDRs are redacted in this file**; the addresses above are the phone's, the Mac's
and the headphones' own, and this repo is public.

### ✅ Three devices — the list is PAIRED, and its ORDER MOVES

A laptop was paired as a third device (the QC35 connects two at most, and the app says
so). Addresses redacted; `A` is the phone, `L` the laptop, `M` a disconnected Mac.

```
← 04 04 03 13  03  <A> <L> <M>        length 0x13 = 1 + 3×6
   byte 0 = 03 = bits 0,1             A and L connected; M is bit 2 and is not
```

✅ **The list holds PAIRED devices, not connected ones** — three entries while only two
can be connected. That is the bitmask reading confirmed a second way, by a route that
does not depend on the first.

⚠ **THE ORDER IS NOT STABLE, and this was predicted wrongly.** The prediction on record
was byte 0 = `05`, assuming the list appends and the newly-paired laptop would land in
slot 3 behind the disconnected Mac. It landed in slot **2**, ahead of it — the order
follows connection, not pairing time. **Anything that keys a row by its index will
relabel devices as connections change.** Read the address out of each reply; never cache
a position across reads.

`04 05` INFO for all three, which is what makes the list legible:

```
<A>  03 02 03  "Pixel 9"          connected
<L>  01 01 03  "pippijn-mac"      connected
<M>  00 01 01  "…Mac mini"        NOT connected
```

⚠ **Byte 0 is non-zero exactly when the device is connected** — `00` for the
disconnected one, across four readings including the same Mac before and after its link
dropped. ⚠ **Its VALUE among connected devices is not established**: the phone reads `03`
and the laptop `01`. A profile count is the obvious guess and it is a guess. Byte 2 is
not connection state either — the Mac read `01` there both while connected and while
not, and the laptop reads `03`.

⚠ `04 08` PAIRING_MODE's second byte has now been seen as `00`, `01` and `03` with no
account of what moves it. Carried, never interpreted.

### ⚠ `04 03` REMOVE_DEVICE, captured — and still refused

Bose Connect's Connections screen has an EDIT mode with a delete control per row. Removing
one (a Mac, disconnected, never the phone) sends:

```
→ 04 03 05 06 <bd_addr>      operator 05 START, payload = the ADDRESS to forget
← 04 03 06 06 <bd_addr>      Result, echoing it
```

⚠ **The payload is an address, and the address most easily to hand is the phone's own** —
`04 04` LIST_DEVICES returns it and `04 05` INFO already takes it as a parameter. That is
why `Hazards.bose()` refuses `04 03` outright, and **knowing the frame does not change
that**: the guard is about which address a caller reaches for, not about ignorance of the
encoding.

**What a safe implementation would need**, if this is ever wanted in-app: read `04 09`
first — the *connected* device — and refuse any removal naming it, which means relaxing
the blanket refusal to a contextual one. Nothing is blocked without it; the vendor app
does removals perfectly well.

⚠ **The frame was nearly missed.** The window looked empty: `btspp` returned nothing
across 11:24–11:26 while the removal plainly happened. The app's link had been open since
before the log rotated, so tshark never saw the channel set up and left the payload in
`data.data`. See the field note in `captures.md` — asking one field and believing the
silence is how a capture "proves" the app sent nothing.

### ✅ "Disconnect & forget" is ONE command, and `04 02` is the device talking back

Bose Connect's EDIT mode offers a delete control on *connected* rows too, and its dialog
says **"DISCONNECT & FORGET"** — there is no disconnect-only anywhere in the app for this
device. Captured 2026-08-26 (addresses redacted):

```
→ 04 03 05 06 <L>          the app sends ONLY this
← 04 02 07 07 21 <L>       device: DISCONNECT, operator 07 Processing
← 05 01 03 09 00 02 01 …   device: SOURCE, now empty
← 15 02 03 01 00           device: AR streaming status
← 04 02 06 06 <L>          device: DISCONNECT, operator 06 Result
← 04 03 06 06 <L>          device: REMOVE_DEVICE, Result
```

⚠ **`04 02` DISCONNECT IS STILL UNWATCHED AS A COMMAND.** Every `04 02` frame here is
inbound — the headphones narrating what removing a connected device made them do. Reading
this exchange as "so `04 02` disconnects" would be inferring a command's shape from a
notification, which is the same move that produced two wrong guesses on this device this
week. **Removing a connected device disconnects it; that is the only route attested.**

⚠ **AND THE DEVICE PUSHES UNSOLICITED FRAMES.** `05 01` SOURCE and `15 02` AR arrived
without being asked, mid-exchange, with **no block `09` NOTIFICATION subscription anywhere
in any capture**. This qualifies the earlier note that "the app polls and never
subscribes": the *app* never subscribes, and the device volunteers regardless. So a reader
must tolerate frames it did not request, interleaved with the reply it is waiting for —
one more reason [[BoseFrame.frames]] exists, and a reason to match on block/function
rather than taking the first frame in the buffer.

## ⚠ Block `09` NOTIFICATION — already ON. The work is LISTENING, not subscribing.

Written up earlier the same day as "the app never subscribes, so there is no subscription
mechanism to copy". True and misleading: **the device subscribes itself.**

```
00 02  ALL_FUNCTION_BLOCKS  21 03 3f → 00 01 02 03 04 05 08 09 10 15
09 02  NOTIFY BY_FBLOCK     21 00 3e → 01 02 03 04 05       10 15
```

Same encoding, and the subscription covers **every block that carries settings** — missing
only `00` PRODUCT_INFO (static), `08` DEBUG and `09` itself. Nothing was ever sent to turn
this on; it is the default. That is the account of the unsolicited `05 01` SOURCE and
`15 02` AR frames that arrived mid-removal.

`NotificationPackets` in `com.bose.monet` builds `09 02` as `<mode> <block bitmask>` with
`NotificationBitmask` = `00` OVERWRITE · `01` ENABLE · `02` DISABLE — so changing it is
possible and **there is nothing to change**. `09 03` BY_FUNCTION answers `04 01 01`
(wants an argument) and `09 04` PERIODIC answers `04 01 04`, not supported.

⚠ **So a live-updating card is not a subscription problem, it is a TRANSPORT problem.**
[[Transport.exchange]] is request/response: it writes, reads a window, returns. A frame
the device volunteers between exchanges has nowhere to go, and one volunteered *during* a
window arrives glued to the reply — which is survivable only because every Bose decoder
now splits the buffer and **matches on block and function** rather than taking the first
frame. A card that updated itself would need a reader that runs without a request, which
this transport has no shape for.

⚠ **This is the second time today a "the app does not do X" observation nearly became "X
is not available".** The first was `04 01 05` reading as "not gettable" when it meant "ask
with Start". The vendor app's behaviour bounds what is *attested*, never what the device
*does*.

### ✅ The read stops when the protocol is finished — 8.77 s → 1.84 s on the wire

`Transport.exchange` waited **400 ms of quiet** after the last byte before returning, on a
device whose replies arrive about **1 ms** after the request (probe timestamps: sent
`.569`, answered `.570`). A QC35 card open makes seven exchanges, so the prediction was
~2.8 s of pure waiting to recover.

`BoseFrame.terminates` now ends a read when the frame that *finishes* the exchange has
arrived — a `01`/`02` ends at `03` STATUS, a `05` START at `06` RESULT, either at `04`
ERROR.

⚠ **Those first figures — 7.1 s and 6.0 s — were BOTH wrong, and the change had not even
taken effect.** They were taken through a `uiautomator` poll, which reads the *focused*
window; and the early stop was wired at one of the **two** `RfcommTransport.open` call
sites. A **renamed** device does not match `Registry.fromAdvertisement`, so it is
identified by *asking* it — down the other path, the unpatched one. Pippijn's QC35 is
called "Pippijn Bose QC35"; the card had been printing "Bose QC35 **(renamed)**" all day.

✅ **Measured on the wire instead, which cannot be confounded by the UI: 8.77 s → 1.84 s**
for the same forty frames, with the gap between a reply and the next request falling from
**418 ms to a median of 13 ms**.

⚠ **And the capture named its own remaining defect.** One exchange stayed at 418 ms —
`04 08` PAIRING_MODE, whose GET is answered with `06` RESULT rather than `03` STATUS. That
was written down in this file the same morning and not carried into the rule, so RESULT now
ends a GET too.

⚠ **What remains is REDUNDANT READS, and they are the real cost.** The wire shows the whole
cycle — `01 06`, `01 02`, `01 01` GET_ALL, `04 04`, `04 05`, `04 08`, `02 02` — running two
or three times per card open, and `01 06`/`01 02` fetched individually *before* the GET_ALL
that already returns both. Forty frames where fourteen would do. #1191.

**Formerly unaccounted:** roughly six seconds. `RfcommTransport.open` does a socket connect
and then drains for up to 700 ms to swallow the device's greeting, and neither has been
timed. ⚠ **Named rather than assumed** — the last time a number here was reasoned about
instead of measured, `34 s` turned out to be the probe's own `SEQ_WAIT` window rather than
anything the headphones did.

## ✅ The QC45 answers the two predictions this page left standing — 2026-08-26

### ✅ #1098 — `00 02` is four bytes, and `1f` is in it

    → 00 02 01 00        ← 00 02 03 04  86 cc 03 ff

    86 cc 03 ff → 00 01 02 03 04 05 06 07 08 09 12 13 16 17 19 1a 1f

The prediction above was that a device reporting `1f` cannot describe itself in 24 bits,
so the QC45's mask must answer four bytes or more. It answers four, and bit `1f` is set.
**The QC35's decoding rule is confirmed on a second device** — same big-endian payload,
LSB-first within each byte — and it reproduces every QC45 block this page had already
recorded from sweeps (`00`–`05`, `09`, `17`, `19`, `1f`) with none missing.

⚠ **Seven blocks here have never been asked anything**: `06 07 08 12 13 16 1a`. The mask
is the device's own list, so their absence from this page is a gap in what has been read,
not evidence that they are empty.

### ✅ #1185 — the QC45's mode names come FROM THE DEVICE, and they are not inverted

`1f 01 05 00` is a Start. The whole transaction arrives as one batched buffer and
`BoseFrame.frames` splits it into twelve frames:

    1f 01 07  Processing
    1f 03 03  01                                   ← the ACTIVE slot
    1f 06 03  2f  00 00 01 00 00 01 "Quiet"
    1f 06 03  2f  01 00 02 00 00 01 "Aware"
    1f 06 03  2f  02 00 0a 01 01 01 "Home"
    1f 06 03  2f  03 00 00 01 00 00 ""
    1f 01 06  Result

Slot `00` is Quiet and slot `01` is Aware — which is what `Drivers.BoseQc45.write`
already maps `ANC` and `AMBIENT` onto. **The labels are right.**

⚠ **This is a different kind of fact from the QC35's, which were wrong.** There, read and
write shared one invented table and so agreed with each other no matter what the device
did. The task existed because of exactly that. Here the *device* supplies the names, and
a reply from outside the loop is the only thing that could have settled it either way.

**Cross-checked against a state this session did not set:** `1f 03` reported the active
slot as `01` Aware, and `01 05` independently answered `0b 0a 03` — level `0a`, the value
this page already recorded for Aware. Two functions, one truth, neither of them ours.

⚠ **The level is NOT cleanly located in the slot record.** The only nonzero byte after
each name is `0a` for Aware, `09` for Home, `09` for the empty slot, and none for Quiet —
consistent with Quiet `00` … Aware `0a`, but the offset differs by one between Aware (42)
and the other two (41), and nothing here explains that. Byte `[2]` (`01` Quiet, `02`
Aware, `0a` Home, `00` empty) is undecoded too. **Recorded as observed, not as a field.**

### ⚠ "Home" is a third real mode, and the driver cannot represent it

`modes = setOf(ANC, AMBIENT)`, and `read` returns `ANC` only for level `00`. Slot `02`
Home is a user-set level — `09` today — so selecting Home on the headphones shows up in
the card as Ambient. The QC45's ANC is an eleven-point scale that this repo models as its
two ends; the QC35 needed `ANC_LOW` for the same reason.

### ✅ #966 — the QC45 has `01 04`, and it reads `00`

    → 01 04 01 00        ← 01 04 03 01 00

Supported — a Status, not `04 01 04` — and `00` is "never" under the offered set driven
on the QC35. ⚠ **Read only.** That the QC45 *honours* the timer is untested, and the
task's premise is that Bose Music never shows this row on this device at all.

### The QC45's settings enumeration — `01 01 05 00`

    01 02  00 "Pippijn Bose QC45"     01 03  e1 00 01 81 5e 00 00   voice prompts
    01 04  00            standby      01 05  0b 0a 03               ANC level
    01 07  f6 0a 00 00 …  EQ          01 09  80 09 03 00 01 40 08 … Action button
    01 0a  06     multipoint off      01 0b  01 02 0f               self voice
    01 0c  01                         01 0e  01

⚠ **Four of these are built for the QC35 and unwired on the QC45**: `01 02` rename,
`01 03` voice prompts, `01 04` standby, `01 0b` self voice. Same block, same functions,
and `01 0b`'s `01 02 0f` is byte-for-byte the QC35's Medium. But "same wire identity, so
the same meaning" is precisely the assumption this page has been wrong about before —
`01 05` and `01 06` differ by model — so each row needs its own read before it is drawn.
#1193.

### ⚠ `01 03` is SEVEN bytes on the QC45 and five on the QC35

    QC35   a1 00 04 cf de
    QC45   e1 00 01 81 5e 00 00

Byte 0 parses the same way: `e1` is bit 5 set (prompts on) and low bits `01`, US English,
which is what `a1` said on the QC35. The two trailing bytes are unexplained, and they
matter — the language bitmask is *bytes 1–4*, so if the QC45's mask were six bytes wide
this window would be reading the wrong end of it.

**Consistency, offered as consistency and not as proof:** under the four-byte reading the
two devices' language bits overlap where they should. QC35 sets `1 2 3 4 6 7 8 9 10 11 14
15 18`; QC45 sets `1 2 3 4 6 8 15 16`. Six of the QC45's eight are bits the QC35 also
sets, and the shared run `1 2 3 4 6` is identical. A misaligned window would not land on
the other device's pattern. Read as six bytes instead, every bit falls past the enum's 22
entries and the device offers no languages at all, which is not a state the firmware can
be in.

⚠ **That is still an argument from neatness, which this page has been burned by** — so it
was settled a different way the same day, off the vendor parser rather than off the
vendor's screen. See "The language mask is four bytes — from the parser, not from
neatness" at the end of this page: `get()` then `getInt()`, four bytes, confirmed.

## ✅ Three of the four QC45 settings, driven and restored — 2026-08-26

Each one driven to a value it did **not** hold, verified by an independent GET, and put
back. The frames are the QC35's, unchanged.

    01 04  →  02 01 3c   ← 03 01 3c    GET → 3c      restore 00   GET → 00    standby
    01 0b  →  02 02 01 03 ← 03 01 03 0f GET → 01 03 0f restore 02  GET → 02   self voice
    01 03  →  02 01 01   ← (nothing)   GET → 81      restore 21   GET → a1   voice prompts

**`01 04` is writable and persists on the QC45**, which is the half #966 left open. ⚠ It
proves the field takes a value and survives a separate read — **not** that the headphones
actually power down after sixty minutes. That costs an hour of idleness and nobody has
spent it.

### ⚠⚠ `01 03` answers NOTHING when it changes something — and only `01 03`

Five packets down one socket, both directions of change:

    → 01 03 02 01 21   ← 01 03 03 07 a1 …    already on: NO-OP, answered at once
    → 01 03 02 01 01   ← (nothing)           on → off:   a real change, SILENT
    → 01 03 01 00      ← 01 03 03 07 81 …    the GET shows it took
    → 01 03 02 01 21   ← (nothing)           off → on:   a real change, SILENT
    → 01 03 01 00      ← 01 03 03 07 a1 …    the GET shows it took

**Silence here means the write WORKED**, which is the opposite of how this repo has read
silence everywhere else. The first time it happened the setting had already been applied
and a read-back was the only thing that could have said so.

⚠ **Do not generalise it to "QC45 writes are silent".** In the same sitting `01 04` and
`01 0b` each changed state *and* answered a Status normally. It is this one function —
plausibly because toggling prompts makes the device reload prompt audio and the reply
falls outside the read window.

⚠ **This breaks the early-stop for `01 03`.** `BoseFrame.terminates` treats a SET_GET as
finished on Status/Result/Error, so a real toggle has no terminator and falls through to
the timeout — an eight-second stall per switch press. Whatever wires this (#1193) has to
take its confirmation from a follow-up GET rather than from the write's own reply.

### ✅ The language mask is four bytes — from the parser, not from neatness

The earlier note here argued the window from how tidily the two devices' bits overlap and
said plainly that this was an argument from neatness. It is now read off the code:
`SettingsBmapPacketParser` does `ByteBuffer.wrap(payload)`, then **`get()`** for byte 0
and **`getInt()`** for the supported mask. Four bytes, big-endian, immediately after byte
0 — so the QC45's two trailing bytes are simply past what the vendor reads.

    QC35  a1 00 04 cf de           13 languages
    QC45  e1 00 01 81 5e 00 00      8: US English, French, Italian, German,
                                       Mexican Spanish, Mandarin, Japanese, Cantonese

The same method settles the flags: the parser builds `VoicePromptEvent(bit5, bit7, …)` —
it tests **bit 5 and bit 7 and nothing else**.

### ⚠ Bit 6 was set on the QC45, and this session cleared it for good

The QC45 read `e1` — bits 7, 6, 5 — before anything was written to it. After the first
real toggle it reads `a1`, and it has stayed there: three reads over eighty seconds, and
a deliberate `01 03 02 01 61` which the device answered `a1`, refusing the bit.

⚠ **Neither of the writes that cleared it touched bit 6** — `BoseWrites.voicePrompts`
sends `0x20 | language` and nothing else. So this is a device-owned flag that a toggle
resets, is not settable from here, and Bose Connect never reads.

✅ **A falsifiable guess, worth one power cycle:** bit 6 means *not modified since
power-on*. It fits everything seen — set on a QC45 that had not been written to, cleared
by the first write, never returning, and absent from the QC35, which had been written to
all day before its `a1` was recorded. **Power the QC45 off and on and read `01 03`. If it
answers `e1`, that is the meaning; if it answers `a1`, the guess is dead** and the flag is
something a toggle destroys permanently, which would matter more.

## ✅ The QC45 slot record, decoded by watching Bose Music edit one — 2026-08-26

Pippijn created a mode in the vendor app while this session was reading the table, which
gave a before and an after of one controlled change. **That is the diff that decoded the
record**, and it did it in a way no amount of staring at a single reading could.

    slot 3 before   03 00 00 01 00 00  ""         [41]=09
    slot 3 after    03 00 07 01 01 01  "Commute"  [41]=09  [42]=07
    1f 08 before    04 07              1f 08 after  04 0f

So `1f 08` is `<capacity> <bitmask of occupied slots>` — four slots, and filling the
fourth turned `0111` into `1111`. `1f 03` is the active slot; `1f 01 05 00` dumps them all.

### ⚠ TWO fields looked like the level, and the obvious one is wrong

`[2]` and `[42]` both read `07` on the new mode. **That agreement is a coincidence**, and
it is the same trap this page records for the slot order — "the one captured example had
`01` in both bytes, hiding the order". Selecting each slot in turn and reading `01 05`
separates them 4/4:

    slot  name      [2]   [42]   01 05 reads
    00    Quiet     01    00     00      ← [2] would predict 01
    01    Aware     02    0a     0a      ← [2] would predict 02
    02    Home      0a    00     00      ← [2] would predict 0a
    03    Commute   07    07     07        both agree, by luck

✅ **`[42]` is the level.** `[2]` is wrong on three of the four and is **undecoded** —
`01` Quiet, `02` Aware, `0a` Home, `07` Commute. It is not a level and this page does not
guess what it is.

⚠ **The discriminating read was Home**: `[2]`=`0a` against `[42]`=`00`, the widest
disagreement available. Testing only Commute — the mode that had just changed and was
therefore the tempting one to look at — would have confirmed both fields and settled
nothing.

### The rest of the record, as far as it goes

    [0]      slot index
    [1]      always 00
    [2]      ⚠ undecoded, and NOT the level
    [3]      1 on the two user slots, 0 on Quiet and Aware — set even while slot 3 was EMPTY
    [4] [5]  0 0 on the empty slot, 1 1 once it held a mode
    [6…]     name, NUL-padded
    [41]     09 on both user slots, including while slot 3 was empty — so a property of the
             SLOT, not of the mode in it. Undecoded.
    [42]     the ANC level, 00 quiet … 0a aware

### What reimplementing custom modes still needs

Reading and selecting are done: `1f 01 05 00` lists, `1f 03 05 02 <slot> 01` selects, and
all four were driven this session. **The write that CREATES or EDITS a mode is not
attested** — the app sent it, and only the result was seen. `1f 06` answers a Get with an
index, so a Set there is the obvious shape, but the shape of a 47-byte record write is a
guess and this page's own rule is one bounded guess with a read-back, then capture. All
four slots are occupied, so there is no free slot to experiment in.

### ⚠ Bose Music's SDK names a mode-preset function the QC45 does not have

`com.bose.madrid` has `CncOutOfModesActivity` — "Cnc" is Controllable Noise Cancellation,
and an *out of modes* screen matches the capacity of four that `1f 08` reports. Following
that thread gives the write, apparently complete, out of the app's own code:

    SettingsCncPresetsSetGetPacket(int selectedIndex, int[] presetValues)
      block    BmapFunctionBlock.Settings   = 01
      function BmapFunction.SettingsCncPresets = 0f
      operator SetGet                       = 02
      payload  <selectedIndex> <level…>

The enum's constructor is `(name, ordinal, block, byte)`, and the byte is anchored by
five functions this repo has already measured — `SettingsStandbyTimer` `04`,
`SettingsCnc` `05`, `SettingsAnr` `06`, `SettingsButtons` `09`, `SettingsMultipoint` `0a`.
So the reading of `0f` is not in doubt.

**The prediction was written down before it was tried**: `01 0f 01 00` should answer the
selected index `03` and the four levels `00 0a 00 07`.

    → 01 0f 01 00        ← 01 0f 04 01 04      function not supported

⚠⚠ **The device does not have it.** The SDK is Bose Music's, and this page already warns
that "the QC45 runs a newer firmware whose `1f` block this SDK has never heard of" — the
converse holds too, and it is the sharper half: **a function present in the vendor app is
not thereby present in the vendor's device.** `01 0f` is how some other Bose product keeps
its CNC presets. On this one they live in block `1f`, and `01 0e` CncPersistence is the
only neighbour of it that answers (`01`).

The decompile therefore did not shortcut the capture. It did buy the vocabulary — "preset
values plus a selected index" is the shape to expect in whatever `1f` write does this.

## ✅✅ The mode-edit write, captured and replayed — 2026-08-26

The SDK route failed (above), so this came from a snoop capture of Bose Music, decoded
with `scripts/btsnoop.py`. **The edit had already happened** — Pippijn created "Commute"
at 14:01 while this session was reading the table — so the capture cost nobody anything.

    → 1f 06 02 27  <slot> 00 <nameId>  <name, 32 bytes NUL-padded>  <level> 00 00 00
    ← 1f 06 03 2f  the whole 47-byte record back

**The level is byte `[35]` of the write.** Not argued — *watched*. One slider drag sends
one write per position, and only that byte moves:

    14:01:12  slot=3 [2]=07 'Commute'  [35]=05
    14:01:23  slot=3 [2]=07 'Commute'  [35]=00
    14:01:27  slot=3 [2]=07 'Commute'  [35]=07
    14:01:30  slot=3 [2]=07 'Commute'  [35]=0a
    14:01:33  slot=3 [2]=07 'Commute'  [35]=06
    14:01:34  slot=3 [2]=07 'Commute'  [35]=07   ← where he left it

⚠ **The level sits at a DIFFERENT offset in the write than in the read** — `[35]` going
out, `[42]` coming back. The two records are not the same struct, and treating the reply
as an echo of the request would put the level five bytes wrong.

### ✅ Replayed from this repo's own socket

Reconstructed rather than replayed byte-for-byte, so the fields are understood and not
just copied — prediction written first, then sent:

    → 1f 06 02 27 03 00 07 "Commute"… 03 00 00 00
    ← 1f 06 03 2f 03 00 07 01 01 01 "Commute"… 09 03 00 00 00 00
    → 01 05 01 00        ← 01 05 03 03 0b 03 03      level 3, as predicted

Then set back to `07` and confirmed. **This is the whole custom-mode feature**: `1f 01
05 00` lists, `1f 06 02` edits, `1f 03 05 02 <slot> 01` selects, `1f 08` says how many
slots there are and which are used.

⚠ **`1f 08` is WRITTEN, not only read.** Creating the fourth mode sent `1f 08 02 02 04 0f`
— the app updates the occupancy mask itself. Nothing here has tried it, and a wrong mask
is how a mode becomes invisible without being deleted.

### ⚠ `1f 06`'s write answers with the full record — unlike `01 03`

The reply is a Status carrying the whole 47-byte record, so this write is self-verifying
and needs no follow-up Get. That is the opposite of `01 03` voice prompts on the same
device, which answers *nothing* when it changes something. **Two writes, one session, one
device, opposite conventions** — so "how this vendor confirms a write" is not a fact about
the vendor, and each function has to be established on its own.

⚠ **A slider drag is one write per position** — eight for one adjustment. Anything built
on this should send on release, not on change, or the channel carries a burst per gesture.

### ⚠ The HARDWARE BUTTON cycles the modes, so the selection moves on its own

Pippijn pressed it a few times on 2026-08-26 and it rotated through the slots, landing
back on Commute — confirmed here immediately afterwards: `1f 03` `03`, `01 05` `0b 07 03`.

**So the active slot is not this app's to cache.** Nothing was sent, the app was not
open, and the selection changed anyway. A card that reads `1f 03` once and remembers it
will show the wrong mode the moment the wearer touches the headphones — which is the
normal way to use them, not an edge case. ⚠ Block `09` NOTIFICATION is already subscribed
by default on this device (see above), so the honest fix is to listen rather than to poll.

### Still undecoded, and not guessed at

`[2]` — `01` Quiet, `02` Aware, `0a` Home, `07` Commute — is **constant across every edit
of a given mode**, so it belongs to the mode's identity rather than its level. Bose Music
offers a list of names when a mode is made, which makes a name-or-icon id the obvious
reading; obvious is not measured, and it stays open here.
