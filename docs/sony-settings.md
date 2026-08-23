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

✅ **Driven on hardware 2026-08-16 evening.** `56 01` read back
`preset=a2, levels=[3, 0, 0, 2, 4, 6]` — byte-identical to the morning capture's
`57 01 a2 06 0d 0a 0a 0c 0e 10`, reached by this repo's own driver rather than by
replay. Presets `a1` and `a2` were then each written and confirmed by read-back, and
`a2` restored. `a1` reads flat, `[0, 0, 0, 0, 0, 0]`.

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

✅ **And that is now proven on hardware**, not just in a replay: asking the XM4 for
its band table returns `[400, 1000, 2500, 6300, 16000]`. A wrong unescape would put
15662 at the end, so the reading is what the escape rules predict.

## Automatic power off — block `f0` (SYSTEM), type `04`

    → f6 04              GET_PARAM
    ← f7 04 01 10 00     RET_PARAM — read at connect, 10:58:22
    → f8 04 01 11 00     "Do not turn off"
    ← f9 04 01 11 00
    → f8 04 01 10 00     "Off when headphones are removed"
    ← f9 04 01 10 00

One byte, `10` vs `11`, and its notify **does** echo the value set — so unlike
multipoint, this one is confirmable from its own reply. ✅ **Driven on hardware
2026-08-16 evening**, both directions, each confirmed by read-back and restored.
⚠ Only these two values were exercised; the XM4's menu offered no timed options, so a timer encoding — if one
exists — is unmeasured, and an unknown value must read as "not understood".

## Multipoint — block `d0`, type `d2`

    → d6 d2              GET_PARAM
    ← d7 d2 01 <on>      RET_PARAM — read at connect, 10:58:23, value 00
    → d8 d2 01 01        SET_PARAM

⚠ **THE XM4 REFUSES TO ENABLE MULTIPOINT.** Driven on hardware 2026-08-16 evening:

    → d8 d2 01 01        SET_PARAM, multipoint on
    ← (ack) then d9 d2 01 00     NTFY_PARAM — still OFF

and a following `d6 d2` reads `d7 d2 01 00`. The device acknowledges the frame and
reports the unchanged state. ⚠ **This is not a fault in the frame.** Sony Headphones
Connect gets the same answer: its switch flips on and immediately back, both when
driven over adb and when pressed by hand. So `d8 d2 01 01` is what the app sends and
what the app fails with.

Not the codec, though the app's screen invites that guess. It was refused with Sound
Quality Mode on **both** settings — re-tested over RFCOMM, because the first attempt
at the second mode was a tap that missed the switch and sent nothing while the screen
looked like a refusal (`docs/captures.md`). The app's own words are that LDAC "is not
possible" *during* multipoint — a consequence, not a precondition. The cause is
unknown and is not worth another hypothesis without evidence.

⚠ **A multipoint reply is NOT always about a different setting.** An earlier reading
of the morning capture said it always is — `d8 d2 01 01` there drew `99 01 06 01`, a
`90`-block notification, and a `90`-block write drew `d9 d2 01 00`. Today the same
SET drew `d9 d2 01 00`, its **own** opcode. The reply names whichever parameter the
device considers changed, so it is sometimes this one and sometimes not. Either way
the consequence for code is unchanged: **read back with `d6 d2`**, never trust the
reply, which is why [`setMultipoint`] always does a real read.

⚠ **`d8 d2 01 00` has still never been sent.** Multipoint has never been on for this
code to turn off. That `00` is this field's off value is known only because the GET
and the notification both report it.

⚠ The `90` block answers **no reads at all**: `90 01`, `92 01`, `94 01`, `96 01`,
`96 02` and `96 06` each drew a bare ack and no DATA frame. A hand-built
`98 01 06 01` — guessed from the notification's shape — also drew only an ack and
changed nothing. The block's payload shape is **not** established; do not copy that
guess out of this paragraph.

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

## Sound Quality Mode — block `e0` (AUDIO), type `01`

    → e6 01              GET_PARAM
    ← e7 01 00 00        RET_PARAM — "Prioritize Sound Quality", i.e. LDAC
    → e8 01 00 01        SET_PARAM — "Priority on Stable Connection"
    ← e9 01 00 01        NTFY_PARAM, echoing the value

✅ **Decoded and driven the same evening**, both directions, each confirmed by its own
echo and by `e6 01`, and restored. Found by accident: changing it was a step in
testing multipoint, and the frames were in the capture.

⚠ **The third byte is `00` here and `01` in every other setting on this page.**
Auto-off sends `f8 04 01 <v> 00`, multipoint `d8 d2 01 <v>`, the button
`f8 06 01 <v>`; this one sends `e8 01 00 <v>`. Whatever that byte is, it is not one
thing — carried verbatim rather than tidied into a "count".

Changing it renegotiates the codec: the link drops and comes back, and the vendor app
warns "Reconnects to the audio device" first. Treat it as connection-disturbing.

## The [CUSTOM] button — block `f0` (SYSTEM), type `06`

