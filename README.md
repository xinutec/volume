# volume — headphone control over the vendor channels

Everything in `docs/` was measured against the real headphones on 2026-08-15.
**Re-measure; firmware moves things.**

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

## Next

1. **JBL EQ and auto-off writes.** Reads are all done (`docs/protocols.md`):
   status, gestures and the ANC capability answer. Writing is `aa 40` EQ preset,
   `aa 41` custom EQ, `aa 33` auto-off, each read back through `aa 21 01 3x`.
   EQ needs ears — a read-back proves the field moved, not that it sounds right.
2. **Sony EQ** — `50`–`5b` EQEBB, same session mechanism, `SONY_SEQ=1`.
3. **Bose multipoint** — `04 04`/`04 09` read the paired list + active device;
   writes untried.
4. **Bose EQ / auto-off / buttons** — among the 15 write-capable fns in
   `docs/bose-read-surface.md`.
5. **JLab reads.** ANC writes work, but no read command is known — its periodic
   broadcast carries battery, not mode. Capture the app opening its dashboard.

## ⚠ The open decision (#785)

2026-08-12 Pippijn settled: headphones go in **thoth's Angular UI**, one remote.
2026-08-15, asked fresh: **pure Kotlin app**. The second knowingly reverses the
first; tie-break deferred to the protocol work, which favours neither (the Kotlin
is identical either way). Native = QS tile + widget. Angular = one remote for
headphones *and* the Mac's CoreAudio + Picades, which cannot move to the phone
(`project_thoth`). **Pippijn's call — don't re-decide it silently.**

## Probe

```bash
./deploy.sh                              # build + install, Pixel 9 by MODEL
./probe.sh list                          # bonded + detected channel/protocol
./probe.sh scan                          # what is advertising over LE, right now
./probe.sh gattmap <name>                # every GATT service, char and property
./probe.sh free                          # force-stop vendor apps
./probe.sh send|raw <mac> <uuid> <hex>   # one packet, one socket
./probe.sh seq  <mac> <uuid> <hex,hex>   # one socket — THE RFCOMM WRITE TOOL
./probe.sh gatt <name|addr> <hex,hex>    # one LE connection — THE GATT WRITE TOOL
./probe.sh sweep <mac> <uuid> <proto> [blocks] [fns]
```
Output: `adb logcat -s volume-probe`. `VOLUME_ADB_DEVICE` overrides the target.
`--ez reconnect true` for Fast Pair sweeps.

### Traps, each of which cost a wrong conclusion

- ⚠ **One vendor app holds the channel exclusively.** A connect failure while one
  runs is not a protocol result. Keep them installed — captures need them.
  `com.harman.ble.jbllink` is the *speaker* app; headphones are `jbl.stc.com`.
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

`docs/protocols.md` — the wire formats, capture method, channel traps.
`docs/bose-read-surface.md` — Bose surface, error taxonomy, how ANC was found.

```bash
nix develop ~/Code/recall#android --command ./gradlew :app:testDebugUnitTest
nix run ../dev-lint#gate -- . gate.json
```
No flake of its own; SDK from recall's devshell, like `xinutec-infra/govee-android`.
