# volume — headphone control over the vendor RFCOMM channels

Task #783's instrument, not the product (#785 is the app). Everything in `docs/`
was measured against the real headphones on 2026-08-15. **Re-measure; firmware
moves things.**

⚠ Repo is PUBLIC and carries the headphones' MACs — Pippijn's call, 2026-08-15.

## All five speak. Three protocols.

| Device | MAC | Channel | Protocol | ANC |
| --- | --- | --- | --- | --- |
| Bose QC45 | `E4:58:BC:3E:9D:AA` | SPP `00001101` | Bose | ✅ r/w |
| Bose QC35 | `4C:87:5D:CC:A0:23` | SPP `00001101` | Bose | ✅ r/w |
| Sony XM4 | `80:99:E7:F9:D0:61` | `96cc203e-…` | Sony framed | framing only |
| JBL Tour One M2 | `28:6F:40:8A:D3:E4` | `df21fe2c-…` | Harman | cmds located |
| JLab JBuds Sport ANC 4 | `EC:9A:0C:E0:D2:96` | `df21fe2c-…` | Harman | cmds located |

```
QC45   1f 03 05 02 <slot> 01     slot 0=Quiet 1=Aware 2=Home 3=unnamed
QC35   01 06 02 01 <value>       00 / 01 / 03
```
Both driven from our socket; headphones announced the mode aloud.

## Next

1. **Harman args.** Real cmds: JBL block `07` fns `00 08 09 10 1b`, block `03`
   fns `03 05 08 0d`. `07 10 0000` → `07 11 0004 0102b800` works on both. ANC is
   in there.
2. **Sony command table** — read SonyHeadphonesClient rather than sweep.
3. **Bose multipoint** — `04 04`/`04 09` read the paired list + active device;
   writes untried.
4. **Bose EQ / auto-off / buttons** — among the 15 write-capable fns in
   `docs/bose-read-surface.md`.

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
`--ez reconnect true` for Harman sweeps.

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