This is what #955 went looking for, after the morning's attempt sent nothing at all.

    → f0 06              GET_CAPABILITY
    ← f1 06 01 02 01 00 03 00 02 00 01 21 02 31 03 00 31 01 33 22 32 32 01 00 34
    → f6 06              GET_PARAM
    ← f7 06 01 00        RET_PARAM — 00 "Ambient Sound Control", 31 "Digital assistant"
    → f8 06 01 31        SET_PARAM
    ← 99 01 02 01        ⚠ a 90-block frame: the device asking for a reconnect
    → 98 01 02 01        the app agreeing, once the owner accepts its dialog
    ← f9 06 01 31        NTFY_PARAM — only now

⚠ **THE WRITE WORKS FROM THE VENDOR APP AND NOT FROM THIS REPO.** `f8 06 01 31` sent
from the probe is acked and then ignored: no `99`, no `f9`, and `f6 06` still reads
the old value. Tried across a plain session, a session that also sent
`98 01 02 01`, a session that sent both in one socket, and a session that read the
capability first. The vendor app sending the byte-identical frame gets `99 01 02 01`
back inside 400 ms.

⚠ **This is a DIFFERENT failure from multipoint**, and collapsing them would throw
away the only clue. Multipoint is refused for everyone, including Sony's own app.
The button is refused only for us — so there is something about the app's session
that this probe does not reproduce, and *that* is the thing to look for. The reads
(`f0 06`, `f2 06`, `f6 06`) all answer us fine.

⚠ Only two actions have codes. "Amazon Alexa" was in the menu and never selected. The
capability reply plainly contains more values than two, but reading `21 31 33 22 32
32 34` as *the list* would be inventing a structure for a byte string.

## Method notes that cost something

⚠ **The snoop log flushes lazily.** The morning bugreport's log ended at 11:06:44
though its mtime was 11:10 — the last minutes were still in memory. Wait a few
minutes before pulling, and check the LAST FRAME's timestamp against what you did;
size and mtime both lie.

⚠ **A settings app renders changes it has not delivered.** The morning's button
attempt produced 425 frames in its window, **every one `HCI_EVT` or `HCI_CMD`, no ACL
data at all** — the app changed its own UI having lost the link. Confirm the app is
still connected *immediately before each action*, not just at the start.

⚠ **A UI toggle that springs back cannot tell you whether anything was sent.** The
evening run wrote up a multipoint retry as "refused" when the tap had missed the
switch entirely. Take the coordinates from `uiautomator dump` bounds, and prefer
RFCOMM — the probe echoes every byte it sends, so "not asked" can never read as "asked
and refused".

## ✅ The SDK names the whole XM4 surface — 2026-08-23, offline, no hardware

`apktool d com.sony.songpal.mdr` was already on disk (`~/.cache/volume-apks/sony-smali`)
and nobody had read its enums. `scripts/smali_enum.py` does, and it turns most of the
`?` rows on this page into named commands without a capture, a device, or a write.

⚠ **"Each a plain enum whose ordinal IS its byte" — `docs/protocols.md` said that, and
it is wrong past the first eight entries.** These enums carry the protocol byte as a
*separate constructor argument*:

    invoke-direct {v0, v1, v2, v3}, …Command;-><init>(Ljava/lang/String;IB)V
                       ^name ^ordinal ^the byte

`CONNECT_*` are ordinals 0–7 and bytes `00`–`07`, so counting agrees — and then
`GET_TEST` is ordinal 8 and byte `0f`. This is the third time in this repo that an
ordinal has been taken for a wire value; the other two are the JBL's gesture tables.

### ✅ The XM4 speaks **v1/table1**, and that is measured rather than assumed

The APK ships `mdr/{v1,v2}/{table1,table2}`, with different meanings for the same
bytes. Every frame this repo has captured lands on **v1**:

| observed | v1/table1 | v2/table1 would say |
| --- | --- | --- |
| `f6 04` auto power off | `04` AUTO_POWER_OFF | `04` VOICE_ASSISTANT_SETTINGS |
| `f6 06` the [CUSTOM] button | `06` ASSIGNABLE_SETTINGS | `06` WEARING_STATUS_DETECTOR |
| `56 01` EQ | `01` PRESET_EQ | `01` EBB |
| `e6 01` sound quality | `01` CONNECTION_MODE | `01` UPSCALING |
| `66 02` ANC | `02` NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE | `02` NC_MODE_SWITCH_AND_ASM_ON_OFF |

✅ **And the clincher is the nine device-info strings**, which this page has carried
since 2026-08-16 as "`36 02..0b` device info strings" with no idea what they were. `36`
is `UPDT_GET_PARAM` and v1's `UpdateInquiredType` names every one:

```
37 02  CATEGORY_ID                   "HP002"
37 03  SERVICE_ID                    "MDRID294301"
37 04  NATION_CODE                   "CE7"
37 06  SERIAL_NUMBER                 "0000000000502474"
37 07  BLE_TX_POWER                  cb
37 08  BATTERY_POWER_THRESHOLD       14 = 20%
37 09  UPDATE_METHOD                 25
37 0a  BATTERY_POWER_THRESHOLD_FOR_INTERRUPTING_FW_UPDATE   14
37 0b  UNIQUE_ID_FOR_DEVICE_BINDING  "8BD1C6930CD0F12E"
```
v2's `UpdtInquiredType` has no `03` and no `0b` at all, so those two frames could not
have been produced by a v2 device. Nine values landing on nine names is the check that
makes the rest of the extraction trustworthy rather than hopeful.

