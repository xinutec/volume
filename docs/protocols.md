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
operator: 01 Get · 02 SetGet · 03 Status · 04 Error · 05 Start · 06 Result · 07 Processing
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
in v2/table1 alone, each a plain enum whose ordinal *is* its byte. Subsystems run
in blocks of ten: `GET_CAPABILITY RET_CAPABILITY GET_STATUS RET_STATUS SET_STATUS
NTFY_STATUS GET_PARAM RET_PARAM SET_PARAM NTFY_PARAM`.

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
→ aa 21 01 34   ← aa 22 02 34 00        ⚠ NOT the EQ preset — unidentified
→ aa 21 01 35   ← aa 22 02 35 df        "multi-AI" per the SDK; `df` undecoded
→ aa 21 01 36   ← aa 22 02 36 00        BT connection status
→ aa 21 01 37   ← aa 22 02 37 00        OTA
→ aa 21 01 38   ← aa 22 02 38 01        ⚠ was called Auto Play & Pause — see below
→ aa 21 01 39   ← aa 22 02 39 01        TWS
→ aa 21 01 3a   ← (nothing)             ⚠ PersoniFi: this model does not answer
→ aa 21 01 30   ← aa 22 0d 30 01 00 ff 00 df 01 00 01 01 00 1e 00     all of them
→ aa 91 01 21   ← aa 91 09 22 01 01 04 07 05 01 a1 07     ANC capability, undecoded
```
The whole sweep, 2026-08-16 21:36. ⚠ The `30` bundle holds the same values but its
field ORDER is not established — `01 00` opens it and `00 1e 00` closes it, which fits
`31 32 … 33`, and the middle does not line up with a plain concatenation. The per-field
reads are unambiguous and cheaper, so nothing depends on decoding the bundle.

⚠ **"`38` is Auto Play & Pause" was published here and is now doubtful.** It rested on
one point: `38` read `01` while the app's switch showed on. Driving that switch two
hours later sent **`aa 35 01 <on>`**, twice, matching off and on — and `31`/`32`/`33`
all mirror between setter and status field, which would make the status field `35`,
not `38`. But `aa 21 01 35` reads `df`, which is not a boolean.
So: the SETTER is measured, the status field is not settled, and `01` is a value most
fields hold. ⚠ A single agreeing byte is not a mapping — that is what this cost.

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
getter here, as it does on all four vendors. ⚠ `1e` = 30 was never varied by us, so the
units are still "minutes, unverified"; only the on/off byte is measured.

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

⚠ **`aa 21 01 34` is NOT the EQ preset**, though it was written down as one. It read
`00` before selecting JAZZ and `00` after. A status field that does not move when the
setting moves is about something else.

⚠ **`aa 40 01 01` drew nothing and changed nothing.** `aa 40`/`aa 41`/`aa 42` are
named in the SDK tables but none of them is the path the app uses; `aa a2` is.

### ⚠ What the app has, and what we have — 2026-08-16

Every row of the vendor app's device screen, read off it top to bottom, against the
status sweep taken minutes later. **Three of twenty-two are driven by us; eleven are now decoded.** Written down
because "is it all understood?" could not be answered before without opening the app,
and answering it from what `docs/` happened to mention would have flattered us.

| the app's row | wire | us |
| --- | --- | --- |
| battery % | in `aa 12`, and `aa 25` | — |
| ⏻ power off | ? | — |
| Ambient Sound Control master switch | ✅ `aa 91 01 13` | — |
| Noise Cancelling / Ambient Aware / TalkThru | `aa 91` | ✅ r/w |
| Customize ANC | `aa 74`/`aa 75`? | — |
| Personi-Fi | `3a` **answers nothing** | — |
| Equalizer | `aa a2` | ✅ r/w |
| Low Volume Dynamic EQ | ✅ `aa 9e` | — |
| Spatial Sound + Movie/Music/Game | ✅ `aa 9d`, ⚠ modes silent | — |
| Gestures | `aa 77` reads, `aa 71` sets | 👁 read, #970 |
| Smart Talk + 5/15/20 s | ✅ `aa 9f`, seconds | — |
| VoiceAware + Low/Mid/High | ✅ `aa 98`, level unknown | — |
| Smart Audio & Video + Audio/Video | ✅ `aa 81`/`82`/`83` | — |
| SilentNow | ? | — |
| Auracast | ? | — |
| LE Audio | ? | — |
| Auto Play & Pause | ✅ `aa 35 01 <on>` | — |
| Personal Sound Amplification | ? | — |
| Left / Right Sound Balance | ✅ `aa a8` | — |
| Voice Assistant | `35` = `df`? | — |
| Voice Prompts (language) | ? | — |
| Max Volume Limiter | ? | — |
| Auto Power Off + 30 min/1 hr/2 hr | ✅ `33`, minutes proven | ✅ on/off only |

⚠ **`?` means nobody has looked**, not that it is hidden. Each unknown is one capture
of the app touching that one control, and the method in `docs/captures.md` applies
unchanged; the reason there are twenty of them is that the work stopped when ANC
worked. ⚠ Several rows are toggles that are OFF right now — Spatial Sound, Smart Talk,
VoiceAware, LE Audio, balance — so any of them could be the unidentified `34`, and
attributing it needs one of them varied, not more reading.

### ✅ Eight more, driven through the vendor app — 2026-08-16 21:44 and 22:04

Two runs of `scripts/drive-jbl.sh`, each change followed immediately by its inverse.
Most of these share one convention, the same as `aa a2`: `aa <cmd> <len> <operator>
<payload…>`, operator **`00` set · `01` get · `02` status**.

| the app's row | set | get | status |
| --- | --- | --- | --- |
| Ambient Sound Control, master off | `aa 91 01 13` | `aa 91 01 11` | `aa 91 07 12 …` all three slots `00` |
| Low Volume Dynamic EQ | `aa 9e 02 00 <on>` | `aa 9e 01 01` | `aa 9e 02 02 <on>` |
| Spatial Sound | `aa 9d 03 00 <on> <mode>` | `aa 9d 01 01` | `aa 9d 03 02 <on> <mode>` |
| Smart Talk | `aa 9f 03 00 <on> <seconds>` | `aa 9f 01 01` | `aa 9f 03 02 <on> <seconds>` |
| VoiceAware | `aa 98 03 00 02 <on>` | `aa 98 01 01` | `aa 98 03 02 02 <on>` |
| Auto Play & Pause | `aa 35 01 <on>` | ? | ⚠ see above |
| Left / Right Sound Balance | `aa a8 05 00 01 <on> 02 64` | `aa a8 01 01` | `aa a8 05 02 01 <on> 02 64` |
| Smart Audio & Video | `aa 81 08 00 01 35 00 <v> 00 ff ff` | `aa 82 00` | `aa 83 08 …` |

✅ **Smart Talk's timeout is in SECONDS** — `05`/`0f`/`14` are exactly the app's 5 s,
15 s and 20 s. Four points, so this is measured rather than inferred from one.

✅ **Smart Talk was then seen doing its job unprompted**: at 21:48:36 the headphones
volunteered `aa 91 07 12 01 00 02 00 03 01` — into TalkThru — and `…01 01 02 00 03 00`
back twenty seconds later. Notifications, not commands; it is the feature working.

⚠ **Smart Audio & Video's payload is located, not decoded.** The byte that moves is
`e6` when the switch is turned off and `96` when it is turned on. That is not a
boolean, and 230/150 look like milliseconds of latency, which is a guess and is
written here as one.

⚠ **VoiceAware's `02` is unexplained** — the Low/Mid/High slider was never dragged,
so `02` may be that level or may be a field id. One drag would settle it.

⚠ **Spatial Sound's Movie / Music / Game buttons send NOTHING when tapped.** Confirmed
twice, the second time with the three taps landing on one row at identical
coordinates, so this is no longer a suspected mis-tap. The mode byte travels with the
on/off write instead. ⚠ It is still NOT established that the buttons reach the device
at all: tapping Movie and *then* toggling would show a mode byte other than `01`, and
that has not been done.

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
✅ `aa 13 01 00 01` → a 37-byte table of `<id> <value> 00` triples, which is the
shape of a capability list and is undecoded.

⚠ **This sweep is the cheapest lead left.** Every getter above has a setter by the
mirror rule, and the ones still unnamed — `93`, `a5`, `90`, `b0`, `9b`, `a0` — are
that many of the app's remaining rows.

### Gestures — read and decoded

```
→ aa 77 02 01 ff                        ff = ALL; or one GestureType byte
← aa 77 11 02  06 0b  07 04  08 00  0c 00  09 00  0a 00  0b 00  0e 00
```
Pairs of `<gesture><action>`, so as shipped: left tap → ANC/ambient cycle, left
double tap → TalkThru, and the other six unassigned. Both tables are the app's
own, indexed by enum ordinal through `CmdBase.values` / `values_Action`:

```
gesture  00 L-whole 01 R-whole 02/03 L-swipe fwd/back 04/05 R-swipe fwd/back
         06/07/08 L tap/double/triple   09/0a/0b R tap/double/triple
         0c/0d L hold/double-hold       0e/0f R hold/double-hold
         fd L-all  fe R-all  ff all     10 balance dial  11 volume dial
