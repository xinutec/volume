# The Bose read surface, swept

`./probe.sh sweep <mac> <spp-uuid> bose 00-12 00-0f` walks every
`[block][function] 01 00` — operator `01` is Get, so the whole sweep is read-only
and safe to run on headphones that are being worn.

Swept against the **QC45** on 2026-08-15. 304 packets, one socket, ~2 minutes.

⚠ **Sweep past block `12`.** The first sweep ran `00-12` and missed block `1f`,
which is where the QC45 actually keeps its ANC control — see the end of this file.
Blocks `0a`-`0d` answer "block not supported", which reads like the end of the map
and is not.

## The error codes are the map

Operator `04` in a reply is an error, and its payload distinguishes three very
different things. This is what makes the sweep useful rather than merely noisy:

| Reply | Meaning (inferred from the distribution) |
| --- | --- |
| `04 01 03` | **block** not supported — blocks `0a`–`0d` answer this uniformly |
| `04 01 04` | **function** not supported within a real block |
| `04 01 05` | ⚠ **the function EXISTS but is not gettable** |
| `04 01 01` | seen only in block `04`, likely "no such device / bad argument" |

⚠ **`04 01 05` is the interesting one.** It marks a function that is real and
refuses a Get — i.e. a **Set or an action**. Those are exactly the write commands
an app needs, and a sweep finds them without ever sending a write:

```
01 01   02 01   03 03   03 05   03 08   03 09   03 0b   03 0c
04 07   05 02   05 06   06 01   07 01   07 05   07 0b
```

## What answered, decoded

```
00 00 / 00 01  "1.1.0"                     protocol / firmware version
00 05          "1.0.6-80+f5f219b"          build string
00 06          e4 58 bc 3e 9d aa           its own BD_ADDR
00 07          "084896T50188177AE"         serial number
00 0a          "SOR" 00 00 00 00 00        product/region code
01 02          00 "Pippijn Bose QC45"      device name
01 05          0b 00 03
01 07          f60a0000 f60a0001 f60a0002  three 4-byte records
01 09          80 09 03 00 01 40 08 00 00 00 80
01 0b          01 02 0f
02 02          5a ff ff 00                 0x5a = 90 — battery %, near-certainly
02 0d / 02 0e  ASCII CSV, ~180 bytes       per-cell battery/charge statistics
03 04          ff 00 00 00 00 "0.0.0"
03 0d          00 01 07 ff
04 01          00 00 03                    device-management summary
04 04          01 fc 41 16 e0 9d 2a        paired device 1 = the phone
04 09          fc 41 16 e0 9d 2a           currently connected device
05 01          00 02 01 fc4116e09d2a
05 03          01 ff
05 04          01 ff ff
05 05          20 0b
05 07          00 28 01 2c 00 9b           ⚠ CHANGES between sweeps
05 0d          00 97 00 00 01 c2 00 28 01 2c   ⚠ CHANGES between sweeps
09 02          00 00 00 00 00
```

`04 04` and `04 09` are the **multipoint** surface — block `04` is device
management, and it hands back the paired list and the active device as raw
BD_ADDRs.

⚠ **Two functions are live telemetry.** Sweeping twice a minute apart moved
`05 07`'s last byte `9b → 96` and `05 0d`'s second byte `97 → 92`. Whatever they
are (voltage, temperature, a charge counter), **they must not be used as a fixed
fingerprint**, and they are noise in any diff — which matters for the ANC hunt
below.

## ANC — READING it, found by diff, 2026-08-15

**Block `01`, function `05`. The second payload byte is the mode.** This is where
the current level is *read*; ⚠ it is **read-only**, and writing ANC is a different
block entirely — see below. Do not read this section as the whole answer.

```
01 05 01 00  ->  01 05 03 03  0b 00 03      before
01 05 01 00  ->  01 05 03 03  0b 0a 03      after Pippijn selected Aware
```

⚠ **Three fields moved in that diff and only one of them is ANC.** `05 04`
(`02 ff ff`↔`01 ff ff`) and `08 07` (`03`↔`04`) look just as convincing — and
both had *already* differed between two sweeps taken **before** anything was
touched, so they drift on their own. `01 05` was identical across both
pre-change sweeps and moved only with the mode.

**That is the whole method: take the baseline TWICE.** A single before/after diff
here would have offered three candidates with no way to choose, and the plausible
one is not the right one. A second baseline costs a minute and converts a guess
into an elimination.

Mode values: `0a` = Aware, confirmed. The `00` seen beforehand is the other mode
(Quiet) — **inferred, not yet confirmed**, because the pre-change state was not
established before the baseline was taken.

The surrounding bytes `0b … 03` did not move and are unexplained; do not assume
they are padding.

## The QC35's ANC is elsewhere

The QC35-era ANC command is block `01` function `06`, and on the QC45 that
answers `04 01 04` — **function not supported**. The QC45 moved it to `01 05`.
So the two Bose models share a framing and need **different command tables**; the
QC35 needs its own sweep and its own before/twice-baseline/after diff.

## ⚠ Writing ANC is NOT at `01 05` — it is block `1f`