### ✅ `FunctionType` — **the device will list its own features**, and this is the next move

`CONNECT_GET_SUPPORT_FUNCTION` is `06`, its reply `07`, and its payload is a list of
`FunctionType` bytes. The encoding is *block nibble | inquired type*, so every setting
this repo has decoded appears in it at exactly the two bytes it is driven with:

```
11 BATTERY_LEVEL       12 UPSCALING_INDICATOR   13 CODEC_INDICATOR    14 BLE_SETUP
15 LEFT_RIGHT_BATTERY_LEVEL   17 LEFT_RIGHT_CONNECTION_STATUS   18 CRADLE_BATTERY_LEVEL
21 POWER_OFF           22 CONCIERGE_DATA        23 TANDEM_KEEP_ALIVE  30 FW_UPDATE
38 PAIRING_DEVICE_MANAGEMENT_CLASSIC_BT         39 VOICE_GUIDANCE
41 VPT                 42 SOUND_POSITION
51 PRESET_EQ           52 EBB                   53 PRESET_EQ_NONCUSTOMIZABLE
61 NOISE_CANCELLING    62 NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE     63 AMBIENT_SOUND_MODE
71 AUTO_NC_ASM         81 NC_OPTIMIZER          92 VIBRATOR_ALERT_NOTIFICATION
a1 PLAYBACK_CONTROLLER b1 TRAINING_MODE         c1 ACTION_LOG_NOTIFIER
d1 GENERAL_SETTING1    d2 GENERAL_SETTING2      d3 GENERAL_SETTING3
e1 CONNECTION_MODE     e2 UPSCALING             f1 VIBRATOR           f2 POWER_SAVING_MODE
f3 CONTROL_BY_WEARING  f4 AUTO_POWER_OFF        f5 SMART_TALKING_MODE f6 ASSIGNABLE_SETTINGS
```
`62` is ANC, `51` the equaliser, `f4` auto power off, `f6` the button, `d2` multipoint —
all five already driven from this repo, at those bytes. So the table is cross-validated
five ways before it is used for anything new.

⚠ **This is the Sony answer to "what does this device actually have", and it is ONE
READ.** The JBL needed twenty-three rows read off a screen by hand (`docs/protocols.md`)
because its SDK has no such list; Sony's device declares it. Asking is safe — a Get on
an established session — and it replaces guessing about which of the rows below the XM4
supports. ⚠ **Nothing here has asked it yet**, and until it does, every row below is a
thing the *protocol* has, not a thing this *unit* has.

### ✅ The value enums, and the four questions they answer

⚠ Each of these is a claim about the vendor's APK. They are what makes a command
usable, and none is a measurement — see the caveat at the end.

**`EqPresetId` — the whole preset menu**, which this page said "the menu holds more and
nothing captured enumerates them":
```
00 OFF   01 ROCK   02 POP   03 JAZZ   04 DANCE   05 EDM   06 R_AND_B_HIP_HOP   07 ACOUSTIC
10 BRIGHT  11 EXCITED  12 MELLOW  13 RELAXED  14 VOCAL  15 TREBLE  16 BASS  17 SPEECH
a0 CUSTOM  a1..a5 USER_SETTING1..5     ff UNSPECIFIED    (08–0f, 18–1f reserved)
```
✅ All five ids ever observed land: `a0`/`a1`/`a2` are CUSTOM and two user slots, `16` is
BASS and `17` is SPEECH. **`setEqPreset(preset: Int)` can carry names now** instead of a
number the owner has to recognise.

**`AutoPowerOffElementId`** — this page says only `10`/`11` were exercised and that "a
timer encoding, if one exists, is unmeasured". It exists and it is named:
```
00 POWER_OFF_IN_5_MIN   01 …30_MIN   02 …60_MIN   03 …180_MIN
10 POWER_OFF_WHEN_REMOVED_FROM_EARS  11 POWER_OFF_DISABLE
```
✅ The two driven values are exactly the two the XM4's menu offered. ⚠ **That the other
four are in the enum is not evidence this unit takes them** — the menu did not offer
them, which is the device declining before anything is sent. One bounded write with a
read-back is what would settle it, and `06`/`07` above is the cheaper question.

**`AssignableSettingsPreset` — what the [CUSTOM] button can be.** This page refused to
read the capability reply's `21 31 33 22 32 32 34` as a list, on the grounds that doing
so would be inventing a structure. It was right to refuse, and here is the real list:
```
00 AMBIENT_SOUND_CONTROL   10 VOLUME_CONTROL   20 PLAYBACK_CONTROL
30 VOICE_RECOGNITION       31 GOOGLE_ASSISTANT 32 AMAZON_ALEXA   33 TENCENT_XIAOWEI
ff NO_FUNCTION
```
✅ `00` and `31` are the two this repo drove, and they are named exactly as the app's
screen names them.

