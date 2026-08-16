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

## Automatic power off — block `f0` (SYSTEM), type `04`

    → f6 04              GET_PARAM
    ← f7 04 01 10 00     RET_PARAM — read at connect, 10:58:22
    → f8 04 01 11 00     "Do not turn off"
    ← f9 04 01 11 00
    → f8 04 01 10 00     "Off when headphones are removed"
    ← f9 04 01 10 00

One byte, `10` vs `11`, and its notify **does** echo the value set — so unlike
multipoint, this one is confirmable from its own reply. ⚠ Only these two values were
exercised; the XM4's menu offered no timed options, so a timer encoding — if one
exists — is unmeasured, and an unknown value must read as "not understood".

## Multipoint — block `d0`, type `d2`

    → d6 d2              GET_PARAM
    ← d7 d2 01 <on>      RET_PARAM — read at connect, 10:58:23, value 00
    → d8 d2 01 01        SET_PARAM

⚠ **The reply to a multipoint write is about a DIFFERENT setting**, and this is the
trap in the file. Setting `d2` to `01` drew `99 01 06 01`, a `90`-block
notification; setting that `90`-block parameter back to `00` drew `d9 d2 01 00`. In
both directions the device acked what it was told and then notified *the other
thing*.

An earlier reading of this called it a mis-tap — the screen was driven blind by
coordinate, so the layout might have shifted. ⚠ **That is now ruled out.** Decoded
with the acks in view, both taps are complete, well-formed transactions: SET, ack,
one notification, our ack. Nothing was lost and nothing landed astray.

*Inference*, and labelled as one: enabling multipoint forces the connection-quality
setting off LDAC, and putting that setting back turns multipoint off — so each write
has a side effect on the other, and what the device volunteers is the side effect.
The consequence for code holds whatever the cause: **read back with `d6 d2`.**

⚠ **`d8 d2 01 00` has never been sent.** Multipoint was turned off through the
`90`-block parameter, not this one. That `00` is this field's off value is known
only because the GET and the notification both reported it.

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
