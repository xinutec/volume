# The vendor protocols, as captured

Measured 2026-08-15 from HCI snoop logs of the official apps plus our own
driven sessions. Five devices, four wire formats, two transports.

## ⚠ The channel is never the UUID that looks proprietary

Both directions of this cost real time:

- QC45 advertises `9b26d8c0-…`, which looks exactly like a vendor channel. **The
  Bose app does not use it** — the protocol is on plain SPP `00001101` (RFCOMM
  ch 8). Four packets to `9b26d8c0` drew silence; the identical packet on SPP
  returned the firmware version.
- `df21fe2c-…` is on the JBL *and* the JLab and answers freely — and is **not a
  control channel**. It is Google Fast Pair (below). An answering socket is not
  evidence you are on the right one.
- `66666666-…` is the **BES chip's OTA service**, named in the JBL app's own
  `BesSdkConstants` (with `77777777` and `97979797` as its characteristics). It
  connects and stays silent because an OTA session is not a command session — it
  is chipset boilerplate, not the JLab oddity it first read as.
- `00000000-deca-fade-…` is annotated "Bose proprietary" in #783 and is on the
  Sony too. `81c2e72a`/`931c7e8a`/`f8d1fbe4` are on Sony *and* JBL *and* both
  Bose; `931c7e8a` answers a fourth framing (`fe 03 01 04` + 16 zero bytes),
  unidentified and not pursued, since being on all five rules it out as anyone's
  control channel.

**Judge by what the vendor app connects to**, no capture needed:
```
adb shell dumpsys bluetooth_manager | grep "RFCOMM Connection opened"
  → e4:58:bc:3e:9d:aa handle:16 scn:8 dlci:16 mtu:990
```
`Channels.kt` returns vendor, channel and protocol as three fields for this
reason; its tests are the real SDP records.

## Bose — QC45 and QC35, one framing, different tables

```
[function block][function][operator][payload length][payload…]
operator: 00 Set · 01 Get · 02 SetGet · 03 Status · 04 Error · 05 Start · 06 Result · 07 Processing
         ⚠ writes here use 02 SetGet; plain 00 Set has never been sent
         ⚠ **AND 05 Start is not only for writes.** It opens a TRANSACTION — Processing,
           then the items, then Result — and it is how several functions are READ:
           `01 01` GET_ALL, `00 04`, `05 06`. Those answer `04 01 05` to a plain Get,
           which this repo read for months as "not gettable, i.e. a Set". It is not.
           Pairing mode and REMOVE_DEVICE are Starts too. See `bose-read-surface.md`.
```
```
→ 00 01 01 00                    ← 00 01 03 05 "1.1.0"   QC45
                                 ← 00 01 03 05 "1.0.4"   QC35
```
Full read/write surface, error taxonomy and the ANC hunt: `bose-read-surface.md`.

## Sony WH-1000XM4

`3e | type | seq | len(4 BE) | payload | sum | 3c`, escaping the three marker
bytes. ACK inverts the sequence number. Driven; see `SonyFrame.kt`. DLCI `0x2b`,
6,716 frames — by far the chattiest.

```
→ 3e 0c 00 00000002 0000 0e 3c
← 3e 01 01 00000000 02 3c                 ACK
  3e 0c 01 00000004 01 00 70 00 82 3c     DATA
```

`payload[0]` is the command. The table is `Command.smali` in
`com.sony.songpal.tandemfamily.message.mdr.{v1,v2}.{table1,table2}` — 152 commands
in v2/table1 alone. Subsystems run in blocks of ten: `GET_CAPABILITY RET_CAPABILITY
GET_STATUS RET_STATUS SET_STATUS NTFY_STATUS GET_PARAM RET_PARAM SET_PARAM
NTFY_PARAM`.

⚠ **RETRACTED — "each a plain enum whose ordinal *is* its byte".** It is not. The
byte is a **separate constructor argument**, and the two agree only for the eight
`CONNECT_*` entries before diverging: `GET_TEST` is ordinal 8 and byte `0f`.
Counting the enum is therefore right often enough to be believed and wrong
everywhere past the handshake. Third time in this repo — the JBL's gesture and
action tables are the other two. `scripts/smali_enum.py` reads the argument.

⚠ **And the XM4 is v1, not v2**, which changes what the *type* byte means: `f6 06`
is ASSIGNABLE_SETTINGS under v1 and WEARING_STATUS_DETECTOR under v2. The evidence
and the whole named surface are in `docs/sony-settings.md`.

```
20 POWER   50 EQEBB (EQ + bass boost)   60 NCASM (noise cancelling / ambient)
a0 PLAY    b0 auto-play   e0 AUDIO   f0 SYSTEM   04 device info
```

Verified against the XM4 — every GET drew its own RET, so the table is right:
```
→ 62 02   ← 63 02 00                          NCASM status
→ 52 00   ← 53 00 00                          EQEBB status
→ f2 00   ← f3 00 00                          SYSTEM status
→ 60 02   ← 61 02 02 00 01 02 00 14 01 14     NCASM capability, 0x14 = 20 steps
```
The `02` is `NcAsmInquiredType.NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE`.

### ⚠ Sony needs a SESSION, not a packet

`66 02` sent one-per-socket draws a bare ACK and nothing else — on every one of
the ten inquired types tried, which reads like "this command returns no data". It
does not. Inside one socket, with the device's DATA frames acknowledged, the same
byte answers:

```
→ 3e 0c 00 |00000002| 00 00                 CONNECT_GET_PROTOCOL_INFO
← 3e 0c 01 |00000004| 01 00 70 00
→ 3e 0c 01 |00000002| 66 02                 NCASM_GET_PARAM
← 3e 0c 01 |00000008| 67 02 01 02 02 01 00 00
```
`SONY_SEQ=1 ./probe.sh seq …` does the framing and the acking. This is the third
appearance of one trap: **a one-shot exchange cannot hold a protocol that has
state.** The Bose ANC write needs it, Sony reads need it.

⚠ **The framing itself is settled** — escaping, length and checksum — by one
captured frame; `docs/sony-settings.md`. It is no longer a hypothesis to blame when
a device stays quiet.

⚠ **The sequence byte ALTERNATES, and a repeat is discarded in silence.** Send two
frames with the same one and the device treats the second as a retransmission: no
error, no reply, and the caller reports "this device has no mode" — which is a
sentence another device says truthfully. A hard-coded `00` therefore works for as
long as a session asks exactly one question, and fails the moment anything is sent
before the read. Found only because a Bluetooth cycle gave the app a genuinely
fresh link; every test until then had reused one the probe had already opened.

⚠ **`00 00` (`CONNECT_GET_PROTOCOL_INFO`) opens the session.** The probe always
sent it first, which is why its reads worked; a driver that skips it gets away
with it on an established link and not on a new one.

Inquired type `02` is `NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE`; the v2 enum adds
`01 NC_ON_OFF`, `11`–`19` mode-switch variants, `21`/`22` ambient-only, `30`
NC/AMB toggle. Capability `61 02 | 02 00 01 02 00 14 01 14` gives 0x14 = 20
ambient steps.

## ✅ Sony ANC

```
read   66 02
write  68 02 <on> 02 <nc> 01 00 <ambient>
reply  67 02 <on> 02 <nc> 01 00 <ambient>        69 02 … is the notify
```
```
Noise Canceling   01 02 02 01 00 00
Ambient Sound     01 02 00 01 00 14      0x14 = 20, the max of the 20 steps
Off               00 02 00 01 00 14      ambient settings retained while off
```
Driven both ways from our socket, each confirmed by a separate `66 02` read, and
left on Noise Canceling where it started.

The fields were **measured, not guessed**: the app's v1 message classes are
obfuscated, so the mode was changed in the vendor app three times and the value
re-read each time. Only the bytes that actually moved are named — `on` (byte 0),
`nc` (byte 2), `ambient` (byte 5). Bytes 1, 3 and 4 held `02 01 00` in all three
states and are **not** identified; do not read meaning into them.

## JBL + JLab — what `df21fe2c` really is

**Google Fast Pair Message Stream**, which both implement because both are Fast
Pair certified. Not a vendor protocol, which is why one "implementation covers
both" and why **neither vendor app contains the UUID** (checked: `strings` over
every entry in both APKs).

```
[message group][message code][length: 2 bytes BE][payload…]
```
On connect the device **volunteers** its state — no request needed:
```
JBL   03 01 |0003| ea3f99                 model ID (3 bytes)
      03 02 |0006| 44f0a9e76f5f           BLE address
      03 03 |0001| 3c                     battery 60%
      03 0a |0008| d5e83d6d980caeee       session nonce (8 bytes)
      03 09 |0007| "6.8.0.0"              firmware
      06 03 |0007| 01 44f0a9e76f5f
JLab  03 03 |0003| 5a 50 64               battery 90/80/100
```
Model ID 3 bytes, nonce 8 bytes, battery 1-or-3, `ff 01` acks — that combination
is what identifies it; no spec handshake was performed.

⚠ **`03 03` is battery and its LENGTH carries topology** — over-ear JBL returns
one value (`3c`=60), JLab earbuds return three (90/80/100 = L/R/case). Code
assuming one battery drops two thirds of the earbud state.

⚠ **`ff 01 <len> <group><code>` is an ACKNOWLEDGEMENT, not an error.** It means
"message received", so it says nothing about whether the feature exists. A NAK is
`ff 02`. An earlier reading had this inverted and built a command map out of it.

