package org.xinutec.volume.protocol

/**
 * The JBL's auto power off: a switch, and how long it waits.
 *
 * ⚠ **Deliberately not [AutoOff].** Sony's auto power off is a *rule* — never, or
 * when you take them off — and the JBL's is a *timer*. Both vendors call theirs
 * "Auto Power Off", and a single type covering both would have to invent a state
 * neither device has: a Sony that counts minutes, or a JBL that senses wearing.
 *
 * ⚠ [minutes] was `0x1e` = 30 in every frame ever seen, and was never varied, so the
 * unit is the vendor app's word and not a measurement. It is carried rather than
 * offered: a write sends back whatever was read, so nothing here depends on the unit
 * being right.
 */
data class TimedOff(
    val on: Boolean,
    val minutes: Int,
)

/**
 * JBL auto power off — status field `33`, driven 2026-08-16.
 *
 * ```
 * → aa 21 01 33              ← aa 22 04 33 <on> <minutes> <?>
 * → aa 33 03 <on> <minutes> <?>   ← aa 00 02 33 00     the ack, not the answer
 * ```
 *
 * ⚠ **The setter's shape was guessed from the status reply and worked first time**,
 * as it does on all four vendors here — every one of them mirrors its getter. That is
 * the only kind of guess this repo has found reliable; value bytes have never been
 * guessable. Driven both ways and confirmed by read-back, never by the ack.
 */
object JblAutoOff {
    const val FIELD: Byte = 0x33

    /** `aa 33` sets; `aa 21 01 33` asks and `aa 22 04 33 …` answers. */
    const val SET: Byte = 0x33

    /**
     * The third payload byte, `00` in every frame captured and every frame sent.
     *
     * ⚠ Echoed, not understood. It sits where a second half of a 16-bit timeout would
     * sit, which is a guess, so it is written back as the constant it has always been
     * rather than composed from [TimedOff.minutes].
     */
    private const val TRAILER: Byte = 0x00

    fun get(): ByteArray = byteArrayOf(Bes.HEADER, Bes.STATUS_GET, 0x01, FIELD)

    /** Decode `aa 22 04 33 <on> <minutes> <?>`, or null if it is not that. */
    fun state(reply: ByteArray): TimedOff? {
        val p = Bes.status(reply, FIELD) ?: return null
        if (p.size < 2) return null
        return TimedOff(on = p[0] != 0x00.toByte(), minutes = p[1].toInt() and 0xff)
    }

    fun set(v: TimedOff): ByteArray =
        byteArrayOf(
            Bes.HEADER,
            SET,
            0x03,
            if (v.on) 0x01 else 0x00,
            v.minutes.toByte(),
            TRAILER,
        )
}

/**
 * The JBL's ten-band equaliser — `aa a2`, decoded from the 2026-08-16 capture.
 *
 * ```
 * → aa a2 02 01 ff           read whichever table is in use
 * ← aa a2 74 00 02 <id> …    the curve, and the id of the table it came from
 * → aa a2 74 00 00 <id> …    write — the same frame with operator 00
 * ```
 *
 * The payload is `00 <operator> <table>`, thirteen bytes that never varied, ten
 * ten-byte records `<a> 01 <gain float32 LE> <frequency float32 LE>`, and a trailing
 * `01`. ⚠ **The length byte does not count that trailing `01`** — it reads `0x74` =
 * 116 while 117 bytes follow it, so a reader that trusts the length drops a byte and
 * one that trusts the frame keeps a byte the length denies. Both are correct here
 * because the records are found by offset.
 *
 * ⚠ The first record's leading byte is `0a` where every other record's is `01`.
 * Unexplained. The vendor app sends `0a` too, so [set] preserves it rather than
 * normalising it to something tidier.
 *
 * ⚠ **`aa 21 01 34` is NOT this**, though it was written down as "EQ preset 0". It
 * read `00` before selecting JAZZ in the app and `00` after. A status byte that does
 * not move when the setting moves is about something else.
 */
object JblEq {
    const val CMD: Byte = 0xa2.toByte()

