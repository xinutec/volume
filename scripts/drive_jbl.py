#!/usr/bin/env python3
"""Drive the JBL vendor app through its own rows, for a protocol capture.

Everything general lives in `vendor_drive`, including the four faults this way of
driving exists to prevent — read that docstring before changing anything here. What
is JBL in this file is the package name, the sub-screen list and the groups.

Usage:
    scripts/drive_jbl.py --list
    scripts/drive_jbl.py screens
    scripts/drive_jbl.py smarttalk voiceaware
"""

from __future__ import annotations

import sys
from typing import Callable

from vendor_drive import Driver, dump, run, say, switch_for, tile_for

VENDOR = "jbl.stc.com"

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
    return run(
        GROUPS,
        package=VENDOR,
        banner="=== JBL capture begins — every change is undone before the next starts ===",
        description=__doc__ or "",
        default="screens",
    )


if __name__ == "__main__":
    sys.exit(main())