⚠ **A dropped link means the device rejected the message**, not that it was
supported. `--ez reconnect true` reopens so a sweep survives it.

⚠ **Group `04` is Device Action — its first code RINGS the headphones.** Do not
walk a blind range across it while they are worn.

**So there is no ANC or EQ here.** Fast Pair carries model, battery, firmware,
ring and source-switching, and that is all. The `07 xx` commands an earlier pass
pointed at are Fast Pair groups, not JBL commands.

### Where JBL control actually lives — BLE GATT, driven

⚠ **Not RFCOMM at all.** Every RFCOMM channel on the JBL is silent or answers
something else, and the app never opens one — confirmed from a snoop capture of it
working. Control is GATT:

```
service  65786365-6c70-6f69-6e74-2e636f6d0000   ASCII "excelpoint.com" (BES's parent)
  write  …0002                                  Write Request
  notify …0001                                  subscribe via its 0x2902 CCC
```
Reached at the device's **LE address, which rotates** — scan for it (`probe.sh
scan`), and connect through the scanner's own device object with `autoConnect =
false`; see `Scan.kt` and `Gatt.kt`, where both traps are written down.

Frames are the BES protocol, read out of `jbl.stc.com` (apktool → smali;
`com.harman.bluetooth`, the stack `TourOne2Control` extends) and then driven:

```
[aa][command][length: 1 byte][payload…]      CmdBase.combine(), HEADER_COMMAND = 0xaa
```

## ✅ JBL ANC

```
read   aa 91 01 11
write  aa 91 07 10  01 <anc>  02 <ambient>  03 <talkthru>
reply  aa 91 07 12  01 <anc>  02 <ambient>  03 <talkthru>
```
`01 01 02 00 03 00` is Noise Cancelling, `01 00 02 01 03 00` Ambient Aware — both
observed against the app's own screen. Driven from our socket and confirmed by a
separate read-back, not by the echo. Sub-op `10` sets, `11` gets, `12` returns.
✅ **TalkThru is the third slot**, driven 2026-08-16 and confirmed against the app's
own selector. ⚠ `read` used to fall through to OFF for it, reporting a mode the device
was really in as the one state it cannot be put into.

`aa 21 01 30` (all status) moves with it — `30 01 00 …` under ANC, `30 00 02 …`
under Ambient — so ANC is legible from two places.

## The JBL read surface

Per-feature status is one byte each, and cheaper to read than the `30` bundle:
```
→ aa 21 01 31   ← aa 22 02 31 01        ANC on
→ aa 21 01 32   ← aa 22 02 32 00        ambient aware off
→ aa 21 01 33   ← aa 22 04 33 00 1e 00  auto-off: off, 0x1e = 30 minutes
→ aa 21 01 34   ← aa 22 02 34 00        EQ_PRESET — inert; this model uses aa a2
→ aa 21 01 35   ← aa 22 02 35 df        "multi-AI" per the SDK; `df` undecoded
→ aa 21 01 36   ← aa 22 02 36 00        BT connection status
→ aa 21 01 37   ← aa 22 02 37 00        OTA
→ aa 21 01 38   ← aa 22 02 38 01        ✅ AUTO_PLAY_PAUSE_ENABLE_STATUS
→ aa 21 01 39   ← aa 22 02 39 01        TWS
→ aa 21 01 3a   ← (nothing)             ANC_TUNING; 3a–3e are all silent here
→ aa 21 01 30   ← aa 22 0d 30 01 00 ff 00 df 01 00 01 01 00 1e 00     all of them
→ aa 91 01 21   ← aa 91 09 22 01 01 04 07 05 01 a1 07     ANC capability, undecoded
```
The whole sweep, 2026-08-16 21:36. ⚠ The `30` bundle holds the same values but its
field ORDER is not established — `01 00` opens it and `00 1e 00` closes it, which fits
`31 32 … 33`, and the middle does not line up with a plain concatenation. The per-field
reads are unambiguous and cheaper, so nothing depends on decoding the bundle.

✅ **The status fields are an SDK enum, and it settles two arguments.**
`EnumDeviceStatusType` is fourteen entries and the wire byte is its ordinal + `0x30`:

```
30 ALL_STATUS      31 ANC            32 AMBIENT_AWARE_MODE   33 AUTO_OFF
34 EQ_PRESET       35 MULTI_AI       36 BT_CONNECTION_STATUS  37 OTA_UPGRADE_STATUS
38 AUTO_PLAY_PAUSE_ENABLE_STATUS     39 TWS_CONNECTION_STATUS   3a ANC_TUNING_STATUS
3b IN_EAR_STATUS   3c SEAL_CHECK_STATUS  3d PERSONIFI_TEST_MODE_STATUS
```
Eight of those were already measured and agree, which is what makes the other six
trustworthy. ⚠ **Six names here were abbreviated when this was written** — `IN_EAR`,
`SEAL_CHECK`, `BT_CONNECTION`, `OTA_UPGRADE`, `TWS_CONNECTION`, `ANC_TUNING`. The SDK
spells all six with a `_STATUS` suffix, and they are written out above now: a future
session grepping the APK for the name in this table would not have found it.

✅ **`34` is EQ_PRESET after all — and inert on this model.** It was filed here as
"NOT the EQ preset" because it stayed `00` across a JAZZ selection, which was the
right *observation*: this device carries its equaliser as a ten-band curve under
`aa a2`, so the legacy preset field never moves. Named, not unidentified.

⚠ **`38` IS Auto Play & Pause, and the retraction printed here was wrong.** The
sequence is worth keeping: it was published on one agreeing byte (too little),
retracted when the setter turned out to be `aa 35 01 <on>` (reasonable, but an
argument from a rule rather than from evidence), and the SDK's own enum now settles
it. ⚠ **So setter and status field do NOT always mirror on this protocol** — `31`,
`32` and `33` do, and this one does not. The mirror is a useful guess, not a law.

⚠ **`3a` is ANC_TUNING_STATUS, not PersoniFi** — PersoniFi is `3d`. This file said
otherwise.

Measured 23:14 on the M2: `3a` through `3e` answer **nothing**. In-ear and seal check
are earbud features on an over-ear, and the tuning and test-mode fields are inactive.
⚠ `3b`'s "reply" was an `aa 25` battery frame that arrived in the window — an
unsolicited notification, not an answer, and the reason a decoder must check the
command byte rather than the leading `aa`.

### ✅ Auto power off — driven 2026-08-16

```
→ aa 21 01 33        ← aa 22 04 33 <on> <minutes> <?>     00 1e 00 = off, 30 min
→ aa 33 03 <on> <minutes> <?>   ← aa 00 02 33 00          the ack, not the answer
```
✅ **The unit is MINUTES, proven 2026-08-16 22:09** by driving the app's own three
options and reading the frames: `1e`/`3c`/`78` = 30/60/120 for "30 min"/"1 hr"/"2 hr".
This replaces a note that had said "minutes, unverified" since the field was found.

Driven both ways from this code and confirmed by read-back. ⚠ **The shape was
guessed from the status reply and worked first time** — the setter mirrors the
getter here, as it does on all four vendors.

✅ **The duration is driven from this code too, 2026-08-25 10:06.** `3c` and `78` were
written and read back, then `1e` restored — all three with the switch left at `00`, so
the ablation varied the timeout byte and nothing else, and changing it was inaudible.
The app now offers the three as chips beside the switch.

⚠ **`78` = 120 does not separate an 8-bit field from a 16-bit little-endian one**, since
its high byte would land on the trailing `00` that is already there. Every value either
this app or the vendor's can send fits in one byte, so the trailer stays echoed.

### ✅ Equalizer — `aa a2`, a CURVE **and** a table id

```
→ aa a2 02 01 <id>   read a table; ff = whichever is in use, and the reply names it
← aa a2 74 00 02 <id> <13 bytes> <10 records> 01
→ aa a2 74 00 00 <id> <13 bytes> <10 records> 01      write — operator 00, not 02
```
Each record is 10 bytes, `<a> 01 <gain float32 LE> <frequency float32 LE>`, and the
ten frequencies are **exactly the app's axis** — 32, 64, 125, 250, 500, 1k, 2k, 4k,
8k, 16k. JAZZ is `+4 +2 +1 +2.5 −1.5 −1.5 0 +1 +2 +4`; flat is all zeroes.

⚠ **`0x74` = 116 does not count the trailing `01`** — 117 bytes follow the length.
⚠ The first record's leading byte is `0a` where every other record's is `01`.
Unexplained; the vendor app sends it too, so a write copies the read frame and
substitutes only the gains. Doing that reproduces the app's JAZZ frame **byte for
byte**, which is what `JblSettingsTest` asserts.

✅ **The table id is `EnumEqPresetIdx`**, an SDK enum, and it names the app's whole
preset menu:
```
00 OFF   01 JAZZ   02 VOCAL   03 BASS   04 USER   05 ROCK   06 PIANO   07 CLUB   08 STUDIO
```
Which matches what was captured without knowing it: the flat curve read back as `00`
and the app's JAZZ write used `01`. ⚠ Only those two curves' gains have been seen, and
a write carries the gains as well as the id, so the other seven cannot be synthesised
from this list — the names are known, the numbers are not.

⚠ **A write carries both a curve and an id, so "nothing sends a preset id" was
wrong.** Payload byte 2 is a table selector: `aa a2 02 01 c9` and `…ca` are echoed
back in it, `ff` comes back as the id actually in use, the flat curve read as `00`,
and the app's JAZZ write used `01`. Which of the two the device honours is NOT
established — nothing captured varies one without the other.

⚠ **`c9` (196 bytes) and `ca` (86) are other tables in the same record shape**, read
at connect. `c9` is *longer* than a user curve, so a decoder that only checks the
array is big enough will read ten of its records as the equaliser.

⚠ **The named curves are the APP's**, as on the Bose and unlike Sony: selecting JAZZ
sends ten numbers, not a name.

⚠ **`aa 21 01 34` is EQ_PRESET and this is not it.** It read `00` before selecting JAZZ
and `00` after: it is the legacy one-byte preset field, inert on a model that carries its
equaliser as a curve. See the status-field list above, where it is named.

⚠ **`aa 40 01 01` drew nothing and changed nothing.** `aa 40`/`aa 41`/`aa 42` are
named in the SDK tables but none of them is the path the app uses; `aa a2` is.

### ⚠ What the app has, and what we have — 2026-08-16, app column 2026-08-17

Every row of the vendor app's device screen, read off it top to bottom, against the
status sweep taken minutes later. **All twenty-three rows have a wire identity and twenty
are in our app: thirteen writable, seven read-only, three absent.** Power off, Customize
ANC, LE Audio, Auracast and Voice Prompts joined 2026-08-25, and every count here is read
off the column below rather than incremented.

⚠ **A third number used to sit here — "twelve are decoded well enough to drive" — and it
was both stale and underivable from this table.** Nothing below distinguishes "decoded" from
"driven", so it could not be checked and drifted quietly. Two numbers that the column
actually supports are worth more than three where one is folklore.

⚠ Those three numbers are different questions and collapsing them flatters the work:
knowing a row is `aa 81` is not knowing what its three parameters mean, and it does not.
Written down because "is it all understood?" could not be answered before without
opening the app, and answering it from what `docs/` happened to mention would have
flattered us.

⚠ **The twelve did not move on 2026-08-17 and that is not an oversight.** Spatial Sound,
VoiceAware and Smart Audio & Video were all drivable already and already counted; what
changed is that all three are now decoded *completely*. A tally of drivable rows cannot
show that, which is the limit of counting them.

| the app's row | wire | us |
| --- | --- | --- |
| battery % | ✅ `aa 25`, and it can be ASKED | ✅ read |
| ⏻ power off | ✅ `aa 97 00` | ✅ button, asks first |
| Ambient Sound Control master switch | ✅ `aa 91 07 10` all-zero, driven | ✅ r/w |
| Noise Cancelling / Ambient Aware / TalkThru | `aa 91` | ✅ r/w |
| Customize ANC | ✅ `aa 91 01 21`, decoded | ✅ read-only |
| Personi-Fi | ✅ `aa a1`; ⚠ `aa 9a` is the TEST | — |
| Equalizer | `aa a2` | ✅ r/w |
| Low Volume Dynamic EQ | ✅ `aa 9e` | ✅ r/w |
| Spatial Sound + Movie/Music/Game | ✅ `aa 9d`, modes decoded | ✅ r/w |
| Gestures | ✅ `aa 77` reads AND sets, confirmed by ear | ✅ editable, restores a refusal |
| Smart Talk + 5/15/20 s | ✅ `aa 9f`, seconds | ✅ r/w |
| VoiceAware + Low/Mid/High | ✅ `aa 98`, level decoded | ✅ r/w |
| Smart Audio & Video + Audio/Video | ✅ `aa 81`/`82`/`83`, 3 params | ✅ r/w, 3-way |
| SilentNow | ⚠ opening it sends nothing | — |
| Auracast | ✅ `aa b0` session, `aa b1` key `02` switch | ✅ read-only |
| LE Audio | ✅ `aa b1` key `01`, measured | ✅ read-only, ⚠ link |
| Auto Play & Pause | ✅ set `aa 35`, status `38`, DRIVEN | ✅ r/w |
| Personal Sound Amplification | ✅ `aa a0`, contradiction resolved | ✅ read-only, ⚠ hearing |
| Left / Right Sound Balance | ✅ `aa a8`, DRIVEN | ✅ switch r/w |
| Voice Assistant | ✅ `aa 92`, measured | — |
| Voice Prompts | ✅ `aa 93` sub `04`, ABLATED | ✅ read-only; ⛔ language is OTA |
| Max Volume Limiter | ✅ `aa a5 03 00 01 <on>` | ✅ read-only by choice, ⚠ hearing |
| Auto Power Off + 30 min/1 hr/2 hr | ✅ `33`, minutes proven | ✅ r/w, all three |

✅ **Every row is now placed.** The last one, LE Audio, is `aa b1` key `01`, measured
2026-08-17 — `aa b1` is where it was guessed to live, and the guess was right for the
same reason it was cheap: `GetSetFeatureCmd` names its own keys.

### ✅ Switching the JBL OFF — found missing 2026-08-17 23:47, driven 2026-08-23 16:32

The one thing a JBL owner most obviously wants, and for a week this app could not do it:
`Drivers.JblBes.modes` held `ANC`, `AMBIENT`, `TALK_THRU` and no `OFF`.

✅ **Nothing on the wire was missing, and no new frame was needed.** `read` already
decoded the all-zero reply as OFF, and `write` already built it, because "exactly one
slot is set" makes OFF fall out of the same arithmetic as the other three. The gap was
one element in a set.

```
→ aa 91 07 10 01 00 02 00 03 00           ours: sub-op 10, all three slots zero  ✅ ACCEPTED
→ aa 91 01 13                             genSetANCModeOFF, what the vendor app sends
→ aa 91 01 11   ← aa 91 07 12 01 00 02 00 03 00      off: all three slots 00
                ← aa 91 07 12 01 01 02 00 03 00      ANC
