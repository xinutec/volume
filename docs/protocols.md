# The vendor control protocols, as captured

Every byte here was read off the wire on **2026-08-15** from a full Bluetooth HCI
snoop log of the official apps talking to the real headphones — not from
documentation and not from a guess. Method at the end.

Status: **all five speak, and all five have answered our own socket.** Five
devices, **three** wire formats — the JBL and the JLab share one.

| Device | Channel | Protocol |
| --- | --- | --- |
| Sony WH-1000XM4 | `96cc203e-…` | Sony framed |
| Bose QC45 | **SPP `00001101`** | Bose 4-byte |
| Bose QC35 | **SPP `00001101`** | Bose 4-byte |
| JBL Tour One M2 | **`df21fe2c-…`** | `[block][cmd][len:2]` |
| JLab JBuds Sport ANC 4 | **`df21fe2c-…`** | `[block][cmd][len:2]` |

Note that **not one** of the three channels is the UUID you would pick by
eyeballing the SDP record. Both ways of being wrong are below.

---

## ⚠ The finding that unblocked everything: use SPP, not the exotic UUID

The QC45 advertises `9b26d8c0-a8ed-440b-95b0-c4714a518bcc`, which looks exactly
like the vendor control channel. **It is not the one the Bose app uses.** The app
connects to plain **SPP `00001101-…` (RFCOMM channel 8)**, and that is where the
protocol lives.

Four get-shaped packets sent to `9b26d8c0` drew total silence, and the natural
reading — "the protocol is wrong" — was wrong. The packet was right the whole
time. `00010100` is *literally the first thing the official app sends*; it was
going to a channel that does not speak.

**Judge a channel by what the vendor app connects to, never by which UUID looks
proprietary.** The phone will tell you, no capture required:

```
adb shell dumpsys bluetooth_manager | grep "RFCOMM Connection opened"
  → ... e4:58:bc:3e:9d:aa handle:16 scn:8 dlci:16 mtu:990
```

`scn` is the RFCOMM channel; match it against the SDP record to get the UUID.

## ⚠ …and the same mistake in the opposite direction

`df21fe2c-2515-4fdb-8886-f12c4d67927c` is on the JBL **and** the JLab, so it
cannot say which vendor a device is — and it is **the control channel for both**.
It was originally filed here as "shared across vendors, not a marker" and
therefore ignored, which is precisely backwards.

Meanwhile the JLab's own exotic-looking `66666666-…` and `99999999-…` are
**decoys**: both sockets connect and neither ever answers a byte.

So *"which vendor is this?"* and *"which channel do I open?"* are **different
questions with different answers**, and `Channels.kt` now returns them as
separate fields. A shared UUID is useless for the first and authoritative for the
second.

---

## Bose — QC45 and QC35, same protocol

Four-byte header, then payload:

```
[function block] [function] [operator] [payload length] [payload…]
```

Operator `0x01` is Get and `0x03` is Status/response. Verified against **our own**
socket, byte-identical to what the official app receives:

```
→ 00 01 01 00                       get version
← 00 01 03 05 31 2e 31 2e 30        QC45: "1.1.0"
← 00 01 03 05 31 2e 30 2e 34        QC35: "1.0.4"
```

Blocks seen in the capture, with what identifies them:

| Block | Evidence from the capture |
| --- | --- |
| `00` | version, `00 02` → `86cc03ff`, `00 03` → `407501` |
| `01` | product/settings; carries the device NAME as ASCII |
| `04` | device management — **paired-device list, i.e. multipoint** |
| `05` | status; repeated `05 04 03 03 01 ff ff` notifications |
| `09`, `12 0b` | set during connect (`09 02 02 05 0180040237`) |

Two payloads worth seeing decoded, because they show the shape is understood:

```
← 04 05 03 10 fc4116e09d2a 03 02 03 "Pixel 9"        paired device + name
← 01 01 07 00 01 02 03 12 00 "Pippijn Bose QC35"     the QC35 naming itself
```

`fc:41:16:e0:9d:2a` is the phone. The QC35 line is what independently confirmed
that device's model, which the SDP record could not.

## Sony WH-1000XM4

Documented in `SonyFrame.kt` and already driven from our own socket — see the
README. Channel `96cc203e-…`, framing
`3e | type | seq | len(4, BE) | payload | sum | 3c`, ACK inverts the sequence
number. The capture shows it on **DLCI 0x2b, 6,716 frames** — by far the
chattiest of the five.

## JBL and JLab — one protocol, one channel, both driven

Header is **`[block] [command] [length: 2 bytes, big-endian] [payload…]`**. The
length field was *inferred* from the capture and is now **confirmed**: every
frame the devices returned to our own socket parses exactly.

`07 10 0000` is echoed back verbatim, which makes it a perfect harmless probe —
it is how both devices were identified as speaking the same protocol.

