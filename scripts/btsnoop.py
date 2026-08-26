#!/usr/bin/env python3
"""Decode a phone's `btsnoop_hci.log` into the vendor frames this repo cares about.

⚠ **This exists because `tshark` is not on this Mac and cannot be relied on.** On
2026-08-26 the documented `mergecap`/`tshark` route in `docs/captures.md` was
unavailable — no binary in the store, and `nix shell nixpkgs#wireshark-cli` blocked
behind a store GC freeing 43 GB. A capture is minutes of somebody's attention and the
window does not come back, so the decode must not depend on a tool that may be absent
when the log finally lands.

It also removes the dissector trap the same page records: tshark claims an RFCOMM
channel only if it *witnessed the channel being set up*, so the field is `data.data`
or `btspp.data` **in the same log** depending on when capture started. Reading the
frames straight out of the ACL stream has no such state.

    scripts/btsnoop.py <btsnoop-file> [more…] [--mac AA:BB:…] [--block 1f] [--all]
    scripts/btsnoop.py <btsnoop-file> --channels     which device said what, and where

Frames are matched by SHAPE — `<block> <fn> <operator> <len>` with the length
agreeing with what follows — so a payload that merely contains a plausible byte pair
is not reported. ⚠ **Shape is not proof**: `--all` prints every RFCOMM payload, which
is what to reach for when an expected frame does not appear, since "not found" here
means "did not match", never "was not sent".
"""

from __future__ import annotations

import argparse
import struct
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

# btsnoop timestamps count microseconds from 0000-01-01, which is 0x00dcddb30f2f8000
# microseconds before the Unix epoch.
EPOCH_OFFSET_US = 0x00DCDDB30F2F8000

H4_ACL = 0x02
H4_EVT = 0x04
EVT_CONNECTION_COMPLETE = 0x03

# The operators, from `docs/bose-read-surface.md`. A frame whose operator is outside
# this set is not a BMAP frame however well the length agrees.
OPERATORS = {
    0x00: "SET",
    0x01: "GET",
    0x02: "SET_GET",
    0x03: "STATUS",
    0x04: "ERROR",
    0x05: "START",
    0x06: "RESULT",
    0x07: "PROCESSING",
}

# ⚠ The QC45's own list, from `00 02` on 2026-08-26. Restricting to it is what keeps
# audio payload from decoding as frames: an arbitrary byte pair passes the shape test
# often enough to bury the real traffic.
BOSE_BLOCKS = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09,
               0x12, 0x13, 0x16, 0x17, 0x19, 0x1A, 0x1F}


@dataclass
class Payload:
    """One RFCOMM information field, with where and when it was seen."""

    when: datetime
    sent: bool
    handle: int
    dlci: int
    data: bytes


def read_records(path: Path) -> list[tuple[datetime, bool, bytes]]:
    """Every H4 packet in a btsnoop file, with its time and direction."""
    raw = path.read_bytes()
    if not raw.startswith(b"btsnoop\x00"):
        raise SystemExit(f"{path}: not a btsnoop file")
    # 8-byte magic, then version and datalink type, both big-endian.
    version, _datalink = struct.unpack_from(">II", raw, 8)
    if version != 1:
        raise SystemExit(f"{path}: btsnoop version {version}, expected 1")
    out: list[tuple[datetime, bool, bytes]] = []
    i = 16
    while i + 24 <= len(raw):
        _orig, incl, flags, _drops, ts = struct.unpack_from(">IIIIq", raw, i)
        i += 24
        data = raw[i:i + incl]
        if len(data) < incl:
            # ⚠ A truncated tail is NORMAL: the log flushes lazily, so the last
            # record is routinely half-written. Stopping is right; complaining is not.
            break
        i += incl
        when = datetime(1970, 1, 1, tzinfo=timezone.utc) + timedelta(
            microseconds=ts - EPOCH_OFFSET_US
        )
        # Bit 0 set means the packet came FROM the controller, i.e. from the device.
        out.append((when, not (flags & 1), data))
    return out


