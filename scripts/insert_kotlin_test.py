#!/usr/bin/env python3
"""Self-test for `insert_kotlin.py`'s idea of what a declaration carries.

⚠ **Every case here is one the tool got wrong in real use, or one it got right
and must keep getting right.** The annotation cases are 2026-09-12: two inserts
in one session landed between an existing `@Test` and its function, because the
walk knew about comments and nothing else. The tool's own header says it exists
to stop exactly that, which is why it now has a test rather than a promise.
"""

from __future__ import annotations

from insert_kotlin import block_top

FIXTURES: list[tuple[str, str, int]] = [
    (
        "a KDoc and an annotation are both the declaration's",
        """
        |    }
        |
        |    /** Why. */
        |    @Test
        |    fun target() {
        """,
        2,
    ),
    (
        "an annotation with no KDoc still belongs to it",
        """
        |    }
        |
        |    @Test
        |    fun target() {
        """,
        2,
    ),
    (
        "a KDoc with no annotation, which is what the tool was written for",
        """
        |    }
        |
        |    /** Why. */
        |    fun target() {
        """,
        2,
    ),
    (
        "a bare declaration takes the line above it",
        """
        |    }
        |
        |    fun target() {
        """,
        2,
    ),
    (
        "line comments count, as ktlint treats them as attached",
        """
        |    }
        |
        |    // Why.
        |    // More.
        |    fun target() {
        """,
        2,
    ),
    (
        "a multi-line KDoc",
        """
        |    }
        |
        |    /**
        |     * Why.
        |     */
        |    @Test
        |    fun target() {
        """,
        2,
    ),
    (
        "a multi-line annotation, and the KDoc above it",
        """
        |    }
        |
        |    /** Why. */
        |    @Suppress(
        |        "LongMethod",
        |    )
        |    fun target() {
        """,
        2,
    ),
    (
        "several annotations",
        """
        |    }
        |
        |    @Test
        |    @Suppress("LongMethod")
        |    fun target() {
        """,
        2,
    ),
]


def unindent(block: str) -> list[str]:
    """The `|`-prefixed fixture text as lines, keepends, like the tool reads them."""
    out: list[str] = []
    for raw in block.strip("\n").split("\n"):
        if "|" not in raw:
            continue
        out.append(raw.split("|", 1)[1] + "\n")
    return out


def main() -> None:
    failures: list[str] = []
    for name, block, want in FIXTURES:
        lines = unindent(block)
        target = next(i for i, line in enumerate(lines) if "fun target()" in line)
        got = block_top(lines, target)
        if got != want:
            failures.append(f"  {name}: inserts at line {got + 1}, wanted {want + 1}")
    if failures:
        print("insert_kotlin: block_top is wrong for:")
        print("\n".join(failures))
        raise SystemExit(1)
    print(f"insert_kotlin: {len(FIXTURES)} cases ok")


if __name__ == "__main__":
    main()
