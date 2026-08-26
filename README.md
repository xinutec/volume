# volume — headphone control over the vendor channels

Everything in `docs/` was measured against the real headphones: the ANC wire formats
on 2026-08-15, the app's own behaviour and the Sony/Bose settings captures on
2026-08-16. **Re-measure; firmware moves things.**

## ⚠ This app is a replacement, not an addition

A **pure Kotlin native app** — Pippijn's call, #785 — and the vendor apps (Bose
Music, Sony Headphones, JBL, JLab) are to be **uninstalled** once it replaces them
(2026-08-16). Two consequences, and both change what counts as done:

- **Coexisting with them is not a goal.** Never trade away responsiveness to leave a
  channel free for an app that is going to be deleted — nothing is waiting for it,
  so the only cost of holding a channel is power and the only cost of dropping it is
  a reconnect its owner feels. See `Leases`.
- **Anything they still do exclusively is something Pippijn loses on uninstall
  day.** The "Next" list below is therefore parity work, not exploration.
  ✅ **The screen now shows the settings too** — expand a card and it reads what that
  pair has: EQ or tone, multipoint, power off, sound quality, the button. Driven from
  the UI and verified on the QC45 (tone → Bass boost → back to Flat, each read back
  from the device). ⚠ **A setting the device refuses is drawn as a VALUE, never a
  control** — the XM4 reports multipoint and its [CUSTOM] button and then ignores
  writes to both, so a switch there would flip and spring back.

**The app** is `VolumeActivity`: every *connected* headphone it knows how to drive,
its model once identified, and its ANC modes as chips. It follows the radio while on
screen, so a pair switched on appears without anyone touching it
(`docs/liveness.md`). **ANC is also on a Quick Settings tile and a home-screen
widget** — `AncTileService`, `AncWidget`, both going through `Tap`.

⚠ **Control channels are owned per PROCESS, not per screen** (`Sessions`). The tile
and the app drive the same headphones, and a device accepts one control channel, so
a second one simply fails. They release on backgrounding — but ⚠ **in split screen
`onStop` never fires**, because both halves stay resumed, so there the idle lease is
the only thing that lets go. That is the arrangement on this phone.

The **probe** (`ProbeService`, `probe.sh`) stays — it is the only tool that can
investigate a device the app cannot drive, and both share `:protocol`, so a byte
fixed in one is fixed in both.

## Two modules

```
:protocol   every byte of the five wire formats. NO Android dependency, so
            `./gradlew :protocol:test` runs on any JVM — no phone, no pairing,
            no headphones switched on. This is where most of the code lives.
:app        only what genuinely needs a device: RFCOMM sockets, GATT, LE
            scanning, permissions, screen.
```
The line is drawn at **I/O, not at "app vs library"**: `Transport` is declared in
`:protocol` and only implemented in `:app`, which is what lets `DriversTest`
replay recorded transcripts through the real driver code off-device.

⚠ **That split flatters itself if you stop there.** The byte layouts are the easy
part and were nearly right first time; every wrong conclusion in this repo came
from *session* behaviour — greetings answering questions never asked, writes that
need a transaction, reads that need an ack. So the fixtures are real captures, and
`Confirmation` exists so a caller cannot mistake a reply for a result.

⚠ Repo is PUBLIC and carries the headphones' MACs — Pippijn's call, 2026-08-15.

## ANC driven on all five. Two transports.

| Device | Address | Channel | Protocol | ANC |
| --- | --- | --- | --- | --- |
| Bose QC45 | `E4:58:BC:3E:9D:AA` | RFCOMM, SPP `00001101` | Bose | ✅ r/w |
| Bose QC35 | `4C:87:5D:CC:A0:23` | RFCOMM, SPP `00001101` | Bose | ✅ r/w |
| JBL Tour One M2 | scan for it | **GATT**, `65786365-…0000` | BES `aa` | ✅ r/w |
| Sony XM4 | `80:99:E7:F9:D0:61` | RFCOMM, `96cc203e-…` | Sony framed | ✅ r/w |
| JLab JBuds Sport ANC 4 | `EC:9A:0C:E0:D2:96` | RFCOMM, SPP `00001101` | JLab `c0 ff` | ✅ r/w |