def handle_addresses(
    records: list[tuple[datetime, bool, bytes]],
) -> list[tuple[datetime, int, str]]:
    """Every connection event, in order: when a handle started meaning an address.

    ⚠ **Without this every device in the log looks like one stream.** This phone is
    bonded to thirteen things and routinely holds several at once, so a capture of
    "the headphones" also contains a watch and a laptop. Frames from the wrong device
    decode perfectly and mean nothing.

    ⚠⚠ **A handle is REUSED after a disconnect, so this CANNOT be one flat map.** The
    first version built `{handle: address}` over the whole file and applied it
    everywhere, which silently relabels earlier traffic with whoever held the handle
    LAST. On 2026-08-26 that made a QC35 exchange look like a QC45 one and nearly put
    "the vendor app wrote to your QC45" into the record — caught only because the
    payload was five bytes where that device answers seven. **The tell was the data
    disagreeing with the label, which is not a check that always exists.**
    """
    out: list[tuple[datetime, int, str]] = []
    for when, _sent, data in records:
        if len(data) < 3 or data[0] != H4_EVT or data[1] != EVT_CONNECTION_COMPLETE:
            continue
        if len(data) < 12 or data[3] != 0x00:  # non-zero status: the link failed
            continue
        handle = struct.unpack_from("<H", data, 4)[0] & 0x0FFF
        # The address is little-endian on the wire and written the other way round.
        out.append((when, handle, ":".join(f"{b:02X}" for b in reversed(data[6:12]))))
    return out


def address_at(
    events: list[tuple[datetime, int, str]], handle: int, when: datetime
) -> str | None:
    """Which address [handle] meant at [when] — the latest event at or before it.

    ⚠ Returns None for traffic BEFORE any connection event for that handle. A log that
    starts mid-connection has nothing to learn from, and guessing forward from a later
    event is the mislabelling this replaced.
    """
    best: str | None = None
    for at, h, addr in events:
        if h == handle and at <= when:
            best = addr
    return best


def rfcomm_payloads(records: list[tuple[datetime, bool, bytes]]) -> list[Payload]:
    """Reassemble ACL fragments and pull the RFCOMM information field out of each.

    ⚠ **Reassembly is not optional.** Bose batches its replies — eight frames in one
    write — and a batch crosses the ACL fragment boundary routinely. Reading only the
    first fragment silently truncates the interesting reply, which is the failure this
    repo already met once as "the device answered a short frame".
    """
    pending: dict[int, tuple[datetime, bool, bytearray, int]] = {}
    out: list[Payload] = []
    for when, sent, data in records:
        if not data or data[0] != H4_ACL or len(data) < 5:
            continue
        handle_flags, dlen = struct.unpack_from("<HH", data, 1)
        handle = handle_flags & 0x0FFF
        pb = (handle_flags >> 12) & 0x3
        body = data[5:5 + dlen]
        if pb == 0x1:  # continuation of an L2CAP PDU already begun
            held = pending.get(handle)
            if held is None:
                continue
            held[2].extend(body)
        else:
            if len(body) < 4:
                continue
            l2_len, _cid = struct.unpack_from("<HH", body, 0)
            pending[handle] = (when, sent, bytearray(body[4:]), l2_len)
        held = pending.get(handle)
        if held is None:
            continue
        w, s, buf, want = held
        if len(buf) < want:
            continue
        del pending[handle]
        pdu = bytes(buf[:want])
        frame = _rfcomm_information(pdu)
        if frame:
            # ⚠ **The DLCI is what separates the protocols**, and it has to be
            # reported rather than assumed. A phone talking to one pair of headphones
            # runs several RFCOMM channels at once: on 2026-08-26 this log carried
            # Bose's BMAP alongside a protobuf stream whose bytes pass the BMAP shape
            # test by luck, and both would otherwise print as "frames".
            out.append(Payload(when=w, sent=s, handle=handle,
                               dlci=pdu[0] >> 3, data=frame))
    return out


def _rfcomm_information(pdu: bytes) -> bytes:
    """The information field of an RFCOMM UIH frame, or empty for anything else."""
    if len(pdu) < 4:
        return b""
    control = pdu[1]
    # UIH is 0xEF with the poll/final bit optionally set; anything else is link
    # management (SABM, UA, DISC) and carries nothing this repo reads.
    if control & ~0x10 != 0xEF:
        return b""
    if pdu[2] & 1:
        length = pdu[2] >> 1
        i = 3
    else:
        length = (pdu[2] >> 1) | (pdu[3] << 7)
        i = 4
    # ⚠ With credit-based flow control the credit octet sits between the length and
    # the data and is NOT counted in the length. Taking `length` bytes from the wrong
    # offset shifts every byte by one, which decodes as a valid-looking frame.
    if control & 0x10:
        i += 1
    return pdu[i:i + length]