action   00 default 01 vol+ 02 vol- 03 ambient 04 talkthru 05 next 06 prev
         07 anc 08 play/pause 09 anc+ambient 0a play/dismiss-VA 0b anc-ambient
         0c anc-off 0d ambient-off       a0…ac assistant actions
```

⚠ **`01` and `02` are volume up and down.** A gesture write can therefore bind a
button to a volume change — the one thing this repo will not do casually. Read
gestures freely; leave the writes until there is a reason.
```
aa 21 01 <30..3a>   status: 30 all · 31 ANC · 32 ambient · 33 auto-off · 34 ⚠ NOT EQ
                    35 multi-AI · 36 BT connection · 37 OTA · 38 auto play/pause
                    39 TWS · 3a PersoniFi
aa 31  set ANC          aa 32  set ambient aware    aa 33  set auto-off
aa 40/41/42  named in the SDK, ⚠ none is the EQ path — aa a2 is, and is driven
aa 71  set gesture      aa 72  read gesture         aa 77  gesture batch
aa 91  ANC modes (`aa 91 01 11` reads)              aa 11  device info
aa 25  battery          aa 94  serial               aa 9b  multi-status
aa 74/75  ANC tuning    aa 81/82  smart switch      aa 95  factory reset
```
Replies are `aa <cmd+1>` (`aa 11` → `aa 12`), per `RetHeader`. Seen on the wire:
`aa 12` device info with the name and battery, `aa 22` status, `aa 94` the serial,
`aa 77` the gesture map, `aa a2` EQ as IEEE floats. `aa b1 …` every four seconds
is a keepalive, and it is the bulk of any capture.

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
| Sony WH-1000XM4 | `com.sony.songpal.mdr` | Sony \| Headphones Connect |
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

Route in by strings first — `strings classes*.dex | grep -i anc` found
`com.harman.bluetooth.reqimp.CommandHeader` in one step.
