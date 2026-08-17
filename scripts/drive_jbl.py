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

    @property
    def centre(self) -> tuple[int, int]:
        return (self.x0 + self.x1) // 2, (self.y0 + self.y1) // 2

    @property
    def clipped(self) -> bool:
        return self.y0 < SAFE_TOP or self.y1 > SAFE_BOTTOM

    @property
    def off_by(self) -> int:
        """How far to scroll to bring this row into the safe band, signed.

        Positive means the row is below the band and the list must come UP.

        ⚠ **This is the number [Driver.show] used to throw away.** It found the row,
        saw `clipped`, and then nudged a blind 600 px — up to forty times, which is
        two and a half minutes of scrolling to place a row it had already located.
        Knowing where something is and still searching for it is the waste here.
        """
        if self.y1 > SAFE_BOTTOM:
            return self.y1 - SAFE_BOTTOM
        if self.y0 < SAFE_TOP:
            return self.y0 - SAFE_TOP
        return 0


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
    outstanding: list[str] = field(default_factory=list)

    def show(self, want: str) -> list[Node] | None:
        """Bring a label on screen AND clear of both edges.

        ⚠ **Says what it is doing while it hunts, and that is not decoration.** A
        lookup can nudge forty times before its one tap, and the old version printed
        nothing until the tap landed — so a working run and a wedged one looked
        identical from the outside, which is a thing Pippijn has now had to ask about
        twice. Scrolling with no explanation is indistinguishable from stuck.
        """
        for attempt in range(2):
            for step in range(20):
                nodes = dump()
                found = label(nodes, want)
                if found is not None and not found.clipped:
                    if step:
                        say(f"    found '{want}' after {step} scrolls")
                    return nodes
                if step == 0:
                    say(f"    scrolling to '{want}'…")
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
        self.tap(before, want)
        time.sleep(self.wait)
        after = switch_for(dump(), want)
        if after is None or after.checked == before.checked:
            say(f"!! '{want}' did not move (still {before.checked}) — not driven")
            return False
        say(f"    '{want}' {before.checked} -> {after.checked}")
        if want in self.outstanding:
            self.outstanding.remove(want)
        else:
            self.outstanding.append(want)
        return True

    def pick(self, want: str) -> bool:
        """Tap a segmented option. No state to read back; the capture is the proof."""
        nodes = self.show(want)
        if nodes is None:
            return False
        node = label(nodes, want)
        if node is None:
            return False
        self.tap(node, want)
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
    adb("shell", "am", "force-stop", OURS, check=False)
    time.sleep(2)
    adb(
        "shell", "monkey", "-p", VENDOR, "-c",
        "android.intent.category.LAUNCHER", "1", check=False,
    )
    for _ in range(40):
        time.sleep(3)
        if focused_package() != VENDOR:
            continue
        # Connected == the app draws a battery reading. ⚠ Scoped to the vendor app
        # by the focus check above: on its own this regex matched another app.
        #
        # ⚠ `dump()` asserts focus AGAIN and raises if it has moved, so it must be
        # caught HERE. Launching the app puts the launcher in front for an instant,
        # and letting that escape aborts the whole run during the very loop whose job
        # is to wait for the app to settle — a retry loop that cannot retry.
        try:
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
    picker was never touched. Spatial Sound taught the shape of the answer: its mode
    buttons send NOTHING on their own and the mode rides along with the next on/off
    write. So picking a level and reading the capture would prove nothing; the level
    has to be picked and then the switch flipped.

    Two full cycles at two different levels, restoring Mid last. If `02` is the
    level, the two ON writes differ in exactly that byte and in nothing else.
    """
    say("--- VoiceAware LEVEL: Low, then High, restoring Mid ---")
    for level in ("Low", "High", "Mid"):
        if not d.pick(level):
            say(f"!! could not pick '{level}' — stopping before the flip")
            return
        say(f"    picked {level}; now the on/off that should carry it")
        if not d.cycle("VoiceAware"):
            return


def group_spatial_mode(d: Driver) -> None:
    """⚠ The ablation this file's docs asked for and nobody had run.

    `docs/protocols.md`: "tapping Movie and *then* toggling would show a mode byte
    other than `01`, and that has not been done." This is that, and it also settles a
    second question — whether the mode buttons reach the DEVICE at all, or are purely
    app-side until something commits them.

    Found OFF on Music, and Music is restored last.
    """
    say("--- Spatial Sound MODE: Movie then toggle, restoring Music ---")
    for mode in ("Movie", "Game", "Music"):
        if not d.pick(mode):
            say(f"!! could not pick '{mode}'")
            return
        say(f"    picked {mode}; now the on/off that should carry it")
        if not d.cycle("Spatial Sound"):
            return


def group_smartav(d: Driver) -> None:
    """⚠ Smart Audio & Video's payload is LOCATED, not decoded.

    `aa 81 08 00 01 35 00 <v> 00 ff ff` moves one byte: `e6` off, `96` on. That is
    not a boolean, and 230/150 looked like milliseconds of latency — written down as
    a guess. Two named modes sit on this row, so if the byte is latency it should
    take a third and fourth value here rather than flipping between two.
    """
    say("--- Smart Audio & Video: Audio Mode / Video Mode ---")
    for mode in ("Audio Mode", "Video Mode"):
        if not d.pick(mode):
            say(f"!! could not pick '{mode}'")
            return


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
        driver = Driver()
        nodes = driver.show(args.tap)
        node = label(nodes, args.tap) if nodes else None
        if node is None:
            print(f"could not reach '{args.tap}'", file=sys.stderr)
            return 2
        driver.tap(node, args.tap)
        return 0

    if args.state:
        found = switch_for(dump(), args.state)
        if found is None:
            print(f"no switch for '{args.state}' on screen", file=sys.stderr)
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
    try:
        preflight(driver)
        for name in chosen:
            require_vendor()
            GROUPS[name](driver)
    except (NotInVendorApp, RuntimeError) as failure:
        say(f"!! stopped: {failure}")
    finally:
        # ⚠ However this ends, say what was left moved. A run that does not print
        # "all restored" left something on the headphones.
        if driver.outstanding:
            say(f"!! LEFT CHANGED, restore by hand: {', '.join(driver.outstanding)}")
        else:
            say("=== all restored ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
