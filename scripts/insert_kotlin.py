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

The anchor is matched against declaration lines. The text goes above everything
that declaration carries — its KDoc, its `//` lines and its annotations — or
directly above the anchor when it carries nothing.
"""

from __future__ import annotations

import sys
from pathlib import Path

# A doc block is `/** … */`; a run of `//` lines attached to a declaration counts
# too, since ktlint treats those as belonging to it as well.
DOC_END = "*/"
DOC_START = "/**"


def annotation_start(lines: list[str], end: int) -> int | None:
    """The `@` line opening the annotation that ends at `end`, if that is what it is.

    ⚠ Counts brackets textually, so an annotation argument containing a literal
    parenthesis in a string would defeat it. That failure is one-directional: the
    walk stops early and the text lands lower, which is a diff to look at rather
    than a declaration silently split from its own annotation.
    """
    if not lines[end].strip().endswith(")"):
        return None
    depth = 0
    i = end
    while i >= 0:
        depth += lines[i].count(")") - lines[i].count("(")
        if depth == 0:
            return i if lines[i].strip().startswith("@") else None
        i -= 1
    return None


def block_top(lines: list[str], at: int) -> int:
    """The first line of everything the declaration at `at` carries above itself.

    ⚠ **Annotations belong to the declaration as much as its KDoc does**, and
    leaving them out is how this tool twice put new code between an existing
    `@Test` and its function on 2026-09-12 — splitting a test from the annotation
    that makes it one. ktlint does not catch that: the result is still a
    well-formed file, just one where a function lost its `@Test` and another
    grew a second one.

    A blank line ends the run: nothing above one is attached to what is below it.
    """
    i = at - 1
    while i >= 0:
        stripped = lines[i].strip()
        if not stripped:
            break
        if stripped.startswith("@") or stripped.startswith("//"):
            i -= 1
            continue
        if stripped.endswith(DOC_END):
            while i >= 0 and DOC_START not in lines[i]:
                i -= 1
            i -= 1
            continue
        opened = annotation_start(lines, i)
        if opened is None:
            break
        i = opened - 1
    return i + 1


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
    at = block_top(lines, find_anchor(lines, anchor))
    text = text_path.read_text()
    if not text.endswith("\n"):
        text += "\n"
    # ⚠ ktlint wants a blank line between two declarations, and the anchor's own
    # doc comment now sits directly below the insert — without this the first
    # thing the tool does is earn `blank-line-before-declaration`, which is the
    # wasted gate cycle it exists to avoid.
    if at < len(lines) and lines[at].strip():
        text += "\n"
    path.write_text("".join(lines[:at]) + text + "".join(lines[at:]))
    print(f"{path}: inserted above line {at + 1}")


if __name__ == "__main__":
    main()