⚠ **`10` VOLUME_CONTROL is in that list**, which makes Sony's button the second place in
this repo where a setting can bind a control to the volume — the JBL's three gesture
actions are the first. The rule is the same and it is not optional.

### ✅ The capability reply decodes exactly, and it is a NESTED list

`f1 06 01 02 01 00 03 00 02 00 01 21 02 31 03 00 31 01 33 22 32 32 01 00 34` — 23 bytes
this page called "plainly more values than two" and declined to parse. With
`AssignableSettingsKey` (`00` left, `01` right, `02` custom, `03` C), `…Action` and
`…Function` it reads straight through, and the length lands on the byte:

```
01                one assignable key
02                CUSTOM_KEY  ← the XM4's button, and the only key it has
01 00             ⚠ two bytes NOT attributed; carried, not explained
03                three presets are offered for it
  00 02  00 01  21 02          AMBIENT_SOUND_CONTROL: tap → NC_ASM_OFF,
                               long-press-then-activate → NC_OPTIMIZER
  31 03  00 31  01 33  22 32   GOOGLE_ASSISTANT: tap → GET_YOUR_NOTIFICATION,
                               double tap → STOP_GA, hold-during → TALK_TO_GA
  32 01  00 34                 AMAZON_ALEXA: tap → VOICE_INPUT_CANCEL_AA
```
✅ **1 + 1 + 2 + 1 + 6 + 8 + 4 = 23**, and every byte is a legal member of the enum its
position calls for. A wrong grouping would leave a remainder or an illegal value, and
this is the check that separates a decode from a story.

✅ **And it answers the hearing question before it was asked.** The XM4 offers exactly
three presets and VOLUME_CONTROL is not among them, so this unit's button cannot be
bound to the volume at all. ⚠ That is the DEVICE's answer, not a property of the enum —
so an editor must offer what `f0 06` returns, never what `AssignableSettingsPreset`
contains. Reading the enum as the menu is the same mistake as reading `values_Action` as
the JBL's action space, which cost two published corrections there.

### ✅ `99`/`98` are ALERT, which sharpens #965 considerably

The button write's unexplained exchange — the device sending `99 01 02 01`, the app
answering `98 01 02 01`, and only then `f9 06 01 31` — is the **ALERT block**:
`98` `ALERT_SET_PARAM`, `99` `ALERT_NTFY_PARAM`, and v1's `AlertInquiredType` `01` is
`FIXED_MESSAGE`. So the device is raising a **dialog** and the write commits when the
app answers it. That is the "Reconnects to the headphones" prompt, on the wire.

⚠ **And this repo never receives the `99` at all** — it sends the identical SET and gets
a bare ack. So the question in #965 is no longer "what does `98 01 02 01` do"; it is
**why the device raises no alert for us**. The obvious candidate is now named: the app
declares itself with `CONNECT_GET_SUPPORT_FUNCTION` (`06`) and this repo never has.
⚠ Labelled a hypothesis. The test is cheap and read-only up to the last frame: `00 00`,
then `02`, `04`, `06`, then the SET.

### The rows this names that nothing has touched

⚠ **A wire identity is not a decode.** Each of these is one Get away from a value and
one capture away from meaning; none of them has been asked.

| frame | feature | note |
| --- | --- | --- |
| `10`/`11` | COMMON_GET/RET_BATTERY_LEVEL | the XM4 card has no battery at all today |
| `18`/`19` | AUDIO_CODEC — `01` SBC `02` AAC `10` LDAC `20`/`21` aptX | which codec is live |
| `14`/`15` | UPSCALING_EFFECT — `00` OFF `01` VALID `02` INVALID | DSEE, as a status |
| `e6 02` | UPSCALING — `00` OFF `01` AUTO | **DSEE Extreme**, the setting |
| `24`/`25` | CONNECTION_STATUS | |
| `1c`/`1d` | BLUETOOTH_DEVICE_INFO | |
| `f6 03` | CONTROL_BY_WEARING — `00`/`01` | pause when removed |
| `f6 05`, `fa 05` | SMART_TALKING_MODE — **Speak-to-Chat** | sensitivity `00` AUTO `01` HIGH `02` LOW; mode-out time `00` FAST `01` MID `02` SLOW `03` NONE |
| `f6 02` | POWER_SAVING_MODE | |
| `f6 01` | VIBRATOR | |
| `70`/`71`/`74` | SENSE — `01` AUTO_NC_ASM | **Adaptive Sound Control** |
| `82`–`87` | OPT — `01` NC_OPTIMIZER; control `00` CANCEL `01` START | ⚠ plays test tones |
| `46`–`49` | VPT `01`, SOUND_POSITION `02` | |
| `66 01`, `66 03` | NC alone, ambient alone | this repo drives `66 02` |
| `d6 d1`, `d6 d3` | GENERAL_SETTING1 and 3 | `d2` is multipoint |
| `22` | COMMON_SET_POWER_OFF | ⚠ ends the session, like the JBL's `aa 97 00` |
| `c4`/`c9` | LOG — ACTION_LOG_NOTIFIER | ⚠ telemetry, see below |

