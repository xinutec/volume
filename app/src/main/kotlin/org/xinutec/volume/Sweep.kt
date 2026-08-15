package org.xinutec.volume

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
    fun bose(blocks: IntRange, functions: IntRange): List<ByteArray> =
        blocks.flatMap { b ->
            functions.map { f -> byteArrayOf(b.toByte(), f.toByte(), 0x01, 0x00) }
        }

    /**
     * Harman (JBL, JLab): `[block][command][length: 2 bytes big-endian]`.
     *
     * Length is hard-wired to zero, which is what the official app's own reads look
     * like (`04 11 0000`, `07 10 0000`). A zero-length body is the closest thing
     * this protocol has to a operator-less Get.
     */
    fun harman(blocks: IntRange, commands: IntRange): List<ByteArray> =
        blocks.flatMap { b ->
            commands.map { c -> byteArrayOf(b.toByte(), c.toByte(), 0x00, 0x00) }
        }

    fun packets(protocol: String, blocks: IntRange, functions: IntRange): List<ByteArray> =
        when (protocol.lowercase()) {
            "bose" -> bose(blocks, functions)
            "harman" -> harman(blocks, functions)
            else -> throw IllegalArgumentException("unknown sweep protocol '$protocol'")
        }
}
