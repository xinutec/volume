# The vendor protocols, as captured

Measured 2026-08-15 from a full HCI snoop log of the official apps plus our own
driven sessions. Five devices, three wire formats.

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
6,716 frames — by far the chattiest. Command table **not mapped**.

```
→ 3e 0c 00 00000002 0000 0e 3c
← 3e 01 01 00000000 02 3c                 ACK
  3e 0c 01 00000004 01 00 70 00 82 3c     DATA
```

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

### Where JBL control actually lives — found, not yet spoken

From `jbl.stc.com` (apktool → smali; `com.harman.bluetooth`, the BES stack that
`TourOne2Control` extends):

```
[aa][command][length: 1 byte][payload…]      CmdBase.combine(), HEADER_COMMAND = 0xaa
```
```
aa 21 01 <30..3a>   status: 30 all · 31 ANC · 32 ambient · 33 auto-off · 34 EQ
                    35 multi-AI · 36 BT connection · 37 OTA · 38 auto play/pause
                    39 TWS · 3a PersoniFi
aa 31  set ANC          aa 32  set ambient aware    aa 33  set auto-off
aa 40  set EQ preset    aa 41  set custom EQ        aa 42  read custom EQ
aa 71  set gesture      aa 72  read gesture         aa 77  gesture batch
aa 91  ANC modes (`aa 91 01 11` reads)              aa 11  device info
aa 25  battery          aa 94  serial               aa 9b  multi-status
aa 74/75  ANC tuning    aa 81/82  smart switch      aa 95  factory reset
```
Replies are `aa <cmd+1>` (`aa 11` → `aa 12`), per `RetHeader`.

⚠ **The device does not answer this yet.** `aa 11 00` on SPP `00001101` (the
SDK's own `BES_SPP_CONNECT`, RFCOMM ch 12) connects and stays silent, as do the
three shared UUIDs. Unresolved: a connect handshake (`com.harman.auth` exists) or
a different transport. The JBL app could not be observed doing it — launched
headless it reaches its dashboard and opens no RFCOMM connection at all, which is
consistent with it waiting behind the lock screen.

⚠ **Never sweep this protocol.** It has no Get operator; `aa 31`, `aa 33`, `aa 40`
and `aa 95` are writes, and `Sweep` deliberately cannot emit them.

⚠ Neither device is identifiable by UUID (JBL has none unique, JLab only silent
ones), so `Channels.kt` names both by device name and says so.
`com.harman.ble.jbllink` is the speaker app; headphones are `jbl.stc.com`.

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