✅ **`AsmId` names a byte this repo carries without understanding.** The ANC write is
`68 02 <on> 02 <nc> 01 00 <ambient>`, and this page says bytes 1, 3 and 4 "held `02 01
00` in all three states and are **not** identified". `AsmId` is `00 NORMAL` / `01 VOICE`,
which is the app's **Focus on Voice** switch — so byte 4 is very likely it, sitting at
its default. ⚠ **Likely, not measured**: nothing has moved it, and this page's own rule
is that a byte constant across every captured state is not attributed by a plausible
name. Flipping Focus on Voice in the vendor app for one capture settles it, and would
add the switch.

⚠ **`a0`–`a9` PLAY includes the volume, and `a8` is its SET.** The 110 unsolicited
`a9 01 20 12` frames in the 2026-08-16 capture are this block notifying. Whatever the
`20 12` is, `PLAY_SET_PARAM` is the one command family on this device that could raise a
level, and it is out of scope for the same reason the JBL's `56` VOLUME_CONTROL is.

⚠ **Sony's headphones keep usage telemetry too**, and the app uploads it: `c4 01 00` →
`c9 01 {"v":"M6","logs":[…]}`, already visible in the capture. The same note as the
JBL's `aa 13` — nothing here reads it, and nothing should start without a better reason
than the frame being understood.

⚠ **All of the above is the APK's word.** Every row is a claim about Sony's app until it
is met on the wire; `docs/captures.md` is why that distinction has its own paragraph.
The five cross-checks above are what make it worth acting on, not what make it true.

## ✅ THE XM4 LISTS ITS OWN FEATURES — driven 2026-08-23 16:43, and it changes the method

```
→ 06 00        CONNECT_GET_SUPPORT_FUNCTION, CommonCapabilityInquiredType FIXED_VALUE
← 07 00 16     71 62 f5 81 51 a1 e1 e2 d2 f6 d1 f4 f3 39 12 13 11 30 c1 14 22 21
```
`16` = 22 is a **count**, and exactly 22 bytes follow. Every one is a legal
`FunctionType`, so the whole frame is accounted for with no remainder:

```
71 AUTO_NC_ASM          62 NC_AND_AMBIENT_SOUND_MODE ✅driven   f5 SMART_TALKING_MODE
81 NC_OPTIMIZER         51 PRESET_EQ ✅driven                   a1 PLAYBACK_CONTROLLER ⚠volume
e1 CONNECTION_MODE ✅driven   e2 UPSCALING             d2 GENERAL_SETTING2 ⛔refused
f6 ASSIGNABLE_SETTINGS #965   d1 GENERAL_SETTING1      f4 AUTO_POWER_OFF ✅driven
f3 CONTROL_BY_WEARING   39 VOICE_GUIDANCE              12 UPSCALING_INDICATOR
13 CODEC_INDICATOR      11 BATTERY_LEVEL               30 FW_UPDATE ⚠out of scope
c1 ACTION_LOG_NOTIFIER ⚠telemetry   14 BLE_SETUP       22 CONCIERGE_DATA
21 POWER_OFF ⚠ends the session
```

✅ **This validates the offline extraction against hardware.** 22 of 22 land on names,
the declared count matches, and all five rows this repo already drives appear at exactly
the bytes they are driven with.

⚠ **The ABSENCES are worth as much as the entries, and they are the part a hand-built
list cannot give you.** Not present, therefore not on this unit: `41` VPT, `42`
SOUND_POSITION, `52` EBB, `f1` VIBRATOR, `f2` POWER_SAVING_MODE, `d3` GENERAL_SETTING3,
`61`/`63` NC-alone and ambient-alone, `92` VIBRATOR_ALERT, `b1` TRAINING_MODE, and the
earbud batteries `15`/`17`/`18`. Six of those were named in #1097 as things to go and
try. **One read retired them**, at no risk and no capture.

⚠ **This is what the JBL has no equivalent of.** That device's twenty-three rows were
counted off a screen by hand, which is why its Ambient Sound Control master switch hid
inside another row for a week (#1041). Ask the device, where the device will answer.

### ✅ Six rows read, and six confirmed against the vendor app's own screens

All reads, in one session, no writes:

| sent | reply | reads as | Sound Connect shows |
| --- | --- | --- | --- |
| `10 00` | `11 00 50 00` | battery 80%, not charging | **80%** ✅ |
| `18 00` | `19 00 10` | codec LDAC | ⚠ not displayed — see below |
| `14 00` | `15 00 02 00` | upscaling effect INVALID | (no row) |
| `e6 02` | `e7 02 00 00` | DSEE Extreme off | **Off** ✅ |
| `f6 03` | `f7 03 00 01` | pause when removed on | **On** ✅ |
| `f6 05` | `f7 05 00 00` | Speak-to-Chat off | **Off** ✅ |

And three already-known rows re-confirmed on the same screens: the equaliser reads `a2`,
which `EqPresetId` calls `USER_SETTING2` and the app calls **Custom 2**; Sound Quality
Mode is **Prioritize Sound Quality** (`ConnectionModeSettingValue 00`); the [CUSTOM]
button is **Ambient Sound Control** (`AssignableSettingsPreset 00`, the value #965 cannot
move); multipoint is **Off**.

⚠ **The codec is the one that is NOT confirmed**, and the distinction matters. The app
shows *Sound Quality Mode*, which is `e1` CONNECTION_MODE — a different field from `13`
CODEC_INDICATOR, which is what was actually negotiated. "Prioritize Sound Quality" is
**consistent** with LDAC and is not the same claim. Left as a decode.

⚠ **`15 00 02 00` reads INVALID rather than OFF** while `e6 02` says the setting is off,
and the app has no row for it. Two fields, and only one of them is the switch: a decoder
must not report "DSEE is off" from the effect status, which is about whether it is
*doing* anything right now.

⚠ **Nothing was written to reach any of this** — the vendor app was navigated by tapping
category headers only, never a control. ⚠ And the header labels are `clickable="false"`
with the tap landing on an enclosing container, which is the JBL's inert-label trap in a
second app; here the label sits *inside* the clickable row rather than beside it, so the
coordinates work. Check the geometry rather than assuming either shape.

⚠ **The app is called "Sound Connect" now**, not "Headphones Connect". The package is
unchanged (`com.sony.songpal.mdr`), so nothing in this repo breaks — but a future session
looking for the old name in the launcher will not find it.

## ✅ TWO OF THE THREE SWITCHES DRIVEN — 2026-08-23 17:20–17:31

The writers that #1097 asked for, against the XM4, each read first and put back after.

| setting | frame | result |
| --- | --- | --- |
| **DSEE Extreme** `e2` UPSCALING | `e8 02 00 <v>` | ✅ **driven both ways**, restored to Off |
| **Pause when removed** `f3` CONTROL_BY_WEARING | `f8 03 00 <v>` | ✅ **driven both ways**, restored to On |
| **Speak-to-Chat** `f5` SMART_TALKING_MODE | `f8 05 01 <v>` | ✅ **driven both ways** (18:50, worn), restored to Off |

Pause-when-removed is the clean one, and its transcript is the whole proof in six frames:

```
[2] → f6 03        ← f7 03 00 01     On, the value found
[3] → f8 03 00 00  ← f9 03 00 00     write Off
[4] → f6 03        ← f7 03 00 00     ✅ Off, by an independent read
[5] → f8 03 00 01  ← f9 03 00 01     write On again
[6] → f6 03        ← f7 03 00 01     ✅ back where it started
```

✅ **The settingType byte is settled.** Every frame above lands on a named SDK enum —
`ControlByWearingSettingType.ON_OFF` = `00`, `ControlByWearingSettingValue.ON` = `01` —
and the device answered all of them. The same reading explains two settings that were
already driven and whose third byte differed: `SonySoundQuality` sends `00`
(`ConnectionModeSettingType.SOUND_CONNECTION`) and `SonyAutoOff` sends `01`
(`AutoPowerOffParameterType.ACTIVE_AND_SELECTIME_ID`, whose table has no `00` at all).
This page used to say only that the byte "is not the same thing in both". It is the same
kind of thing in both.

### ⚠ THE DEVICE VOLUNTEERS NOTIFICATIONS, AND IT COST A WORKING WRITE

`e8 02 00 01` was reported by the driver as **unverifiable**. It had worked. What happened
is that the XM4 answered the write with its `e9` NTFY_PARAM *and then* emitted `17`
COMMON_NTFY_UPSCALING_EFFECT — a second, unrequested frame — which arrived in the window
the next read was waiting in:

```
[3] → e8 02 00 00  ← e9 02 00 00                 the write, answered
[4] → e6 02        ← 17 00 02 00   ⚠ not an answer to anything asked
```

⚠ **That `17` is not noise, it is causally related**: changing the upscaling *setting*
makes the device announce the upscaling *effect*. Which is the same two-fields-one-switch
trap already noted for `15` — and here it does not merely mislead a reader, it displaces a
reply.

⚠ **On Speak-to-Chat it is worse: the session never recovers.** An `f5` SYSTEM_NTFY_STATUS
arrives early and from then on every exchange returns the previous one's answer —

```
[5] → f6 05        ← 11 00 50 00    [4]'s battery answer
[6] → f8 05 00 00  ← f7 05 00 00    [5]'s answer
[7] → 10 00        ← f9 05 01 00    [6]'s notify
```

— and a spacer read does **not** absorb it, which was the first thing tried. `#1107` holds
the fix; it needs a receive-only `Transport`, which does not exist yet.

