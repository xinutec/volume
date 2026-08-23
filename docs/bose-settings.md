# Bose QC45 — EQ, multipoint, Action button

Decoded from the 2026-08-16 snoop capture of Bose Music (`docs/captures.md` has the
action log), by pairing each Set with the Status it drew. Encoded in
`BoseSettings.kt`, whose tests replay these frames.

Framing is the usual `<block> <fn> <operator> <len> <payload>`. Operators, from Bose
Connect's own `BmapPacket$OPERATOR`: `00` SET · `01` GET · `02` SET_GET · `03` STATUS ·
`04` ERROR · `05` START · `06` RESULT · `07` PROCESSING.

⚠ **`02` is SET_GET, not SET** — corrected 2026-08-23 from the enum, having been written
here as "Set" since the 15th. The byte is unchanged and every Bose write in this repo
uses it successfully; what was wrong was the name, and it hid the reason these writes
answer at all. SET_GET returns the resulting state, which is why `setMultipoint` and
friends get a payload back. Plain `00` SET has never been sent from here.

⚠ **Direction below is from `hci_h4.direction`**, not inferred: `0x00` sent,
`0x01` received.

⚠ **A plain `02` SET_GET was enough for all three of these.** Only the ANC mode table
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

✅ **`f6`/`0a` ARE the −10/+10 limits — settled on hardware 2026-08-16 evening**, and
by two independent routes. The device declares them: a flat QC45 answers
`01 07 03 0c  f6 0a 00 00  f6 0a 00 01  f6 0a 00 02`, so each group's first two bytes
are that band's own min and max. And both ends were then driven — bass −10 and bass
+10, each accepted and read back, then restored to flat. Until that evening the whole
evidence was that those bytes led every group, with nothing outside 0…+8 exercised.

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

✅ **Driven on hardware 2026-08-16**, on and off, each confirmed by read-back and
restored. ⚠ **The XM4 refuses the same setting outright** (`docs/sony-settings.md`) —
so "multipoint works" is true of this device and false of that one, and neither
result transfers.

⚠ The 2026-08-15 sweep did not list `01 0a` among block `01`'s readable functions
though it plainly answers a Get. Either the sweep's function range stopped short or
the device answered differently then — do not treat that list as complete.

## Action button shortcut — `01 09`

    → 01 09 02 03 80 09 <action>
    ← 01 09 03 0b 80 09 <action> 00 01 40 08 00 00 00 80

`03` Hear Battery Level, `10` Spotify. ⚠ **Only those two were driven**; the QC45's
menu offers more, and an unexercised code decodes to unknown rather than the nearest
match.