def bmap_frames(data: bytes, blocks: set[int]) -> list[bytes] | None:
    """Split a payload into BMAP frames, or None if it is not BMAP at all.

    ⚠ Returns None rather than a partial list: a payload that *starts* like BMAP and
    then stops agreeing is far more likely to be something else than to be a damaged
    frame, and reporting the prefix would invent traffic.
    """
    out: list[bytes] = []
    i = 0
    while i + 4 <= len(data):
        block, fn, operator, ln = data[i], data[i + 1], data[i + 2], data[i + 3]
        if block not in blocks or operator not in OPERATORS:
            return None
        if i + 4 + ln > len(data):
            return None
        out.append(data[i:i + 4 + ln])
        i += 4 + ln
    return out if out and i == len(data) else None


def show(frame: bytes) -> str:
    """One BMAP frame as `block fn OPERATOR len  payload`."""
    block, fn, operator, ln = frame[0], frame[1], frame[2], frame[3]
    body = frame[4:]
    text = "".join(chr(c) if 32 <= c < 127 else "." for c in body)
    return (
        f"{block:02x} {fn:02x} {OPERATORS[operator]:<10} len={ln:02x}  "
        f"{body.hex(' '):<40} |{text}|"
    )


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("logs", nargs="+", type=Path)
    ap.add_argument("--block", help="only frames in this block, e.g. 1f")
    ap.add_argument("--mac", help="only this device, e.g. E4:58:BC:3E:9D:AA")
    ap.add_argument("--all", action="store_true",
                    help="every RFCOMM payload, decoded or not")
    ap.add_argument("--since", help="HH:MM:SS local, ignore anything earlier")
    ap.add_argument("--dlci", type=int,
                    help="only this RFCOMM channel; --channels lists them")
    ap.add_argument("--channels", action="store_true",
                    help="what each RFCOMM channel carried, then stop")
    args = ap.parse_args()

    want_block = int(args.block, 16) if args.block else None

    records: list[tuple[datetime, bool, bytes]] = []
    for log in args.logs:
        records.extend(read_records(log))
    records.sort(key=lambda r: r[0])

    connections = handle_addresses(records)
    payloads = rfcomm_payloads(records)
    if not payloads:
        # ⚠ An empty result is EVIDENCE, and saying so is the whole point — see
        # `docs/captures.md`: no ACL means nothing was sent, not that this missed it.
        print(f"{len(records)} HCI packets, NO RFCOMM payloads at all.", file=sys.stderr)
        return 1

    if args.channels:
        seen: dict[tuple[str, int], tuple[int, int]] = {}
        for p in payloads:
            key = (address_at(connections, p.handle, p.when)
                   or f"handle {p.handle}", p.dlci)
            n, ok = seen.get(key, (0, 0))
            seen[key] = (n + 1, ok + (bmap_frames(p.data, BOSE_BLOCKS) is not None))
        print(f"{'device':<20} dlci  payloads  BMAP-shaped")
        for (addr, dlci) in sorted(seen):
            n, ok = seen[(addr, dlci)]
            print(f"{addr:<20} {dlci:4d}  {n:8d}  {ok:11d}")
        return 0

    since = None
    if args.since:
        first = payloads[0].when.astimezone()
        h, m, s = (int(x) for x in args.since.split(":"))
        since = first.replace(hour=h, minute=m, second=s, microsecond=0)

    shown = 0
    for p in payloads:
        local = p.when.astimezone()
        if since and local < since:
            continue
        if args.dlci is not None and p.dlci != args.dlci:
            continue
        if args.mac:
            at = address_at(connections, p.handle, p.when)
            if at is None or at.upper() != args.mac.upper():
                continue
        frames = bmap_frames(p.data, BOSE_BLOCKS)
        arrow = "→" if p.sent else "←"
        if frames is None:
            if args.all:
                print(f"{local:%H:%M:%S.%f}  {arrow} d{p.dlci:<2} raw  {p.data.hex(' ')}")
                shown += 1
            continue
        if want_block is not None and not any(f[0] == want_block for f in frames):
            continue
        for f in frames:
            if want_block is not None and f[0] != want_block:
                continue
            print(f"{local:%H:%M:%S.%f}  {arrow} d{p.dlci:<2} {show(f)}")
            shown += 1

    print(f"\n{len(records)} HCI packets, {len(payloads)} RFCOMM payloads, "
          f"{shown} lines shown.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