✅ **What is already fixed**: `exchangeFramed` now takes the frame whose command byte could
answer the request, not simply the last DATA frame in the window. That turns a wrong answer
into no answer, and does find the reply when both are present. It does not resynchronise.

### ✅ SPEAK-TO-CHAT READS AND WRITES WITH DIFFERENT TYPE TABLES — 18:50

⚠ **This page said for an hour that the XM4 refused Speak-to-Chat, and that was wrong.**
It also carried a second wrong explanation on top of the first — that the setting needed
the headphones worn. Both are struck out below rather than deleted, because the way the
mistake survived is the reusable part.

```
→ f6 05        ← f7 05 00 00     SmartTalkingModeSettingType.ON_OFF        = 00
→ f8 05 01 01  ← ack             SmartTalkingModeParameterType.MODE_ON_OFF = 01
→ f6 05        ← f7 05 00 01     ✅ ON, by an independent read in a fresh session
→ f8 05 01 00                    ✅ restored, confirmed the same way
```

**The read's type byte and the write's type byte come from different enums.** Sony's app
has two payload classes for this one feature and no other: `ve0.c` parses a RET with
`SmartTalkingModeSettingType`, and `ve0.d` builds a SET with `SmartTalkingModeParameterType`,
wrapped by `qe0.r3`, which is `Command.SYSTEM_SET_PARAM`. Every other setting on this page
uses one table in both directions.