    /** `ff` asks for whichever table is in use; the reply says which that was. */
    const val CURRENT: Byte = 0xff.toByte()

    const val BANDS = 10

    /** The payload length of a curve frame — and the check that this IS one. */
    private const val LEN: Byte = 0x74

    private const val SET: Byte = 0x00
    private const val STATUS: Byte = 0x02

    /** Where the ten records start, counting from the `aa`. */
    private const val RECORDS = 19
    private const val RECORD = 10

    fun get(table: Byte = CURRENT): ByteArray = byteArrayOf(Bes.HEADER, CMD, 0x02, 0x01, table)

    /**
     * Decode a curve frame, or null.
     *
     * ⚠ **The length byte is checked, not just the size.** The same `aa a2` command
     * answers `c9` and `ca` with two other tables, 196 and 86 bytes of records in the
     * same shape. The 196-byte one is *longer* than a curve, so a size-only guard
     * passes it and decodes ten of someone else's records as the user's equaliser.
     */
    fun curve(frame: ByteArray): EqCurve? {
        if (frame.size < RECORDS + BANDS * RECORD) return null
        if (frame[0] != Bes.HEADER || frame[1] != CMD || frame[2] != LEN) return null
        if (frame[4] != STATUS) return null
        return EqCurve(
            table = frame[5].toInt() and 0xff,
            bands =
                (0 until BANDS).map {
                    val at = RECORDS + it * RECORD
                    EqBand(hz = float(frame, at + 6).toInt(), gain = float(frame, at + 2))
                },
        )
    }

    /**
     * Build a write from the frame that was just read, changing only the gains.
     *
     * ⚠ **A template rather than a constant, because thirteen of these bytes are not
     * understood.** Composing a frame from scratch would mean writing down what they
     * are, and the only evidence is that they were the same in every frame one unit
     * emitted on one evening. Echoing them back is the same discipline as Sony's
     * `02 01 00`: carry what you cannot explain, and it cannot be carried wrong.
     *
     * Returns null if [read] is not a curve frame or [gains] is not [BANDS] long,
     * rather than emitting a frame of the wrong shape at the headphones.
     */
    fun set(read: ByteArray, table: Int, gains: List<Float>): ByteArray? {
        if (curve(read) == null || gains.size != BANDS) return null
        val out = read.copyOf()
        out[4] = SET
        out[5] = table.toByte()
        for (i in 0 until BANDS) {
            putFloat(out, RECORDS + i * RECORD + 2, gains[i])
        }
        return out
    }
}

/**
 * The ten band centres, which are **exactly the app's own axis**.
 *
 * Read off the wire rather than off the screen: each record carries its frequency as
 * a float beside its gain, so these are the device's numbers and the app's labels
 * agree with them.
 */
val JBL_HZ = listOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

/**
 * `EnumEqPresetIdx`, the SDK's name for the table id an `aa a2` frame carries.
 *
 * ⚠ **Names only — the CURVES for seven of these have never been seen.** A write sends
 * the ten gains as well as the id, so this list cannot be turned into nine buttons:
 * [JBL_CURVES] still offers the two whose bytes were captured. The value of having it
 * is that a curve the owner set from the vendor app now renders as "Vocal" rather than
 * as "table 2", which is the difference between a number and a fact.
 *
 * Trustworthy for the same reason the gesture tables are: the ordinal is the wire
 * value, and the two ids observed — `00` for the flat curve and `01` for the app's
 * JAZZ write — land exactly on OFF and JAZZ.
 */
val JBL_EQ_PRESETS =
    listOf(
        "Off",
        "Jazz",
        "Vocal",
        "Bass",
        "User",
        "Rock",
        "Piano",
        "Club",
        "Studio",
    )

/**
 * The two curves the vendor app was seen to send, with the ids it sent them under.
 *
 * ⚠ **This is not the app's preset menu.** The JBL app offers more; these are the two
 * whose bytes were captured — flat at 20:26:55 and JAZZ at 20:36:48. Naming the rest
 * from the menu would be putting a vendor's label on a curve nobody has measured.
 *
 * ⚠ A write carries **both** a table id and a full curve, so which of the two the
 * device honours is not established. Both are sent exactly as the app sent them,
 * which is the only combination known to work.
 */