```
JBL  → 07 10 0000
     ← 03 01 |0003| ea3f99          03 02 |0006| 7d089818ddd9
       03 03 |0001| 3c              03 0a |0008| 92417496e773d72c
       07 10 |0000|                 07 34 |000c| 01de786a7ee88dd4c490a3c9
       03 09 |0007| "6.8.0.0"       03 0b |0018| …24 bytes…
       06 03 |0007| 017d089818ddd9  07 11 |0004| 0102b800

JLab → 07 10 0000
     ← 03 01 |0003| 6f08a1          03 02 |0006| 708e3ea2dd56
       03 03 |0003| 5a 50 64        03 0a |0008| 95275bbf6c1fee c4
       07 10 |0000|                 07 11 |0004| 0102b000
```

⚠ **`03 03` is battery, and its LENGTH carries the topology** — inferred, and the
inference is worth stating because it shapes the UI: the over-ear JBL returns one
value (`3c` = 60), the JLab earbuds return three (`5a 50 64` = 90 / 80 / 100,
almost certainly left / right / case). A reader that assumes one battery per
device will silently drop two thirds of the earbud state.

`03 09` is the firmware version as ASCII on both. The capture also shows the JBL
app **writing** `1a 0d "Europe/London"`, so this protocol is not read-only.

From the capture of the official JBL app, showing where to look and what else the
protocol carries — its traffic sits on DLCIs `0x22` and `0x14`:

```
→ 03 08 0002 0125
← 03 02 0036 … "6.8.0" … "1D150104060124 0A0" …   version + serial in the clear
→ 04 11 0000 / 04 13 0000 / 04 15 0000            zero-length gets
→ … 1a 0d "Europe/London"                         the app SETS the timezone
```

### ⚠ Harman drops the link instead of erroring — and that inverts the map

Bose answers an unsupported command with an error code. **The JBL and JLab close
the RFCOMM connection**, so a blind sweep dies at its first miss. `Probe`
therefore takes a `reconnect` flag; `--ez reconnect true` reopens and continues,
and reports which packet killed the link, because that is a *result*.

Once it can get past the first miss, the error reply appears and the map inverts:

| Reply | Meaning |
| --- | --- |
| `ff 01 00 02 <block> <cmd>` | **unsupported** — the error echoes the offending command |
| link dropped | ⚠ **supported, but the body was malformed** (a real command handed a zero length) |

So on Harman it is the **dropped** packets that name the real commands, not the
answering ones. Block `07`, functions `00 08 09 10 1b`, and block `03` functions
`03 05 08 0d` are real and take arguments. Everything else in `07 00`–`07 1f`
answers the error.

⚠ **Timing matters more here than on Bose.** At a 400 ms window the whole sweep
read as silent — 0 answered out of 144. At 1500 ms it answered 25 of 32. A
too-short window looks exactly like a device that does not implement anything.

⚠ **Correction to an earlier claim in this file:** `07 10 00 00` was described as
"echoed back verbatim, a perfect harmless probe". On a *fresh* connection it does
elicit a state burst containing `07 10 00 00`; inside a sweep it drops the link.
Whether that burst is an echo or an unsolicited state dump is **not established**,
and the earlier wording was over-confident.

⚠ **Neither device can be identified by UUID.** The JBL advertises nothing
unique, and the JLab advertises only its dead decoys — so `Channels.kt` names
both from the device name, and reports that it did. The channel and protocol are
known regardless, so an unrecognised name still yields a working connection.

⚠ `com.harman.ble.jbllink` is JBL's **speaker** app and is not relevant here
(Pippijn, 2026-08-15). The headphone app is `jbl.stc.com`.

---

## How to capture

1. **Settings → System → Developer options → Enable Bluetooth HCI snoop log**,
   then cycle Bluetooth. This cannot be done over adb — `setprop
   persist.bluetooth.btsnooplogmode` is denied to the shell user by SELinux, and
   there is no `cmd bluetooth_manager` subcommand for it.
   ⚠ **`getprop` on that property is ALSO denied**, and returns empty rather than
   erroring — so an empty read means "denied", *not* "disabled". Confirm from
   logcat instead, which says `SnoopLogger: Snoop Logs full mode enabled`.
2. Drive each vendor app so it handshakes:
   `adb shell monkey -p <pkg> -c android.intent.category.LAUNCHER 1`.
   The headphones must be **on and linked** (`ACL BR/EDR:Y`) or the app connects
   to nothing and the capture is empty.
3. `adb bugreport br.zip`, then extract
   `FS/data/misc/bluetooth/logs/btsnoop_hci.log`.
4. Read it:
   ```
   tshark -r btsnoop_hci.log -Y 'btrfcomm.dlci==0x10 && bthci_acl.src.bd_addr==<mac>' \
          -T fields -e bthci_acl.src.bd_addr -e btspp.data -e data.data
   ```
   Find the DLCI first by counting frames per `btrfcomm.dlci`; the vendor channel
   is rarely the busiest one, and `0x18`/HFP will mislead you.
   ⚠ Wireshark dissects some channels as SPP (`btspp.data`) and others as raw
   (`data.data`) — query **both** or half the conversations come back empty.

There is also a **live snoop socket** on device port 8872 (`adb forward tcp:8872
tcp:8872`), which streams btsnoop with a valid header. It dropped our connection
repeatedly and the bugreport proved more reliable; worth revisiting for a fast
iteration loop.
