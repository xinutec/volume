#!/usr/bin/env python3
"""Drive the JBL vendor app through its own rows, for a protocol capture.

The method is `docs/captures.md`: every change is followed immediately by its
inverse, so the differing bytes are the field and nothing is left moved. Each line
printed here becomes a row of the timeline that labels the capture — without it a
snoop log is a haystack.

⚠ **This replaced a shell version, and the reason is the bug list, not taste.**
Four faults reached the phone, each one a check that did not exist:

  1. A tap on a row clipped by the header could hand back the NEIGHBOUR's switch —
     the wrong setting moving while the log printed the label that was asked for.
  2. Our own app holds the JBL's LE GATT link, which takes ONE client. While it did,
     the vendor app greyed out and every tap landed on a dead UI, so nothing reached
     the wire — indistinguishable in the capture from a control the app keeps to
     itself.
  3. A run that aborted between a change and its inverse left the setting changed.
  4. ⚠ **It never checked WHICH APP was in front.** A whole run drove the agent
     console: `uiautomator dump` returns the FOCUSED window, the vendor app was not
     even launched, and the readiness check — "is a battery percentage on screen" —
     matched something else entirely. Ten minutes of swiping a chat transcript.

Every one of those is a precondition, and shell had grown a state machine, retry
loops, restore tracking and an embedded Python XML parser to hold them. The fleet's
rule (`feedback_move_off_shell_scripts`) is that shell gets no gate and a heredoc'd
language is invisible to every engine we have — which is exactly how four
preconditions went missing without anything going red.

Usage:
    scripts/drive_jbl.py --list
    scripts/drive_jbl.py screens
    scripts/drive_jbl.py smarttalk voiceaware
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from typing import Callable, Iterator

VENDOR = "jbl.stc.com"
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


class NotInVendorApp(RuntimeError):
    """The foreground app is not the one we mean to drive."""


def adb(*args: str, check: bool = True) -> str:
    out = subprocess.run(
        ["adb", *args], capture_output=True, text=True, check=False
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


def screen_timeout(value: str | None = None) -> str:
    """Read, or set, the display sleep in milliseconds.

    ⚠ **A run can put the phone to sleep and then wait for it to wake.** `dumpsys` and
    `uiautomator dump` are not touches, so preflight's poll loop — two minutes of
    reading and no tapping — lets the idle timer run out. On 2026-08-17 the last tap
    was 10:49:05, the screen went dark at 10:54 on a five-minute timeout, and preflight
    spent its full budget waiting for an app it had itself sent to the lock screen,
    then reported "never came up focused and connected". The device was fine.
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
    BELOW (`Movie` `[84,866][388,902]`, tile `[84,718][388,855]`, an 11 px gap). So:
    prefer the clickable that contains the label, fall back to one immediately above
    it, and otherwise leave it alone — a gradient bar has neither, and tapping its
    inert label sends nothing, which is the right outcome for a control this cannot
    drive.
    """
    x, y = row.centre
    inside = [
        n for n in nodes if n.clickable and n.x0 <= x <= n.x1 and n.y0 <= y <= n.y1
    ]
    if inside:
        return min(inside, key=lambda n: (n.x1 - n.x0) * (n.y1 - n.y0))
    above = [
        n
        for n in nodes
        if n.clickable
        and n.x0 == row.x0
        and n.x1 == row.x1
        and 0 <= row.y0 - n.y1 < TILE_GAP
    ]
    return max(above, key=lambda n: n.y1, default=None)


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


def preflight(driver: Driver) -> None:
    """Release our link, put the VENDOR app in front, and prove it is there.

    ⚠ Two separate facts, and conflating them is what went wrong: the app must be
    FOCUSED (so taps land on it) *and* CONNECTED (so they reach the headphones).
    """
    say("preflight: releasing our own GATT link")
    wake()
    if locked():
        raise RuntimeError("the phone is locked — unlock it and re-run")
    adb("shell", "am", "force-stop", OURS, check=False)
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
            to_top()
            settled = any(re.fullmatch(r"\d+%", n.text) for n in dump())
        except NotInVendorApp:
            continue
        if settled:
            say(f"preflight: {VENDOR} is focused and connected")
            return
    raise RuntimeError(f"{VENDOR} never came up focused and connected")


SCREENS = [
    "Personi-Fi", "Customize ANC", "Equalizer", "Gestures", "SilentNow",
    "Auracast", "Personal Sound Amplification", "Voice Assistant", "Voice Prompts",
]


def group_screens(d: Driver) -> None:
    say("--- opening each sub-screen; back out of every one ---")
    for row in SCREENS:
        d.open_screen(row)


def group_asc(d: Driver) -> None:
    say("--- Ambient Sound Control master switch (found ON) ---")
    d.cycle("Ambient Sound Control")


def group_lvdeq(d: Driver) -> None:
    say("--- Low Volume Dynamic EQ (found ON) ---")
    d.cycle("Low Volume Dynamic EQ")


def group_spatial(d: Driver) -> None:
    say("--- Spatial Sound (found OFF, Music) ---")
    if d.flip("Spatial Sound"):
        for option in ("Movie", "Game", "Music"):
            d.pick(option)
        d.flip("Spatial Sound")


def group_smarttalk(d: Driver) -> None:
    say("--- Smart Talk (found OFF, 5s) ---")
    if d.flip("Smart Talk"):
        for option in ("15s", "20s", "5s"):
            d.pick(option)
        d.flip("Smart Talk")


def group_voiceaware(d: Driver) -> None:
    say("--- VoiceAware (found OFF, Mid) ---")
    d.cycle("VoiceAware")


def group_vaware_level(d: Driver) -> None:
    """⚠ The ablation for VoiceAware's unexplained `02` — is it the LEVEL?

    `aa 98 03 00 02 <on>` has a byte nobody has varied, because the Low/Mid/High
    picker was never touched.

    ⚠ **This used to cite Spatial Sound's "the mode buttons send NOTHING on their own"
    as settled, and build on it.** That claim came from a run whose taps missed (see
    [tile_for]); on the tile, a Spatial Sound pick both selects the mode and switches
    the feature on. So the reasoning that a level "has to be picked and then the switch
    flipped" rested on nothing.

    ⚠ **It does not follow that VoiceAware behaves the same way**, and assuming it does
    would repeat the original mistake in the other direction — this is a gradient bar
    with three labels under it, not three tiles. So each level is picked AND the switch
    cycled: if the pick writes, that frame stands on its own; if it does not, the cycle
    still carries the level. Dropping the cycle would have been an over-correction that
    could come back with nothing at all.

    The read already puts a number on the guess: at rest the getter answers
    `aa 98 01 01` → `aa 98 03 02 02 00` with the render showing Mid, so byte 4 is `02`
    at Mid. If it is the level, Low and High make it `01` and `03`.

    Found at Mid with the switch OFF, and Mid is picked last.
    """
    say("--- VoiceAware LEVEL: pick, then cycle so a write carries it ---")
    d.note("VoiceAware")
    for level in ("Low", "High", "Mid"):
        if not d.pick(level):
            say(f"!! could not pick '{level}'")
            return
        say(f"    picked {level}; now a cycle to carry it")
        if not d.cycle("VoiceAware"):
            return


def group_spatial_mode(d: Driver) -> None:
    """⚠ The ablation this file's docs asked for and nobody had run.

    `docs/protocols.md`: "tapping Movie and *then* toggling would show a mode byte
    other than `01`, and that has not been done." This is that, and it also settles a
    second question — whether the mode buttons reach the DEVICE at all, or are purely
    app-side until something commits them.

    ⚠ **REWRITTEN 2026-08-17 — the premise above is wrong, and so was the run.** The
    old version tapped the mode LABEL, which is inert (see [tile_for]), so it never
    selected anything; the capture it produced was empty of mode traffic and that
    emptiness became the "modes send nothing on their own" claim in `docs/protocols.md`.

    On the tile, picking a mode is itself a write: tapping Movie with Spatial Sound OFF
    selected Movie **and turned the switch ON**, confirmed by render. So the mode does
    not need a toggle to carry it and there is no need to cycle at all — each pick is
    one labelled write, and three picks give three frames differing in the mode byte.

    Found on Music with the switch OFF, and that is what the restore returns to.
    """
    say("--- Spatial Sound MODE: three picks, each its own write ---")
    d.note("Spatial Sound")
    for mode in ("Movie", "Game", "Music"):
        if not d.pick(mode):
            say(f"!! could not pick '{mode}'")
            return
    say("    restoring: Music is picked, now the switch back OFF")
    if switch_for(dump(), "Spatial Sound") is None:
        say("!! no Spatial Sound switch to restore")
        return
    if not d.flip("Spatial Sound"):
        say("!! LEFT ON — Spatial Sound needs turning off by hand")


def group_smartav(d: Driver) -> None:
    """⚠ Smart Audio & Video's payload is LOCATED, not decoded.

    `aa 81 08 00 01 35 00 <v> 00 ff ff` moves one byte: `e6` off, `96` on. That is
    not a boolean, and 230/150 looked like milliseconds of latency — written down as
    a guess. Two named modes sit on this row, so if the byte is latency it should
    take a third and fourth value here rather than flipping between two.

    ⚠ **This group used to pick the two modes and stop**, and it picked the LABEL,
    which sends nothing at all (see [tile_for]) — so the capture window would have been
    empty and the emptiness would have read as a finding about the protocol. The picks
    go to the tile now, and the switch is cycled after each one: whether a pick writes
    on its own is exactly what is unknown here, and the cycle means the run returns
    something either way.

    ⚠ **Which mode is live is NOT in the accessibility tree.** Both labels come back
    `selected="false" checkable="false"` — the selection is drawn in colour only. It
    was read off a screenshot: Audio Mode, switch ON. So the restore target here is a
    constant rather than something the driver can check, and that is why Audio Mode is
    picked last instead of first.

    The read gives the resting value: `aa 82 00` → `aa 83 08 00 01 35 00 96 00 ff ff`,
    so `96` is Audio Mode with the switch ON. If the byte is the MODE, Video Mode makes
    it something else; if it is latency, `e6`/`96` should not be the only two values.
    """
    say("--- Smart Audio & Video: Video Mode, restoring Audio Mode ---")
    d.note("Smart Audio & Video")
    try:
        for mode in ("Video Mode", "Audio Mode"):
            if not d.pick(mode):
                say(f"!! could not pick '{mode}'")
                return
            say(f"    picked {mode}; now the on/off that should carry it")
            if not d.cycle("Smart Audio & Video"):
                return
    finally:
        # ⚠ **The mode is restored however this exits.** Bailing out on a failed cycle
        # used to skip the restoring pick, so a run that stopped to avoid doing harm
        # left the headphones on Video Mode — and, the selection being invisible to the
        # tree, `unrestored` could not see it either. The switch was reported; the mode
        # was silent. A picked mode is cheap to re-assert and the only way back.
        d.pick("Audio Mode")


def group_leaudio(d: Driver) -> None:
    """⚠ Re-negotiates the link, so it runs last of the toggles."""
    say("--- LE Audio (found OFF) — connection-disturbing ---")
    d.cycle("LE Audio")


def group_maxvol(d: Driver) -> None:
    """⚠ Hearing protection, driven ONCE with Pippijn's explicit approval.

    Driven through the app's own switch rather than by composing a write: the set
    frame for `aa a5` has never been observed, and guessing bytes at the one command
    that governs how loud these can get is the guess not to make. Off and straight
    back on, both halves verified by reading the switch.
    """
    say("--- Max Volume Limiter (found ON) — ⚠ approved once, restored immediately ---")
    if not d.cycle("Max Volume Limiter"):
        say("!! the limiter did not cycle cleanly — CHECK IT BY HAND")


GROUPS: dict[str, Callable[[Driver], None]] = {
    "maxvol": group_maxvol,
    "screens": group_screens,
    "asc": group_asc,
    "lvdeq": group_lvdeq,
    "spatial": group_spatial,
    "smarttalk": group_smarttalk,
    "voiceaware": group_voiceaware,
    "vaware-level": group_vaware_level,
    "spatial-mode": group_spatial_mode,
    "smartav": group_smartav,
    "leaudio": group_leaudio,
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
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
    args = parser.parse_args()

    if args.list:
        for name in GROUPS:
            print(name)
        return 0

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
        for text in survey():
            print(text)
        return 0

    if args.tap:
        # ⚠ Goes through `pick`, which retargets a segmented label onto its tile. This
        # had its own copy of scroll-then-tap-the-label, so `--tap Movie` and a run's
        # own `pick("Movie")` did different things — and the debug affordance used to
        # check the driver was the one that was wrong.
        if not Driver().pick(args.tap):
            print(f"could not reach '{args.tap}'", file=sys.stderr)
            return 2
        return 0

    if args.flip:
        return 0 if Driver().flip(args.flip) else 2

    if args.state:
        # ⚠ Scrolls, like `--tap`. It used to read only what was already drawn, so
        # "no switch for X" meant either *this row has no switch* or *the row is
        # further down the list* — two answers that want opposite next moves, and
        # the second is the common one. `show` collapses them: after it returns, a
        # missing switch is a fact about the row.
        driver = Driver()
        nodes = driver.show(args.state)
        found = switch_for(nodes, args.state) if nodes else None
        if found is None:
            print(f"no switch on the '{args.state}' row", file=sys.stderr)
            return 2
        print("true" if found.checked else "false")
        return 0

    chosen = args.groups or ["screens"]
    unknown = [g for g in chosen if g not in GROUPS]
    if unknown:
        print(f"unknown group(s): {', '.join(unknown)}", file=sys.stderr)
        return 2

    driver = Driver()
    say("=== JBL capture begins — every change is undone before the next starts ===")
    # ⚠ The phone's own idle timer is part of the test rig, so it is borrowed for the
    # run and given back in `finally` — including on Ctrl-C, because leaving someone's
    # screen set to never sleep is not a thing to do silently.
    was = screen_timeout()
    say(f"screen sleep held off for the run (was {was} ms)")
    screen_timeout("1800000")
    try:
        preflight(driver)
        for name in chosen:
            require_vendor()
            GROUPS[name](driver)
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


if __name__ == "__main__":
    sys.exit(main())