val JBL_CURVES: List<Pair<String, EqCurve>> =
    listOf(
        "Flat" to EqCurve(0, JBL_HZ.map { EqBand(it, 0f) }),
        "Jazz" to
            EqCurve(
                1,
                JBL_HZ
                    .zip(listOf(4f, 2f, 1f, 2.5f, -1.5f, -1.5f, 0f, 1f, 2f, 4f))
                    .map { (hz, gain) -> EqBand(hz, gain) },
            ),
    )

/** IEEE 754 single, little-endian, which is how this vendor writes a gain. */
private fun float(b: ByteArray, at: Int): Float =
    Float.fromBits(
        (b[at].toInt() and 0xff) or
            ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or
            ((b[at + 3].toInt() and 0xff) shl 24),
    )

private fun putFloat(b: ByteArray, at: Int, v: Float) {
    val bits = v.toRawBits()
    b[at] = (bits and 0xff).toByte()
    b[at + 1] = ((bits shr 8) and 0xff).toByte()
    b[at + 2] = ((bits shr 16) and 0xff).toByte()
    b[at + 3] = ((bits shr 24) and 0xff).toByte()
}

/**
 * The JBL's Max Volume Limiter — `aa a5`, the SDK's `SafeSoundCmd`.
 *
 * ```
 * → aa a5 01 01        ← aa a5 03 02 01 <status>
 * ```
 *
 * ⚠ **Read only, and that is a decision rather than a limitation.** This is hearing
 * protection; it was found switched on, and nothing in this repo will write it. The
 * getter came free from the vendor app's own connect-time sweep, so showing it costs
 * nothing and touching it is never necessary.
 *
 * ⚠ **The offset is the SDK's, not a guess.** `SafeSoundCmd` parses `setStatus` from
 * frame index 5. That reading was calibrated on a command whose answer is already
 * known: `SpeakToChatCmd` takes `setOn` from index 4 and `setLatency` from index 5,
 * and those are exactly where Smart Talk's driven values sit. Both payload bytes here
 * happen to be `01`, so the capture alone could not have separated them — one
 * agreeing byte is what made `38` look like Auto Play & Pause earlier the same day.
 */
object JblSafeSound {
    const val CMD: Byte = 0xa5.toByte()

    /** Where `SafeSoundCmd.setStatus` reads from, counting the `aa`. */
    private const val STATUS_AT = 5

    fun get(): ByteArray = byteArrayOf(Bes.HEADER, CMD, 0x01, 0x01)

    fun state(reply: ByteArray): Boolean? {
        if (reply.size <= STATUS_AT) return null
        if (reply[0] != Bes.HEADER || reply[1] != CMD) return null
        if (reply[3] != 0x02.toByte()) return null
        return reply[STATUS_AT] != 0x00.toByte()
    }
}

/**
 * The BES chip's framing, which the JBL and the JLab share at the byte level.
 *
 * `aa <command> <length> <payload…>`, and a reply comes back under `command + 1`
 * (`aa 11` → `aa 12`) — except the status pair, where `aa 21` asks and `aa 22`
 * answers for every field.
 */
object Bes {
    const val HEADER: Byte = 0xaa.toByte()

    /** `aa 21 01 <field>` asks; `aa 22 <len> <field> <payload…>` answers. */
    const val STATUS_GET: Byte = 0x21
    const val STATUS_RET: Byte = 0x22

    /**
     * The payload of `aa 22 <len> <field> …`, or null if this is another field.
     *
     * ⚠ Checking the field byte matters: these arrive unsolicited as well as in
     * answer, so the reply to `33` can be preceded by a `31` nobody asked for.
     */
    fun status(reply: ByteArray, field: Byte): ByteArray? {
        if (reply.size < 5) return null
        if (reply[0] != HEADER || reply[1] != STATUS_RET || reply[3] != field) return null
        val len = reply[2].toInt() and 0xff
        if (len < 1 || reply.size < 3 + len) return null
        return reply.copyOfRange(4, 3 + len)
    }
}
