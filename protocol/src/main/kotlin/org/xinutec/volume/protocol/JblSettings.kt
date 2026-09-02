package org.xinutec.volume.protocol

/**
 * The JBL's auto power off: a switch, and how long it waits.
 *
 * ⚠ **Deliberately not [AutoOff].** Sony's auto power off is a *rule* — never, or
 * when you take them off — and the JBL's is a *timer*. Both vendors call theirs
 * "Auto Power Off", and a single type covering both would have to invent a state
 * neither device has: a Sony that counts minutes, or a JBL that senses wearing.
 *
 * ⚠ **[minutes] really is minutes** — the vendor app's own "30 min", "1 hr" and "2 hr"
 * sent `1e`, `3c` and `78` on 2026-08-16, so the unit is measured rather than a label
 * this repo chose. [JBL_IDLE_MINUTES] is what to offer; the field itself is a whole
 * byte and is carried as read, because nothing has probed its edges.
 */
data class TimedOff(
    val on: Boolean,
    val minutes: Int,
)

/**
 * The three idle timeouts the vendor app offers, in minutes.
 *
 * ⚠ **What is OFFERED, not what is legal.** These are the three the app's picker sends;
 * the wire field is a whole byte and its edges are unprobed. So a value read back from
 * outside this list is shown as it stands rather than snapped to the nearest — firmware
 * may know timeouts the app does not.
 */
