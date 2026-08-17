package org.xinutec.volume.protocol

/**
 * JBL Auto Play & Pause — set `aa 35`, reported as status field `38`.
 *
 * ```
 * → aa 35 01 <on>      ← aa 00 02 35 <on>      the ack, not the answer
 * → aa 21 01 38        ← aa 22 02 38 <on>
 * ```
 *
 * ⚠ **Setter and status field do NOT mirror here**, and this row is why that is written
 * down as a habit rather than a rule. `31`, `32` and `33` all set on the field they
 * report; this one sets on `35` and reports on `38`. The claim "38 is Auto Play & Pause"
 * was published on one agreeing byte, retracted on the mirror argument, and re-argued
 * from the SDK — none of which is a measurement. **Driven both ways 2026-08-17 23:38**:
 * off and back on, each confirmed by reading `38`, which is what settles it.
 */
object JblAutoPlay {
    const val FIELD: Byte = 0x38
    const val SET: Byte = 0x35

    fun get(): ByteArray = byteArrayOf(Bes.HEADER, Bes.STATUS_GET, 0x01, FIELD)

    fun set(on: Boolean): ByteArray = byteArrayOf(Bes.HEADER, SET, 0x01, if (on) 0x01 else 0x00)

    fun state(reply: ByteArray): Boolean? {
        val p = Bes.status(reply, FIELD) ?: return null
        if (p.isEmpty()) return null
        return p[0] != 0x00.toByte()
    }
}

/**
 * Left / right sound balance: a switch, and where the balance sits.
 *
 * ⚠ **[level] is carried, not offered.** `64` = 100 is what this unit reports and what
 * the app draws as "0" on an L—R slider, so 100 is its centre. The range is NOT
 * established — nothing here has moved it — so a write sends back whatever was read and
 * the UI offers only the switch. The same discipline as [TimedOff.minutes].
 */
data class Balance(
    val on: Boolean,
    val level: Int,
)

/**
 * JBL left/right balance — `aa a8`, driven 2026-08-17 23:39.
 *
 * ```
 * → aa a8 01 01                    ← aa a8 05 02 01 <on> 02 <level>
 * → aa a8 05 00 01 <on> 02 <level> ← aa a8 05 02 01 <on> 02 <level>
 * ```
 *
 * ⚠ **The payload is key/value pairs, not positional** — `01` is the switch's key and
 * `02` the level's, so the byte after the operator is a KEY and reading it as the value
 * says "on" for a feature that is off. `LeftRightSoundBalanceCmd` agrees: it takes the
 * switch from index 5 and the level from index 7, never from index 4.
 *
 * ✅ Switched on at centre and off again, each read back — inaudible at level 100, which
 * is why that was the safe way to prove the writer.
 */
object JblBalance {
    const val CMD: Byte = 0xa8.toByte()

    private const val LEN: Byte = 0x05
    private const val SET: Byte = 0x00
    private const val STATUS: Byte = 0x02
    private const val ON_KEY: Byte = 0x01
    private const val LEVEL_KEY: Byte = 0x02
    private const val ON_AT = 5
    private const val LEVEL_AT = 7

    fun get(): ByteArray = byteArrayOf(Bes.HEADER, CMD, 0x01, 0x01)

    fun set(v: Balance): ByteArray =
        byteArrayOf(
            Bes.HEADER,
            CMD,
            LEN,
            SET,
            ON_KEY,
            if (v.on) 0x01 else 0x00,
            LEVEL_KEY,
            v.level.toByte(),
        )

    fun state(reply: ByteArray): Balance? {
        if (reply.size <= LEVEL_AT) return null
        if (reply[0] != Bes.HEADER || reply[1] != CMD) return null
        if (reply[2] != LEN || reply[3] != STATUS) return null
        if (reply[4] != ON_KEY || reply[6] != LEVEL_KEY) return null
        return Balance(on = reply[ON_AT] != 0x00.toByte(), level = reply[LEVEL_AT].toInt() and 0xff)
    }
}

/**
 * Personal Sound Amplification — **read, never written, and for the hearing reason.**
 *
 * ```
 * → aa a0 01 01     ← aa a0 07 02 01 <on> 02 <level> 03 <index>
 * ```
 *
 * ⚠ **PSAP amplifies the world into your ears.** It is not the Max Volume Limiter, but
 * it is the other control on this device whose whole job is to make things louder, so it
 * gets the same treatment: shown, never set. No writer exists here and none should
 * without Pippijn asking for one.
 *
 * ✅ **This row's published contradiction is resolved, and it was a misreading, not a
 * device fault.** The note said `PSAPCmd` takes `setOn` from index 4 — `01` in the
 * captured reply — while the app's own row said *Disabled*. `PSAPCmd` has TWO branches:
 * the other one reads `setOn` from index **5**, level from 7 and index from 9, and that
 * is the one this frame's shape selects. Index 4 is the KEY `01`, and index 5 is `00`.
 * The device and the app agreed all along.
 */
object JblPsap {
    const val CMD: Byte = 0xa0.toByte()

    private const val LEN: Byte = 0x07
    private const val STATUS: Byte = 0x02
    private const val ON_AT = 5
    private const val LEVEL_AT = 7

    fun get(): ByteArray = byteArrayOf(Bes.HEADER, CMD, 0x01, 0x01)

    fun state(reply: ByteArray): Boolean? {
        if (reply.size <= LEVEL_AT) return null
        if (reply[0] != Bes.HEADER || reply[1] != CMD) return null
        if (reply[2] != LEN || reply[3] != STATUS) return null
        if (reply[4] != 0x01.toByte()) return null
        return reply[ON_AT] != 0x00.toByte()
    }
}