```
QC45   1f 03 05 02 <slot> 01              slot 0=Quiet 1=Aware 2=Home 3=unnamed
QC35   01 06 02 01 <value>                00 Off · 01 High · 03 Low
JBL    aa 91 07 10 01 <anc> 02 <amb> 03 <talkthru>     read with aa 91 01 11
Sony   68 02 <on> 02 <nc> 01 00 <ambient>               read with 66 02
JLab   c0 ff 00 46 03 00 <mode> 04 04 01 00 <sum>      00=off 01=NC on 02=Be Aware
       read with c0 ff 00 44 00 00 01 00 04 → 45 03 00 <mode> …
```
All driven from our own socket. The Bose announced each mode aloud; the JBL and
the Sony were confirmed by an independent read-back and against the vendor app's
screen, and each device was left in the mode it started in.

⚠ **Two channels that answer are not control channels.** `df21fe2c` is Google
Fast Pair — battery, model, firmware, and the LE address, but no ANC or EQ — and
`931c7e8a` answers a fourth framing on four of the five devices. An earlier pass
took Fast Pair for JBL/JLab's own protocol and built a command map out of its
acknowledgements. `docs/protocols.md` has the correction.

## Next — parity work, in four states

⚠ **Read the state before picking one up.** *Captured* means the bytes are on disk
but nobody has looked; *decoded* means the frames are written down; *written* means
there is driver code and a replay test; *driven* means **this code has changed a
setting on a headphone**. On the XM4 that is now every decoded setting bar multipoint,
which the device refuses to everyone.

⚠ The gap between the last two is the one that matters, and it is where every wrong
conclusion in this repo has lived. A replay test proves the driver agrees with the
vendor app's bytes. It cannot show the device answers *us* — the Sony needed a
session opener nobody knew about, the Bose refuses an untransacted write on one
function and accepts it on three others, and both look identical to a green suite.

**Driven** = this code changed it on a headphone and read it back. Anything else is
named in a task; the frames are in `docs/`, not repeated here.

| | QC45 | QC35 | Sony XM4 | JBL M2 | JLab |
| --- | --- | --- | --- | --- | --- |
| ANC | ✅ | ✅ | ✅ | ✅ 4 modes, +TalkThru | ✅ |
| EQ / tone | ✅ 3 bands | — | ✅ preset + 6 bands | ✅ 10-band curve | — |
| Multipoint | ✅ | — | ⛔ refused | — | — |
| Auto power off | ❓ #966 | ✅ never / 5 / 20 / 40 min / 1 / 3 hr | ✅ | ✅ + 30 min / 1 hr / 2 hr | — |
| Sound quality | — | — | ✅ | — | — |
| Battery | — | ✅ read | ✅ read | ✅ read | — |
| Voice guidance | — | — | ✅ | — | — |
| Codec | — | — | ✅ read | — | — |
| Power off | — | — | ✅ | ✅ asks first | — |
| DSEE / upscaling | — | — | ✅ | — | — |
| Speak-to-Chat | — | — | ✅ | ✅ r/w (Smart Talk) | — |
| Pause when removed | — | — | ✅ | ✅ r/w (Auto Play) | — |
| Speak-to-Chat detail | — | — | ✅ sensitivity · passthrough · mode-out | — | — |
| Touch sensor panel | — | — | ✅ | — | — |
| Focus on Voice | — | — | ✅ ⚠ ambient only | — | — |
| Button / gestures | ✅ | ❓ #1188 | ✅ ⚠ needs the alert answered | ✅ editable, restores a refusal | — |
| Self voice (sidetone) | — | ✅ off / low / medium / high | — | — | — |
| Voice prompts | — | ✅ + language, 13 the device offers | — | ✅ read | — |
| Rename | — | ✅ | — | — | — |
| Paired devices | — | ✅ list · connect new · forget | — | — | — |

