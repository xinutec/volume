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
| `04 01 05` | ⚠ **function EXISTS, not gettable** — i.e. a Set or an action |
| `04 01 01` | bad/missing argument (pinned by `1f 06` Get with no index) |

`04 01 05` locates write commands **without sending a write**. On the QC45:
`01 01  02 01  03 03  03 05  03 08  03 09  03 0b  03 0c  04 07  05 02  05 06
06 01  07 01  07 05  07 0b`.

## ✅ ANC

```
QC45   1f 03 05 02 <slot> 01     slot 0=Quiet 1=Aware 2=Home 3=unnamed
QC35   01 06 02 01 <value>       00 / 01 / 03   (2nd reply byte 0b is constant)
```
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

QC35 block `1f` (may not exist — older model).

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

⚠ **`01 08` ALERTS returns no reply at all** — not `04` Error, not a Status. Asked
three times, silent three times. That is a *third* outcome beyond this page's error
taxonomy, and a driver that waits for an answer hangs on it rather than failing.
Whatever `01 08` is, it is not a read.

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
