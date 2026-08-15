package org.xinutec.volume

/**
 * Hex in and out. The probe's whole contract with the terminal is hex strings, and
 * #783 asks for the raw bytes rather than a summary, so this is the one formatting
 * decision that matters and it gets tested.
 */
object Hex {
    /** Parse `"3e0c00"`, `"3e 0c 00"` or `"3E:0C:00"`. Throws on anything else. */
    fun parse(s: String): ByteArray {
        val clean = s.filterNot { it == ' ' || it == ':' || it == '-' || it == '\n' }
        require(clean.length % 2 == 0) { "hex needs an even number of digits: '$s'" }
        return ByteArray(clean.length / 2) { i ->
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "not hex: '$s'" }
            ((hi shl 4) or lo).toByte()
        }
    }

    /** Lowercase, space-separated — readable in logcat and pasteable back in. */
    fun format(b: ByteArray, from: Int = 0, to: Int = b.size): String =
        (from until to).joinToString(" ") { "%02x".format(b[it]) }
}
