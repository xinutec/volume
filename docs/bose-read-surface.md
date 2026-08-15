# The Bose read surface, swept

`./probe.sh sweep <mac> <spp-uuid> bose 00-12 00-0f` walks every
`[block][function] 01 00` — operator `01` is Get, so the whole sweep is read-only
and safe to run on headphones that are being worn.

Swept against the **QC45** on 2026-08-15. 304 packets, one socket, ~2 minutes.

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

## ⚠ ANC is NOT identified yet, and guessing would be wrong

Nothing above is *known* to be the noise-cancellation mode. Several candidates
have the right shape — `01 05` (`0b 00 03`), `01 0b` (`01 02 0f`), `03 06`
(`01 01`) — and picking one because it looks plausible is exactly the reasoning
that sent four packets to `9b26d8c0` and got silence.

Note the QC35-era ANC command is block `01` function `06`, and on the QC45 that
answers `04 01 04` — **function not supported**. So the QC45 moved it, and the
two models will need different command tables even though they share a framing.

**The experiment that settles it** costs one action: sweep, change the ANC mode
on the headphones, sweep again, and diff — ignoring `05 07` and `05 0d`. Whatever
else moved is the ANC state. `./probe.sh sweep … > before.txt`, toggle,
`> after.txt`, `diff`.

## Not yet swept

The **QC35** (same protocol, different table — expect `01 06` to answer there)
and the **Harman** devices (`./probe.sh sweep … harman`, which sends
`[block][cmd] 00 00`).

The sweep ends in `Broken pipe` around block `0d`: the device closes the
connection after a long run of unsupported blocks. Harmless — everything past
`09` answered "block not supported" anyway — but it means a sweep should be
range-limited rather than run to `12` blindly.
