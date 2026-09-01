#!/usr/bin/env python3
"""Walk Sony's Sound Connect through its own screens, for a parity survey.

Everything general lives in `vendor_drive`, including the four faults this way of
driving exists to prevent — read that docstring before changing anything here. What is
Sony in this file is the package name, the navigation, and the category list.

⚠ **READ-ONLY BY CONSTRUCTION.** Every group below taps category headers and the
`All device settings` entry, never a control. That is not caution for its own sake: this
device's rows include an `NC Optimizer` that plays test tones into worn headphones, so a
survey that could stray onto a control is a survey that can start one.

Usage:
    scripts/drive_sony.py --list
    scripts/drive_sony.py categories
"""

from __future__ import annotations

import sys
from typing import Callable

from vendor_drive import Driver, run, say

VENDOR = "com.sony.songpal.mdr"

# ⚠ The app is called **Sound Connect** now, not Headphones Connect. The package is
# unchanged, so nothing here breaks — but a future session looking for the old name in
# the launcher will not find it.
ENTRY = "All device settings"

CATEGORIES = [
    "Noise Canceling/Ambient Sound",
    "Sound Quality/Volume",
    "Connection",
    "Controls",
    "Power/Battery",
    "System",
]

# ⚠ **NEVER OPEN THESE**, and each for its own reason.
FORBIDDEN = {
    "NC Optimizer": "plays test tones into headphones that have to be worn",
    "Find Your Equalizer": "a guided listening test — same class as the optimizer",
    "360 Reality Audio Setup": "account-gated; a login prompt is a hard stop",
    "Download software automatically": "firmware, out of scope",
}


def group_categories(d: Driver) -> None:
    """Open each category, list what is in it, and come back the long way.

    ⚠⚠ **BACK from a category lands on the DEVICE CARD, not on the category list**, and
    finding that out cost a run: the second category was then tapped at coordinates read
    from the previous screen, which hit a shortcut on the card instead. Measured
    2026-09-01. So each category is reached by re-entering [ENTRY] from the card rather
    than by trusting where BACK went — one extra tap, and it cannot land on a control.
    """
    for row in CATEGORIES:
        if not d.pick(ENTRY):
            say(f"!! '{ENTRY}' not reachable — skipping {row}")
            continue
        if d.open_screen(row):
            say(f"--- {row}")


GROUPS: dict[str, Callable[[Driver], None]] = {
    "categories": group_categories,
}


def main() -> int:
    return run(
        GROUPS,
        package=VENDOR,
        banner="=== Sony survey begins — READ ONLY, no control is tapped ===",
        description=__doc__ or "",
        # The device card draws the battery as `50%`.
        ready=r"\d+%",
        # The card scrolls, and can be left scrolled by earlier work.
        landing_scrolls=True,
        default="categories",
    )


if __name__ == "__main__":
    sys.exit(main())
