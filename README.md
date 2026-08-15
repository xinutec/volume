# volume — headphone control probe (task #783)

**This is not the app.** It is the instrument that answers #783: *can a
headphone's proprietary control channel be spoken at all?* #785 is built from the
bytes it captures, and once the shape of the real app is settled this probe does
not survive into it.

## The answer, for Sony: yes

Measured 2026-08-15 against **WH-1000XM4** (`80:99:E7:F9:D0:61`) on
`96cc203e-5068-46ad-b32d-e316f5e069ba`, over a **secure** RFCOMM socket, first
attempt.

```
→ 3e 0c 00 00 00 00 02 00 00 0e 3c
← 3e 01 01 00 00 00 00 02 3c              ACK   seq=01 payload=(none)
  3e 0c 01 00 00 00 04 01 00 70 00 82 3c  DATA  seq=01 payload=01 00 70 00
```

Both reply frames checksum clean and their declared lengths match their payloads,
so the framing in `SonyFrame.kt` is **confirmed**, not merely self-consistent:

```
3e | type(1) | seq(1) | length(4, big-endian) | payload | sum(1) | 3c
```

What the exchange establishes beyond the framing:

- **The device ACKs first, then answers**, as two frames in one read.
- **The ACK inverts the sequence number.** Sending `seq=00` returns an ACK with
  `seq=01`; sending `seq=01` returns `seq=00`. Verified both ways round.
- `0x00` requests protocol info and `0x01` returns it — the reply payload
  `01 00 70 00` is stable across runs.
- ⚠ **We do not ACK the device's data frame yet.** Each probe run opens a fresh
  socket, so nothing has needed it; a long-lived session almost certainly will.

## Not yet answered

- **Bose QC45** (`E4:58:BC:3E:9D:AA`). Both channels refuse to connect — but the
  headphones were powered off (`ACL BR/EDR:N`), so this says nothing about the
  protocol. Re-probe with them **on and connected to the phone**.
- **JBL Tour One M2**, **JLab JBuds Sport ANC 4**, and the unidentified
  `LE-Pippijn Headphon` (plain SPP, suspected QC35).
- **Anything that changes a setting.** Everything above is read-only by design.

## Reading the SDP record

`Channels.kt` decides which channel to open, and its tests are the real SDP
records read off the Pixel 9. The trap it exists for:

⚠ **Most vendor-looking UUIDs are shared.** `81c2e72a`, `931c7e8a` and `f8d1fbe4`
are on the Sony *and* the JBL *and* the unidentified Bose; `df21fe2c` on the JBL
*and* the JLab. **`00000000-deca-fade-deca-deafdecacaff` is on the Sony, the QC45
and the unidentified Bose alike** — task #783 annotates it "Bose proprietary",
and it is not. Keying on it would have opened the wrong channel.

Only three devices can be identified by UUID at all (Sony, QC45, JLab). The **JBL
advertises no unique UUID whatsoever**, so it and a QC35 are indistinguishable by
SDP; those fall back to the device name, and the detection says so rather than
presenting a guess as a match.

## Using it

```bash
./probe.sh install                    # build, install, grant BLUETOOTH_CONNECT
./probe.sh free                       # force-stop the vendor apps
./probe.sh list                       # bonded devices + detected control channel
./probe.sh send <mac> <uuid> <hex> [type] [seq]   # Sony-framed
./probe.sh raw  <mac> <uuid> <hex>                # bytes verbatim
```

Everything is logged under `adb logcat -s volume-probe`. `VOLUME_ADB_DEVICE`
overrides the target (default: Pixel 9 over the VPN).

⚠ **Only one app may hold a device's RFCOMM channel.** A connect failure while a
vendor app is running is not a protocol result — run `./probe.sh free` first. Keep
the vendor apps installed: capturing the remaining protocols needs them.

⚠ **Hearing safety.** Probe with reads. Never use a volume command as a
round-trip proof, and restore any level touched for a test in the same step.

## Build

`nix develop ~/Code/recall#android --command ./gradlew :app:testDebugUnitTest` —
19 unit tests, no device needed. The Android SDK comes from recall's devshell;
this repo has no flake of its own, same as `xinutec-infra/govee-android`.