⚠ **`f8 05 00 01` is accepted, acked, and does nothing.** No error, no refusal — the write
just does not happen. That is the whole trap: an ack is not an outcome, and this device
will ack a frame it has no intention of acting on.

⚠ **The device named the right table and it was read as noise.** The bad SET drew
`f9 05 01 00` — a `01` in a slot where `00` had been sent. That transposition was written
up here as "the notify shape is unexplained, possibly a malformed echo". It was not
malformed. It was the answer.

~~The write is sent, the device acks it, and a read afterwards still says Off, so this
joins multipoint and the [CUSTOM] button.~~ **Struck 18:50.** It joins neither. Both of
those have the vendor app as a control — for multipoint the app fails too, for the
[CUSTOM] button the app succeeds where we do not. Speak-to-Chat had **no control at all**,
and was filed next to them on the strength of looking similar.

~~It may need the headphones worn, since it is a wearing-sensor feature.~~ **Struck 18:50
— tested and false.** Worn, `f8 05 00 01` still did nothing; worn, `f8 05 01 01` worked
immediately. The wearing hypothesis was plausible, cheap to test, and wrong, and testing
it is what turned up the real cause.

⚠ **The generalisation failed, not the byte.** `SonySwitch` was built from three settings
that all used one type byte in both directions, and the fourth was assumed to match. Three
agreeing samples are not a rule when the vendor SDK has a separate class saying otherwise —
and the SDK had been extracted and was sitting on disk when the assumption was made.

## ⚠ ADAPTIVE SOUND CONTROL IS NOT A SETTING — 2026-08-23 17:56

The XM4's headline feature, and the biggest single gap in the parity table. It cannot be
implemented the way every other row here is, and the reason is in the command table.

```
→ 70 01     SENSE_GET_CAPABILITY, SenseInquiredType.AUTO_NC_ASM
← 71 01 01  ✅ supported, SenseTableType.TYPE1
```

**The SENSE block has three commands and that is all of them**: `70` GET_CAPABILITY,
`71` RET_CAPABILITY, `74` SET_STATUS. There is **no** `GET_PARAM`, no `RET_PARAM`, no
`SET_PARAM`, no `NTFY`. Compare the neighbouring blocks, which all have the full nine.

⚠ **So there is nothing to read.** Every driver in this repo confirms a write by reading
the value back, and that is not an implementation choice — it is the rule that caught the
XM4 refusing multipoint and caught Speak-to-Chat being written with the wrong byte. A
setting with no getter cannot be confirmed by this codebase's own standard.

⚠ **And there is nothing to switch off.** `SenseSettingControl` has exactly two entries,
`00 NO_USE` and `01 START`. The vendor app's whole Adaptive Sound Control class is one
method that sends `74 01 01` and nothing else — no stop, no toggle, no query. Checked by
reading every method on it, not by grepping for "stop" and finding none.

So `74 01 01` is a **trigger**: "begin sensing now". Whatever holds the on/off state, it is
not in this block, and it is not in the 22 functions the device declares either — the only
SENSE entry there is `71 AUTO_NC_ASM`, which is this.

⚠ **Do not add a toggle backed by nothing.** A switch drawn from `74 01 01` alone could
not report its own state or be turned off. That is #1041 in reverse — there a real control
was missing from the UI, here an unbacked one would be added to it.

### ⚠ CORRECTION, same evening: "the device blocks it" was NOT established

The paragraph above says what the SENSE block contains, and that part stands. What was
written next to it — that Adaptive Sound Control is therefore *blocked by the device* —
does not follow, and it was published anyway. **Sony's app plainly does ASC on this
model.** An absence in one block is evidence about that block.

What the APK shows on a second look:

- `SenseSettingControl` has **two different tables**. v1, which the XM4 speaks: `00 NO_USE`,
  `01 START`. v2: `00 START_SETTING`, `01 END_SETTING`. The app class that sends END is on
  the **v2** path, so it is not the XM4's — but it does show START/END is a bracket around
  an edit, not an on/off, which is a different reading of `74` than "trigger".
- There is a whole app-side subsystem: `AscLocationPositionSelectFragment`,
  `ActivityRecognitionUiTab`, `AscSoundSettingsEditContract`, a places model. ASC on this
  app is substantially more than one frame.
- Sony's code uses Google's `DetectedActivity` **only for logging badges**, not for ASC. So
  the activity detection is not coming from the phone's GMS APIs.

⚠ **None of that says where the on/off lives, and neither did the original claim.** The
honest state is: *not found yet*, which is not the same as *refused*. The distinction is
the one this page exists to keep — see multipoint, which **is** refused and has the vendor
app failing identically as its control. ASC has no such control, because nobody has watched
the vendor app toggle it.