⚠ **Speak-to-Chat spent an hour in this table as "sent, not taken" and it was wrong.**
The write was malformed: it reads with one type table and writes with another, alone among
these settings. The XM4 acked the bad frame and did nothing, which is indistinguishable
from a refusal until you look at the vendor SDK. `docs/sony-settings.md` has it.

⚠ **"Refused" means the VENDOR APP FAILS TOO, and only multipoint has earned it.** The
XM4's [CUSTOM] button is not refused — Sony's app changes it and we cannot, which is #965
and is an asymmetry, not a wall. Adaptive Sound Control is not refused either: its on/off
turns out to be **app-side**, so there is no device toggle to refuse. ⚠ Calling either of
them device-blocked was an overclaim made on 2026-08-23 and corrected the same evening.

⚠ **The QC35 column was five rows short until 2026-08-26**, having been "ANC only" since it
was written. Everything above was decoded and driven that day, each label checked against
Bose Connect's own screens rather than against this repo's reading of the bytes — which is
how the ANC row came to be wrong at **all three** of its values for months. Only Music
Share (`04 0a`/`04 0b`, needs a second Bose) and the Action button are outstanding.

⚠ **This table is what is DRIVEN, not what the devices have.** The JBL's own app has
twenty-three device controls and twenty are in the app — thirteen writable, seven
read-only. ⚠ Not all twenty are rows above: this table lists what is driven, so the
read-only ones live in `docs/protocols.md`'s inventory instead. The gap is #974.

✅ **The XM4 has now been ASKED, 2026-08-23.** `06 00` returns its own supported-function
list — 22 entries, count byte matching, every one a legal `FunctionType`. Six new rows
were read from it and **six were confirmed against Sound Connect's own screens**; the
absences retired six leads #1097 had listed as worth trying. `docs/sony-settings.md`.
⚠ **`✅ read` above means read and confirmed, NOT driven** — battery on both pairs, and the
Sony's codec. Nothing writes them, and for the codec nothing can: it is negotiated between
the two ends, so what an owner actually chooses is sound quality.

⚠ **The Bose pair has a NAMED surface and no audit**, which are different things.
As of 2026-08-23 the Sony and Bose command spaces are read out of the vendor APKs —
every block, function and value enum, in `docs/sony-settings.md` and
`docs/bose-settings.md` — so the rows below "EQ / tone" are no longer unknown, they are
unasked. ⚠ Nothing there has been met on the wire; a name from an APK is a claim about
the vendor's app until a device answers it. Both devices will also **list their own
features on request** (Sony `06`, Bose `00 04`), and neither has been asked.

⛔ **Refused is not broken.** The XM4 acks `d8 d2 01 01` and `f8 06 01 31` and ignores
both; the QC45 accepts the equivalents from this code. Multipoint fails for Sony's own
app too, so that one is the device's rule — the button works for the app and not for
us, which is the lead in #965. ⚠ Never merge the two.

⚠ **A "preset" is the app's on Bose and JBL** — three band values, or a ten-band curve
of floats — and the device's on Sony, where it is an opaque id. ⚠ The JBL's curve does
carry a *table id* beside the ten gains, but it is sent together with them and never
alone, so it is not a preset in Sony's sense and nothing establishes which the device
obeys.

**Open:** #1185 the QC45's ANC labels, ⚠ **P2 — the QC35's were wrong at all three values**
· #966 Bose auto-off on the QC45 · #974 the three JBL rows still outside the app ·
#935 disconnect, the one connections verb still unattested · #1038 the probe's
decode-and-print · #1098 the Bose BMAP inventory, QC45 half · #1154 a settings read that
drops a row · #1191 a card open still re-reads everything two or three times.

