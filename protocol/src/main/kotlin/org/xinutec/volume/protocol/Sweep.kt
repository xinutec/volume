package org.xinutec.volume.protocol

/**
 * Builds the packet list for a read-surface sweep.
 *
 * The point of a sweep is to find which `(block, function)` pairs a device answers
 * at all, so the app can be built against what exists rather than against a guess.
 * Kept separate from the socket work so the packets can be asserted in a unit test
 * — a sweep that silently builds Set-shaped packets would be writing arbitrary
 * settings to headphones on someone's head.
 */
object Sweep {
    /** Inclusive byte range, parsed from `"00-12"` or a single `"04"`. */
    fun range(s: String): IntRange {
        val parts = s.split("-")
        require(parts.size in 1..2) { "range must be 'aa' or 'aa-bb': '$s'" }
        val lo = Hex.parse(parts[0]).first().toInt() and 0xff
        val hi = if (parts.size == 2) Hex.parse(parts[1]).first().toInt() and 0xff else lo
        require(lo <= hi) { "range is inverted: '$s'" }
        return lo..hi
    }

    /**
     * Bose: `[function block][function][operator][payload length]`.
     *
     * Operator is hard-wired to `0x01` (Get) and the length to `0x00`. Neither is a
     * parameter, deliberately: those two bytes are the whole safety argument for
     * running this against live headphones, and a caller that could vary them would
     * eventually vary them.
     */
    fun bose(blocks: IntRange, functions: IntRange): List<OutFrame> =
        blocks.flatMap { b ->
            functions.map { f -> OutFrame(byteArrayOf(b.toByte(), f.toByte(), 0x01, 0x00)) }
        }

    /**
     * Fast Pair message stream (JBL, JLab): `[group][code][length: 2 bytes BE]`.
     *
     * Length is hard-wired to zero. Unlike [bose] that is **not** a safety argument —
     * this protocol has no Get operator, so a zero-length body is a message, not a
     * read, and the device answers `ff 01` (acknowledged) rather than "unsupported".
     *
     * ⚠ **Group `04` is Device Action, whose first code rings the headphones.** A
     * sweep that walks into it will ring whatever is on your head. Range-limit to the
     * group being investigated rather than walking `00`–`12`.
     */
    fun fastPair(groups: IntRange, codes: IntRange): List<OutFrame> =
        groups.flatMap { g ->
            codes.map { c -> OutFrame(byteArrayOf(g.toByte(), c.toByte(), 0x00, 0x00)) }
        }

    fun packets(protocol: String, blocks: IntRange, functions: IntRange): List<OutFrame> =
        when (protocol.lowercase()) {
            "bose" -> bose(blocks, functions)
            "fastpair" -> fastPair(blocks, functions)
            else -> throw IllegalArgumentException("unknown sweep protocol '$protocol'")
        }
}