```

✅ **Driven both ways and confirmed by ear**, which is the only evidence that separates
"the device stored it" from "the device acts on it". Going into OFF the M2 announced
**"ambient sound control off"** — the vendor app's own name for the row — and back to ANC
it said **"noise cancelling"**. ⚠ The cycle went ANC → OFF → ANC rather than repeating
OFF, because a write against the value already held is indistinguishable from a broken
command; it may announce nothing at all, which reads as a refusal.

⚠ **`aa 91 01 13` was never needed and remains unexercised here.** Two plausible writers
and the device took the cheaper one, so the SDK's dedicated OFF sub-op is still only a
name. Do not write it down as equivalent — nothing has sent it.

⚠ **It never appeared in the 23-row inventory, and the reason is worth keeping.** That
table counts the vendor app's ROWS, and this control hides *inside* the ANC row rather
than beside it — so a gap analysis built from someone else's UI cannot see it. #1041.

⚠ **And the obvious guard against a repeat is the wrong one.** "Every driver offers OFF"
fails on the **QC45**, which genuinely has none — its ANC is a slot table of Quiet and
Aware and Bose Music cannot stop it either, so that test would assert something false
about the hardware. `DriversTest.no driver reports a mode it does not offer` states the
actual defect instead: the JBL could *report* a state it could not be *asked* for.

### ✅ Three rows settled by one read sweep and two drives — 2026-08-17 23:36

⚠ **PSAP's "contradiction" was OUR misreading, not a device fault — RESOLVED.** This
file said `PSAPCmd` parses `setOn` from index 4 = `01` while the app's row says
*Disabled*, and refused to display the row on the strength of it. `PSAPCmd` has **two
branches**: the one this frame's shape selects reads the switch from index **5**, the
level from 7 and the index from 9. Index 4 is the KEY `01`; index 5 is `00`.

```
← aa a0 07 02  01 00  02 64  03 00     PSAP: off, level 100
← aa a8 05 02  01 00  02 64            balance: off, centre
```
So both are **key/value pairs, not positional**, and a positional read reports a feature
that is off as on. The device and the app agreed the whole time. ⚠ PSAP is still
read-only here, now by choice rather than by doubt: it amplifies the world into your
ears, which is [the volume limiter]'s argument in a second place.

✅ **Auto Play & Pause is DRIVEN**, 23:38 — `aa 35 01 00` then `aa 35 01 01`, each
confirmed by reading `aa 21 01 38`. ⚠ Setter `35`, status `38`: they do **not** mirror,
and the mirror argument is what retracted this finding once when it was right.

✅ **Balance is DRIVEN**, 23:39 — on at level 100 and off again, both read back.
Level 100 is this unit's centre, which the app draws as "0"; the range is NOT
established, so a write carries the level back unchanged.

⚠ **SilentNow's screen sends nothing when opened, and that is still not explained.**
It is not evidence there is no command: the reads that *do* work — Spatial Sound,
Max Volume Limiter — are also absent from the control layer that looked authoritative
(`BaseControl`, whose defaults log "not implement" for all 198 methods including
theirs). Any claim that a feature has no wire form has to survive being run against a
feature known to have one. This one did not.

⚠ **`?` means nobody has looked**, not that it is hidden. Each unknown is one capture
of the app touching that one control, and the method in `docs/captures.md` applies
unchanged; the reason there are twenty of them is that the work stopped when ANC
worked. ⚠ Several rows are toggles that are OFF right now — Spatial Sound, Smart Talk,
VoiceAware, LE Audio, balance — so any of them could be the unidentified `34`, and
attributing it needs one of them varied, not more reading.

### ✅ Eight more, driven through the vendor app — 2026-08-16 21:44 and 22:04

Two runs of `scripts/drive_jbl.py`, each change followed immediately by its inverse.
Most of these share one convention, the same as `aa a2`: `aa <cmd> <len> <operator>
<payload…>`, operator **`00` set · `01` get · `02` status**.

| the app's row | set | get | status |
| --- | --- | --- | --- |
| Ambient Sound Control, master off | `aa 91 01 13` | `aa 91 01 11` | `aa 91 07 12 …` all three slots `00` |
| Low Volume Dynamic EQ | `aa 9e 02 00 <on>` | `aa 9e 01 01` | `aa 9e 02 02 <on>` |
| Spatial Sound | `aa 9d 03 00 <on> <mode>` | `aa 9d 01 01` | `aa 9d 03 02 <on> <mode>` |
| Smart Talk | `aa 9f 03 00 <on> <seconds>` | `aa 9f 01 01` | `aa 9f 03 02 <on> <seconds>` |
| VoiceAware | `aa 98 03 00 <level> <on>` | `aa 98 01 01` | `aa 98 03 02 <level> <on>` |
| Auto Play & Pause | `aa 35 01 <on>` | ? | ⚠ see above |
| Left / Right Sound Balance | `aa a8 05 00 01 <on> 02 64` | `aa a8 01 01` | `aa a8 05 02 01 <on> 02 64` |
| Smart Audio & Video | one of three fixed `aa 81 08 …` frames | `aa 82 00` | `aa 83 08 …` |

✅ **Smart Talk's timeout is in SECONDS** — `05`/`0f`/`14` are exactly the app's 5 s,
15 s and 20 s. Four points, so this is measured rather than inferred from one.

✅ **Smart Talk was then seen doing its job unprompted**: at 21:48:36 the headphones
volunteered `aa 91 07 12 01 00 02 00 03 01` — into TalkThru — and `…01 01 02 00 03 00`
back twenty seconds later. Notifications, not commands; it is the feature working.

✅ **Smart Audio & Video has NO enable byte. There are three whole frames.** Measured
2026-08-17; the app sends one of exactly these and nothing else:

| state | payload after `aa 81 08` |
| --- | --- |
| off | `00 01  35 00  e6 00  ff ff` |
| Audio Mode | `00 01  35 00  96 00  ff ff` |
| Video Mode | `c5 00  2e 00  50 00  ff ff` |

Grouping into little-endian pairs is a *reading*, not the vendor's word for it: off
`(256, 53, 230)`, Audio `(256, 53, 150)`, Video `(197, 46, 80)`, terminated `ffff`. The
numbers themselves are undecoded and look like DSP tuning; what is settled is which
frame corresponds to which state, which is all that is needed to drive it.

⚠ **The off frame is sent whatever mode is selected**, and that is the measurement that
matters here — it is why "which of the three bytes is the enable" has no answer:

    12:50:01  pick Video Mode, switch on   → c5 00 2e 00 50 00
    12:50:40  switch OFF, Video STILL selected → 00 01 35 00 e6 00
    12:51:25  switch ON,  Video still selected → c5 00 2e 00 50 00

Off carries the Audio-family first two values while Video is lit, so it is a state of
its own rather than a modifier on the current mode.

⚠ **A stated prediction, refuted.** Audio's third value moves `96` → `e6` when switched
off, a difference of `0x50`; Video's is `50`, so `0x50 + 0x50 = a0` was written down
beforehand as what Video-off should read if the third value carried the enable. It never
appeared — the app does not compute an off-value from the mode, it sends a constant.
Recorded because the arithmetic was tidy enough to have been believed without the test.

⚠ Two earlier attempts at this returned a plausible wrong answer; how, and the other
traps around driving this row, are in `docs/captures.md`.

✅ **VoiceAware's `02` was the LEVEL, and the level is Mid.** Settled 2026-08-17 by
Pippijn dragging the bar by hand while the capture ran — the driver cannot do it, and
two attempts to reach the levels by tapping had failed, the second by pressing another
card's control (`docs/captures.md`). Three drags, Low then High then Mid:

    12:13:39  → aa 98 03 00 02 01     the switch coming on, at Mid
    12:13:41  → aa 98 03 00 01 01     Low   — repeats while the drag travels
    12:13:45  → aa 98 03 00 03 01     High
    12:13:46  → aa 98 03 00 02 01     Mid

So **`01` Low · `02` Mid · `03` High**, and the byte that had been written down as an
unexplained constant was simply the level nobody had moved off its default. ⚠ Like the
mode pickers, a level change carries `on = 01`: dragging the bar switches VoiceAware on.
The resting read `aa 98 03 02 02 00` — Mid, off — says the same thing from the other
side, and had done all along.

⚠ **RETRACTED — "Spatial Sound's Movie / Music / Game buttons send NOTHING when
tapped."** They send. This section previously said the opposite, "confirmed twice, the
second time with the three taps landing on one row at identical coordinates, so this is
no longer a suspected mis-tap". The identical coordinates were the evidence *for* a
mis-tap: all three taps hit the mode LABEL, which is a `clickable="false"` TextView with
the real target — a `relativeLayoutText{1,2,3}` tile — sitting 11 px above it. An empty
capture window was read as a fact about the headphones when it was a fact about the tap.

✅ **What the tile actually does**, measured 2026-08-17 with both windows in one capture:

    10:47:03  tap the LABEL "Movie"   → nothing on the wire at all
    10:49:06  tap the TILE  "Movie"   → aa 9d 03 00 01 02

and picking each mode in turn, with no toggling in between:

    11:11:28  Movie  → aa 9d 03 00 01 02    ← aa 9d 03 02 01 02
    11:11:35  Game   → aa 9d 03 00 01 03    ← aa 9d 03 02 01 03
    11:11:43  Music  → aa 9d 03 00 01 01    ← aa 9d 03 02 01 01
    11:11:53  switch → aa 9d 03 00 00 01    ← aa 9d 03 02 00 01

So **mode `01` Music · `02` Movie · `03` Game**, and a pick is its own write which also
sets the enable byte to `01` — tapping a mode turns Spatial Sound on. Confirmed in the
render: the switch was OFF before the tile tap and ON after. Smart Audio & Video and
Smart Talk behave the same way, so "a segmented pick writes" is the rule here, not the
exception.

⚠ The old claim's other half survives: the mode byte does travel with the on/off write.
The 09:30 run that produced the retracted finding sent `aa 9d 03 00 01 01` — mode
`01`, Music — because the Movie tap before it had done nothing. Nothing was ever left
on Movie.

### The vendor app's own connect-time sweep — 22:14:25, 40 exchanges in two seconds

Forcing our app to release the link made the vendor app reconnect, and it read
everything it knows about. That list is a map of this device's surface, free:

```
aa 9b 02 01 01   aa 91 01 11   aa 91 01 21   aa 9f 01 01   aa a2 02 01 ca/ff/c9
aa 9e 01 01      aa 9d 01 01   aa 82 00      aa 77 02 01 ff   aa 98 01 01
aa a0 01 01      aa 21 01 35   aa 93 01 04   aa 93 01 01   aa a5 01 01
aa 90 01 03      aa 94 01 01   aa b0 01 00   aa 61 02 fe 35   aa 13 01 00 01
aa a8 01 01
```
✅ `aa 94 01 01` → `aa 94 11 02 "TL1461-AN0018486"` — the **serial number**, in ASCII.
✅ `aa 13` is **`AnalyticsCmd`** — usage telemetry the headphones keep and the app
harvests, not the capability list this file guessed from the shape. `HandleParse.
parseAnalyticsInfoData` names the fields: `aNCTimes`, `aAActiveTimes`, `tTActiveTimes`,
`vAActivityTimes`, `gATimes`, `playPauseTimes`, `prevNextTimes`, `manualPairingTimes`,
`powerOnDuration`, `playtimeDuration`, `bTConnectionDuration`, `voicecallDuration`,
`lowBattWarningTimes`, each of the last five split L/R on TWS models.

⚠ **Worth stating plainly, since this app exists to replace the vendor's:** the
headphones count how often you use each feature and how long you listen, and the
vendor app collects it. Nothing here reads `aa 13`, and nothing should start without a
reason better than "the frame is understood".

⚠ **This sweep is the cheapest lead left.** Every getter above has a setter by the
mirror rule, and the ones still unnamed — `93`, `a5`, `90`, `b0`, `9b`, `a0` — are
that many of the app's remaining rows.

### ✅ The SDK names the opcodes — no capture needed, 2026-08-16 22:30

`jbl.stc.com` carries the BES SDK with **a class per command**, and each sets its own
byte in its constructor:

```
const/16 v0, 0xaa   iput ...->header:I
const/16 v0, 0x9f   iput ...->cmd:I          ← the opcode
const/4  v0, 0x1    iput-byte ...->subReqCmd:B    ← get
const/4  v0, 0x2    iput-byte ...->subRetCmd:B    ← status
```
`apktool d base.apk`, then read `com/harman/bluetooth/imp/cmd/*Cmd.smali`.

| opcode | SDK class | the app's row |
| --- | --- | --- |
| `23` | BeepingCmd | — |
| `77` | GestureCmd | Gestures |
| `91` | AdvancedAncCmd | ANC, and Customize ANC |
| `98` | VoiceAwareCmd | VoiceAware |
| `99` | EnvironmentNoiseCheckCmd | (inside Customize ANC) |
| `9a` | EarCanalTestingCmd | Personi-Fi |
| `9c` | TipsTypeCmd | — |
| `9d` | SpatialStatus3DCmd | Spatial Sound |
| `9e` | LowEQCompensationCmd | Low Volume Dynamic EQ |
| `9f` | SpeakToChatCmd | Smart Talk |
| `a0` | PSAPCmd | Personal Sound Amplification |
| `a5` | SafeSoundCmd | **Max Volume Limiter** |
| `a7` | SyncTimeToDeviceCmd | — |
| `a8` | LeftRightSoundBalanceCmd | Left / Right Sound Balance |
| `aa` | PreserveSettingsCmd | — |
| `b0` | LeaAudioCmd | LE Audio |
| `b1` | GetSetFeatureCmd | ⚠ see below |

✅ **This was checked before it was trusted**: five opcodes decoded from captures the
hour before — `98`, `9d`, `9e`, `9f`, `a8` — land on the classes whose names match the
rows they were decoded from, and `77` matches the gesture command already known. Six
independent agreements, so the extraction is not being read hopefully.

✅ `reqimp/CmdGen.smali` names the ANC sub-ops outright: `genSetANCModes` → `10`,
`genReqANCModes` → `11`, **`genSetANCModeOFF` → `13`**. That last one is the master
Ambient Sound Control switch, decoded from a capture and now confirmed by name.

⚠ **`aa b1` is NOT a keepalive**, as this file said. It is `GetSetFeatureCmd`, and the
four-second traffic is a real poll with a real answer — `aa b1 04 01 00 01 00` out,
`aa b1 04 02 00 01 00` back, 313 times in one capture — plus **writes** with operator
`00` (`aa b1 03 00 01 00`). Calling it a keepalive is what made it easy to filter out
and ignore; it is a generic feature channel and may be the route to several rows that
have no command class of their own.

### ✅ Opening each sub-screen names five more — 2026-08-16 23:03, no writes

`scripts/drive_jbl.py screens` opens every row that leads somewhere and backs out.
Opening is a READ, so this costs nothing and risks nothing; each screen fires its own
getter on the way in.

| screen opened | what went out |
| --- | --- |
| Customize ANC | `aa 91 01 21` → `aa 91 09 22 01 01 04 07 05 01 a1 07` |
| Gestures | `aa 77 02 01 ff` |
| Auracast | `aa b0 02 02 00`, `aa b0 03 01 01 1e`, `aa b0 01 00` |
| Personal Sound Amplification | `aa a0 01 01` |
| **Voice Assistant** | **`aa 92 01 01`** → `aa 92 09 02 01 01 02 01 03 00 04 00` |
| **Voice Prompts** | **`aa 93 01 01`** → `aa 93 05 02 01 00 00 08`, and `aa 93 01 04` |

### ✅ Voice Prompts is `aa 93` sub-command `04` — ablated 2026-08-25 11:47

```
→ aa 93 01 04   ← aa 93 02 05 <on>          the SWITCH
→ aa 93 01 01   ← aa 93 05 02 01 00 00 08   something else, and it never moves
```

Toggling the vendor app's own switch moved `05 01` → `05 00` and back. ⚠ **`aa 93 01 01`
was unchanged across both halves** — and that is the frame this row had been filed under
since 2026-08-17. Two sub-commands on one command byte, and the one that looked like the
answer is the one that never moved. **Only an ablation separates them**; reading either
alone gives a plausible frame.

⛔ **The LANGUAGE is out of scope, and this is a rule rather than a difficulty.** The
picker offers eleven languages, and `BesOTATask` reads `OTA_LANGUAGE_TYPE` out of
`OTADfuInfo`: choosing one pushes a language FILE over the DFU path. `FileType` is
`DEFAULT/FIRMWARE/LANGUAGE/COMBO`, and `MultiLangVoicePrompt.VoiceType` is
`INVALID/LANGUAGE/NONE/TONE`. **This repo does no OTA work**, so the language was not
touched — the picker was opened, read, and backed out of, and `aa 93 01 01` was verified
byte-identical afterwards.

⚠ **Hence no SETTER for the switch either.** The mirror rule that guessed every other
writer here is already broken on this device (Auto Play & Pause sets on `35`, reports on
`38`), and `aa 93`'s neighbouring sub-commands are the OTA ones. Guessing in that space is
the sweep this file forbids, on the one family where a wrong guess starts a file transfer.

⚠ **A reply here arrived CONCATENATED** — `aa 93 02 05 01 aa 25 0d …`, the restore's answer
with an unsolicited battery frame glued on. Same hazard as `aa b1`, and `JblVoicePrompts`
bounds its read by the length byte for it. `JblSettingsTest` pins that exact buffer.

⚠ **Voice Assistant remains unshown — `aa 92 09 02 01 01 02 01 03 00 04 00`.** The app says
"Native" and none of `EnumHotwordVaType`'s OFF/GA/ALEX/DEFAULT/XIAOWEI fits, so the TLV's
values are unmapped. Unlike Voice Prompts there is no safe ablation in sight: the picker's
alternatives are assistants, not a switch.

✅ **`aa 92` and `aa 93` were both unknown an hour ago** — `93` was one of the unnamed
getters in the connect sweep, and `92` had never been seen at all.

✅ **`aa 91 01 21` is Customize ANC, and it is DECODED — 2026-08-25.**
`AdvancedAncCmd` gives cmd `91`, get `21`, status `22`, **set `20`**, and a parser whose
arithmetic is `(len - 1) / 2` pairs with the key at `i * 2 + 4` and the value at `i * 2 + 5`.

| key | `AdvanceAncSettings` field |
| --- | --- |
| `01` | adaptiveAnc — ⚠ `0` MANUAL_ADAPTIVE_ANC · `1` TRUE_ADAPTIVE_ANC, declared constants |
| `04` | manualAdaptiveAncLevel |
| `05` | leakageCompensation |
| `06` | earCanalCompensation |
| `08` | autoCompensation |
| `a1` | ambientAwareLevel |

So `aa 91 09 22 01 01 04 07 05 01 a1 07` is **adaptive, manual level 7, leakage 1, ambient
level 7** — four of the six keys, which is why every field on `AdvancedAnc` is nullable.

⚠ **A THIRD grammar on `aa 91`.** `10`/`12` are fixed mode slots; `21`/`22` is a sparse
pair list. One command byte, two shapes, told apart only by the sub-command.

⚠ **`a1` is a KEY.** It is the only one outside `01`–`08` and it arrives last, so a reader
that assumes a contiguous key space or walks fixed offsets loses the ambient level while
returning a healthy-looking object for the rest. That is what `JblSettingsTest` pins.

⚠ **The LEVELS have no established scale.** `7` is out of nothing anybody here has found —
the SDK declares constants for the tuning field alone, and the app's slider bounds were not
located. So they are shown as numbers with their key names, never as sliders, and there is
**no writer**: sub-command `20` is named and has never been sent.

✅ **Auracast's SESSION is `aa b0`**, now measured rather than inferred from
`LeaAudioCmd` parsing into `AuracastGroup`. ⚠ This was read at the time as ruling `b0`
out for LE Audio and leaving that row unplaced. Both halves were half right: `b0` is
the session, and the *switches* for both Auracast and LE Audio are keys on `aa b1` —
see the `aa b1` section, where the whole row finally resolved.

⚠ **Personi-Fi, SilentNow and Equalizer fired NOTHING when opened.** For the equaliser
that is explained — the curve was already read at connect. For the other two it is a
real result and not a missed tap: the taps are verified by the driver, and the frames
either side are present. Their state is app-local until something is committed.

### ✅ Personi-Fi is `aa a1`, and no hearing test was needed — 2026-08-16 23:43

```
→ aa a1 01 01            ← aa a1 05 02 01 <on> 02 <?>     get
→ aa a1 03 00 01 <on>    ← aa a1 03 02 01 <on>            set
```
⚠ **This file said Personi-Fi was `aa 9a`.** `9a` is `EarCanalTestingCmd` — the test
itself — and the *enable* is `a1` (`PersoniFiModeHearingTextCmd`). Two commands, and
naming the feature after the wrong one would have sent a future session hunting in the
test flow for a switch that lives elsewhere.

✅ **The screen sends nothing until it is provisioned**, which is why opening it in the
earlier sweep looked silent: the feature needs a 6 MB download before it will run at
all. Once downloaded, this device already had a profile stored, so the switch could be
cycled without anyone taking a hearing test.

⚠ **`aa 9a` EarCanalTesting is still unexercised** — that is the RETEST button, and it
plays tones into someone's ears. It is the one command here that cannot be reached
without a person volunteering for it.

⚠ **TEST REPORT sends NOTHING.** The per-ear curve it draws is app-side in that window;
no frame carried it. ⚠ And it is someone's hearing profile — a repo this public gets
the command shape and never the values.

### ⚠ A REPLY IS NOT THE ONLY FRAME IN THE BUFFER — measured 2026-08-25

This file records the same surprise in three places — `aa 21 01 3b`, `aa 93`, `aa b1` — each
time as a quirk of that command. **It is not. It is a property of every read on this
device**, and meeting it three times as a local oddity is how it went a week without being
fixed.

`Gatt.collect` concatenates every notification that arrives in the window, and it has to: a
long answer comes split across MTU-sized notifications and the split is an artefact of the
MTU, not of the protocol. Meanwhile the device volunteers `aa 25` battery **every ten
seconds**, unasked.

**85 getters in one connection, 64 reaching the log:**

| | |
| --- | --- |
| answered | **64 of 64** — no dropouts at all |
| carried a second frame glued on | **8 of 64 (12.5%)**, every one an `aa 25` |
| came back under an unexpected command | 0 |

⚠ **All 8 decoded correctly, because the battery frame arrived AFTER the answer.** The
decoders here bound themselves by the length byte and check the command, so trailing junk is
ignored. **The failure is the same event arriving FIRST**: offset 0 becomes someone else's
frame, every decoder correctly returns null, and a settings row silently disappears. That is
#1154, and the decoders were never wrong — they were handed the wrong offset.

✅ **Fixed in `Bes.frame` / `Drivers.JblBes.ask`**, which tries the whole buffer first and
only then walks frames by their length byte. ⚠ **The symptom was never reproduced on
demand** — it was seen once, across two renders — so this is argued from the mechanism above
plus replay tests, not demonstrated against the failure.

⚠ **A skip cannot pass an `aa a2`**: that frame's length byte undercounts its content by
one, so a skip over it lands a byte short. No curve has ever arrived unsolicited, so the case
does not occur — and it would surface as a null, never as a wrong value.

### ✅ `aa b1` GetSetFeature — grammar and keys, 2026-08-17

`com/harman/commands/GetSetFeatureCmd` is a **generic key → value map**, and it names
its own keys: alongside the generic `getFeatures(List)` / `setFeatures(Map)` it carries
`getLeAudioStatus`, `setLeAudioStatus`, `getAuracastStatus`, `setAuracastStatus` and
`doHeartBeat`. Reading what those four build gives the key ids for free.

```
aa b1 <len> <op> [<keyId> <valueSize> <value…>]…      op  00 get · 01 set · 02 status
```

| key | feature | get | set |
| --- | --- | --- | --- |
| `00` | the vendor's keepalive | ⚠ silent | `aa b1 04 01 00 01 00` every 4 s |
| `01` | **LE Audio** | `aa b1 03 00 01 00` | `aa b1 04 01 01 01 <on>` |
| `02` | **Auracast** | `aa b1 03 00 02 00` | `aa b1 04 01 02 01 <on>` |
| `03` | ⚠ **unnamed, and it answers** | `aa b1 03 00 03 00` | (not sent) |
| `04`–`07` | nothing there | — | — |

✅ **Measured, not only read off the decompile** — 2026-08-17, gets only:

```
→ aa b1 03 00 01 00     ← aa b1 04 02 01 01 00      LE Audio  off
→ aa b1 03 00 02 00     ← aa b1 04 02 02 01 01      Auracast  on
→ aa b1 03 00 03 00     ← aa b1 04 02 03 01 00      ?         off
→ aa b1 03 00 04 00 … 07     ← (nothing) ×4
```

✅ **Re-read 2026-08-25 11:07, byte-identical**: LE Audio `00`, Auracast `01`, key `03`
`00`. Both named keys are now rows on the card, **read-only**. LE Audio because writing it
renegotiates the audio link the app is talking over; Auracast because nothing has
established what its switch alone does — a broadcast is an `aa b0` session, and the key has
never been driven either way.

⚠ **The probe could not reach the device until the app was force-stopped, and the error
blamed the DEVICE.** `volume`'s own process held a GATT client from a settings read, so the
second `discoverServices` returned an EMPTY service list and the probe printed
`service 65786365-… not on this device`. That sentence is about a lease, not about the
headphones, and it failed in 19 ms — far too fast for a connect that had really happened.
⚠ Three identical retries all failed the same way, so this reads as a hard fact about the
device rather than as contention. `am force-stop org.xinutec.volume` cleared it instantly.
Same shape as `probe.sh free` for the vendor apps, except the app holding the channel is
ours.

⚠ **Key `03` is real and is named nowhere** — not in `GetSetFeatureCmd`, which has a
`getXxxStatus` pair for `01` and `02` only. Every row of the app's device screen is
already accounted for elsewhere, so this is a feature the app does not show, or shows
somewhere that has not been opened. Attributing it needs the same ablation as any
other unknown: change something and see if it moves. **It reads `00` today**, so a
future session comparing against that number should read this line first.

⚠ **A get answers about the FIRST key only.** Asking `01` and `02` in one frame
(`aa b1 05 00 01 00 02 00`) returned `01` alone, and a frame led by key `00` returned
nothing at all — which is what made an eight-key sweep look like a rejected frame when
it was really the keepalive key declining to answer. So ask one key per frame; the
vendor's list form buys nothing on this firmware.

⚠ **Replies arrive CONCATENATED.** Key `03`'s status came back glued to an unsolicited
battery frame in one notification — `aa b1 04 02 03 01 00 aa 25 0d …`. Any reader that
scans to the end of the buffer instead of stopping at the length byte will read `aa 25`
as more key/value triples. `JblFeature.state` bounds its walk for exactly this reason.

⚠ **Three claims this file made about `aa b1` were wrong**, and all three came from
reading the operator byte as if `b1` shared the `<cmd> <len> <operator>` layout of the
rest of the protocol. It does not — `b1`'s operator is followed by *key/size/value*
triples, so a frame can be a get and still start with `00`:

- `aa b1 03 00 02 00` was filed as "a set, operator `00`". It is **`getAuracastStatus`** — a
  get of key `02`.
- "`aa b1` is **NOT** a keepalive" was too strong. `doHeartBeat` is a **set of key `00`**
  every four seconds, so it is a keepalive that happens to be a write.
- "The key ids are NOT in the SDK" was true of `BesDeviceStatusControl`, which does
  pass the map through — but false of `GetSetFeatureCmd` one layer down, which names
  three of them outright. The ablation this file called "the honest next step" was
  never needed.

⚠ **`aa b0` and `aa b1` are both Auracast, and both readings were right.** `LeaAudioCmd`
(`b0`) is the *session* — `scanLeAudioSource`, `receiveLeAudioSource`, `cancelReceive…`.
Key `02` on `b1` is the *switch*. Naming either one "Auracast" alone loses the other.

⚠ **Only these three keys are named anywhere.** `GetSetFeatureCmd` has no other
`getXxxStatus` pair, so a fourth key would have to come from the app's obfuscated
layer or from asking the device. `getFeatures` takes a **list**, which is the vendor's
own way to ask for several at once and the only safe way to look for more — one
opcode, provably a get. That is not the blind opcode sweep this file forbids.

### ✅ Max Volume Limiter and remote power off — driven 2026-08-16 23:29, once, by agreement

```
→ aa a5 03 00 01 <on>    ← aa a5 03 02 01 <on>     SafeSound = Max Volume Limiter
→ aa 97 00               ← aa 00 02 97 00          POWER OFF, and that is the whole command
```

✅ **The limiter's status offset is now proven, not inferred.** The connect-sweep frame
was `aa a5 03 02 01 01` — both payload bytes `01`, so the capture could not say which
was the status, and the offset came from the SDK's `SafeSoundCmd.setStatus` reading
frame index 5. Driving it produced `…02 01 00` when off and `…02 01 01` when on: index
5 moves, index 4 does not. The SDK's answer was right, and the byte that would have
been a coin-flip is now measured.

⚠ **The limiter was off for nine seconds, with nothing playing, by explicit agreement,
and no writer was added to this repo.** Knowing the frame does not change the rule:
`JblSafeSound` still has only a getter. Hearing protection is not a thing to expose
because it turned out to be easy.

⚠ **`aa 97 00` ends the session, and its dialog says the way back is physical** — "To
power on again, press a power button on a headphone". So it is the last thing any run
can do, and it costs someone getting up.

✅ **Battery is `aa 25`**, volunteered every ten seconds — **and it answers a getter**,
`aa 25 01 01`, measured 2026-08-17. Worth knowing: waiting for the notification means a
blank card for up to ten seconds.

```
→ aa 25 01 01   ← aa 25 0d 01 00 00 <slave> <master> <box> ff ff ff ff ff ff ff
```
✅ **Each level byte is a charging bit and a 7-bit percentage**, and `BatteryInfoCmd`
discards anything over 100 as *unknown*. So the trailing `ff`s are **absent cells, not
padding**, and the box slot reads `ff` on this over-ear because there is no case —
which is the decode explaining an observation rather than the other way round.
`parseBatteryInfo` puts slave at index 6, master at 7, box at 9, and reads them only
when the sub-command byte is `01`.

⚠ **Master and slave cannot be told apart from any capture here**, because the two
bytes have been equal in every frame — `3c 3c` = 60% on 2026-08-17, `5a 5a` = 90% on
2026-08-16 when it matched the app's "90%". That calibration fixes the SCALE and says
nothing about the slots. It is precisely the shape of `SafeSound`'s two `01` bytes,
with one difference: SafeSound could be driven until one moved, and a battery cannot.

✅ **Three rows are readable RIGHT NOW with values already captured**, from the vendor
app's own connect sweep, with no driving at all:
```
aa a5 01 01  → aa a5 03 02 01 01     SafeSound   = Max Volume Limiter, on
aa a0 01 01  → aa a0 07 02 01 00 02 64 03 00     PSAP, disabled
aa b0 01 00  → aa b0 02 10 01        LeaAudio
```
⚠ **Max Volume Limiter is hearing protection and is ON.** It can be shown from the
getter above without ever being written, which is the only way this repo should touch
it.

### Gestures — read, written, and confirmed by ear

```
→ aa 77 02 01 ff                        ff = ALL; or one GestureType byte
← aa 77 11 02  06 0b  07 04  08 00  0c 00  09 00  0a 00  0b 00  0e 00

→ aa 77 03 00 06 05                     set ONE pair: len 3, operator 00
← aa 77 03 02 06 05                     status names the gesture it acted on
→ aa 77 11 00 <8 pairs>                 set the batch: len = 1 + 2N
```
Pairs of `<gesture><action>`, so as shipped: left tap → ANC/ambient cycle, left
double tap → TalkThru, and the other six unassigned.

✅ **Writes work, 2026-08-17.** `aa 77 03 00 06 0b` was accepted and read back while
the gesture held `00`, so it is a real change and not a write against the value
already there. Then confirmed physically: bound to `05` NEXT_TRACK the button skipped
the track and did **not** announce ambient, and restoring `0b` brought the
announcement back — the same press, one byte apart, which is the only evidence that
separates "the device stored it" from "the device acts on it".

⚠ **`aa 77` sets as well as reads; `aa 71` is not needed.** `CmdGen` has both
`genSetGestureInBach77` (used here) and `genSetGestureControlOld71`, whose name says
what it is. The length byte is `combine()`'s doing — it writes `payload.length` at
index 2 — and the payload is `00` followed by the pairs, so `1 + 2N`. The read reply's
own `11` = 1 + 16 is the arithmetic agreeing with the SDK.

⚠ **A REFUSED ACTION IS SILENTLY COERCED TO `00`, WHICH CLEARS THE BINDING.** The
device does not reject the frame; it answers `aa 77 03 02 <gesture> 00` and the
gesture is now unassigned. So a failed write is destructive, not inert — save the map
first and restore in the same connection. This cost the left button its ANC/ambient
binding for ~1 s while `07` was tried.

**Which actions each gesture accepts, measured by trying them** (the unassigned
gestures already hold `00`, so a refusal there costs nothing):

| gesture | accepts | refuses |
| --- | --- | --- |
| `06` LEFT_TAP | `04` talkthru · `05` next · `06` prev · `09` anc+ambient · `0a` play/dismiss-VA · `0b` anc-ambient · `0c` anc-off · `0d` ambient-off | `03` ambient · `07` anc · `08` play/pause · assistants |
| `08` LEFT_TRIPLE_TAP | nothing — all 14 tried were coerced to `00` | — |

⚠ **The permitted set is not a prefix or a range**: `07` ANC is refused where `0c`
ANC-off is taken, `03` ambient refused where `0d` ambient-off is taken, `08`
play/pause refused where `0a` play/pause-with-VA-dismiss is taken.

⚠ **RETRACTED — "every right-cup gesture refuses writes".** Written here hours earlier
on a sweep that tried `09` → `08` play/pause and got `00` back. The refusal was about
the ACTION, not the gesture: `aa 77 03 00 0a 06` — right double-tap → previous track —
was accepted and read back at 23:26. Right-cup gestures are writable. What is still
true is that left TRIPLE tap refused all fourteen actions tried.

⚠ **LEFT_TAP is the left cup's physical BUTTON.** The M2's left cup has no touch
surface at all — touching it everywhere does nothing, which read as "the write is
dead" until the button was pressed. The right cup is the touch panel.

### ✅ What the vendor app's own Gestures screen offers — 2026-08-17 23:21

Two tabs, and **neither offers a free choice of action**, which is the answer to why a
sweep meets so many refusals:

- **Action Button** (left cup) — three checkboxes deciding what the tap CYCLES through:
  Noise Cancelling ✅, Ambient Aware ✅, Off ☐. Double-tap is fixed at TalkThru. So the
  app never sends "left tap → next track"; it sends a cycle membership, which is what
  `0b` ANC_AMBIENT is.
- **Touch Panel** (right cup) — one dropdown with exactly two entries, *Playback & Voice
  Assistant Control* or *None*.

✅ **The Touch Panel bundle writes FOUR gestures at once**, read back immediately after
selecting it:

```
09 0a   right tap        → play/pause, dismiss VA   (NOT 08 play/pause)
0a 05   right double     → next track
0b 06   right triple     → previous track
0e a1   right hold       → activate native voice assistant
```

⚠ **`a1` is in no SDK array.** `values_Action` runs `54`–`60` for the assistants and
this is not among them — but the app's own `product_gesture_config.json` lists
`activateNativeVoiceAssistant` as `0xA1` (and `eQOnOff` as `0xC8`), so the JSON and the
device agree and the array is simply not the whole space. ⚠ It also means this file's
`0e`–`16` and `54`–`60` corrections were both wrong in the same way: derived from a
table rather than from a device.

⚠ **The app cannot express the destructive case**, which is why it needs no warning: it
never offers an action the device would refuse, so it never sees a binding coerced to
`00`. Anything with a free action list — ours — has to handle that itself.

✅ **Ours does, since 2026-08-25.** `Drivers.JblBes.writeGesture` writes the previous action
back when the device coerces one to `00`, and `GestureWrite` keeps "refused, put back" apart
from "refused, and the restore failed too". Driven on hardware: the left button refuses `07`
while holding `0b`, and the map read back byte-identical afterwards.

```
gesture  00 L-whole 01 R-whole 02/03 L-swipe fwd/back 04/05 R-swipe fwd/back
         06/07/08 L tap/double/triple   09/0a/0b R tap/double/triple
         0c/0d L hold/double-hold       0e/0f R hold/double-hold
         10 balance dial  11 volume dial  12/13 mic button short/long
         ⚠ L-all/R-all/ALL are 03/02/01 — they COLLIDE with the swipes
action   00 default 01 vol+ 02 vol- 03 ambient 04 talkthru 05 next 06 prev
         07 anc 08 play/pause 09 anc+ambient 0a play/dismiss-VA 0b anc-ambient
         0c anc-off 0d ambient-off
         60/5f cancel/talk default assistant   5e/5d/5c Google
         5b/5a Alexa   59/58 Xiaowei
         57 game-chat balance  56 volume  55 mic mute  54 LED
```
⚠ **The tail of both tables was wrong here twice, and "ordinal IS the wire value" is
why.** It holds only for the identity prefix — `values` is identity to `0f` and
`values_Action` to `0d`. After that `values` maps LEFT_ALL/RIGHT_ALL/ALL to
`03`/`02`/`01` and the dials to `10`–`13`, and `values_Action` runs **downward from
`60`**, so the assistants are `54`–`60` and not the `0e`–`16` this file claimed in its
own previous correction. Read the arrays, do not count the enum.

⚠ **THREE actions are volume, not two: `01` vol+, `02` vol-, and `56` VOLUME_CONTROL.**
The third only became visible once the real table was read. A gesture write can bind a
button to a volume change, which is the one thing this repo will not do casually, so
these three stay out of any sweep and out of any writer.

⚠ The `ff` in `aa 77 02 01 ff` is a read sentinel with no entry in `values`;
`GestureCmd.isReset` recognises `fd`/`fe`/`ff`. The ordinal table does not reach it.
```
aa 21 01 <30..3d>   EnumDeviceStatusType, ordinal + 0x30 — see the list above
aa 31  set ANC          aa 32  set ambient aware    aa 33  set auto-off
aa 40/41/42  named in the SDK, ⚠ none is the EQ path — aa a2 is, and is driven
aa 71/72  the OLD gesture set/read — aa 77 does both here, see Gestures
aa 91  ANC modes (`aa 91 01 11` reads)              aa 11  device info
aa 25  battery          aa 94  serial               aa 9b  multi-status
aa 74/75  ANC tuning    aa 81/82  smart switch      aa 95  factory reset
```
Replies are `aa <cmd+1>` (`aa 11` → `aa 12`), per `RetHeader`. Seen on the wire:
`aa 12` device info with the name and battery, `aa 22` status, `aa 94` the serial,
`aa 77` the gesture map, `aa a2` EQ as IEEE floats. ⚠ `aa b1 …` every four seconds is
`GetSetFeatureCmd` polling, **not a keepalive** — it is the bulk of any capture and it
carries writes too.

⚠ **Never sweep this protocol.** It has no Get operator; `aa 31`, `aa 33`, `aa 40`
and `aa 95` are writes, and `Sweep` deliberately cannot emit them.

## ✅ JLab ANC

On plain **RFCOMM SPP `00001101`** — not GATT, and not any of the framings its app
bundles.

```
write  c0 ff 00 | 46 03 00 <mode> 04 | 04 01 00 | <sum>
       mode  00 = off   01 = Noise Cancelling On   02 = Be Aware   (all driven)
reply  00 ff 01 | 47 01 00 | 01 00 47 00
```
`<sum>` is every preceding byte added up mod 256 — checked against both captured
frames (`…01 04 04 01 00 12` and `…02 04 04 01 00 13`). The device accepted a
frame with it omitted, so it does not appear to be verified; send it anyway.

Driven from our socket and confirmed in the vendor app's own UI, then restored to
Noise Cancelling On where it started.

✅ **The read, found 2026-08-16:**

```
read   c0 ff 00 | 44 00 00 | 01 00 | 04
reply  00 ff 01 | 45 03 00 | <mode> <a> <b> | 00 <sum> 00
       mode  00 = off   01 = Noise Cancelling On   02 = Be Aware
```
All three driven from this code and read back. ⚠ **The mode is the SEVENTH byte,
and `<a> <b>` are not constant** — they read `04 04` in either ANC mode and `00 00`
with ANC off, so a decoder keying on them is reading a different field.

⚠ **`00` = off is now measured**, not assumed. It could not be checked while there
was no read; with one it was driven and read back, whole payload `00 00 00`.

⚠ The reply's checksum does **not** follow the request's sum-mod-256 rule. It came
out **exactly 2 less** than that sum in all three states — consistent enough to be a
rule, and not one anybody has worked out, so the driver does not check it.

⚠ **The `47` reply is not a success signal.** A write with `mode = 03` drew the
identical `47` and changed nothing. Verify a JLab write by reading the state back,
never by the reply — which is now possible.

⚠ **Guessing the mode byte failed and measuring worked**, as with the Sony. The
values came from capturing the app twice, once per mode; `03` was invented and is
simply not a mode.

The device's periodic `00 ff 01 31 01 00 <L> <R> 04 00 02 00 00 <x>` broadcast
carries the two battery levels but **not** the ANC mode, and its last byte does not
follow the request checksum rule either. `c0 ff 00 30 00 00 01 00 f0` asks for that
same frame on demand.

⚠ **"The app appears to track mode locally" was WRONG, and disproving it is what
found the read.** The test is worth keeping because it needs no capture: set a mode
from *this* code, then launch `com.jlab.app` cold and look at what its UI draws. It
showed the mode the device was actually in, both ways round — so a read had to
exist, and the capture of that launch contained it. Reading the app's own behaviour
beat reading its bundled SDKs, again.

The rest of what the app asks on opening, all `c0 ff 00 <cmd> 00 00 01 00 <sum>`
and answered by `<cmd>+1` — ⚠ **a map of where to look, not a decode**, since only
`44`/`45` above has been read:

```
30 → 31  battery, the same frame as the broadcast      44 → 45  ANC mode  ← decoded
48 → 49  0b 00 03 78 78 5a 78 78 78 5a 78 78 78 06     4c → 4d  1b 00 …
50 → 51  02 00 …    58 → 59  01 00 …    62 → 63  04 00 04 04 04 04
66 → 67  01 00 …    70 → 71  1e 00 78 78 … (30 bytes)  76 → 76  echoes its own cmd
7a → 7a  01 00 …    7e → 7f  01 00 01 01 01 00
```

### How the JLab was found — it is a Realtek chip, and none of that mattered

`com.jlab.app` is a rebrand of QCY's app and bundles six chip SDKs (`airoha`,
`bes`, `bluetrum`, `jieli`, `qcywq`, `realtek`). **"JBuds Sport ANC" appears
inside `com.realsil.sdk.bbpro`** — Realtek's BBPro SDK — so that is this model's
platform, and the BES services it also exposes are a red herring.

Its GATT, measured with `probe.sh gattmap`:
```
0000fe2c  Fast Pair    …1234 …1235 …1237 write+NOTIFY, …123a read/write/NOTIFY
66666666  …77777777    write write-nr NOTIFY        BES OTA
01000100  …03000300    write write-nr               ← write here
          …02000200    NOTIFY                       ← replies here
```
Realtek's transport is `AA <type> <length: 2 LE> <payload>`, payload
`<cmdId: 2 LE> <params>`, from `TransportLayerPacket` (`SYNC_WORD = 0xaa`,
`HEADER_LENGTH = 4`). IDs are in `core/protocol/CommandContract` (`0x18`
GET_STATUS, `0x0c` INFO_REQ, `0x105` GET_LE_ADDR) and the `*Req` classes
(`0xc44`–`0xc46` ANC scenario, `0x2xx` EQ, `0x7xx` key mapping).

⚠ **A sixth SDK, checked 2026-08-23 and also a dead end.** `com.qcymall.qcylibrary`
is QCY's own layer — the app is a QCY rebrand, so this was the most promising place
left for the `c0 ff` framing. It is not there: the only thing in it that builds
frames is `wq/sdk`, whose `DeviceMutualMapper` wraps payloads in **`0x33`** header
and footer and is the OTA path. `c0 ff` appears nowhere in the APK. ⚠ Written down
so the next session does not spend the evening finding the same nothing — **the
JLab stays capture-only**, and it is the one device of the five with no offline
route.

⚠ **Every one of those leads was a dead end**, and they cost the most time of
anything here. `01000100` accepts writes and answers neither the BES `aa` protocol
nor Realtek `aa <type> <len:2 LE>` frames at any `type`; QCY's own `ff <len>`
framing drew nothing either. The 14-byte thing that *does* arrive on both SPP and
GATT is an unsolicited periodic broadcast, not a reply — it turned up in different
packets' windows, which is what gave it away.

**What worked was capturing the app**, exactly as with the JBL: one bugreport per
mode change, then diff the two frames. Thirty minutes of reading SDKs was beaten
by two taps and a snoop log.

⚠ **The JLab advertises no name**, and its Fast Pair "BLE address" (`03 02`)
is *not* what it advertises under — that address never appears in a scan. Match it
on the stable `21 55 35 33` run inside its `fe2c` service data instead.

⚠ Neither device is identifiable by UUID (JBL has none unique, JLab only silent
ones), so `Channels.kt` names both by device name and says so.
`com.harman.ble.jbllink` is the speaker app; headphones are `jbl.stc.com`.

## The vendors' own apps

Every device's protocol was read out of its vendor app, and each capture starts by
launching one. They were scattered through this document and **Bose was missing
entirely**, so here they are in one place:

| device | app package | app name |
|---|---|---|
| Bose QC45 | `com.bose.bosemusic` | Bose Music |
| Bose QC35 | `com.bose.monet` | Bose Connect |
| Sony WH-1000XM4 | `com.sony.songpal.mdr` | Sony \| **Sound Connect** (was Headphones Connect) |
| JBL Tour One M2 | `jbl.stc.com` | JBL Headphones |
| JLab JBuds Sport ANC 4 | `com.jlab.app` | JLab |

⚠ **Two packages per vendor, and the wrong one looks plausible.** `com.bose.monet`
drives the QC35 and *will not* see the QC45; `com.harman.ble.jbllink` is JBL's
**speaker** app, not the headphone one. Both mistakes cost a capture that recorded
nothing and read as "the device does not answer".

⚠ **These are due to be uninstalled** once this app replaces them (Pippijn,
2026-08-16), so coexisting with them is explicitly NOT a design goal — see
`Leases`. Keep the APKs (`~/.cache/volume-apks`) regardless: they are the reference
for every byte here, and an uninstall does not remove the need to re-read them.

## Capturing

1. Developer options → **Enable Bluetooth HCI snoop log**, then cycle Bluetooth.
   Cannot be set from adb (SELinux denies `setprop`; no `cmd bluetooth_manager`
   subcommand; the `bluetooth_btsnoop_default_mode` global is ignored on A17).
   ⚠ **`getprop` on it is also DENIED and returns empty** — empty ≠ disabled.
   Confirm via logcat: `SnoopLogger: Snoop Logs full mode enabled`.
2. Drive the app: `adb shell monkey -p <pkg> -c android.intent.category.LAUNCHER 1`.
   Headphones must be linked (`ACL BR/EDR:Y`) or the capture is empty.
3. `adb bugreport br.zip` → `FS/data/misc/bluetooth/logs/btsnoop_hci.log`.
   ⚠ **It ROTATES.** Extract `.log` *and* `.log.last` and `mergecap` them, or the
   channel setup is missing and Wireshark cannot dissect RFCOMM — traffic then
   looks *absent* rather than undissected.
4. ```
   tshark -r merged.pcapng -Y 'btrfcomm.dlci==0x10' -e btspp.data -e data.data
   ```
   Find the DLCI by counting frames per `btrfcomm.dlci` first; the vendor channel
   is rarely the busiest and `0x18`/HFP will mislead. ⚠ Query **both** payload
   fields — some channels dissect as SPP, others raw.
5. ⚠ Some frames stay generic `L2CAP Connection oriented channel` even merged.
   They are RFCOMM: strip `[addr][control][len(1-2)][credit if ctrl=0xff]` by
   hand. `47 ef 13 | 00 01 03 05 "1.1.0" | 51`.

A **live snoop socket** exists on device port 8872 (`adb forward`), streaming
valid btsnoop, but it dropped our connection repeatedly; the bugreport was
reliable.

## Reading the vendor APKs

Cheaper than capturing, and it gives the whole command table rather than the few
commands an app happened to send. APKs are in `~/.cache/volume-apks` (513 MB,
outside the repo).

```bash
nix run nixpkgs#apktool -- d -r -f jbl.stc.com.apk -o jbl-smali   # -r skips resources
```
⚠ **Use apktool, not jadx.** `nix run nixpkgs#jadx` loads the dex and then hangs
without writing a file (both `-d` and `--single-class`); apktool baksmalis all
four dex in ~2 min. Smali is verbose but the constants read straight off:
`.array-data` blocks give byte tables, and `-0x56t` is `0xaa`.

⚠ **An enum's ORDINAL is not its wire byte, and this repo has been caught three
times.** A vendor enum that carries a protocol byte declares it as its own
constructor argument, and the two agree for a prefix before diverging — which is
exactly what makes counting believable. `scripts/smali_enum.py` reads the argument
and prints `byte=NAME` for a file; the JBL's gesture actions, Sony's whole command
table and Bose's block map all came out of it.

⚠ **How readable each vendor's APK is does not follow how new it is.** `jbl.stc.com`
has one named class per command; `com.bose.monet` (Bose Connect, the OLDER app)
ships the BMAP tables completely unobfuscated as `io.intrepid.bose_bmap`; and
`com.bose.bosemusic`, which drives the newer QC45, is obfuscated to `BX`/`Og0` and
gives nothing. `com.jlab.app` bundles six chip SDKs and the one that matters was a
dead end — that device was decoded by capture. So try the sibling app before
concluding a vendor cannot be read.

Route in by strings first — `strings classes*.dex | grep -i anc` found
`com.harman.bluetooth.reqimp.CommandHeader` in one step.