✅ **Driven on hardware 2026-08-16** by this repo's own driver: Spotify, then back to
Hear Battery Level, each confirmed by read-back. ⚠ Worth noting beside the XM4's
[CUSTOM] button, whose write this repo cannot make stick at all (#965) — the same
kind of setting, and only one of the two vendors accepts it from us.

⚠ `80 09` is unexplained and is carried verbatim. The eight-byte trailer is static —
identical for both actions here and in the 08-15 sweep — and is **not** a mask of
available actions: read in either byte order it has four bits set, and under neither
do both `03` and `10` fall on one.

## Not found

**Auto power off.** Not on the QC45's device page in Bose Music at all, so there was
nothing to drive. Absent from the app is not proof it is absent from the device —
but there is no capture to be had this way.

## ✅ The BMAP tables, in the clear — 2026-08-23, offline, no hardware

⚠ **`com.bose.monet` is NOT obfuscated.** Bose Connect ships `io.intrepid.bose_bmap`
with every class and enum named — `BmapPacket$FUNCTION_BLOCK`, `SettingsPackets`,
`StatusPackets` — so the block/function map this page and `bose-read-surface.md` built
by sweeping is simply *written down* in the APK. ⚠ Bose **Music** (`com.bose.bosemusic`,
the QC45's app) is the opposite: its protocol layer is obfuscated to `BX`, `Og0`, `cC5`,
and the JBL trick does not transfer there. So the older app is the better reference for
both devices, which is not the order anyone would guess.

`scripts/smali_enum.py` reads them. Function blocks:

```
00 PRODUCT_INFO   01 SETTINGS   02 STATUS   03 FIRMWARE_UPDATE   04 DEVICE_MANAGEMENT
05 AUDIO_MANAGEMENT  06 CALL_MANAGEMENT  07 CONTROL  08 DEBUG  09 NOTIFICATION
0c HEARING_ASSISTANCE  0d DATA_COLLECTION  0e HEART_RATE  10 VPA  15 AUGMENTED_REALITY
```
⚠ **`1f` is not in that list**, and the QC45 keeps its ANC there. Bose Connect's SDK
predates it — which is exactly why `bose-read-surface.md` had to find `1f` by sweeping
past where the map appeared to end. **An SDK's silence about a block is not the
device's.**

### The functions, per block

```
00 PRODUCT_INFO   01 BMAP_VERSION   02 ALL_FUNCTION_BLOCKS   03 PRODUCT_ID_VARIANT
                  04 GET_ALL_FUNCTIONS   05 FIRMWARE_VERSION   06 MAC_ADDRESS
                  07 SERIAL_NUMBER   0a HARDWARE_REVISION   0b COMPONENT_DEVICES
01 SETTINGS       01 GET_ALL   02 PRODUCT_NAME   03 VOICE_PROMPTS   04 STANDBY_TIMER
                  05 CNC   06 ANR   07 BASS_CONTROL   08 ALERTS   09 BUTTONS
                  0a MULTIPOINT   0b SIDETONE   15 IMU_VOLUME_CONTROL
02 STATUS         01 GET_ALL_FUNCTIONS   02 BATTERY_LEVEL   03 AUX_CABLE_DETECTION
                  04 MIC_LEVEL   05 CHARGER_DETECT
04 DEVICE_MGMT    01 CONNECT   02 DISCONNECT   03 REMOVE_DEVICE   04 LIST_DEVICES
                  05 INFO   06 EXTENDED_INFO   07 CLEAR_DEVICE_LIST   08 PAIRING_MODE
                  09 LOCAL_MAC_ADDRESS   0a PREPARE_P2P   0b P2P_MODE   0c ROUTING
05 AUDIO_MGMT     01 SOURCE   02 GET_ALL   03 CONTROL   04 STATUS   05 VOLUME
                  06 NOW_PLAYING
07 CONTROL        01 GET_ALL   02 CHIRP
09 NOTIFICATION   01 RESET   02 BY_FBLOCK   03 BY_FUNCTION   04 PERIODIC
0d DATA_COLLECT   01 GET_ALL   02 RECORDS   03 PAUSE   04 CLEAR   05 UID   06 ENABLE
```
✅ **Six frames this repo already drives land on their names**, which is what makes the
rest usable: `01 07` BASS_CONTROL is the equaliser above, `01 0a` MULTIPOINT, `01 09`
BUTTONS is the Action button, `01 06` ANR is the QC35's three-state ANC, `01 05` CNC is
the QC45's eleven-level one, `01 02` PRODUCT_NAME is the device name. And in
`bose-read-surface.md`: `00 01` BMAP_VERSION `"1.1.0"`, `00 05` FIRMWARE_VERSION,
`00 06` MAC_ADDRESS, `00 07` SERIAL_NUMBER, `00 0a` HARDWARE_REVISION `"SOR"`,
`02 02` BATTERY_LEVEL `5a` = 90%. Twelve independent agreements.

### ✅ #966 — auto power off is `01 04` STANDBY_TIMER, and it was in the sweep all along

That task says Bose auto power off is "not on the QC45's device page in Bose Music at
all, so there was nothing to drive". The device has it regardless, and the SDK names it.

⚠ **And this corrects a reading in `bose-read-surface.md`.** That page lists, under QC35
reads, `01 04  3c  ← battery 60% (QC45 keeps it at 02 02)`. Under this map `01 04` is
**SETTINGS/STANDBY_TIMER** and `3c` = 60 is **sixty minutes**, which is Bose Connect's
own default. The QC35's battery is at `02 02` like the QC45's — the sweep recorded
`02 02  46` = 70 there and attributed it elsewhere. Two plausible readings of one byte,
and 0x3c being a believable battery percentage is what let the wrong one stand.

⚠ **Labelled an inference, and it is one read from being a measurement**: on the QC35,
read `01 04` and `02 02` and compare both against Bose Connect's own screens. If `01 04`
tracks the standby timer and `02 02` the battery, #966 is answered for both devices and
the auto-off row can be built. Nothing here has asked yet.

### ⚠ `04 07` CLEAR_DEVICE_LIST is a destructive command in a range already swept

`bose-read-surface.md` locates write commands by their `04 01 05` error — "function
exists, not gettable" — and its QC45 list contains `04 07`. That is **CLEAR_DEVICE_LIST:
it unpairs every device the headphones know**, including the phone sending it. It is the
Bose equivalent of the JBL's `aa 95` factory reset, and it sits three functions away from
`04 04` LIST_DEVICES, which is a harmless read this repo has already done.

The sweep was safe because `Sweep.kt` hard-wires the operator to Get. ⚠ **A Bose *writer*
that took a block and function as parameters would have no such protection**, and this
is the reason it must not.

Most of that error list can now be named: `01 01` SETTINGS/GET_ALL, `02 01`
STATUS/GET_ALL_FUNCTIONS, `05 02` AUDIO/GET_ALL, `05 06` NOW_PLAYING, `07 01`
CONTROL/GET_ALL, `03 03`/`03 05` firmware transfer and validate. ⚠ `03 08`, `03 09`,
`03 0b`, `03 0c`, `07 05` and `07 0b` are **not** in Bose Connect's SDK at all — the same
gap as `1f`, so they are QC45-era functions and stay unattributed.

### The rows this names that nothing has touched

⚠ Wire identity only; none has been asked, and `01 05`/`01 06` differing by model is the
reminder that a function present on one of these two need not exist on the other.

| frame | feature | note |
| --- | --- | --- |
| `00 04` | GET_ALL_FUNCTIONS | ⚠ **the device's own capability list** — the cheapest first read, and the Bose answer to "what does this unit have" |
| `00 02` | ALL_FUNCTION_BLOCKS | which blocks exist, before asking inside one |
| `01 03` | VOICE_PROMPTS | on/off; `SettingsPackets$SupportedVoicePromptLanguages` carries the languages |
| `01 04` | STANDBY_TIMER | **#966** |
| `01 08` | ALERTS | |
| `01 0b` | SIDETONE | how much of your own voice you hear on a call |
| `02 03` | AUX_CABLE_DETECTION | QC35 has the socket |
| `02 05` | CHARGER_DETECT | |
| `04 04`/`04 05`/`04 06` | LIST_DEVICES, INFO, EXTENDED_INFO | the paired list, already read once |
| `04 08` | PAIRING_MODE | ⚠ connection-disturbing |
| `04 0c` | ROUTING | which device the audio goes to — multipoint's other half |
| `05 01`/`05 06` | SOURCE, NOW_PLAYING | what is playing, from the headphones' side |
| `07 02` | CHIRP | makes them beep — find-my; harmless but audible |
| ⚠ `01 15` | IMU_VOLUME_CONTROL | **a volume control**; the hearing rule, in a third place |
| ⚠ `0d` | DATA_COLLECTION | usage telemetry, as on the JBL and the Sony |

⚠ **All of the above is the APK's word**, and Bose Connect's at that — the QC45 runs a
newer firmware whose `1f` block this SDK has never heard of. Every row is a claim about
the app until it is met on the wire.
