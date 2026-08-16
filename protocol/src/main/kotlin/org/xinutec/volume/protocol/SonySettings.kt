package org.xinutec.volume.protocol

/**
 * When the headphones switch themselves off.
 *
 * ⚠ Two values, because two is all the XM4's menu offered. A timed option — which
 * other Sony models have — would be a third encoding, and nothing here says what it
 * would be. Named from the app's own words.
 */
enum class AutoOff {
    /** "Do not turn off" — `11`. */
    NEVER,

    /** "Off when headphones are removed" — `10`. */
    WHEN_REMOVED,
}

/**
 * Sony auto power off — block `f0` (SYSTEM), type `04`.
 *
 * ```
 * → f6 04              GET_PARAM
 * ← f7 04 01 <v> 00    RET_PARAM
 * → f8 04 01 <v> 00    SET_PARAM
 * ← f9 04 01 <v> 00    NTFY_PARAM, after the ack
 * ```
 *
 * The GET was found in the connect-time conversation at 10:58:22, so unlike the
 * multipoint below this one has a read that was actually observed answering.
 */
object SonyAutoOff {
    const val TYPE: Byte = 0x04

    const val GET: Byte = 0xf6.toByte()
    const val RET: Byte = 0xf7.toByte()
    const val SET: Byte = 0xf8.toByte()
    const val NOTIFY: Byte = 0xf9.toByte()

    private const val NEVER: Byte = 0x11
    private const val WHEN_REMOVED: Byte = 0x10

    fun get(): ByteArray = byteArrayOf(GET, TYPE)

    fun set(mode: AutoOff): ByteArray =
        byteArrayOf(SET, TYPE, 0x01, if (mode == AutoOff.NEVER) NEVER else WHEN_REMOVED, 0x00)

    /**
     * ⚠ Accepts [RET] and [NOTIFY] alike, and **nothing else**. An unknown value byte
     * yields null rather than a default — a timed setting, if this model ever learns
     * one, must read as "not understood" instead of silently as "never".
     */
    fun state(payload: ByteArray): AutoOff? {
        if (payload.size < 4) return null
        if (payload[0] != RET && payload[0] != NOTIFY) return null
        if (payload[1] != TYPE) return null
        return when (payload[3]) {
            NEVER -> AutoOff.NEVER
            WHEN_REMOVED -> AutoOff.WHEN_REMOVED
            else -> null
        }
    }
}

/**
 * Sony multipoint — block `d0`, type `d2`.
 *
 * ```
 * → d6 d2              GET_PARAM
 * ← d7 d2 01 <on>      RET_PARAM
 * → d8 d2 01 <on>      SET_PARAM
 * ```
 *
 * ⚠ **Do not confirm a multipoint write from the reply. The reply is about a
 * different setting.** Setting `d2` to `01` drew `99 01 06 01` — a `90`-block
 * notification — and setting that `90`-block parameter back to `00` drew
 * `d9 d2 01 00`. In both directions the device acked what it was told and then
 * notified *the other thing*.
 *
 * The likeliest reading, and it is an inference: enabling multipoint forces the
 * connection-quality setting off LDAC, and putting that setting back turns
 * multipoint off — so each write has a side effect on the other, and what the device
 * volunteers is the side effect, not an echo. Whatever the cause, the consequence
 * for this code is settled: read back with [get].
 *
 * ⚠ **`d8 d2 01 00` has never been sent.** Multipoint was turned *off* in the
 * capture through the `90`-block parameter, not through this one. The value `00` is
 * known to be this field's off value only because [get] and the notification both
 * reported it.
 */
object SonyMultipoint {
    const val TYPE: Byte = 0xd2.toByte()

    const val GET: Byte = 0xd6.toByte()
    const val RET: Byte = 0xd7.toByte()
    const val SET: Byte = 0xd8.toByte()
    const val NOTIFY: Byte = 0xd9.toByte()

    fun get(): ByteArray = byteArrayOf(GET, TYPE)

    fun set(on: Boolean): ByteArray = byteArrayOf(SET, TYPE, 0x01, if (on) 0x01 else 0x00)

    fun state(payload: ByteArray): Boolean? {
        if (payload.size < 4) return null
        if (payload[0] != RET && payload[0] != NOTIFY) return null
        if (payload[1] != TYPE) return null
        return when (payload[3]) {
            0x00.toByte() -> false
            0x01.toByte() -> true
            else -> null
        }
    }
}

/**
 * One headphone family's multipoint switch — two devices have it decoded, which is
 * what makes this an interface rather than two methods on two drivers.
 */
interface MultipointDriver {
    fun readMultipoint(t: Transport): Boolean?

    fun writeMultipoint(t: Transport, on: Boolean)
}

/**
 * Write it, read it back, and say which happened.
 *
 * ⚠ **Always a real read**, on both devices, for the same reason from two different
 * causes: the Sony answers a multipoint write by describing a *different* setting,
 * and the Bose answers with a flags byte whose value never equals the one written.
 * Either would report every write as failed — or every write as fine — if the reply
 * were treated as the answer.
 */
fun MultipointDriver.setMultipoint(t: Transport, on: Boolean): Confirmation<Boolean> {
    writeMultipoint(t, on)
    val after = readMultipoint(t) ?: return Confirmation.Unverifiable
    return if (after == on) Confirmation.Confirmed else Confirmation.Contradicted(after)
}