The test is the method that already worked twice today: **capture Sound Connect turning
Adaptive Sound Control on and off, and read the frames.** That is what settled the [CUSTOM]
button's asymmetry and what would have settled Speak-to-Chat in one step instead of three.

## ✅ GENERAL_SETTING1 IS THE TOUCH PANEL, and the device says so itself

Found while looking for where Adaptive Sound Control keeps its state. `d1` was an
untouched row named only `GENERAL_SETTING1`.

```
→ d0 d1     ← d1 d1 02 13 "TOUCH_PANEL_SETTING" 00 01 00
→ d0 d2     ← d1 d2 02 12 "MULTIPOINT_SETTING" 1a "MULTIPOINT_SETTING_SUMMARY" 01 00
→ d6 d1     ← d7 d1 01 00      false
→ d6 d2     ← d7 d2 01 00      false
```

✅ **The shape was decoded by comparison against a known value, not by guessing.** `d2` is
multipoint, which this repo has read many times and independently knows to be **off**; its
capability and its value frame have the identical structure to `d1`'s. So:

```
<cmd> <GsInquiredType> <GsStringFormat 02 ENUM_NAME> <len> <name> <len> <summary> <GsSettingType> <value>
```

The lengths check out — `12` = 18 = `len("MULTIPOINT_SETTING")`, `13` = 19 =
`len("TOUCH_PANEL_SETTING")`, `1a` = 26 for the summary — and `01` is
`GsSettingType.BOOLEAN_TYPE` in both. `d7 d2 01 00` reading false is the check that the
byte positions are right, because that answer is already known to be true.

⚠ **These settings are self-describing, which is new here.** Every other setting in this
file is a fixed byte with a meaning learned from a capture. `d1`/`d2`/`d3` carry their own
names, so a model that reports "GENERAL_SETTING1" is throwing away a string the device
sent. ⚠ `d3` GENERAL_SETTING3 is **not** on this unit — it is absent from the 22.

⚠ **Nothing has been written to `d1`, and it should not be assumed writable.** Its
neighbour `d2` is the one setting the XM4 flatly refuses, using the identical `d8 <type>
01 <v>` frame. Sharing a frame family with a refused setting is a reason to test, not a
reason to expect success.

⚠ **The touch panel currently reads OFF.** That is the owner's setting, whatever it is for,
and nothing here changed it.

## ✅ THE THREE UNIDENTIFIED BYTES IN THE ANC FRAME ARE NAMED

`68 02 <on> 02 <nc> 01 00 <ambient>` — this page has said since the 16th that "bytes 1, 3
and 4 held `02 01 00` in all three states and are not identified". Every one of them is an
enum in the SDK:

| byte | enum | value |
| --- | --- | --- |
| `02` after `<on>` | `NcAsmSettingType` | `02` DUAL_SINGLE_OFF |
| `<nc>` | `NcDualSingleValue` | `00` OFF · `01` SINGLE · `02` DUAL |
| `01` | `AsmSettingType` | `01` LEVEL_ADJUSTMENT |
| `00` | **`AsmId`** | `00` NORMAL · `01` VOICE |
| `<on>` | `NcAsmEffect` | `00` OFF · `01` ON |

✅ **`AsmId` is Focus on Voice, and this confirms the guess this page recorded as
unmeasured.** It sits at the position the earlier note suspected — "byte 4 is very likely
it, sitting at its default" — and it is `00` NORMAL because nothing has moved it.

### ✅ FOCUS ON VOICE DRIVEN — 18:05, worn

```
→ 68 02 01 02 00 01 00 14  ← 69 02 01 02 00 01 00 14   ambient, AsmId.NORMAL
→ 66 02                    ← 67 02 01 02 00 01 00 14   ✅ off
→ 68 02 01 02 00 01 01 14  ← 69 02 01 02 00 01 01 14   ambient, AsmId.VOICE
→ 66 02                    ← 67 02 01 02 00 01 01 14   ✅ on, by an independent read
```

⚠ **IT ONLY TAKES IN AMBIENT MODE, and the tidy-up is how that was found.** The restore
sent `68 02 01 02 02 01 00 00` — the exact frame the device had reported before the test,
`AsmId.NORMAL` included — and the read-back came back `67 02 01 02 02 01 01 00`. The mode
was restored; **the AsmId byte was not**. The device had accepted the frame, applied the
part of it that was about noise cancelling, and silently kept Focus on Voice on.

Getting back required routing through ambient: set `68 02 01 02 00 01 00 14`, *then* the
ANC frame. After that the read matched the original byte for byte.

⚠ **A trusted write would have left the owner's headphones changed** and reported success
while doing it. This is the third time today an XM4 write was accepted and ignored —
`f8 05 00 01` for Speak-to-Chat, `f8 06 01 31` for the [CUSTOM] button in #965, and now
this. **On this device an ack means the frame was well-formed and nothing more.**

`Drivers.SonyXm4.setFocusOnVoice` therefore refuses in ANC rather than sending a frame it
knows will be dropped, and returns the value that is actually there.