## Probe

```bash
./probe.sh list                          # bonded + detected channel/protocol
./probe.sh scan                          # what is advertising over LE, right now
./probe.sh gattmap <name>                # every GATT service, char and property
./probe.sh free                          # force-stop vendor apps
./probe.sh send|raw <mac> <uuid> <hex>   # one packet, one socket
./probe.sh seq  <mac> <uuid> <hex,hex>   # one socket — THE RFCOMM WRITE TOOL
./probe.sh gatt <name|addr> <hex,hex>    # one LE connection — THE GATT WRITE TOOL
./probe.sh sweep <mac> <uuid> <proto> [blocks] [fns]
```

The app's own stack, end to end — registry → transport → driver → read-back:
```bash
./probe.sh anc "JBL TOUR" [ANC|AMBIENT|OFF]
```
Verified on all five, 2026-08-15: Sony and JLab over RFCOMM, JBL over GATT with an
LE scan, and a **renamed QC35 identified by asking it** — its record carries
nothing but standard and shared UUIDs, so `Registry.identifyBose` reads `01 06`,
which is ANC on the QC35 and unsupported on the QC45.
Output: `adb logcat -s volume-probe`. `VOLUME_ADB_DEVICE` overrides the target.
`--ez reconnect true` for Fast Pair sweeps.

### Traps, each of which cost a wrong conclusion

- ⚠ **A connect failure while a vendor app is running is not a protocol result.**
  Close it first (`./probe.sh free`). Keep them installed until parity — captures
  need them; `docs/protocols.md` says which app drives which device.
- ⚠ **`send` cannot write.** The Bose ANC edit is transactional (operator-`05`
  Start, then the change). The orphaned write is *accepted* and the unchanged state
  echoes back — reads exactly like a wrong field. Use `seq`. ⚠ But **not every Bose
  write is transactional**: EQ, multipoint and the Action button each took a plain
  `02` Set. Generalising from the one function cost nothing yet only because
  nothing had been sent to the other three.
- ⚠ **Never test a write against the value already held.** A no-op is
  indistinguishable from a broken command; the QC45 selection was found,
  dismissed as inert, then shown correct.
- ⚠ **Response windows differ ~4× by vendor.** JBL at 400 ms: 0/144 answered. At
  1500 ms: 25/32. Too short reads as "implements nothing".
- ⚠ **A device that talks on connect will answer a question you never asked.**
  `send` reads the greeting; `seq` drains it first. `aa 11` looked answered on the
  Fast Pair channel — that was the greeting, and `seq` showed the truth.
- ⚠ **An answering socket is not the right socket.** Both `df21fe2c` and
  `931c7e8a` reply on the JBL and neither is its control channel.
- ⚠ **A control channel need not be RFCOMM.** The JBL's is GATT. Hours went into
  "why is SPP silent" before a capture showed the app opens no socket at all.
- ⚠ **LE addresses rotate**, so a scan is not optional and a noted address goes
  stale. Connect through the scanner's device object with `autoConnect = false`:
  a string address is assumed public, and an accept-list connect waits forever for
  a private address to reappear. Both failures look identical — status 135 after
  the full timeout, which reads like a protocol fault.
- ⚠ **A BLE device is several sightings**, and only one of them carries the name.
  Merge them; keeping the first hid the JBL behind "(no name)".
- ⚠ **"Answers nothing" and "has nothing to answer" look identical**, and this has
  now cost four findings. The Sony was being ignored for repeating a sequence byte.
  ⚠ **And the JLab was written up here as "genuinely has no read command" — which
  was wrong.** It has one; nobody had asked the right byte. Both rendered as
  "reports no mode". Only comparing against a state you already know tells them
  apart — which is exactly how the JLab's read was finally found: set a mode from
  this code, launch the vendor app cold, and watch whose story its UI tells.
