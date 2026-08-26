#!/usr/bin/env python3
"""Insert a declaration into a Kotlin file ABOVE the anchor's doc comment.

⚠ **This exists because anchoring an edit on a declaration line is wrong eight
times out of eight.** A scripted `old -> new` replacement keyed on
`object BoseQc35 : AncDriver {` or `val leAudio:` drops the new text *between*
that declaration and the KDoc written for it. The doc then titles the new thing
and the old one is left bare — and nothing about the diff looks wrong, because
every line is still present and in order, one block too high.

ktlint catches it (`a KDoc may not be preceded by a KDoc`,
`A KDoc is not allowed inside 'class_body'`), so it has never shipped; the cost
is a wasted gate cycle each time, which is ~8 minutes here.

    scripts/insert_kotlin.py <file> <anchor-substring> <text-file>

The anchor is matched against declaration lines. The text is inserted above the
anchor's doc block if it has one, and directly above the anchor if it does not.
"""

from __future__ import annotations

import sys
from pathlib import Path

# A doc block is `/** … */`; a run of `//` lines attached to a declaration counts
# too, since ktlint treats those as belonging to it as well.
DOC_END = "*/"
DOC_START = "/**"


def doc_top(lines: list[str], at: int) -> int:
    """The first line of whatever comment block is attached above `at`."""
    i = at - 1
    # Blank lines between a doc and its declaration are not allowed by ktlint, so
    # anything but a comment immediately above means there is no doc block.
    while i >= 0:
        stripped = lines[i].strip()
        if stripped.endswith(DOC_END):
            while i >= 0 and DOC_START not in lines[i]:
                i -= 1
            return i
        if stripped.startswith("//"):
            i -= 1
            continue
        break
    return at


def find_anchor(lines: list[str], anchor: str) -> int:
    hits = [i for i, line in enumerate(lines) if anchor in line]
    if not hits:
        raise SystemExit(f"anchor not found: {anchor!r}")
    if len(hits) > 1:
        raise SystemExit(f"anchor is ambiguous ({len(hits)} lines): {anchor!r}")
    return hits[0]


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    path, anchor, text_path = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
    lines = path.read_text().splitlines(keepends=True)
    at = doc_top(lines, find_anchor(lines, anchor))
    text = text_path.read_text()
    if not text.endswith("\n"):
        text += "\n"
    path.write_text("".join(lines[:at]) + text + "".join(lines[at:]))
    print(f"{path}: inserted above line {at + 1}")


if __name__ == "__main__":
    main()
