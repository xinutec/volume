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

⚠ **A DROPPED ROW IS THE FAILURE THIS TOOL IS BUILT AGAINST**, because it does not
look like one: the table still prints, still parses, and is simply short. That
happened — the first version matched only `invoke-direct {…}` and Sony's `Command`
builds 6 of its 24 entries with `invoke-direct/range {v5 .. v10}`, whose braces hold
`..`. It printed 18 rows, no error, and `f7` SYSTEM_RET_PARAM — a byte this repo
decodes replies with — was simply absent.

So the row count is now checked against `.field … enum` declarations, which is an
independent count in the same file, and any disagreement is reported on stderr with
a non-zero exit. ⚠ **Do not silence that by widening the regex until the numbers
agree** — the point is that the file says how many there should be.

⚠ **And the guard has to count rows, not just cover names**, because the first
attempt at the fix broke the other way: matching `/range` anywhere in the file also
matched CONSTRUCTOR DELEGATION — a `<init>` overload calling the canonical `<init>`
— and Bose's `BmapPacket$OPERATOR` came out as 11 rows for 9 entries, with `SET_GET`
three times. Every declared name had a row, so a name-coverage check saw nothing
wrong. Only `<clinit>` builds the constants, so only `<clinit>` is read.

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
#
# ⚠ Both spellings, and the `/range` one is not rare: smali switches to it once the
# register numbers outgrow a nibble, so it appears in exactly the LONG enums where a
# missing row is hardest to notice by eye.
INIT = re.compile(
    r"invoke-direct(/range)? \{([^}]*)\}, L[^;]+;-><init>\(Ljava/lang/String;I([^)]*)\)V"
)
NUMBER = re.compile(r"const(?:/4|/16|/high16)? (v\d+), (-?0x[0-9a-f]+)$")
TEXT = re.compile(r'const-string (v\d+), "(.*)"$')
DECLARED = re.compile(r"\.field public static final enum (\w+):")
CLINIT = re.compile(r"\.method static constructor <clinit>\(\)V")
END = re.compile(r"\.end method")


def registers_of(invoke: re.Match[str]) -> list[str]:
    """The argument registers, for either spelling of the invoke.

    ⚠ `{v5 .. v10}` is a RANGE and has to be expanded — it names two registers and
    means six. Splitting it on commas yields one "register" called `v5 .. v10`,
    which resolves to nothing, and the entry is skipped without complaint.
    """
    inside = invoke.group(2).strip()
    if invoke.group(1):
        first, last = (part.strip() for part in inside.split(".."))
        return [f"v{n}" for n in range(int(first[1:]), int(last[1:]) + 1)]
    return [part.strip() for part in inside.split(",")]


def table(path: Path) -> tuple[list[tuple[int, str]], list[str]]:
    """Every `(wire byte, name)` this enum declares, plus how the count disagrees.

    ⚠ Only the `<clinit>` is read — see the module docstring. Constructor overloads
    delegate with the same signature and would otherwise be counted as entries.
    """
    text = path.read_text(errors="replace")
    registers: dict[str, int | str] = {}
    out: list[tuple[int, str]] = []
    inside = False
    for raw in text.splitlines():
        line = raw.strip()
        if CLINIT.match(line):
            inside, registers = True, {}
            continue
        if inside and END.match(line):
            inside = False
            continue
        if not inside:
            continue
        number = NUMBER.match(line)
        if number:
            registers[number.group(1)] = int(number.group(2), 16)
            continue
        label = TEXT.match(line)
        if label:
            registers[label.group(1)] = label.group(2)
            continue
        init = INIT.match(line)
        if not init:
            continue
        args = registers_of(init)
        if len(args) < 3:
            continue
        name = registers.get(args[1])
        ordinal = registers.get(args[2])
        # ⚠ Only a NUMERIC first vendor argument is the wire byte. Several of these
        # enums pass a Class or a String there, and reading that as a byte would
        # silently fall back to whatever the register last held.
        carries_byte = init.group(3)[:1] in "BSI" and len(args) > 3
        value = registers.get(args[3]) if carries_byte else ordinal
        if not isinstance(name, str):
            continue
        if not isinstance(value, int):
            value = ordinal if isinstance(ordinal, int) else 0
        # ⚠ **A NEGATIVE constant is a signed byte, not an error.** `const/4 v3, -0x1`
        # is how these files spell `0xff`, which is the sentinel almost every vendor
        # enum ends with — printing it as -1 loses exactly the row that says "out of
        # range", and `ff` is a value this repo has already had to reason about twice.
        out.append((value & 0xFF, name))
    declared = DECLARED.findall(text)
    found = [name for _, name in out]
    complaints = [f"{name}: no row" for name in declared if name not in found]
    complaints += [
        f"{name}: {found.count(name)} rows" for name in sorted(set(found))
        if found.count(name) > 1
    ]
    complaints += [f"{name}: not declared" for name in found if name not in declared]
    if len(out) != len(declared) and not complaints:
        complaints.append(f"{len(out)} rows for {len(declared)} declared entries")
    return out, complaints


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__, file=sys.stderr)
        return 2
    short = False
    for name in argv:
        path = Path(name).expanduser()
        rows, complaints = table(path)
        print(f"── {path.stem} ({len(rows)}) ──")
        print("   " + "  ".join(f"{value:02x}={label}" for value, label in rows))
        if complaints:
            short = True
            print(
                f"⚠ {path.stem} does not match its own declarations — "
                + "; ".join(complaints),
                file=sys.stderr,
            )
    return 1 if short else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
