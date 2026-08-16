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
  day.** Today this app does ANC and nothing else. The "Next" list below is
  therefore parity work, not exploration — EQ, multipoint, auto-off and button
  assignment all live only in the vendor apps right now.

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

The **probe** (`MainActivity`, `probe.sh`) stays — it is the only tool that can
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
| JLab JBuds Sport ANC 4 | `EC:9A:0C:E0:D2:96` | RFCOMM, SPP `00001101` | JLab `c0 ff` | ✅ write |

```
QC45   1f 03 05 02 <slot> 01              slot 0=Quiet 1=Aware 2=Home 3=unnamed
QC35   01 06 02 01 <value>                00 / 01 / 03
JBL    aa 91 07 10 01 <anc> 02 <amb> 03 <talkthru>     read with aa 91 01 11
Sony   68 02 <on> 02 <nc> 01 00 <ambient>               read with 66 02
JLab   c0 ff 00 46 03 00 <mode> 04 04 01 00 <sum>      01=NC on, 02=Be Aware
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
setting on a headphone**. Only ANC is driven.

⚠ The gap between the last two is the one that matters, and it is where every wrong
conclusion in this repo has lived. A replay test proves the driver agrees with the
vendor app's bytes. It cannot show the device answers *us* — the Sony needed a
session opener nobody knew about, the Bose refuses an untransacted write on one
function and accepts it on three others, and both look identical to a green suite.

**Written, needs hardware** — code, replay tests against captured frames, no device
- **Sony EQ** — `SonyXm4.readEq`/`writeEq`/`bands`, `docs/sony-settings.md`.
  ⚠ Writing a custom *curve* (`58 01 <preset> <count> <levels>`) is structurally
  certain and unexercised: no band was dragged during the capture.

**Decoded, needs a driver** — the frames are exact and encoded in `:protocol` with
the capture as fixtures
- **Sony auto-off, multipoint** — `docs/sony-settings.md`. ⚠ The multipoint pair is
  the one asymmetric row in that file; re-capture before writing it.
- **Bose EQ, multipoint, Action button** — `docs/bose-settings.md`, `BoseSettings.kt`.
  ⚠ The presets are the *app's*, not the device's: three signed band values, no
  preset id on the wire.

**Captured, needs decoding** — `docs/captures.md` has the action log

*(nothing outstanding)*

**Not captured**
- **JBL EQ, auto-off, gestures.** Reads are done (`docs/protocols.md`): status,
  gestures, ANC capability. Writes are `aa 40` EQ preset, `aa 41` custom EQ,
  `aa 33` auto-off, read back through `aa 21 01 3x`. ⚠ EQ needs ears — a read-back
  proves the field moved, not that it sounds right.
- **Sony CUSTOM button** — attempted and **sent nothing** (the link was down); #955.
- **Bose auto-off** — not found in the app's device page; may not exist on the QC45.
- **JLab reads.** ANC writes work, no read command is known — its periodic
  broadcast carries battery, not mode. Capture the app opening its dashboard.

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
adb shell am start -n org.xinutec.volume/.MainActivity --es op anc \
  --es device "'JBL TOUR'" [--es mode ANC|AMBIENT|OFF]
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
- ⚠ **`send` cannot write.** Bose edits are transactional (operator-`05` Start,
  then the change). The orphaned write is *accepted* and the unchanged state
  echoes back — reads exactly like a wrong field. Use `seq`.
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
  now cost three findings. The JLab genuinely has no read command; the Sony was
  being ignored for repeating a sequence byte. Both rendered as "reports no mode".
  Only comparing against a state you already know tells them apart.
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
                         Volume in a split screen with the agent console, and
                         `am start` re-creates the task fullscreen and throws the
                         console out. `install -r` kills the process, so the app
                         comes back in place on the new build. --start to
                         foreground anyway.
./probe.sh               the #783 probe (MainActivity) — for devices the app
                         cannot yet drive.
scripts/watch-list.sh    does the screen follow the radio? Samples the radio and
                         the semantics tree on one clock, prints only on change.
scripts/shot.sh          screenshot just Volume's half of the split, cropped to
                         the window frame the window manager reports.
```
⚠ `adb logcat -s VolumeLive` is the app's own account of the same thing: every
broadcast it receives, what the profile proxies said at that moment, and every
channel it releases.

```bash
nix develop ~/Code/recall#android --command ./gradlew :app:testDebugUnitTest
nix run ../dev-lint#gate -- . gate.json
```
No flake of its own; SDK from recall's devshell, like `xinutec-infra/govee-android`.
