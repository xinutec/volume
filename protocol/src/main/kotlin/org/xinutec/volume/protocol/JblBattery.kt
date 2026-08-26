package org.xinutec.volume.protocol

/**
 * How much charge the headphones report, and whether they are on the cable.
 *
 * ⚠ **One number, though the frame carries two.** The M2 sends the same value twice —
 * `BatteryInfoCmd` calls them master and slave, because the SDK is shared with true
 * wireless earbuds where the two cups have separate cells. On an over-ear they have
 * been identical in every frame this repo has seen, so [percent] is the master and
 * nothing here claims which cup that is.
 */
data class Battery(
    val percent: Int,
    /**
     * Whether it is charging, or **null when the device does not say**.
     *
     * ⚠ **Nullable since 2026-08-26, and the null is the point.** The Bose QC35 reports
     * one battery byte and its `02 05` CHARGER_DETECT is *not supported*, so nothing on
     * that device establishes a charging state. Defaulting to `false` would have put
     * "not charging" on a card as though it were a reading, which is the same class of
     * invention as the mode table that was wrong at all three values.
     */
    val charging: Boolean?,
)

/**
 * JBL battery — `aa 25`, volunteered every ten seconds without being asked.
 *
 * ```
 * ← aa 25 0d 01 00 00 <slave> <master> <box> ff ff ff ff ff ff ff
 * ```
 *
 * Each level byte is **a charging bit and a 7-bit percentage**: `& 0x80` is on the
 * cable, `& 0x7f` is the level, and `BatteryInfoCmd` treats anything over 100 as
 * *unknown* rather than as a number. That is what the trailing `ff`s are — absent
 * cells, not padding, and the box slot reads `ff` on this over-ear because there is
 * no case.
 *
 * ⚠ **The offsets are the SDK's, and no capture here can separate master from slave**
 * — the two bytes have been equal in every frame, exactly as `SafeSound`'s two payload
 * bytes were both `01` and left its status offset undecidable from the capture alone.
 * The difference is that SafeSound could be driven until one moved; a battery cannot.
 * So this reads index 7 because `parseBatteryInfo` does, and that is the whole warrant.
 *
 * ⚠ One calibration point exists and it does not settle the above: `5a` = 90 matched
 * the vendor app's "90%" on 2026-08-16 — but *both* bytes read `5a`, so it confirms the
 * scale and says nothing about which slot is which.
 */
object JblBattery {
    const val CMD: Byte = 0x25

    /**
     * ⚠ It is volunteered every ten seconds, but it can also be ASKED — measured
     * 2026-08-17, `aa 25 01 01` answers immediately with the same frame. Worth having:
     * waiting for a notification means a card that is blank for up to ten seconds.
     */
    fun get(): ByteArray = byteArrayOf(Bes.HEADER, CMD, 0x01, 0x01)

    /** `parseBatteryInfo` reads the levels only when the sub-command is `01`. */
    private const val LEVELS: Byte = 0x01

    private const val SLAVE = 6
    private const val MASTER = 7

    /** Over 100 means "no cell there", per `BatteryInfoCmd`. */
    private const val ABSENT = 100

    private fun level(b: Byte): Battery? {
        val v = b.toInt() and 0xff
        val percent = v and 0x7f
        if (percent > ABSENT) return null
        return Battery(percent = percent, charging = (v and 0x80) != 0)
    }

    /**
     * The charge in a notification, or null if this is not one.
     *
     * ⚠ This frame arrives **unsolicited and CONCATENATED with others** — it turned up
     * glued to an `aa b1` reply, and it was once mistaken for the answer to a question
     * about status field `3b`. So the command byte is checked, and a caller must not
     * assume a reply to its own write is about its own write.
     */
    fun state(frame: ByteArray): Battery? {
        if (frame.size <= MASTER) return null
        if (frame[0] != Bes.HEADER || frame[1] != CMD || frame[3] != LEVELS) return null
        return level(frame[MASTER])
    }

    /** Whether the two cup slots disagree — see the warning on this object. */
    fun cupsDiffer(frame: ByteArray): Boolean? {
        if (frame.size <= MASTER) return null
        if (frame[0] != Bes.HEADER || frame[1] != CMD || frame[3] != LEVELS) return null
        return frame[SLAVE] != frame[MASTER]
    }
}
