# Sony WH-1000XM4 — EQ, auto-off, multipoint

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

## Equalizer — `0x58` set, `0x59` state, `0x5b` capability

    → 58 01 <preset> 00                 set preset
    ← 59 01 <preset> 06 <6 bytes>       resulting state: preset + 6 levels
    ← 5b 01 06 10 00 01 01 01 90 01 03 e8 01 09 c4 01 18 9c 01 3d 2e 80
                                         band table (see below)

Observed presets: `a1`, `a0` (the two Customs), `17`, `16`.

**Six levels, not five.** `06` is the count, then one byte per band. The first is
CLEAR BASS and the remaining five are the graphic bands. ⚠ **Levels are offset by
10**: `0a` is 0 dB, so the range −10…+10 maps to `00`…`14`. A flat preset reads
`0a 0a 0a 0a 0a 0a`; one measured preset read `00 0e 0d 0b 0c 00`.

The `5b` capability frame carries the band centre frequencies as 16-bit values,
and they match the app's own axis labels exactly — `0190`=400, `03e8`=1000,
`09c4`=2500, `189c`=6300, `3d2e`=15662 (shown as 16k):

    01 90 → 400 Hz    03 e8 → 1 kHz    09 c4 → 2.5 kHz
    18 9c → 6.3 kHz   3d 2e → 16 kHz

## Automatic power off — `0xf8` set, `0xf9` state

    → f8 04 01 11 00      "Do not turn off"
    ← f9 04 01 11 00
    → f8 04 01 10 00      "Off when headphones are removed"
    ← f9 04 01 10 00

One byte, `10` vs `11`. ⚠ Only these two values were exercised; the XM4's menu
offered no timed options, so a timer encoding — if one exists — is unmeasured.

## Multipoint — `0xd8` set, `0x98`/`0x99` state

    → d8 d2 01 01        connect to 2 devices: on
    ← 99 01 06 01
    → d9 d2 01 00        off
    ← 98 01 06 00

⚠ The set and state opcodes do NOT pair the way the others do (`d8`/`d9` going
out, `98`/`99` coming back), so do not assume "reply = command + 1" here. That
rule holds for the BES `aa` protocol, not for this.

## Not captured

**Button assignment** (`[CUSTOM]` button → Digital assistant and back) was
performed at 11:07:56 and 11:08:23, and is **absent**: the capture ends at
11:06:44.

⚠ **The snoop log flushes lazily — the last ~2 minutes are still in memory when a
bugreport copies it.** The file's own mtime is later than its last frame, so it
looks complete. Wait a few minutes after the actions before pulling, and always
check the last frame's timestamp against what you did rather than trusting the
file size or mtime.