- ⚠ **Connected is not on-a-profile.** Presence comes from the A2DP and headset
  proxies, which populate *after* the ACL link, so a pair already connected when
  the app starts can be invisible with no ACL event to follow. Listen for the
  profile transitions too.
- ⚠ **A one-shot exchange cannot hold a protocol with state**, and this has now
  cost three separate wrong conclusions. Sony's `66 02` returns a bare ACK
  one-per-socket and real data inside a session that acks the device's frames —
  ten inquired types were written off before that was the difference.

## Hearing safety

Probe with reads; never prove a round-trip with a volume command; restore any
level touched. ANC mode is not volume — it cannot raise a level, which is why it
is the right thing to write first. `Sweep.kt` hard-wires operator/length to
Get/zero (tested) rather than taking them as parameters.

## Docs / build

`docs/protocols.md` — the wire formats, capture method, channel traps, and which
vendor app drives which device (⚠ each vendor ships two plausible ones).
`docs/bose-read-surface.md` — Bose surface, error taxonomy, how ANC was found.
`docs/liveness.md` — why the profile broadcasts, not ACL, keep the list live;
measured timings, and the traps in measuring it.
`docs/sony-settings.md` — Sony EQ, auto-off and multipoint frames.
`docs/bose-settings.md` — Bose EQ, multipoint and Action-button frames.
`docs/captures.md` — ⚠ WHAT WAS DONE AND WHEN for each capture, and the method
that works. A capture without its action log is a haystack.

## Tools

```
./deploy.sh              build + install. ⚠ Does NOT relaunch: the phone keeps
                         Volume in a split screen with the agent console — ⚠ **that
                         split is what holds the SCREEN AWAKE**, so breaking it costs
                         a real unlock rather than a tidy layout. `am start`
                         re-creates the task fullscreen and throws the
                         console out. `install -r` kills the process, so the app
                         comes back in place on the new build. --start to
                         foreground anyway.
./probe.sh               the #783 probe (ProbeService) — for devices the app
                         cannot yet drive.
scripts/watch-list.sh    does the screen follow the radio? Samples the radio and
                         the semantics tree on one clock, prints only on change.
scripts/shot.sh          screenshot just Volume's half of the split, cropped to
                         the window frame the window manager reports.
scripts/drive_jbl.py     drives the JBL VENDOR app while a capture runs, and puts
                         back whatever it moved. `--list` for the groups;
                         `--where LABEL` says where a tap would land WITHOUT
                         tapping, which is the one to reach for first.
scripts/smali_enum.py    reads a vendor enum's name → WIRE BYTE table out of
                         apktool's smali. ⚠ The byte is a constructor argument,
                         not the ordinal — counting the enum has produced three
                         wrong tables in this repo.
```
⚠ **A segmented label in the JBL app is inert** — `Movie`, `5s`, `Audio Mode` are
`clickable="false"` text, and the touch target is a sibling tile. Tapping the label
sends nothing, silently, and an empty capture that follows reads as a fact about the
headphones; that mistake reached `docs/protocols.md` twice. `--where` reports `tile`
or `LABEL — inert`, and VoiceAware's gradient bar is inert either way: it needs a
real drag, which this cannot do.

⚠ `adb logcat -s VolumeLive` is the app's own account of the same thing: every
broadcast it receives, what the profile proxies said at that moment, and every
channel it releases.

```bash
nix develop ~/Code/recall#android --command ./gradlew :app:testDebugUnitTest
./gate.sh                # from anywhere: /Users/pippijn/Code/volume/gate.sh
```
⚠ **Run the gate through `gate.sh`, not the raw `nix run`.** The underlying command is
relative on three counts, and from the wrong directory it fails as though the *flake*
were missing rather than the directory wrong. `gate.sh` cds to itself first and the
pre-commit hook calls the same script, so there is one definition of "the gate".
No flake of its own; SDK from recall's devshell, like `xinutec-infra/govee-android`.