val JBL_IDLE_MINUTES = listOf(30, 60, 120)

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
     * ⚠ Echoed, not understood. It sits where the high half of a 16-bit timeout would
     * sit — and every value anyone has sent, ours and the vendor app's, fits in one
     * byte, so nothing observed can separate the two readings. Written back as the
     * constant it has always been rather than composed from [TimedOff.minutes].
     */
    private const val TRAILER: Byte = 0x00

    fun get(): OutFrame = OutFrame(byteArrayOf(Bes.HEADER, Bes.STATUS_GET, 0x01, FIELD))

    /** Decode `aa 22 04 33 <on> <minutes> <?>`, or null if it is not that. */
    fun state(reply: ByteArray): TimedOff? {
        val p = Bes.status(reply, FIELD) ?: return null
        if (p.size < 2) return null
        return TimedOff(on = p[0] != 0x00.toByte(), minutes = p[1].toInt() and 0xff)
    }

    fun set(v: TimedOff): OutFrame =
        OutFrame(
            byteArrayOf(
                Bes.HEADER,
                SET,
                0x03,
                if (v.on) 0x01 else 0x00,
                v.minutes.toByte(),
                TRAILER,
            ),
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
 * ⚠ **`aa 21 01 34` is EQ_PRESET and does NOT reach this.** It read `00` before
 * selecting JAZZ in the app and `00` after — it is the legacy one-byte preset field,
 * inert on a model that carries its equaliser as a curve.
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

    fun get(table: Byte = CURRENT): OutFrame =
        OutFrame(byteArrayOf(Bes.HEADER, CMD, 0x02, 0x01, table))

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
    fun set(read: ByteArray, table: Int, gains: List<Float>): OutFrame? {
        if (curve(read) == null || gains.size != BANDS) return null
        val out = read.copyOf()
        out[4] = SET
        out[5] = table.toByte()
        for (i in 0 until BANDS) {
            putFloat(out, RECORDS + i * RECORD + 2, gains[i])
        }
        return OutFrame(out)
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

    fun get(): OutFrame = OutFrame(byteArrayOf(Bes.HEADER, CMD, 0x01, 0x01))

    fun state(reply: ByteArray): Boolean? {
        if (reply.size <= STATUS_AT) return null
        if (reply[0] != Bes.HEADER || reply[1] != CMD) return null
        if (reply[3] != 0x02.toByte()) return null
        return reply[STATUS_AT] != 0x00.toByte()
    }
}

/**
 * Which spatial rendering the JBL is set to.
 *
 * ⚠ The wire values are the vendor's and are measured, one tap each: see [JblSpatial].
 * They are not consecutive by accident — `01` is Music, the middle button, so the
 * numbering is not the on-screen order and cannot be derived from it.
 */
enum class SpatialMode(
    val wire: Byte,
) {
    MUSIC(0x01),
    MOVIE(0x02),
    GAME(0x03),
    ;

    companion object {
        fun of(wire: Byte): SpatialMode? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The JBL's Spatial Sound: a switch, and what it is rendering for.
 *
 * ⚠ **The mode is carried even when [on] is false.** The headphones remember it, and
 * the vendor app draws it, so a type with a nullable mode would throw away the user's
 * choice on every switch-off.
 */
data class Spatial(
    val on: Boolean,
    val mode: SpatialMode,
)

/**
 * JBL Spatial Sound — `aa 9d`, decoded 2026-08-17.
 *
 * ```
 * → aa 9d 01 01                  ← aa 9d 03 02 <on> <mode>
 * → aa 9d 03 00 <on> <mode>      ← aa 9d 03 02 <on> <mode>
 * ```
 *
 * `mode` is `01` Music · `02` Movie · `03` Game, measured by picking each in turn and
 * diffing three replies that differ in that byte alone.
 *
 * ⚠ **This row was published as "the mode buttons send NOTHING when tapped" and that
 * was wrong.** The taps behind that claim landed on a `clickable="false"` label rather
 * than the tile beside it, so the capture window was empty for want of a tap, and the
 * emptiness was written up as a fact about the headphones. The retraction and the two
 * windows that settle it are in `docs/protocols.md`. What survives is the other half:
 * the mode does travel with the on/off write, which is why [set] always sends both.
 *
 * ⚠ Unlike [JblAutoOff] this reply is not an ack — the device answers with the status
 * frame itself, so a caller can trust [state] on the reply to a [set].
 */
object JblSpatial {
    const val CMD: Byte = 0x9d.toByte()

    private const val LEN: Byte = 0x03
    private const val SET: Byte = 0x00
    private const val STATUS: Byte = 0x02

    fun get(): OutFrame = OutFrame(byteArrayOf(Bes.HEADER, CMD, 0x01, 0x01))

    fun set(v: Spatial): OutFrame =
        OutFrame(byteArrayOf(Bes.HEADER, CMD, LEN, SET, if (v.on) 0x01 else 0x00, v.mode.wire))

    /**
     * The state a status frame reports, or null if this is not one.
     *
     * ⚠ Checks the command byte, not just the shape. `aa 9f 03 02 00 05` is Smart
     * Talk's reply and differs by one byte; this session drove Smart Talk for three
     * minutes while believing it was driving VoiceAware, so frames that parse under
     * the wrong command are a live failure here rather than a theoretical one.
     */
    fun state(reply: ByteArray): Spatial? {
        if (reply.size < 6) return null
        if (reply[0] != Bes.HEADER || reply[1] != CMD) return null
        if (reply[2] != LEN || reply[3] != STATUS) return null
        val mode = SpatialMode.of(reply[5]) ?: return null
        return Spatial(on = reply[4] != 0x00.toByte(), mode = mode)
    }
}

/**
 * How much of your own voice VoiceAware lets through.
 *
 * ⚠ The wire values run in the on-screen order here, unlike [SpatialMode] — which is a
 * coincidence of two vendors' enums and not a rule to lean on.
 */
enum class VoiceLevel(
    val wire: Byte,
) {
    LOW(0x01),
    MID(0x02),
    HIGH(0x03),
    ;

    companion object {
        fun of(wire: Byte): VoiceLevel? = entries.firstOrNull { it.wire == wire }
    }
}

/** VoiceAware's switch and how much it passes through. [JblVoiceAware] frames it. */
data class VoiceAware(
    val on: Boolean,
    val level: VoiceLevel,
)

/**
 * JBL VoiceAware — `aa 98`, level decoded 2026-08-17.
 *
 * ```
 * → aa 98 01 01                     ← aa 98 03 02 <level> <on>
 * → aa 98 03 00 <level> <on>        ← aa 98 03 02 <level> <on>
 * ```
 *
 * `level` is `01` Low · `02` Mid · `03` High.
 *
 * ⚠ **The level byte sat in this repo's docs as an unexplained `02` for weeks**, and
 * the frame was never wrong — `02` is Mid, and with the slider never moved a level and
 * a constant look identical. It took a drag to separate them, and the drag had to be
 * done by hand: the control is a gradient bar, and two attempts to reach it by tapping
 * produced confident logs and no traffic. `docs/captures.md` has both.
 *
 * ⚠ Same shape as [JblSpatial] and the same consequence: the device takes level and
 * switch in one frame, so the vendor app's slider necessarily turns VoiceAware on.
 * Building the frame here means it need not.
 */
object JblVoiceAware {
    const val CMD: Byte = 0x98.toByte()

    private const val LEN: Byte = 0x03
    private const val SET: Byte = 0x00
    private const val STATUS: Byte = 0x02

    fun get(): OutFrame = OutFrame(byteArrayOf(Bes.HEADER, CMD, 0x01, 0x01))

    fun set(v: VoiceAware): OutFrame =
        OutFrame(byteArrayOf(Bes.HEADER, CMD, LEN, SET, v.level.wire, if (v.on) 0x01 else 0x00))

    /**
     * ⚠ Checks the command byte. `aa 9d 03 02 01 01` — Spatial Sound, on, Music — has
     * this exact length and operator and a byte that is a valid level, so the command
     * is the only thing that tells them apart.
     */
    fun state(reply: ByteArray): VoiceAware? {
        if (reply.size < 6) return null
        if (reply[0] != Bes.HEADER || reply[1] != CMD) return null
        if (reply[2] != LEN || reply[3] != STATUS) return null
        val level = VoiceLevel.of(reply[4]) ?: return null
        return VoiceAware(on = reply[5] != 0x00.toByte(), level = level)
    }
}

/**
 * How long Smart Talk stays in TalkThru after you stop speaking.
 *
 * ⚠ **The wire value IS the number of seconds**, which is measured on four points and
 * not inferred from one: `05`/`0f`/`14` are the app's 5 s, 15 s and 20 s. So [seconds]
 * is not a label chosen here — it is what the byte means, and the two cannot drift.
 */
enum class TalkTimeout(
    val wire: Byte,
    val seconds: Int,
) {
    SEC_5(0x05, 5),
    SEC_15(0x0f, 15),
    SEC_20(0x14, 20),
    ;

    companion object {
        fun of(wire: Byte): TalkTimeout? = entries.firstOrNull { it.wire == wire }
    }
}

/** Smart Talk's switch and its hold, in one value for the reason [Spatial] is one. */
data class SmartTalk(
    val on: Boolean,
    val timeout: TalkTimeout,
)

/**
 * JBL Smart Talk — `aa 9f`, decoded 2026-08-16.
 *
 * ```
 * → aa 9f 01 01                       ← aa 9f 03 02 <on> <seconds>
 * → aa 9f 03 00 <on> <seconds>        ← aa 9f 03 02 <on> <seconds>
 * ```
 *
 * ⚠ **This is the frame that was driven for three minutes under the belief it was
 * VoiceAware.** `aa 9f 03 02 00 05` and VoiceAware's `aa 98 03 02 02 00` have the same
 * length and operator, and a segmented tap meant for one card reached the other's
 * picker. Hence [state]'s command check, and hence the warning repeated on every
 * decoder in this file.
 */
object JblSmartTalk {
    const val CMD: Byte = 0x9f.toByte()

    private const val LEN: Byte = 0x03
    private const val SET: Byte = 0x00
    private const val STATUS: Byte = 0x02

    fun get(): OutFrame = OutFrame(byteArrayOf(Bes.HEADER, CMD, 0x01, 0x01))

    fun set(v: SmartTalk): OutFrame =
        OutFrame(byteArrayOf(Bes.HEADER, CMD, LEN, SET, if (v.on) 0x01 else 0x00, v.timeout.wire))

    fun state(reply: ByteArray): SmartTalk? {
        if (reply.size < 6) return null
        if (reply[0] != Bes.HEADER || reply[1] != CMD) return null
        if (reply[2] != LEN || reply[3] != STATUS) return null
        val timeout = TalkTimeout.of(reply[5]) ?: return null
        return SmartTalk(on = reply[4] != 0x00.toByte(), timeout = timeout)
    }
}

/**
 * JBL Low Volume Dynamic EQ — `aa 9e`, decoded 2026-08-16.
 *
 * ```
 * → aa 9e 01 01            ← aa 9e 02 02 <on>
 * → aa 9e 02 00 <on>       ← aa 9e 02 02 <on>
 * ```
 *
 * ⚠ **A plain switch, and the length byte is `02` rather than [JblSpatial]'s `03`** —
 * the operator plus one payload byte. Reusing a `03`-shaped reader here would find the
 * payload one byte past the end.
 */
object JblLowVolumeEq {
    const val CMD: Byte = 0x9e.toByte()

    private const val LEN: Byte = 0x02
    private const val SET: Byte = 0x00
    private const val STATUS: Byte = 0x02

    fun get(): OutFrame = OutFrame(byteArrayOf(Bes.HEADER, CMD, 0x01, 0x01))

    fun set(on: Boolean): OutFrame =
        OutFrame(byteArrayOf(Bes.HEADER, CMD, LEN, SET, if (on) 0x01 else 0x00))

    fun state(reply: ByteArray): Boolean? {
        if (reply.size < 5) return null
        if (reply[0] != Bes.HEADER || reply[1] != CMD) return null
        if (reply[2] != LEN || reply[3] != STATUS) return null
        return reply[4] != 0x00.toByte()
    }
}

/**
 * Smart Audio & Video — three whole frames, and **no enable byte**.
 *
 * ⚠ **[OFF] is a state, not a modifier.** Measured 2026-08-17: with Video lit,
 * switching off sends the Audio-family payload, not a Video payload with a flag
 * cleared. So this is one three-way choice rather than a switch plus a mode, and
 * modelling it as the latter would invent a state — Video-and-off — that the device
 * never expresses.
 *
 * ⚠ The payload numbers are undecoded and look like DSP tuning. They are carried
 * whole, exactly as [JblEq.set] carries its thirteen unexplained bytes: what is
 * settled is which frame means which state, which is all that driving needs.
 *
 * ⚠ **A tidy prediction was refuted here.** Audio's third value moves `96` → `e6` when
 * switched off, a step of `0x50`, and Video's is `50` — so `a0` was written down in
 * advance as Video-off. It never appeared; the app sends a constant rather than
 * computing one. The arithmetic was neat enough to have been believed unchecked.
 */
enum class SmartAv(
    val payload: String,
) {
    OFF("000135 00e600 ffff"),
    AUDIO("000135 009600 ffff"),
    VIDEO("c5002e 005000 ffff"),
    ;

    /** The eight payload bytes, without the grouping this file writes them in. */
    val bytes: ByteArray get() = Hex.parse(payload.replace(" ", ""))

    companion object {
        fun of(payload: ByteArray): SmartAv? =
            entries.firstOrNull { it.bytes.contentEquals(payload) }
    }
}

/**
 * JBL Smart Audio & Video — `aa 81` sets, `aa 82` asks, `aa 83` answers.
 *
 * ```
 * → aa 82 00              ← aa 83 08 <8 bytes>
 * → aa 81 08 <8 bytes>    ← aa 83 08 <8 bytes>
 * ```
 *
 * ⚠ **Three commands for one row**, unlike every other setting on this device — the
 * `<cmd> <len> <operator>` convention does not hold here, so the operator-based readers
 * in this file cannot be reused.
 */
object JblSmartAv {
    const val SET: Byte = 0x81.toByte()
    const val GET: Byte = 0x82.toByte()
    const val STATUS: Byte = 0x83.toByte()

    private const val LEN: Byte = 0x08
    private const val AT = 3

    fun get(): OutFrame = OutFrame(byteArrayOf(Bes.HEADER, GET, 0x00))

    fun set(v: SmartAv): OutFrame = OutFrame(byteArrayOf(Bes.HEADER, SET, LEN) + v.bytes)

    /**
     * ⚠ Returns null for a payload nobody has captured rather than guessing the
     * nearest. Three frames are known and the space is eight bytes wide; a reader
     * that fell back to [SmartAv.OFF] would report the headphones off whenever the
     * firmware said something new.
     */
    fun state(reply: ByteArray): SmartAv? {
        if (reply.size < AT + LEN) return null
        if (reply[0] != Bes.HEADER || reply[1] != STATUS || reply[2] != LEN) return null
        return SmartAv.of(reply.copyOfRange(AT, AT + LEN))
    }
}

/**
 * `aa b1` — the JBL's key/value feature bag, and the home of the two rows that have
 * no command of their own.
 *
 * ```
 * aa b1 <len> <op> [<key> <size> <value…>]…      op  00 get · 01 set · 02 status
 * ```
 *
 * ⚠ **The operator is NOT followed by a payload, but by triples**, which is why this
 * one command needs its own reader rather than [Bes]. Every other command here is
 * `aa <cmd> <len> <operator> <payload…>`, and reading `b1` that way is what made a
 * captured `aa b1 03 00 02 00` look like a *set* with operator `00` for a whole
 * session. It is a *get* of key `02`.
 *
 * ⚠ **A get answers about the FIRST key only** — measured 2026-08-17: asking for
 * `01` and `02` together returned `01` alone. So ask one at a time; the list form
 * the vendor's SDK offers buys nothing here.
 */
object JblFeature {
    const val CMD: Byte = 0xb1.toByte()

    /** ⚠ Renegotiates the audio link when it changes — see [JblSettings]' callers. */
    const val LE_AUDIO: Byte = 0x01
    const val AURACAST: Byte = 0x02

    private const val GET: Byte = 0x00
    private const val SET: Byte = 0x01
    private const val STATUS: Byte = 0x02

    fun get(key: Byte): OutFrame = OutFrame(byteArrayOf(Bes.HEADER, CMD, 0x03, GET, key, 0x00))

    /**
     * ⚠ **Built and tested, never sent.** Flipping [LE_AUDIO] renegotiates the link
     * this app is talking over, so it belongs behind a deliberate control rather than
     * in a settings read, and nothing wires it yet.
     */
    fun set(key: Byte, on: Boolean): OutFrame =
        OutFrame(byteArrayOf(Bes.HEADER, CMD, 0x04, SET, key, 0x01, if (on) 0x01 else 0x00))

    /**
     * The value [key] carries in a status reply, or null if this frame has no such key.
     *
     * Walks the triples rather than reading a fixed offset: one reply *may* carry
     * several, and a reader that assumed one would silently return the wrong key's
     * value the first time the firmware sent two.
     */
    fun state(reply: ByteArray, key: Byte): Boolean? {
        if (reply.size < 4) return null
        if (reply[0] != Bes.HEADER || reply[1] != CMD || reply[3] != STATUS) return null
        val end = minOf(reply.size, 3 + (reply[2].toInt() and 0xff))
        var at = 4
        while (at + 1 < end) {
            val size = reply[at + 1].toInt() and 0xff
            // at + 1 + size is the last value byte; it has to fall inside the frame.
            if (size == 0 || at + 1 + size >= end) return null
            if (reply[at] == key) return reply[at + 2] != 0x00.toByte()
            at += 2 + size
        }
        return null
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
     * The frame inside [buffer] that [wanted] accepts, or null.
     *
     * ⚠ **A reply is NOT the only thing in the buffer.** `Gatt.collect` concatenates every
     * notification that arrives in the window — it has to, because a long answer comes
     * split across MTU-sized notifications — and this device volunteers `aa 25` battery
     * every ten seconds. Measured 2026-08-25: **8 of 64 getters came back with a battery
     * frame glued on**. Those all decoded, because it arrived *after*. When it arrives
     * FIRST, offset 0 is someone else's frame, every decoder here correctly returns null,
     * and the settings row silently vanishes — #1154.
     *
     * ⚠ **Returns the tail from the match, not the frame's own length.** `aa a2`'s length
     * byte undercounts its content by one, so slicing to it would clip the equaliser's
     * last byte. Decoders read by offset and ignore what follows, so handing them the rest
     * of the buffer is both safe and exactly what they got before this existed.
     *
     * ⚠ **Skipping uses the length byte, so it cannot skip PAST an `aa a2`** — that same
     * off-by-one would land one byte short. No curve has ever arrived unsolicited, so the
     * case does not occur; it would show up as a null, never as a wrong value.
     */
    fun frame(buffer: ByteArray, wanted: (ByteArray) -> Boolean): ByteArray? {
        var at = 0
        while (at + 2 < buffer.size) {
            if (buffer[at] != HEADER) return null
            val rest = buffer.copyOfRange(at, buffer.size)
            if (wanted(rest)) return rest
            at += 3 + (buffer[at + 2].toInt() and 0xff)
        }
        return null
    }

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
