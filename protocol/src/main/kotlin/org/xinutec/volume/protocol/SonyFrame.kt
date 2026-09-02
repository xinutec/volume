package org.xinutec.volume.protocol

/**
 * Sony's framing on the `96cc203e-…` RFCOMM channel.
 *
 * Written from memory of the open-source SonyHeadphonesClient, and **settled by the
 * 2026-08-16 capture** (`docs/sony-settings.md`), which is what this note used to
 * ask for. The band-table reply
 * `3e 0c 00 00000015 5b…01 3d 2e 80 54 3c` decides all three open questions at once:
 * its declared length of 21 matches only *after* unescaping 22 bytes, its checksum
 * holds only over the *unescaped* body, and the `3d 2e` unescapes to `3e`.
 *
 * ⚠ That last one is not academic. Read literally, the top band reads 15662 Hz —
 * plausible, near enough to the app's "16k" label to pass — and unescaped it is
 * exactly 16000. A fixture taken at the wire layer and handed to a payload decoder
 * asserted the wrong number for a day.
 *
 * Shape:
 * ```
 *   3e | type(1) | seq(1) | length(4, big-endian, of payload) | payload | sum(1) | 3c
 * ```
 * `sum` is every byte between the markers added up, mod 256. Inside that region
 * the three marker values are escaped, so a length or checksum byte that happens
 * to equal 0x3e cannot end the frame early.
 */
object SonyFrame {
    const val START: Byte = 0x3e
    const val END: Byte = 0x3c
    const val ESCAPE: Byte = 0x3d

    /** Message types worth sweeping. Which one a given model answers on is exactly
     *  what the probe is for — XM4-era firmware is reported to differ from XM5. */
    const val TYPE_DATA: Byte = 0x00
    const val TYPE_ACK: Byte = 0x01
    const val TYPE_DATA_MDR: Byte = 0x0c
    const val TYPE_DATA_MDR_NO2: Byte = 0x0e

    /**
     * Which command table [type] selects — the same byte means different things on each.
     *
     * ⚠ **This replaces a `table2: Boolean = false` parameter, and the default was the
     * bug.** [Hazards]'s peripheral-unpair refusal only applies on table 2, so a caller
     * that forgot the argument silently selected the table where the check does not
     * fire — fail-open by omission. An enum with no default makes silence a compile
     * error instead.
     */
    fun tableOf(type: Byte): SonyTable =
        if (type == TYPE_DATA_MDR_NO2) SonyTable.TABLE_2 else SonyTable.TABLE_1

    /**
     * Escape the marker bytes. The transform clears bit 4 (`0x3e`→`0x2e`,
     * `0x3c`→`0x2c`, `0x3d`→`0x2d`) behind an `0x3d` lead byte, so the escaped form
     * can never itself be mistaken for a marker.
     *
     * ⚠ Verified against the capture — see the class note. It went unexercised for a
     * long time because the first probe command carries no escapable byte, so a green
     * round-trip said nothing about this path.
     */
    fun escape(body: ByteArray): ByteArray {
        val out = ArrayList<Byte>(body.size + 4)
        for (b in body) {
            if (b == START || b == END || b == ESCAPE) {
                out.add(ESCAPE)
                out.add((b.toInt() and 0xef).toByte())
            } else {
                out.add(b)
            }
        }
        return out.toByteArray()
    }

    /** Inverse of [escape]. A trailing lone escape byte is malformed; it is kept
     *  rather than dropped so the caller sees the truncation in the hex. */
    fun unescape(body: ByteArray): ByteArray {
        val out = ArrayList<Byte>(body.size)
        var i = 0
        while (i < body.size) {
            val b = body[i]
            if (b == ESCAPE && i + 1 < body.size) {
                out.add((body[i + 1].toInt() or 0x10).toByte())
                i += 2
            } else {
                out.add(b)
                i += 1
            }
        }
        return out.toByteArray()
    }

    /** Sum of the unescaped body, mod 256 — computed before escaping, per the shape above. */
    fun checksum(body: ByteArray): Byte =
        body.fold(0) { acc, b -> acc + (b.toInt() and 0xff) }.toByte()

    /** Build a complete frame around [payload]. */
    fun encode(type: Byte, seq: Byte, payload: ByteArray): OutFrame {
        val n = payload.size
        val body =
            byteArrayOf(
                type,
                seq,
                ((n ushr 24) and 0xff).toByte(),
                ((n ushr 16) and 0xff).toByte(),
                ((n ushr 8) and 0xff).toByte(),
                (n and 0xff).toByte(),
            ) + payload
        val withSum = body + checksum(body)
        return OutFrame(byteArrayOf(START) + escape(withSum) + byteArrayOf(END))
    }

    /** A decoded frame, plus whether its checksum held. */
    data class Frame(
        val type: Byte,
        val seq: Byte,
        val payload: ByteArray,
        val checksumOk: Boolean,
    ) {
        // ByteArray in a data class: equals/hashCode must compare contents, and the
        // generated ones compare identity. Overridden so tests can assert on frames.
        override fun equals(other: Any?): Boolean =
            other is Frame &&
                type == other.type &&
                seq == other.seq &&
                checksumOk == other.checksumOk &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int =
            (((type.toInt() * 31) + seq) * 31 + payload.contentHashCode()) * 31 +
                checksumOk.hashCode()

        override fun toString(): String =
            "Frame(type=%02x seq=%02x sum=%s payload=%s)".format(
                type,
                seq,
                if (checksumOk) "ok" else "BAD",
                Hex.format(payload),
            )
    }

    /**
     * Pull every complete frame out of [buf]. Bytes before the first `0x3e` and
     * after the last `0x3c` are ignored, so a partial tail is simply not returned
     * and the caller can read more and try again.
     */
    fun decodeAll(buf: ByteArray): List<Frame> {
        val frames = ArrayList<Frame>()
        var i = 0
        while (i < buf.size) {
            if (buf[i] != START) {
                i++
                continue
            }
            val end = (i + 1 until buf.size).firstOrNull { buf[it] == END } ?: break
            val body = unescape(buf.copyOfRange(i + 1, end))
            // type + seq + 4 length + checksum = 7 bytes before any payload.
            if (body.size >= 7) {
                val declared =
                    ((body[2].toInt() and 0xff) shl 24) or
                        ((body[3].toInt() and 0xff) shl 16) or
                        ((body[4].toInt() and 0xff) shl 8) or
                        (body[5].toInt() and 0xff)
                val payload = body.copyOfRange(6, body.size - 1)
                val ok =
                    checksum(body.copyOfRange(0, body.size - 1)) == body[body.size - 1] &&
                        declared == payload.size
                frames.add(Frame(body[0], body[1], payload, ok))
            }
            i = end + 1
        }
        return frames
    }
}

/** The Sony's two command tables. See [SonyFrame.tableOf] for why there is no default. */
enum class SonyTable { TABLE_1, TABLE_2 }
