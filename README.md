# volume — headphone control over the vendor RFCOMM channels

Task #783's instrument, not the product (#785 is the app). Everything in `docs/`
was measured against the real headphones on 2026-08-15. **Re-measure; firmware
moves things.**

⚠ Repo is PUBLIC and carries the headphones' MACs — Pippijn's call, 2026-08-15.

## All five answer. Two vendors controlled.

| Device | MAC | Channel | Protocol | ANC |
| --- | --- | --- | --- | --- |
| Bose QC45 | `E4:58:BC:3E:9D:AA` | SPP `00001101` | Bose | ✅ r/w |
| Bose QC35 | `4C:87:5D:CC:A0:23` | SPP `00001101` | Bose | ✅ r/w |
| Sony XM4 | `80:99:E7:F9:D0:61` | `96cc203e-…` | Sony framed | framing only |
| JBL Tour One M2 | `28:6F:40:8A:D3:E4` | `df21fe2c-…` | Fast Pair | cmds known, unspoken |
| JLab JBuds Sport ANC 4 | `EC:9A:0C:E0:D2:96` | `df21fe2c-…` | Fast Pair | not started |

```
QC45   1f 03 05 02 <slot> 01     slot 0=Quiet 1=Aware 2=Home 3=unnamed
QC35   01 06 02 01 <value>       00 / 01 / 03
```
Both driven from our socket; headphones announced the mode aloud.

⚠ **`df21fe2c` is Google Fast Pair, not a vendor channel** — battery, model and
firmware, no ANC or EQ. An earlier pass took it for JBL/JLab's own protocol and
built a command map out of its acknowledgements; `docs/protocols.md` has the
correction and the real JBL table (`aa <cmd> <len:1>`, read out of the app).

## Next

1. **Reach the JBL's BES channel.** The command table is known — `aa 31` ANC,
   `aa 40` EQ, `aa 33` auto-off, `aa 71` gestures — but SPP `00001101` connects
   and stays silent. Look at `com.harman.auth`, or capture the app with the phone
   unlocked.
2. **Sony command table** — read SonyHeadphonesClient rather than sweep.
3. **Bose multipoint** — `04 04`/`04 09` read the paired list + active device;
   writes untried.
4. **Bose EQ / auto-off / buttons** — among the 15 write-capable fns in
   `docs/bose-read-surface.md`.
5. **JLab** — untouched beyond Fast Pair. Its app is `com.jlab.app`; read it the
   same way the JBL's was read.

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
./probe.sh free                          # force-stop vendor apps
./probe.sh send|raw <mac> <uuid> <hex>   # one packet, one socket
./probe.sh seq  <mac> <uuid> <hex,hex>   # one socket — THE WRITE TOOL
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

## Hearing safety

Probe with reads; never prove a round-trip with a volume command; restore any
level touched. ANC mode is not volume — it cannot raise a level, which is why it
is the right thing to write first. `Sweep.kt` hard-wires operator/length to
Get/zero (tested) rather than taking them as parameters.

## Docs / build

`docs/protocols.md` — three wire formats, capture method, channel traps.
`docs/bose-read-surface.md` — Bose surface, error taxonomy, how ANC was found.

```bash
nix develop ~/Code/recall#android --command ./gradlew :app:testDebugUnitTest
nix run ../dev-lint#gate -- . gate.json
```
No flake of its own; SDK from recall's devshell, like `xinutec-infra/govee-android`.
