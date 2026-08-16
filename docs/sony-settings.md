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

⚠ **The trailing `00` of a SET is a level COUNT**, sitting where `59`'s `06` sits —
so `58 01 <preset> 06 <6 levels>` is what writing a custom curve must look like.
Structurally certain, **never exercised**: no band was dragged during the capture.

Observed presets: `a0`, `a1`, `a2` (Customs), `17`, `16`. The menu holds more and
nothing captured enumerates them.

**Six levels, not five.** `06` is the count, then one byte per band. The first is
CLEAR BASS and the remaining five are the graphic bands. ⚠ **Levels are offset by
10**: `0a` is 0 dB, so the range −10…+10 maps to `00`…`14`. A flat preset reads
`0a 0a 0a 0a 0a 0a`; one measured preset read `00 0e 0d 0b 0c 00`.

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

## Automatic power off — `0xf8` set, `0xf9` state

    → f8 04 01 11 00      "Do not turn off"
    ← f9 04 01 11 00
    → f8 04 01 10 00      "Off when headphones are removed"
    ← f9 04 01 10 00

One byte, `10` vs `11`. ⚠ Only these two values were exercised; the XM4's menu
offered no timed options, so a timer encoding — if one exists — is unmeasured.

## Multipoint — ⚠ two different subsystems, and it is not understood

With direction read from the capture rather than guessed:

    on      → d8 d2 01 01        ← 99 01 06 01
    off     → 98 01 06 00        ← d9 d2 01 00

⚠ **The two taps did not use the same command**, and that is a fact about the
capture, not a typo. Turning it on sent a `d0`-block command and drew a `90`-block
reply; turning it off sent a `90`-block command and drew a `d0`-block reply. Under
the SDK's blocks-of-ten that reads as two subsystems each SET_PARAM-ing while the
other NTFY_PARAM-s.

The likeliest explanation is that the second tap did not land on the same control —
the screen was driven blind by coordinate, and the layout may have shifted after the
first toggle. **Do not build a driver on this pair until it is re-captured**, with
the app's state checked between taps. Everything else in this file is symmetric and
was confirmed twice; this is the one row that is not.

## Not captured

**Button assignment** (`[CUSTOM]` button → Digital assistant and back), performed at
11:07:56 and 11:08:23. Two separate reasons, and the second is the one that matters:

⚠ **The snoop log flushes lazily.** The first bugreport's log ended at 11:06:44
though its mtime was 11:10 — the last minutes were still in memory. Wait a few
minutes before pulling, and check the LAST FRAME's timestamp against what you did;
size and mtime both lie.

⚠ **But a second pull showed the frames do not exist.** That capture spans
09:19–11:17 and has 425 frames in the 11:06–11:09 window, of which **every one is
`HCI_EVT` or `HCI_CMD` — no ACL data at all**. So nothing was sent to the
headphones: the app changed its own UI and reported success, having lost the link,
almost certainly when the multipoint toggle at 11:06:42 renegotiated the connection.

**Method, for the next attempt:** confirm the vendor app is still connected
*immediately before each action*, not just at the start. A settings app will happily
render a change it has not delivered, which is indistinguishable from one it has —
and is the same shape as this repo's oldest trap, an answer that was never an answer.
Do the connection-disturbing settings (multipoint) LAST.
