#!/usr/bin/env python3
"""Drive a vendor headphone app through its own rows, for a protocol capture.

The method is `docs/captures.md`: every change is followed immediately by its
inverse, so the differing bytes are the field and nothing is left moved. Each line
printed here becomes a row of the timeline that labels the capture — without it a
snoop log is a haystack.

⚠ **This is the device-agnostic half, and the reason it exists is a measured cost.**
A session hand-rolled `adb shell input tap` roughly forty times against coordinates
re-derived by hand, to drive Bose Connect through launch → gear → row → option →
force-stop → read the wire. One restore silently did not take, which is what a blind
tap looks like, and where it landed cannot be established after the fact. A second
vendor app was the moment to stop copying the first one's driver.

⚠ **The four faults this driving method exists to prevent** — every one of them
reached a phone, and every one is a check that did not exist:

  1. A tap on a row clipped by the header could hand back the NEIGHBOUR's switch —
     the wrong setting moving while the log printed the label that was asked for.
  2. Our own app holds the headphones' LE GATT link, which takes ONE client. While it
     did, the vendor app greyed out and every tap landed on a dead UI, so nothing
     reached the wire — indistinguishable in the capture from a control the app keeps
     to itself.
  3. A run that aborted between a change and its inverse left the setting changed.
  4. ⚠ **It never checked WHICH APP was in front.** A whole run drove the agent
     console: `uiautomator dump` returns the FOCUSED window, the vendor app was not
     even launched, and the readiness check — "is a battery percentage on screen" —
     matched something else entirely. Ten minutes of swiping a chat transcript.

⚠ **Fault #4 was committed AGAIN on 2026-08-26**, by a session that drove a vendor
app with `input tap` instead of reaching for this. A tool that exists and is not
reached for is worth as much as one that does not exist, so the vendor-app drive loop
goes through here the way wire writes go through the probe.

A caller supplies the package, its screen map and its groups, then calls [run]. See
`drive_jbl.py` for the shape.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from typing import Callable, Iterator

OURS = "org.xinutec.volume"

# ⚠ A row too near an edge is REFUSED rather than tapped. The switch belonging to a
# label is found by vertical overlap, and a clipped band overlaps the next row.
SAFE_TOP = 380
SAFE_BOTTOM = 2200

# ⚠ One swipe's travel, capped so a long move is several short ones. A single big
# swipe is read as a FLING and carries well past where it was aimed, which is a
# scroll that cannot be aimed at all.
MAX_SWIPE = 800

# How far above its label a segmented tile may sit. Measured at 11 px; the next
# card's tiles are hundreds away. See [tile_for] for what an unbounded search cost.
TILE_GAP = 60

REMOTE_DUMP = "/sdcard/window_dump.xml"

# ⚠ The device policy's `maximumTimeToUnlock` on this phone. Writing more than this to
# `screen_off_timeout` is accepted, reads back, and does nothing. See [screen_timeout].
POLICY_MAX_MS = "300000"


class NotInVendorApp(RuntimeError):
    """The foreground app is not the one we mean to drive."""


_SERIAL: str | None = None


def adb_target() -> list[str]:
    """The `-s <serial>` this run drives, resolved once.

    ⚠⚠ **A bare `adb` DRIVES NOTHING when two phones are attached**, and on this Mac the
    second one is a Pixel 5 that shares no bonded headphones. Every call here then fails
    with `more than one device/emulator` — which reads as a broken script rather than as a
    second transport. Measured 2026-09-03, when the Pixel 5 came online mid-session and
    `drive_bose.py` would have failed on its first call.

    ⚠ **Two attached devices is an ERROR, not a guess.** Picking arbitrarily would drive
    the wrong phone. `probe.sh` has taken this position since 2026-08-24 and this now
    matches it, including the environment variable that resolves it.
    """
    global _SERIAL
    if _SERIAL is None:
        forced = os.environ.get("VOLUME_ADB_DEVICE", "")
        if forced:
            _SERIAL = forced
        else:
            listing = subprocess.run(
                ["adb", "devices"], capture_output=True, text=True, check=False
            ).stdout
            # ⚠ `parts[1] == "device"` is load-bearing: the header line has no second
            # field, and a phone can be listed `offline` or `unauthorized` — neither can
            # be driven, and treating one as usable turns a plug-in problem into a
            # protocol mystery.
            ready = [
                parts[0]
                for parts in (line.split() for line in listing.splitlines())
                if len(parts) > 1 and parts[1] == "device"
            ]
            if len(ready) == 1:
                _SERIAL = ready[0]
            elif not ready:
                raise RuntimeError("no adb device is attached — plug one in, or adb connect")
            else:
                raise RuntimeError(
                    f"{len(ready)} devices attached ({', '.join(ready)}) — "
                    "say which with VOLUME_ADB_DEVICE="
                )
    return ["-s", _SERIAL]


def adb(*args: str, check: bool = True) -> str:
    out = subprocess.run(
        ["adb", *adb_target(), *args], capture_output=True, text=True, check=False
    )
    if check and out.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {out.stderr.strip()}")
    return out.stdout.replace("\r", "")


def say(message: str) -> None:
    print(f"{time.strftime('%H:%M:%S')}  {message}", flush=True)


def focused_package() -> str:
    """The package that owns the focused window, or "" if none."""
    dump = adb("shell", "dumpsys", "window", check=False)
    found = re.search(r"mCurrentFocus=Window\{\S+ \S+ ([^/}]+)", dump)
    return found.group(1) if found else ""


def focused_activity() -> str:
    """The focused activity's short name, or "" — for reporting a navigation landed.

    ⚠ A screen change is the only evidence [Driver.enter] can offer. A setting can be
    read back and proved; a tap that opens a screen cannot, so say where it went.
    """
    dump = adb("shell", "dumpsys", "window", check=False)
    found = re.search(r"mCurrentFocus=Window\{\S+ \S+ [^/}]+/(\S+?)\}", dump)
    return found.group(1).rsplit(".", 1)[-1] if found else ""


def screen_timeout(value: str | None = None) -> str:
    """Read, or set, the display sleep in milliseconds.

    ⚠ **A run can put the phone to sleep and then wait for it to wake.** `dumpsys` and
    `uiautomator dump` are not touches, so preflight's poll loop — two minutes of
    reading and no tapping — lets the idle timer run out. On 2026-08-17 the last tap
    was 10:49:05, the screen went dark at 10:54 on a five-minute timeout, and preflight
    spent its full budget waiting for an app it had itself sent to the lock screen,
    then reported "never came up focused and connected". The device was fine.

    ⚠⚠ **AND THE FIX FOR THAT DID NOT WORK — a value above the device policy's maximum
    is SILENTLY CAPPED.** This phone carries `maximumTimeToUnlock=300000`, so the old
    `1800000` was stored, read back as `1800000` by `settings get`, and still let the
    display sleep at five minutes. The setting agreeing with what was written is not
    evidence that it applies. Check the real ceiling with:

        adb shell dumpsys device_policy | grep -i maximumTimeToUnlock

    So five minutes is the most this can buy, and a run that goes longer than that
    without a touch WILL still sleep. The only real wake lock on this phone is the
    split-screen agent console.
    """
    if value is None:
        return adb("shell", "settings", "get", "system", "screen_off_timeout", check=False).strip()
    adb("shell", "settings", "put", "system", "screen_off_timeout", value, check=False)
    return value


def wake() -> None:
    """Get the screen lit and the shade out of the way. Safe to repeat."""
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP", check=False)
    adb("shell", "cmd", "statusbar", "collapse", check=False)


def locked() -> bool:
    """Is the keyguard up?

    ⚠ **Distinguish "the app is not ready yet" from "nothing can happen at all".**
    A locked phone focuses `NotificationShade`, so every check preflight makes reads
    exactly like an app that is slow to connect — and it waited the full two minutes
    twice before reporting `jbl.stc.com never came up focused and connected`, which
    named the wrong thing entirely. Waiting cannot fix this one and neither can the
    driver: clearing a keyguard means Pippijn's credential, which this does not touch.
    """
    return "isKeyguardShowing=true" in adb("shell", "dumpsys", "window", check=False)


def require_vendor() -> None:
    """⚠ **The check whose absence drove a whole run into the wrong app.**

    Called before every dump and every tap rather than once at the start, because
    the foreground can change underneath a long run — a notification, a crash, our
    own app being relaunched — and every action after that point is both useless
    and, in an app that can act, potentially harmful.
    """
    front = focused_package()
    if front != VENDOR:
        raise NotInVendorApp(f"focused app is {front or '(none)'}, not {VENDOR}")


@dataclass(frozen=True)
class Node:
    text: str
    x0: int
    y0: int
    x1: int
    y1: int
    checkable: bool
    checked: bool
    clickable: bool
    # ⚠ **An affordance with EMPTY text is invisible to the whole label layer.** Bose
    # Connect's landing screen carries every control this way — the settings gear is
    # `content-desc="Settings"` with `text=""` — so a driver matching on text scrolls a
    # screen it structurally cannot see. Measured 2026-08-30.
    desc: str = ""
    # ⚠ **Kept because a HAZARD is identified by widget, not by screen name.** The
    # scroll guard keys on this: Sony's equaliser is a custom `eq_graph` that takes a
    # drag, and the word "Equalizer" also names an ordinary row in the settings list
    # where scrolling is perfectly safe. Matching the title would refuse both.
    rid: str = ""

    @property
    def centre(self) -> tuple[int, int]:
        return (self.x0 + self.x1) // 2, (self.y0 + self.y1) // 2

    @property
    def clipped(self) -> bool:
        return self.y0 < SAFE_TOP or self.y1 > SAFE_BOTTOM

    @property
    def off_by(self) -> int:
        """How far to scroll to bring this row to the MIDDLE of the safe band, signed.

        Positive means the row is below the band and the list must come UP.

        ⚠ **This is the number [Driver.show] used to throw away.** It found the row,
        saw `clipped`, and then nudged a blind 600 px — up to forty times, which is
        two and a half minutes of scrolling to place a row it had already located.
        Knowing where something is and still searching for it is the waste here.

        ⚠ **Aim for the middle, not for the edge.** Asking for exactly the overhang is
        the obvious rule and it does not work: a row hanging 60 px past the bottom asks
        for a 60 px swipe, which is at or under the system's touch slop, so the list
        does not move at all — and since it moved *slightly* or not at all, the
        end-of-travel check does not fire either and the loop creeps through all twenty
        steps. That is what `'Low' never came up clear of the edges` was, for a row
        sitting a finger's width off the bottom of a list that scrolls perfectly well.
        Targeting the centre turns every correction into a decisive scroll.
        """
        if not self.clipped:
            return 0
        return (self.y0 + self.y1) // 2 - (SAFE_TOP + SAFE_BOTTOM) // 2


def _nodes(root: ET.Element) -> Iterator[Node]:
    for element in root.iter("node"):
        bounds = element.get("bounds", "")
        numbers = [int(n) for n in re.findall(r"-?\d+", bounds)]
        if len(numbers) != 4:
            continue
        yield Node(
            text=element.get("text", ""),
            desc=element.get("content-desc", ""),
            rid=element.get("resource-id", ""),
            x0=numbers[0],
            y0=numbers[1],
            x1=numbers[2],
            y1=numbers[3],
            checkable=element.get("checkable") == "true",
            checked=element.get("checked") == "true",
            clickable=element.get("clickable") == "true",
        )


def dump() -> list[Node]:
    require_vendor()
    for _ in range(3):
        adb("shell", "uiautomator", "dump", REMOTE_DUMP, check=False)
        xml = adb("shell", "cat", REMOTE_DUMP, check=False)
        if xml.strip():
            return list(_nodes(ET.fromstring(xml)))
        time.sleep(1)
    raise RuntimeError("uiautomator returned nothing three times")


def survey() -> list[str]:
    """Every label in the scrollable list, in the order it is drawn.

    ⚠ **`--dump` sees ONE viewport and the device screen is longer than one.**
    Concluding that a control is absent from a single dump is how a row that is
    merely below the fold gets written down as missing — the same mistake as reading
    a capture's silence as a decision. This scrolls from the top and accumulates, so
    an absence here is an absence from the whole screen.

    Stops early when a full nudge adds nothing, so it costs no more than the list is
    long. Read-only: it scrolls and never taps.
    """
    refuse_scroll(dump())
    to_top()
    seen: list[str] = []
    for _ in range(24):
        fresh = [n.text for n in dump() if n.text and n.text not in seen]
        if not fresh:
            break
        seen.extend(fresh)
        nudge()
    return seen


def label(nodes: list[Node], want: str) -> Node | None:
    return next((n for n in nodes if n.text == want), None)


def described(nodes: list[Node], want: str) -> Node | None:
    """Find a CLICKABLE node by its `content-desc`, for screens with no text.

    ⚠ Requires exactly ONE match. Two nodes describing themselves the same way is a
    screen this cannot aim at, and guessing between them is how the wrong control gets
    tapped — the fault this module exists to prevent.
    """
    want = want.strip().lower()
    hits = [n for n in nodes if n.clickable and n.desc.strip().lower() == want]
    if len(hits) != 1:
        return None
    return hits[0]


def switch_for(nodes: list[Node], want: str) -> Node | None:
    """The checkable node on the same row: rightmost, overlapping vertically."""
    row = label(nodes, want)
    if row is None or row.clipped:
        return None
    same_band = [
        n for n in nodes if n.checkable and not (n.y1 < row.y0 or n.y0 > row.y1)
    ]
    return max(same_band, key=lambda n: n.x0, default=None)


def tile_for(nodes: list[Node], row: Node) -> Node | None:
    """The clickable tile a segmented label names — which is NOT the label.

    ⚠ **Every segmented label in this app is `clickable="false"`.** `Movie`, `Music`,
    `Game`, `Low`, `Mid`, `High`, `Audio Mode`, `Video Mode` are all inert TextViews
    sitting UNDER the thing that takes the touch: a `relativeLayoutText{1,2,3}` tile
    holding the icon. Tapping the label's own centre lands on nothing and the app does
    not move — silently, because a tap that hits nothing looks exactly like a tap that
    hit something.

    ⚠ **This is why the 2026-08-17 spatial-mode run proved nothing.** It logged
    `tapped Movie at 236,1232`, then read a capture with no mode traffic in it and
    concluded the mode buttons "send nothing on their own". They may well not — but
    that capture cannot say so, because Movie was never selected. Verified by render
    afterwards: Music stayed lit through the whole thing.

    The tile shares the label's x-bounds EXACTLY (`Movie` is `[84,388]`, its tile is
    `[84,718][388,855]`), which is what makes this safe to key on: a plain clickable
    row like `Equalizer` has no such twin above it and falls through to the label.

    ⚠ **The gap has to be bounded, or this drives the WRONG FEATURE and says it worked.**
    Every segmented card in this app uses the same three columns, so "same x-bounds,
    somewhere above" also describes the tiles of the card above, and the card above
    that. `VoiceAware` is a gradient bar with NO per-column tiles, so asking for `Low`
    reached up into `Smart Talk` and pressed its timeout picker — `tapped Low` in the
    log, `aa 9f 03 00 01 05` on the wire, and Smart Talk left switched on at a
    different timeout. Nothing in the run looked wrong; the capture is what noticed.

    ⚠ **And the two cards are not even built the same way**, so "above" alone is the
    wrong question. Smart Talk nests its label INSIDE the tile (`5s` is
    `[216,556][256,607]`, its tile `[84,513][388,650]`); Spatial Sound puts the label
    BELOW (`Movie` `[84,866][388,902]`, tile `[84,718][388,855]`, an 11 px gap).

    ⚠ **Ask for the twin ABOVE first, and only then for a container.** Taking the
    smallest clickable *containing* the label first looks equivalent and is not: the
    whole card is clickable, so for a label that has no tile around it the smallest
    container is the CARD, and its centre is the seam between two tiles. On 2026-08-17
    that put both `Video Mode` and `Audio Mode` on the identical point `540,1325` —
    both selected Video, so a pick and its own restore did the same thing and the
    restore silently did nothing. Asking for the exact-x twin first settles it: a real
    tile shares its label's x-bounds, and a card never does.

    A container is still needed for Smart Talk's nested labels, so it stays as the
    fallback — bounded, because the point of the fallback is a tile and never a card.
    A control with neither, like VoiceAware's gradient bar, gets its inert label and
    sends nothing, which is the right outcome for something this cannot drive.
    """
    above = [
        n
        for n in nodes
        if n.clickable
        and n.x0 == row.x0
        and n.x1 == row.x1
        and 0 <= row.y0 - n.y1 < TILE_GAP
    ]
    if above:
        return max(above, key=lambda n: n.y1)
    # ⚠ **No width limit here, and there was one for half an hour.** Capping the
    # container at half the screen looked like cheap insurance against grabbing a whole
    # card; it also rejects every legitimately full-width row, and the product picker's
    # `JBL TOUR ONE M2` — whose tappable area is the device image BELOW the label,
    # inside a `[0,336][1080,1132]` row — stopped opening. The ordering above is what
    # actually prevents the card case; this was a second fix for an already-fixed bug,
    # paid for by a working one.
    x, y = row.centre
    inside = [
        n for n in nodes if n.clickable and n.x0 <= x <= n.x1 and n.y0 <= y <= n.y1
    ]
    return min(inside, key=lambda n: (n.x1 - n.x0) * (n.y1 - n.y0), default=None)


def nudge(distance: int = 600) -> None:
    """Scroll by `distance` px; positive brings content UP (moves the list down).

    ⚠ Small and settled. A big swipe FLINGS, carrying past the row wanted — so the
    travel is capped rather than trusted, and a long move is several short ones.

    ⚠ The original wrote its end coordinate into the X slot:
    `input swipe 540 1500 <1500-distance> 400`, where argv is `x1 y1 x2 y2`. So every
    scroll was a DIAGONAL from (540,1500) to (900,400) whatever `distance` said, and
    the parameter did nothing. That is why a "600 px" nudge and a "60 px" nudge moved
    the list identically, and why placing a row took twenty tries.
    """
    while distance:
        step = max(-MAX_SWIPE, min(MAX_SWIPE, distance))
        adb("shell", "input", "swipe", "540", "1500", "540", str(1500 - step), "400")
        distance -= step
        time.sleep(0.6)


def to_top() -> None:
    for _ in range(12):
        adb("shell", "input", "swipe", "540", "700", "540", "1500", "400")
    time.sleep(1)


@dataclass
class Driver:
    wait: float = 3.0
    baseline: dict[str, bool] = field(default_factory=dict)

    def show(self, want: str) -> list[Node] | None:
        """Bring a label on screen AND clear of both edges.

        ⚠ **Says what it is doing while it hunts, and that is not decoration.** A
        lookup can nudge forty times before its one tap, and the old version printed
        nothing until the tap landed — so a working run and a wedged one looked
        identical from the outside, which is a thing Pippijn has now had to ask about
        twice. Scrolling with no explanation is indistinguishable from stuck.
        """
        for attempt in range(2):
            previous: tuple[tuple[str, int], ...] | None = None
            for step in range(20):
                nodes = dump()
                found = label(nodes, want)
                if found is not None and not found.clipped:
                    if step:
                        say(f"    found '{want}' after {step} scrolls")
                    return nodes
                if step == 0:
                    say(f"    scrolling to '{want}'…")
                # ⚠ **A blind sweep only goes DOWN, so it cannot find a row that is
                # above it.** Asking for 'Spatial Sound' while parked on 'Smart Audio
                # & Video' spent twenty nudges pressed against the bottom of the list
                # and two minutes of wall clock, then found it one scroll after
                # `to_top`. Nothing was wrong with the aim — the row simply was not
                # ahead. Detect the end of travel instead of sweeping into it: if a
                # nudge moved nothing, this is an end of the list and the remaining
                # steps cannot help.
                here = tuple((n.text, n.y0) for n in nodes if n.text)
                if found is None and here == previous:
                    say(f"    an end of the list, and no '{want}' this way")
                    break
                previous = here
                # ⚠ Checked with the nodes already in hand, right before the only
                # swipe in this loop — so a hazardous screen costs no extra dump.
                refuse_scroll(nodes)
                # ⚠ If the row IS on screen and merely clipped, scroll by exactly
                # what it is off by. Only when it is nowhere in the dump is a blind
                # sweep the right move — and then a whole viewport, not 600 px.
                nudge(found.off_by if found is not None else 900)
            if attempt == 0:
                say(f"    '{want}' not seen in one pass — back to the top to retry")
                to_top()
        say(f"!! '{want}' never came up clear of the edges")
        return None

    def tap(self, node: Node, what: str) -> None:
        require_vendor()
        x, y = node.centre
        adb("shell", "input", "tap", str(x), str(y))
        say(f"tapped {what:<34} at {x},{y}")

    def flip(self, want: str) -> bool:
        """Flip a switch and PROVE it flipped."""
        refuse_forbidden(want)
        nodes = self.show(want)
        if nodes is None:
            return False
        before = switch_for(nodes, want)
        if before is None:
            say(f"!! '{want}' has no switch on its row")
            return False
        # How it was found, once, before this run touched it. That — not a count of
        # flips — is what "restored" has to be measured against.
        self.baseline.setdefault(want, before.checked)
        # ⚠ **A flip that comes straight after another one can be swallowed**, and the
        # second half of a cycle is exactly that. `Smart Audio & Video` went True ->
        # False and then refused the restoring tap seconds later; the identical tap,
        # by hand and a little later, worked. So one retry, rather than reporting "not
        # driven" about a switch that is merely busy — the cost of being wrong here is
        # leaving Pippijn's headphones changed.
        for attempt in range(2):
            if attempt:
                say(f"    '{want}' did not take; one retry")
                time.sleep(self.wait)
            self.tap(before, want)
            time.sleep(self.wait)
            after = switch_for(dump(), want)
            if after is not None and after.checked != before.checked:
                say(f"    '{want}' {before.checked} -> {after.checked}")
                return True
        say(f"!! '{want}' did not move (still {before.checked}) — not driven")
        return False

    def note(self, want: str) -> None:
        """Record how a switch sits BEFORE the group touches anything.

        ⚠ **`flip` taking its own baseline is too late when a `pick` moves the switch
        first.** A Spatial Sound pick turns the feature on, so by the time the
        restoring flip runs, "before" reads `True` — and the run would end by
        announcing that it had left ON something it had just correctly put back to OFF.
        The same false alarm as the flip counter, one layer down.
        """
        nodes = self.show(want)
        found = switch_for(nodes, want) if nodes else None
        if found is not None:
            self.baseline.setdefault(want, found.checked)

    def unrestored(self) -> list[str]:
        """Re-READ every switch this run touched and compare with how it was found.

        ⚠ **Replaces a parity counter that could not tell a restore from a change.**
        The old rule was "odd number of flips means left changed", which is only right
        when a group's flips come in pairs. `spatial-mode` restores with a SINGLE
        deliberate flip — the picks having turned the switch on — so the counter cried
        `LEFT CHANGED, restore by hand: Spatial Sound` about a device that was already
        back where it started. A warning that fires on a correct restore is one that
        gets ignored on a real one.

        Costs a scroll per touched switch, at the end of a run, and answers the only
        question worth asking: is it as Pippijn left it?
        """
        changed = []
        for want, was in self.baseline.items():
            nodes = self.show(want)
            now = switch_for(nodes, want) if nodes else None
            if now is None:
                changed.append(f"{want} (could not re-read)")
            elif now.checked != was:
                changed.append(f"{want} (found {was}, now {now.checked})")
        return changed

    def pick(self, want: str) -> bool:
        """Tap a segmented option, on its TILE rather than its label.

        ⚠ Still no state to read back — the selection is drawn in colour and is absent
        from the accessibility tree (`selected="false"` on the lit one too). So this
        reports whether it *tapped*, never whether it *took*; confirming a pick needs a
        screenshot. See [tile_for] for what taking the label at its word cost.
        """
        refuse_forbidden(want)
        nodes = self.show(want)
        if nodes is None:
            return False
        node = label(nodes, want)
        if node is None:
            return False
        tile = tile_for(nodes, node)
        self.tap(tile or node, want if tile else f"{want} (no tile — label itself)")
        time.sleep(self.wait)
        return True

    def open_screen(self, want: str) -> bool:
        """Open a sub-screen and come straight back.

        ⚠ Opening is a READ — the screen fires its own getter — so this names a
        command with no write at all. Personi-Fi is opened and left immediately: its
        flow is a hearing test that plays tones, and nobody's ears are an instrument
        for a protocol map.
        """
        if not self.pick(want):
            say(f"    (skipped '{want}' — not reachable)")
            return False
        time.sleep(2)
        adb("shell", "input", "keyevent", "KEYCODE_BACK", check=False)
        time.sleep(3)
        return True

    def cycle(self, want: str) -> bool:
        return self.flip(want) and self.flip(want)

    def enter(self, want: str) -> bool:
        """Tap a `content-desc` affordance to NAVIGATE — not to change a setting.

        Separate from [tap] on purpose: this one is for gears and back-arrows, whose
        effect is a screen change rather than a value, so there is no switch to read
        back and prove it moved. It reports the activity it landed on instead.
        """
        node = described(dump(), want)
        if node is None:
            say(f"!! nothing clickable is described '{want}' on this screen")
            return False
        self.tap(node, want)
        time.sleep(self.wait)
        say(f"    entered '{want}' -> {focused_activity()}")
        return True


def preflight(driver: Driver, ready: str, landing_scrolls: bool) -> None:
    """Release our link, put the VENDOR app in front, and prove it is there.

    ⚠ Two separate facts, and conflating them is what went wrong: the app must be
    FOCUSED (so taps land on it) *and* CONNECTED (so they reach the headphones).
    """
    say("preflight: releasing our own GATT link")
    wake()
    if locked():
        raise RuntimeError("the phone is locked — unlock it and re-run")
    adb("shell", "am", "force-stop", OURS, check=False)
    # ⚠⚠ **THE VENDOR APP TOO, or "launch" means "resume wherever it was left".** The
    # readiness check looks for a battery reading, and that is drawn on the app's LANDING
    # screen — so an app resumed deep in its own settings never satisfies it, polls the
    # full budget, and reports "never came up focused and connected" about a phone that is
    # fine. Measured 2026-08-30 on Bose Connect, which came back on
    # `ProductSettingsActivity` because that is where the previous command left it.
    #
    # ⚠ Same failure the scroll-position note below describes, one level up: there the
    # check depended on where the LIST was scrolled, here on which ACTIVITY is resumed.
    # A readiness check that depends on prior state reports the prior state.
    adb("shell", "am", "force-stop", VENDOR, check=False)
    time.sleep(2)
    adb(
        "shell", "monkey", "-p", VENDOR, "-c",
        "android.intent.category.LAUNCHER", "1", check=False,
    )
    for _ in range(40):
        time.sleep(3)
        if focused_package() != VENDOR:
            # ⚠ Not just patience. A dark screen and a pulled-down shade both hold
            # focus away from the app for as long as the loop is willing to wait, and
            # neither clears itself — so waiting is the one thing that cannot work.
            wake()
            if locked():
                raise RuntimeError("the phone locked mid-run — unlock it and re-run")
            continue
        # Connected == the app draws a battery reading. ⚠ Scoped to the vendor app
        # by the focus check above: on its own this regex matched another app.
        #
        # ⚠⚠ **THE SHAPE OF THAT READING IS PER-APP, so the caller supplies it.** JBL
        # draws `70%`; Bose Connect draws `70`, with no percent sign — measured
        # 2026-08-30. A hardcoded `\d+%` was correct for the app it was written against
        # and silently never matches the other one, which costs the poll's FULL budget
        # and then reports "never came up focused and connected" about a healthy phone.
        # That is the same false negative this function already warns about, by a new
        # route: the readiness check reporting on itself rather than on the device.
        #
        # ⚠ `dump()` asserts focus AGAIN and raises if it has moved, so it must be
        # caught HERE. Launching the app puts the launcher in front for an instant,
        # and letting that escape aborts the whole run during the very loop whose job
        # is to wait for the app to settle — a retry loop that cannot retry.
        try:
            # ⚠ **The battery reading is at the TOP of the list, so this check is
            # only valid from the top.** Left scrolled down by previous work, the app
            # was focused and connected and drawing its whole settings list, and
            # preflight still spent its full two minutes concluding the headphones
            # were not there — the one node it looks for was simply off screen. A
            # readiness check that depends on scroll position reports the scroll
            # position. Going to the top first also gives every run the same starting
            # place, which is what makes a scroll count mean anything.
            # ⚠⚠ **CHECK BEFORE SCROLLING, because a swipe is not always a scroll.**
            # On Bose Connect a single swipe on the landing screen NAVIGATES into
            # settings — measured 2026-08-30 — which throws away the battery reading
            # this is waiting for, so scrolling first guaranteed the check could never
            # pass. Checking first also keeps the JBL fix below intact: an app left
            # scrolled down simply fails the first look and gets `to_top`.
            settled = any(re.fullmatch(ready, n.text) for n in dump())
            # ⚠⚠ **THE FALLBACK IS IRREVERSIBLE ON SOME APPS, so it is opt-in.** Scrolling
            # to recover assumes a swipe is undoable. On Bose Connect one swipe LEAVES the
            # landing screen for good, taking the battery reading with it — so a check that
            # merely raced the app's first paint would scroll itself somewhere it can never
            # pass, and poll the full budget from there. A recovery action that can destroy
            # the condition it is recovering is worse than no recovery: without it, the next
            # poll simply looks again and succeeds.
            if not settled and landing_scrolls:
                to_top()
                settled = any(re.fullmatch(ready, n.text) for n in dump())
        except NotInVendorApp:
            continue
        if settled:
            say(f"preflight: {VENDOR} is focused and connected")
            return
    raise RuntimeError(f"{VENDOR} never came up focused and connected")


# ⚠ **Set by [configure], not a constant.** It is reached transitively — `dump` calls
# `require_vendor`, and `dump` is called from everywhere — so threading it through as
# a parameter would touch every call site to say the same thing at each one. A driver
# script sets it once, before anything runs.
VENDOR = ""

# ⚠⚠ **These two were DOCUMENTATION PRETENDING TO BE GUARDS until 2026-09-03.** Each
# driver script defined a `FORBIDDEN` dict naming what must never be tapped, and nothing
# anywhere read it — measured by grepping the repo for a consumer and finding only the
# definitions. The `groups` are read-only by construction, so a normal run was safe; but
# `--tap`, `--flip` and `--survey` bypass the groups entirely, and those are how a survey
# is actually driven by hand. `--tap "NC Optimizer"` would have started test tones into
# headphones that have to be worn, which is the exact thing the dict claimed to prevent.
#
# ⚠ **A guard that does not execute is worse than none**, because the name reads as cover.
FORBIDDEN: dict[str, str] = {}
NO_SCROLL: dict[str, str] = {}


def configure(
    package: str,
    forbidden: dict[str, str] | None = None,
    no_scroll: dict[str, str] | None = None,
) -> None:
    """Name the vendor app every check in this module asserts is in front, and its rules.

    `forbidden` maps a LABEL to why it must never be tapped. `no_scroll` maps a
    RESOURCE-ID FRAGMENT to why a swipe must not happen while it is on screen.
    """
    global VENDOR, FORBIDDEN, NO_SCROLL
    VENDOR = package
    FORBIDDEN = forbidden or {}
    NO_SCROLL = no_scroll or {}


class Refused(RuntimeError):
    """A rule in the driver script said no. ⚠ Never caught to continue past."""


def refuse_forbidden(want: str) -> None:
    """⛔ Stop before a label the driver script named as never-to-be-tapped."""
    why = FORBIDDEN.get(want)
    if why is not None:
        raise Refused(f"⛔ refusing to tap {want!r} — {why}")


def refuse_scroll(nodes: list[Node]) -> None:
    """⛔ Stop before a swipe that would land on something draggable.

    ⚠ **A scroll is a WRITE on some screens.** [nudge] swipes down the middle of the
    display, and a custom graph view takes that as a drag on whichever handle is under
    it — so surveying Sony's equaliser moves a band rather than scrolling the page. The
    handles are not `SeekBar` nodes, so nothing generic marks them draggable; the driver
    script has to name the widget.
    """
    for node in nodes:
        for fragment, why in NO_SCROLL.items():
            if fragment in node.rid:
                raise Refused(f"⛔ refusing to scroll — {fragment} is on screen: {why}")


def run(
    groups: dict[str, Callable[[Driver], None]],
    *,
    package: str,
    banner: str,
    description: str,
    default: str,
    ready: str,
    landing_scrolls: bool,
    forbidden: dict[str, str] | None = None,
    no_scroll: dict[str, str] | None = None,
) -> int:
    configure(package, forbidden, no_scroll)
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("groups", nargs="*", help="which groups to run")
    parser.add_argument("--list", action="store_true", help="print group names")
    parser.add_argument("--dump", action="store_true", help="print every label ON SCREEN")
    parser.add_argument(
        "--survey", action="store_true", help="scroll the whole list and print every label"
    )
    parser.add_argument("--state", metavar="LABEL", help="print a switch's value")
    parser.add_argument("--tap", metavar="LABEL", help="scroll to a label and tap it")
    # ⚠ Restoring by hand was raw `input tap` at coordinates read out of a dump, twice,
    # which is the one operation on these headphones where a wrong number is a setting
    # silently changed. `flip` proves the switch moved; the coordinates do not.
    parser.add_argument("--flip", metavar="LABEL", help="flip one switch and prove it moved")
    # ⚠ **Checking the aim used to mean firing.** `--tap` was the only way to find out
    # where a label resolves to, so verifying [tile_for] across eight controls meant
    # eight real writes — which on 2026-08-17 left Spatial Sound on Game, Smart Talk on
    # 20 s, and both switched on, all from a diagnostic. A question about coordinates
    # should not cost a setting.
    parser.add_argument("--where", metavar="LABEL", help="say where a label resolves, tap nothing")
    args = parser.parse_args()

    if args.list:
        for name in groups:
            print(name)
        return 0

    # ⚠ Resolved HERE, before anything touches the phone, so an ambiguous adb is one
    # clear line rather than a traceback out of whichever call happened to be first.
    # ⚠ After `--list`, which is the one thing worth answering with no device attached.
    try:
        adb_target()
    except RuntimeError as which:
        print(which, file=sys.stderr)
        return 2

    # ⚠ Both of these assert the vendor app is in front, via `dump`. An earlier
    # audit read states with no such check and would have believed another app.
    if args.dump:
        for text in sorted({n.text for n in dump() if n.text}):
            print(text)
        return 0

    # ⚠ Not sorted, unlike --dump: the drawing order is the information. Which rows
    # sit under which heading is what says whether a level control belongs to
    # VoiceAware or to the row below it.
    if args.survey:
        # ⚠ The ad-hoc flags are the UNGUARDED route — the groups are read-only by
        # construction, these are not — so each one that scrolls or taps catches the
        # refusal itself rather than dying in a traceback.
        try:
            for text in survey():
                print(text)
        except Refused as no:
            print(no, file=sys.stderr)
            return 3
        return 0

    if args.tap:
        # ⚠ Goes through `pick`, which retargets a segmented label onto its tile. This
        # had its own copy of scroll-then-tap-the-label, so `--tap Movie` and a run's
        # own `pick("Movie")` did different things — and the debug affordance used to
        # check the driver was the one that was wrong.
        try:
            if not Driver().pick(args.tap):
                print(f"could not reach '{args.tap}'", file=sys.stderr)
                return 2
        except Refused as no:
            print(no, file=sys.stderr)
            return 3
        return 0

    if args.flip:
        try:
            return 0 if Driver().flip(args.flip) else 2
        except Refused as no:
            print(no, file=sys.stderr)
            return 3

    if args.where:
        driver = Driver()
        try:
            nodes = driver.show(args.where)
        except Refused as no:
            print(no, file=sys.stderr)
            return 3
        node = label(nodes, args.where) if nodes is not None else None
        if nodes is None or node is None:
            print(f"could not reach '{args.where}'", file=sys.stderr)
            return 2
        tile = tile_for(nodes, node)
        target = tile or node
        print(f"{target.centre[0]},{target.centre[1]}  {'tile' if tile else 'LABEL — inert'}")
        return 0

    if args.state:
        # ⚠ Scrolls, like `--tap`. It used to read only what was already drawn, so
        # "no switch for X" meant either *this row has no switch* or *the row is
        # further down the list* — two answers that want opposite next moves, and
        # the second is the common one. `show` collapses them: after it returns, a
        # missing switch is a fact about the row.
        driver = Driver()
        try:
            nodes = driver.show(args.state)
        except Refused as no:
            print(no, file=sys.stderr)
            return 3
        found = switch_for(nodes, args.state) if nodes else None
        if found is None:
            print(f"no switch on the '{args.state}' row", file=sys.stderr)
            return 2
        print("true" if found.checked else "false")
        return 0

    chosen = args.groups or [default]
    unknown = [g for g in chosen if g not in groups]
    if unknown:
        print(f"unknown group(s): {', '.join(unknown)}", file=sys.stderr)
        return 2

    driver = Driver()
    say(banner)
    # ⚠ The phone's own idle timer is part of the test rig, so it is borrowed for the
    # run and given back in `finally` — including on Ctrl-C, because leaving someone's
    # screen set to never sleep is not a thing to do silently.
    was = screen_timeout()
    # ⚠ The policy maximum, NOT a large number: anything above it is silently capped and
    # reads back as whatever was written. See [screen_timeout].
    say(f"screen sleep pushed to the {POLICY_MAX_MS} ms ceiling for the run (was {was} ms)")
    screen_timeout(POLICY_MAX_MS)
    try:
        preflight(driver, ready, landing_scrolls)
        for name in chosen:
            require_vendor()
            groups[name](driver)
    except (NotInVendorApp, RuntimeError) as failure:
        say(f"!! stopped: {failure}")
    finally:
        if was.isdigit():
            screen_timeout(was)
        # ⚠ However this ends, say what was left moved — by RE-READING, not by
        # counting. See [Driver.unrestored].
        #
        # ⚠ Still switches only. A `pick` has no state to read back (the selection is
        # colour, absent from the tree), so a group whose actions are all picks can
        # leave the device changed and print the reassuring line. `spatial-mode`
        # handles that by ending on the mode it found; nothing here can verify it.
        try:
            left = driver.unrestored()
        except (NotInVendorApp, RuntimeError) as failure:
            say(f"!! could not verify the restore: {failure}")
            left = sorted(driver.baseline)
        if left:
            say(f"!! LEFT CHANGED, restore by hand: {', '.join(left)}")
        else:
            say("=== every switch this run touched re-reads as it was found ===")
    return 0

