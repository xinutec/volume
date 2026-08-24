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
 * One point on a drawn equaliser curve: a band centre, and its gain.
 *
 * ⚠ **Gain is a float here because it is a float on the wire.** The JBL sends IEEE
 * singles and its app's own curves land on halves (`+2.5`), so rounding to the
 * integer dB that [EqSetting] uses would quietly move a band. Exact equality is
 * therefore meaningful: a write is echoed back bit for bit, so a difference is the
 * device disagreeing rather than arithmetic drift.
 */
data class EqBand(
    val hz: Int,
    val gain: Float,
)

/**
 * A whole curve, and the table id the device keeps it under.
 *
 * ⚠ **Not [EqSetting], and the difference is which end owns the values.** Sony sends
 * an opaque preset id and the *device* answers with the curve it means. The JBL is
 * sent a full curve *and* an id together, so which of the two it honours is not
 * established — nothing captured varies one without the other.
 *
 * ⚠ [table] is measured, not named: `00` was the flat curve and `01` was what the app
 * sent for JAZZ. Two values, so this is not evidence for an ordering of the menu.
 */
data class EqCurve(
    val table: Int,
    val bands: List<EqBand>,
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

    /**
     * ⚠ **From the vendor app's own axis, not from the device.** Sony Headphones
     * Connect labels every slider `-10 … 0 … +10`, and the levels captured off the
     * wire stay inside it. No frame *declares* a range, so this is a typo guard on
     * our side rather than the device's rule — do not report a value outside it as
     * having been refused by the headphones.
     */
    val RANGE = -10..10

    /**
     * `EqEbbInquiredType`, and `01` in every EQ frame captured.
     *
     * ⚠ Not the `00` that `52 00` uses. That is a different command (GET_STATUS),
     * and the two type bytes are not interchangeable — `52 01` also exists and drew
     * its own answer.
     */
    const val TYPE: Byte = 0x01

    const val GET: Byte = 0x56
    const val RET: Byte = 0x57
    const val SET: Byte = 0x58
    const val NOTIFY: Byte = 0x59
    const val GET_BANDS: Byte = 0x5a
    const val BANDS: Byte = 0x5b

    /**
     * Kept for the frame this was first written against; [NOTIFY] is the same byte.
     */
    const val STATE: Byte = NOTIFY

    /**
     * `56 01` — GET_PARAM.
     *
     * Predicted by the SDK's blocks of ten and then **found in the capture** at
     * 10:58:21, where Sony Headphones Connect asks it once on connecting. So this is
     * measured, not inferred: `→ 56 01` drew `← 57 01 a2 06 0d 0a 0a 0c 0e 10`.
     */
    fun get(): ByteArray = byteArrayOf(GET, TYPE)

    /** `5a 01` — the band table. The app re-asks after every preset change. */
    fun getBands(): ByteArray = byteArrayOf(GET_BANDS, TYPE)

    /** `58 01 <preset> 00` — ask for a preset, sending no levels of our own. */
    fun set(preset: Int): ByteArray = byteArrayOf(SET, TYPE, preset.toByte(), 0x00)

    /**
     * `ff` — `EqPresetId.UNSPECIFIED`, the preset byte a *levels* write carries.
     *
     * ⚠ **Not a slot.** Nothing is ever stored under `ff` and no read returns it: the
     * device keeps answering with whichever real preset is selected. It means "leave
     * the selection alone, these are the levels".
     */
    const val UNSPECIFIED = 0xff

    /**
     * `58 01 ff <count> <levels…>` — set the band levels of whatever is selected.
     *
     * ⚠ **The preset byte MUST be [UNSPECIFIED], and that is the whole bug this cost
     * a day to find.** Sending the slot's own id — the `a2` that `57 01` had just
     * reported — produces a frame the XM4 acks and silently drops. Every other byte
     * was already right, so nothing looked wrong: the write went out, the ack came
     * back, and the levels did not move. Captured 2026-08-24 with a band dragged in
     * Sony Headphones Connect, and confirmed in the SDK, where `sendEqBandSteps`
     * hardcodes `UNSPECIFIED` and **ignores the preset it was passed**.
     *
     * ⚠ Which is why there is no preset parameter here. Taking one and discarding it
     * would let a caller believe it had chosen a slot to write into.
     */
    fun setLevels(levels: List<Int>): ByteArray =
        byteArrayOf(SET, TYPE, UNSPECIFIED.toByte(), levels.size.toByte()) +
            ByteArray(levels.size) { (levels[it] + ZERO).toByte() }

    /**
     * Decode `59 01 <preset> <count> <levels…>`, or null if it is not that.
     *
     * ⚠ Accepts [RET] as well as [NOTIFY] — the same payload arrives under two
     * opcodes depending on whether it was asked for. Accepting only the notify made
     * a read of a device that had just answered look like silence.
     *
     * ⚠ Returns null rather than guessing on a short or foreign frame. A capture is
     * full of frames that are not the answer to the question just asked — acks,
     * unsolicited status — and one decoded optimistically becomes a confident wrong
     * reading.
     */
    fun state(payload: ByteArray): EqSetting? {
        if (payload.size < 4) return null
        if (payload[0] != NOTIFY && payload[0] != RET) return null
        if (payload[1] != TYPE) return null
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
     * ⚠ **Feed this the UNESCAPED payload**, which is what [SonyFrame.decodeAll]
     * hands back. The top band is `01 3e 80` = 16000, carried on the wire as
     * `01 3d 2e 80`; read at the wire layer it comes out 15662, which is close enough
     * to the app's "16k" label to look right.
     *
     * Measured against those labels: 400, 1k, 2.5k, 6.3k, 16k.
     */
    fun bands(payload: ByteArray): List<Int> {
        if (payload.size < 6 || payload[0] != BANDS) return emptyList()
        val out = mutableListOf<Int>()
        var i = 6
        while (i + 2 < payload.size) {
            // Each band is `01 <hi> <lo>`; the leading 01 is what makes it findable.
            if (payload[i] == 0x01.toByte()) {
                out +=
                    ((payload[i + 1].toInt() and 0xFF) shl 8) or (payload[i + 2].toInt() and 0xFF)
                i += 3
            } else {
                i++
            }
        }
        return out
    }
}

/**
 * One headphone family's equaliser.
 *
 * ⚠ **Deliberately not part of [AncDriver].** Every device here has ANC; the EQ is a
 * different set — the JLab has none reachable at all — and folding it in would make
 * every driver claim a capability it has to then refuse. The registry composes: a
 * driver may implement both, one, or neither.
 *
 * ⚠ **No shared preset vocabulary, on purpose.** Sony sends an opaque preset id and
 * the device answers with the curve; Bose has no preset on the wire at all, only
 * three signed band values its app happens to name. A common enum would be a
 * fiction, and there is no list-of-presets call here because nothing captured
 * enumerates them — inventing one from the five ids that happened to go past would
 * be a shorter list than the vendor's own menu, presented as if it were complete.
 */
interface EqDriver {
    /** What the device reports, or null when it will not say. */
    fun readEq(t: Transport): EqSetting?

    /**
     * Ask for [preset], and return **the state the device volunteered**, or null.
     *
     * ⚠ Null is the ordinary answer, not a failure — most devices say nothing useful
     * to a write. Returning it at all is because the Sony does: its SET draws an ack
     * and then an unsolicited `NTFY_PARAM` carrying the whole resulting state, in the
     * same reply window. Throwing that away and asking again would be a round trip
     * spent re-learning what has already been said.
     *
     * ⚠ **This is still not a confirmation.** It is a state report; it becomes
     * evidence only in [setEq], which compares it with what was asked for. A device
     * that ignored the write reports the *old* preset here, and that is precisely
     * the case a reply-means-success reading would get wrong.
     */
    fun writeEq(t: Transport, preset: Int): EqSetting?

    /** Band centre frequencies in Hz, in the order [EqSetting.levels] runs. */
    fun bands(t: Transport): List<Int> = emptyList()
}

/**
 * Write a preset, establish what the device holds now, and say which happened.
 *
 * ⚠ **Only the preset is compared.** The levels come back as whatever the device
 * decided the preset means, so requiring them to match something we sent would fail
 * every correct write — and a device that quietly clamps a curve is a real
 * possibility this leaves visible rather than asserting away.
 */
fun EqDriver.setEq(t: Transport, preset: Int): Confirmation<EqSetting> {
    val after = writeEq(t, preset) ?: readEq(t) ?: return Confirmation.Unverifiable
    return if (after.preset == preset) Confirmation.Confirmed else Confirmation.Contradicted(after)
}
