#!/usr/bin/env python3
"""Read a Java enum's name → wire-byte table straight out of apktool's smali.

⚠ **THE ORDINAL IS NOT THE WIRE BYTE**, and assuming it was is what put two wrong
gesture-action tables into `docs/protocols.md` and one wrong claim about Sony. A
vendor enum that carries a protocol byte declares it as a *separate constructor
argument*:

    invoke-direct {v0, v1, v2, v3}, …Command;-><init>(Ljava/lang/String;IB)V
                       ^name ^ordinal ^THE BYTE

For Sony's `Command` the two agree for the first eight entries and then diverge —
`GET_TEST` is ordinal 8 and byte `0f` — so counting the enum gives an answer that
is right often enough to be believed and wrong everywhere it matters.

So this reads the `<clinit>`, tracks the constant loaded into each register, and
takes the byte from the argument the constructor actually declares. Enums with no
byte argument fall back to the ordinal, which is then genuinely the value.

    scripts/smali_enum.py ~/.cache/volume-apks/sony-smali/**/EqPresetId.smali

⚠ It reports what the APK says, which is a claim about the vendor's *app*, never
about the headphones. Everything here still has to be met on the wire — see
`docs/captures.md`.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# `<init>(String name, int ordinal, …)` — every enum starts this way; the group
# after it is the vendor's own arguments, and a leading B/S/I there is the byte.
INIT = re.compile(
    r"invoke-direct \{([vp0-9, ]+)\}, L[^;]+;-><init>\(Ljava/lang/String;I([^)]*)\)V"
)
NUMBER = re.compile(r"const(?:/4|/16|/high16)? (v\d+), (-?0x[0-9a-f]+)$")
TEXT = re.compile(r'const-string (v\d+), "(.*)"$')


def table(path: Path) -> list[tuple[int, str]]:
    """Every `(wire byte, name)` this enum declares, in the file's own order."""
    registers: dict[str, int | str] = {}
    out: list[tuple[int, str]] = []
    for raw in path.read_text(errors="replace").splitlines():
        line = raw.strip()
        number = NUMBER.match(line)
        if number:
            registers[number.group(1)] = int(number.group(2), 16)
            continue
        text = TEXT.match(line)
        if text:
            registers[text.group(1)] = text.group(2)
            continue
        init = INIT.match(line)
        if not init:
            continue
        args = init.group(1).split(", ")
        name = registers.get(args[1])
        ordinal = registers.get(args[2])
        # ⚠ Only a NUMERIC first vendor argument is the wire byte. Several of these
        # enums pass a Class or a String there, and reading that as a byte would
        # silently fall back to whatever the register last held.
        declared = init.group(2)[:1] in "BSI" and len(args) > 3
        value = registers.get(args[3]) if declared else ordinal
        if not isinstance(name, str):
            continue
        if not isinstance(value, int):
            value = ordinal if isinstance(ordinal, int) else 0
        # ⚠ **A NEGATIVE constant is a signed byte, not an error.** `const/4 v3, -0x1`
        # is how these files spell `0xff`, which is the sentinel almost every vendor
        # enum ends with — printing it as -1 loses exactly the row that says "out of
        # range", and `ff` is a value this repo has already had to reason about twice.
        out.append((value & 0xFF, name))
    return out


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__, file=sys.stderr)
        return 2
    for name in argv:
        path = Path(name).expanduser()
        rows = table(path)
        print(f"── {path.stem} ({len(rows)}) ──")
        print("   " + "  ".join(f"{value:02x}={label}" for value, label in rows))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