`01 05` is a **read-only mirror** of the current level. Three writes to it were
tried and all three were refused with `01 05 04 01 05` (operator not supported):

```
01 05 00 01 0a   →  01 05 04 01 05      nothing changed
01 05 00 01 05   →  01 05 04 01 05
01 05 00 01 00   →  01 05 04 01 05
```

The real control is **block `1f`**, which the first sweep never reached because
it ran `00-12`. It was found by capturing the official app while Pippijn changed
the mode, which is the method that should have been used before guessing an
operator:

```
1f 06 02 27  02 00 0a "Home"  …zero padding…  05 00 00 00
1f 06 02 28  00 00 01 "Quiet" …zero padding…  01
```

Operator is **`02`**, not `00`. And the QC45 does not have a two-way ANC switch
at all — it has **named modes**, each carrying a level. Reading them back:

```
1f 07 01 00      ->  00 01 02 03            the mode slots
1f 06 01 01 01   ->  01 00 02 00 00 01 "Aware"    … 0a …
1f 06 01 01 02   ->  02 00 0a 01 01 01 "Home"     … 09 …
1f 06 01 01 03   ->  03 00 00 01 00 00 (unnamed)  … 09 …
```

`1f 06` with a Get and **no** argument answers `04 01 01` — which pins that error
code as *bad or missing argument*, distinct from the block/function/operator
codes above.

⚠ **The level byte's position is not yet unambiguous.** The Set payload (length
`0x27`) and the Get reply (length `0x2f`) do not line up field-for-field, and the
app's captured Sets vary a byte at the *end* (`00`/`02`/`05`/`08`/`09`) while the
Get replies vary one near the *front*. Both are plausibly the 0–10 level. Do not
write a mode until one differential settles it: change a mode's level in the app,
re-read `1f 06 01 01 <idx>`, and see which byte moved.

Rest of the surface: `1f 00` is a version (`1.0.0`), `1f 02` → `02 02 00 00 00 09`,
`1f 08` → `04 07`, `1f 05` → `01`. `1f 09`–`1f 0f` are function-not-supported.

**Hearing safety note for whoever writes this first:** ANC mode is not volume, and
switching modes cannot raise a level — so this write is safe in a way a volume
write would not be. That is the reason to do ANC first and volume last, if ever.

## Writing needs ONE socket — the tool could not do it

⚠ **Bose writes are transactional, so `send` (one socket per packet) can never
perform one, and the failure is silent**: the device accepts the packet and
echoes the *unchanged* state back, which reads exactly like a wrong field. Three
rounds were spent re-reading field layouts before the tool was the problem.

`./probe.sh seq <mac> <uuid> <hex,hex,hex>` sends a list down one connection.

The Start packet is worth knowing on its own — **`1f 01 05 00` dumps the entire
mode table in one reply**:

```
1f 06 03 2f 00 00 01 00 00 01 "Quiet"      slot 0
1f 06 03 2f 01 00 02 00 00 01 "Aware"   …0a  slot 1
1f 06 03 2f 02 00 0a 01 01 01 "Home"    …09  slot 2
1f 06 03 2f 03 00 00 01 00 00 (unnamed) …09  slot 3
```

It also pins the operator taxonomy, which now has direct evidence rather than
inference: `05` **Start** → `07` **Processing** → `06` **Result**, seen as
`1f 01 07 00` … `1f 06 06 00` … `1f 01 06 00` in that one exchange.

## ⚠ Selecting a mode is still unsolved

`1f 03` reads the active slot (`00`, matching a headphone set to Quiet). Writing
it has not worked:

```
1f 03 02 01 01     ->  1f 03 04 01 05     operator not supported
1f 03 05 02 00 01  ->  1f 03 07 00        PROCESSING — accepted, then nothing
```

The second is a verbatim replay of a packet the official app sent, and it is
*accepted* and then has no effect: `1f 03` still reads `00` and `01 05` still
reads level `00`. So it is real and it is incomplete — a missing argument, a
follow-up packet, or a precondition.

**What is actually needed is a capture of ONE isolated, known action.** The
existing capture covers a period in which several things happened, so no packet
can be attributed to "select Aware" with confidence — and every attempt to infer
it from the surrounding traffic has produced a plausible packet that does
nothing. The `1f 06 02 …` writes in that capture are the app *pushing mode
definitions* during sync, not selecting.

## Blocks beyond 1f

`13`–`18`, `1a`–`1e` are function-not-supported or block-not-supported. **`17 0b`**
answers `00`. **`19`** is a whole telemetry block (`19 02` → `0f 71`, `19 03` →
`00 50 53 73`, `19 0c` → `50`), and `19 01` returns the same per-cell CSV as
`02 0d`. Nothing in `13`–`1e` looks like ANC control.

## Not yet swept

The **QC35** (same protocol, different table — expect `01 06` to answer there)
and the **Harman** devices (`./probe.sh sweep … harman`, which sends
`[block][cmd] 00 00`).

The sweep ends in `Broken pipe` around block `0d`: the device closes the
connection after a long run of unsupported blocks. Harmless — everything past
`09` answered "block not supported" anyway — but it means a sweep should be
range-limited rather than run to `12` blindly.
