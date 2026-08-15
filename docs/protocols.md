# The vendor control protocols, as captured

Every byte here was read off the wire on **2026-08-15** from a full Bluetooth HCI
snoop log of the official apps talking to the real headphones — not from
documentation and not from a guess. Method at the end.

Status: **four of five speak**. Sony, Bose QC45 and Bose QC35 have been made to
answer *our own* socket; JBL is decoded from capture but not yet driven. JLab was
not connected during the capture.

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

## JBL Tour One M2 — decoded, not yet driven

Two DLCIs carry vendor traffic (`0x22` and `0x14`). Header is
**`[block] [command] [length: 2 bytes, big-endian] [payload…]`**:

```
→ 03 08 0002 0125
← 03 09 0007 "6.8.0.0"                     firmware version
← 03 01 0003 ea3f99
← 03 0a 0008 92417496e773d72c
← 03 02 0036 … "6.8.0" … "1D150104060124 0A0" …   version + serial
→ 04 11 0000 / 04 13 0000 / 04 15 0000     zero-length gets
→ … 1a 0d "Europe/London"                  the app SETS the timezone
```

⚠ The length field being 2 bytes is **inferred** from `0002/0003/0007/0036`
consistently matching the bytes that follow. It has not been confirmed by driving
the device.

⚠ **The JBL advertises no unique UUID**, so `Channels.kt` identifies it by name.
Note also that `com.harman.ble.jbllink` is JBL's *speaker* app — not relevant to
headphones (Pippijn, 2026-08-15).

## JLab JBuds Sport ANC 4 — not captured

It was disconnected during the capture (`ACL BR/EDR:N`). Its channel opens, and
it advertises the placeholder UUIDs `66666666-…`/`99999999-…`. Reconnected
afterwards; needs another capture round.

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
