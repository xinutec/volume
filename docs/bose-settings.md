# Bose QC45 — EQ, multipoint, Action button

Decoded from the 2026-08-16 snoop capture of Bose Music (`docs/captures.md` has the
action log), by pairing each Set with the Status it drew. Encoded in
`BoseSettings.kt`, whose tests replay these frames.

Framing is the usual `<block> <fn> <operator> <len> <payload>`. Operators: `01` Get,
`02` Set, `03` Status, `04` Error, `05` Start.

⚠ **Direction below is from `hci_h4.direction`**, not inferred: `0x00` sent,
`0x01` received.

⚠ **A plain `02` Set was enough for all three of these.** Only the ANC mode table
(`1f 03`) needs operator `05` Start. "Bose edits are transactional" was written from
that one function and is not true of the protocol — believing it would have added a
Start packet these three would then have answered wrongly.

## Equalizer — `01 07`

    → 01 07 02 02 <level> <band>          one band per frame; ⚠ LEVEL FIRST
    ← 01 07 03 0c <f6 0a lvl band> ×3     the full state, every time

Bands `00` Bass, `01` Mid, `02` Treble. Levels are **signed** — `f6` is −10 — which
is unlike Sony, where the byte is unsigned and offset by 10. Each group is
`<min> <max> <level> <band>`; the band is the group's own fourth byte, so the groups
are self-describing and must not be read positionally.

⚠ **`f6`/`0a` as −10/+10 limits is unproven.** They lead every group in every frame,
and that is the whole evidence: only preset buttons were pressed, so no level outside
0…+8 was ever exercised. Drag a slider to each end to settle it.

⚠ **There is no preset id on the wire.** Bose Music's four preset buttons are the app
writing three band values, so the presets belong to the app — the opposite of Sony,
where the preset is opaque and the levels follow it. What the vendor app sent:

| button | bass | mid | treble |
|---|---|---|---|
| Bass Boost | +8 | 0 | 0 |
| Treble Boost | 0 | 0 | +6 |
| Reset | 0 | 0 | 0 |

⚠ Treble Boost is +6 where Bass Boost is +8 — not a transcription slip, and a reason
not to assume the other two buttons (Bass/Treble Reducer) are the negatives of these.

The app writes all three bands on every press, in the order treble, mid, bass.

**Cross-checked**: the 2026-08-15 read sweep recorded `01 07` answering
`f60a0000/0001/0002` with the EQ flat (`docs/bose-read-surface.md`) — this exact
layout at rest, written down before anyone knew what it meant.

## Multipoint — `01 0a`

    → 01 0a 01 00        get
    → 01 0a 02 01 01     on          ← 01 0a 03 01 07
    → 01 0a 02 01 00     off         ← 01 0a 03 01 06

⚠ **The status byte is not the byte written.** On reads `07`, off reads `06`: enabled
is bit 0, and bits 1–2 were set throughout. A driver comparing the status to the
value it sent would report every write as failed.

⚠ The 2026-08-15 sweep did not list `01 0a` among block `01`'s readable functions
though it plainly answers a Get. Either the sweep's function range stopped short or
the device answered differently then — do not treat that list as complete.

## Action button shortcut — `01 09`

    → 01 09 02 03 80 09 <action>
    ← 01 09 03 0b 80 09 <action> 00 01 40 08 00 00 00 80

`03` Hear Battery Level, `10` Spotify. ⚠ **Only those two were driven**; the QC45's
menu offers more, and an unexercised code decodes to unknown rather than the nearest
match.

⚠ `80 09` is unexplained and is carried verbatim. The eight-byte trailer is static —
identical for both actions here and in the 08-15 sweep — and is **not** a mask of
available actions: read in either byte order it has four bits set, and under neither
do both `03` and `10` fall on one.

## Not found

**Auto power off.** Not on the QC45's device page in Bose Music at all, so there was
nothing to drive. Absent from the app is not proof it is absent from the device —
but there is no capture to be had this way.
