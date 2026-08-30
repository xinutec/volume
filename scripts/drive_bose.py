#!/usr/bin/env python3
"""Drive Bose Connect through its own rows, for a protocol capture.

Everything general lives in `vendor_drive`, including the four faults this way of
driving exists to prevent — read that docstring before changing anything here.

⚠⚠ **NEVER let this reach "Connections".** That screen carries disconnect-and-forget,
which is BMAP `04 03` REMOVE_DEVICE and `04 07` CLEAR_DEVICE_LIST. Re-pairing five
headphones is not a capture, it is an afternoon. Nothing in [GROUPS] may open it.

⚠ **Bose Connect is normally `pm disable-user`'d on this phone**, because it auto-starts
on device connect and sends a block-`00`, which wakes the BMAP session. That mattered
while #1232 was live; it is closed. Disable it again when finished.

⚠⚠ **A SWIPE ON THE LANDING SCREEN IS NAVIGATION, NOT A SCROLL.** One swipe on
`ConnectedToHeadphoneActivity` opens `ProductSettingsActivity` — measured 2026-08-30 by
swiping twice and watching the focus change on the first. The generic driver's whole
scroll model assumes a swipe moves a list, so anything that scrolls before it has
arrived somewhere scrollable will silently end up on a different screen.

⚠ **The landing screen carries NO text.** Every control there is `content-desc` with an
empty `text` — the gear is `content-desc="Settings"` — so the label layer cannot see it
and [Driver.enter] is how you get in. The settings LIST does use real text, which is why
`--survey` and `--where` work once inside.

Usage:
    scripts/drive_bose.py --survey        # scroll the whole screen, tap nothing
    scripts/drive_bose.py --where LABEL   # say where a label resolves, tap nothing
"""

from __future__ import annotations

import sys
from typing import Callable

from vendor_drive import Driver, run, say

VENDOR = "com.bose.monet"

# ✅ Confirmed by `--survey` on 2026-08-30, in drawing order. The docs' 2026-08-26 list
# was right as far as it went and MISSED the last three, which is why a group written
# from notes was not worth shipping.
SETTINGS_ROWS = [
    "Name", "Connections", "Product Tour", "Music Share", "Noise Cancellation",
    "Action Button", "Self Voice", "Standby Timer", "Voice Prompts", "Prompt Language",
    "DISCONNECT", "User Manual", "Product Info",
]

# ⛔ **NEVER TAP THESE**, and the reason is different for each.
FORBIDDEN = {
    "Connections": "carries disconnect-and-forget — BMAP 04 03 / 04 07",
    "DISCONNECT": "drops the headphones off the phone mid-run",
    "Prompt Language": "pushes a language file over DFU — firmware work, out of scope",
    "Name": "renames the device; reversible but it churns the pairing record",
}

def group_prompts(d: Driver) -> None:
    """Voice Prompts, cycled and put back — `01 03` on the wire.

    ⚠ Chosen as the FIRST group deliberately: it is a plain switch, it touches no
    volume, no pairing and no firmware, and `cycle` proves both halves moved. The doc
    notes `01 03` applies asynchronously and answers nothing, so a Get sent immediately
    after reads the old state — that is a quirk of the block, not a failed write.
    """
    say("--- Voice Prompts (a switch; cycled and put back) ---")
    if not d.enter("Settings"):
        return
    d.cycle("Voice Prompts")


GROUPS: dict[str, Callable[[Driver], None]] = {
    "prompts": group_prompts,
}


def main() -> int:
    return run(
        GROUPS,
        package=VENDOR,
        banner="=== Bose capture begins — every change is undone before the next starts ===",
        description=__doc__ or "",
        # ⚠ Bose Connect draws it as `70`, NO percent sign — measured 2026-08-30.
        # ⛔ NEVER scroll Bose Connect's landing screen — one swipe leaves it for good.
        landing_scrolls=False,
        ready=r"\d+",
        default="prompts",
    )


if __name__ == "__main__":
    sys.exit(main())
