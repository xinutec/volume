# The vendor protocols, as captured

Measured 2026-08-15 from a full HCI snoop log of the official apps plus our own
driven sessions. Five devices, three wire formats.

## ⚠ The channel is never the UUID that looks proprietary

Both directions of this cost real time:

- QC45 advertises `9b26d8c0-…`, which looks exactly like a vendor channel. **The
  Bose app does not use it** — the protocol is on plain SPP `00001101` (RFCOMM
  ch 8). Four packets to `9b26d8c0` drew silence; the identical packet on SPP
  returned the firmware version.
- `df21fe2c-…` is on the JBL *and* the JLab, so it cannot identify either — and
  it is **the control channel for both**. Shared ≠ useless: it answers "where",
  not "who".
- The JLab's exotic `66666666-…`/`99999999-…` are **decoys**: they connect and
  never answer.
- `00000000-deca-fade-…` is annotated "Bose proprietary" in #783 and is on the
  Sony too. `81c2e72a`/`931c7e8a`/`f8d1fbe4` are on Sony *and* JBL.

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
6,716 frames — by far the chattiest. Command table **not mapped**.

```
→ 3e 0c 00 00000002 0000 0e 3c
← 3e 01 01 00000000 02 3c                 ACK
  3e 0c 01 00000004 01 00 70 00 82 3c     DATA
```

## Harman — JBL + JLab, one protocol

```
[block][command][length: 2 bytes BE][payload…]
```
```
JBL   ← 03 09 |0007| "6.8.0.0"    03 03 |0001| 3c
      ← 03 02 |0036| … "6.8.0" … "1D150104060124 0A0"   version + serial
JLab  ← 03 03 |0003| 5a 50 64     07 11 |0004| 0102b000
      ← (JBL gives 0102b800)
```

⚠ **`03 03` is battery and its LENGTH carries topology** — over-ear JBL returns
one value (`3c`=60), JLab earbuds return three (90/80/100 = L/R/case). Code
assuming one battery drops two thirds of the earbud state.

⚠ **Harman drops the link instead of erroring**, so a blind sweep dies at its
first miss. `--ez reconnect true` reopens and reports which packet killed it —
that is a *result*. Past the first miss the error appears and the map inverts:

| Reply | Meaning |
| --- | --- |
| `ff 01 00 02 <block><cmd>` | unsupported (echoes the command) |
| link dropped | ⚠ **supported, body malformed** |

So the **dropped** packets name the real commands. Block `07` fns `00 08 09 10 1b`;
block `03` fns `03 05 08 0d`. JBL: 25 errored / 5 dropped. JLab: 32 errored /
0 dropped — same protocol, laxer firmware.

⚠ **`03 xx` looks response-only.** The device volunteers `03 01/02/03/09/0b` after
a `07 10` request, and `03 03 00 00` *as a request* drops the JBL. So `07 xx` are
commands — look for ANC there. Inferred from traffic shape.

⚠ Neither device is identifiable by UUID (JBL has none unique, JLab only decoys),
so `Channels.kt` names both by device name and says so. `com.harman.ble.jbllink`
is the speaker app; headphones are `jbl.stc.com`.

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

Vendor APKs are in `~/.cache/volume-apks` (513 MB, outside the repo) as a second
route to the protocols.
