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
01 04  3c  ← battery 60% (QC45 keeps it at 02 02)
01 06  01 0b  ← ANC       01 03  a1 00 04 cf de     01 09  10 04 02 07
02 02  46        03 01  01      03 04  ff 00000000 "0.0.0"
04 04/04 09  paired + active     05 01/05 03/05 04/05 05/05 07  as QC45
09 02  21 00 3e
```

## Not yet

QC35 block `1f` (may not exist — older model).

EQ (`01 07`), multipoint (`01 0a`) and the Action button (`01 09`) are **decoded** —
`docs/bose-settings.md`, from the 2026-08-16 capture rather than from this sweep.
⚠ Two things that leaves open about the list above: `01 0a` answers a Get but is not
among the readable functions recorded here, and auto-off was never located at all.

A long sweep ends in `Broken pipe` around block `0d`; the device closes after a
run of unsupported blocks. Range-limit rather than sweeping to `12` blindly.
