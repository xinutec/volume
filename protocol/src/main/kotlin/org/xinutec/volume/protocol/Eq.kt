package org.xinutec.volume.protocol

/**
 * An equaliser setting: a named preset, and the levels it puts the bands at.
 *
 * ⚠ **Levels are in dB here, not on the wire.** Sony sends them offset by 10 so a
 * byte can hold −10…+10 unsigned, and every device that has an EQ will have its own
 * offset and its own band count. Keeping the domain in dB is what stops one vendor's
 * encoding leaking into the screen — the same reason [AncMode] is not a byte.
 */
data class EqSetting(
    /** Vendor preset id, opaque: the app shows a name, the device wants a number. */
    val preset: Int,
    /**
     * Levels in dB, in the order the device reports its bands.
     *
     * ⚠ On the Sony this is SIX entries for five visible bands — the first is CLEAR
     * BASS, which the app draws as a separate box rather than a point on the curve.
     * Assuming one level per drawn band is off by one from the first byte onward.
     */
    val levels: List<Int>,
)

/**
 * The Sony EQEBB frames, as captured 2026-08-16 (`docs/sony-settings.md`).
 *
 * Here rather than in the driver because it is pure arithmetic over bytes, and
 * because the capture gives exact fixtures — the tests replay real frames rather
 * than frames written to match the code.
 */
object SonyEq {
    /** ⚠ `0a` is 0 dB. The wire is unsigned; the domain is signed. */
    const val ZERO = 0x0a

    const val SET: Byte = 0x58
    const val STATE: Byte = 0x59
    const val CAPABILITY: Byte = 0x5b

    /** `58 01 <preset> 00` — ask for a preset. */
    fun set(preset: Int): ByteArray = byteArrayOf(SET, 0x01, preset.toByte(), 0x00)

    /**
     * Decode `59 01 <preset> <count> <levels…>`, or null if it is not that.
     *
     * ⚠ Returns null rather than guessing on a short or foreign frame. A capture is
     * full of frames that are not the answer to the question just asked — acks,
     * unsolicited status — and one decoded optimistically becomes a confident wrong
     * reading.
     */
    fun state(payload: ByteArray): EqSetting? {
        if (payload.size < 4) return null
        if (payload[0] != STATE || payload[1] != 0x01.toByte()) return null
        val count = payload[3].toInt() and 0xFF
        if (payload.size < 4 + count) return null
        val levels = (0 until count).map { (payload[4 + it].toInt() and 0xFF) - ZERO }
        return EqSetting(payload[2].toInt() and 0xFF, levels)
    }

    /**
     * Band centre frequencies out of `5b 01 <count> …`, in Hz.
     *
     * ⚠ **The header is SIX bytes**, `5b 01 <count> 10 00 01`, and getting that
     * wrong is silent: starting one byte early reads the `01 01` straddling the
     * header and the first band as `0x0101` = 257 Hz, which is a plausible-looking
     * frequency and wrong. It was caught only because the real answer, 400 Hz, is
     * printed on the app's own axis. The last header byte is unexplained.
     *
     * Measured against those labels: 400, 1k, 2.5k, 6.3k, 16k.
     */
    fun bands(payload: ByteArray): List<Int> {
        if (payload.size < 6 || payload[0] != CAPABILITY) return emptyList()
        val out = mutableListOf<Int>()
        var i = 6
        while (i + 2 < payload.size) {
            // Each band is `01 <hi> <lo>`; the leading 01 is what makes it findable.
            if (payload[i] == 0x01.toByte()) {
                out += ((payload[i + 1].toInt() and 0xFF) shl 8) or (payload[i + 2].toInt() and 0xFF)
                i += 3
            } else {
                i++
            }
        }
        return out
    }
}
