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
 * ⚠ **THE XM4 REFUSES TO ENABLE MULTIPOINT, and so this has never once succeeded.**
 * Driven on hardware 2026-08-16: `d8 d2 01 01` is acked and answered with
 * `d9 d2 01 00` — still off — and a following [get] agrees. Sony Headphones Connect
 * fails identically: its switch flips on and immediately back, both driven over adb
 * and pressed by hand. So the frame below is not suspected of being wrong; the
 * device is refusing it, and the vendor app has no more luck. Not the codec — it was
 * refused in both Sound Quality Modes.
 *
 * ⚠ **Do not confirm a multipoint write from the reply**, which names whichever
 * parameter the device considers changed. Sometimes that is this one — today's
 * `d9 d2 01 00` — and sometimes another: in the morning capture the same SET drew
 * `99 01 06 01`, a `90`-block notification. An earlier note here said the reply is
 * *always* about a different setting, which was one capture generalised too far.
 * Either way [setMultipoint] reads back with [get].
 *
 * ⚠ **`d8 d2 01 00` has never been sent**, because multipoint has never been on for
 * this code to turn off. The value `00` is known to be this field's off value only
 * because [get] and the notification both report it.
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
 * Whether the link is optimised for LDAC or for not cutting out.
 *
 * Named from Sony Headphones Connect's own two labels.
 */
enum class SoundQuality {
    /** "Prioritize Sound Quality" — `00`. LDAC. */
    QUALITY,

    /** "Priority on Stable Connection" — `01`. */
    STABLE,
}

/**
 * Sony Sound Quality Mode — block `e0` (AUDIO), type `01`.
 *
 * ```
 * → e6 01              GET_PARAM
 * ← e7 01 00 <v>       RET_PARAM
 * → e8 01 00 <v>       SET_PARAM
 * ← e9 01 00 <v>       NTFY_PARAM, echoes the value
 * ```
 *
 * ✅ **Driven on hardware 2026-08-16**, both directions, each confirmed by read-back
 * and restored. Decoded the same evening from the vendor app changing it.
 *
 * ⚠ **The third byte is `00` here, where every other setting in this file has `01`.**
 * Auto-off sends `f8 04 01 <v> 00` and multipoint `d8 d2 01 <v>`; this one sends
 * `e8 01 00 <v>`. Carried verbatim from the capture rather than normalised, because
 * whatever that byte counts, it is not the same thing in both.
 *
 * Changing it renegotiates the codec, so the link drops and returns — the vendor app
 * warns "Reconnects to the audio device" before doing it.
 */
object SonySoundQuality {
    const val TYPE: Byte = 0x01

    const val GET: Byte = 0xe6.toByte()
    const val RET: Byte = 0xe7.toByte()
    const val SET: Byte = 0xe8.toByte()
    const val NOTIFY: Byte = 0xe9.toByte()

    fun get(): ByteArray = byteArrayOf(GET, TYPE)

    fun set(mode: SoundQuality): ByteArray =
        byteArrayOf(SET, TYPE, 0x00, if (mode == SoundQuality.STABLE) 0x01 else 0x00)

    fun state(payload: ByteArray): SoundQuality? {
        if (payload.size < 4) return null
        if (payload[0] != RET && payload[0] != NOTIFY) return null
        if (payload[1] != TYPE) return null
        return when (payload[3]) {
            0x00.toByte() -> SoundQuality.QUALITY
            0x01.toByte() -> SoundQuality.STABLE
            else -> null
        }
    }
}

/**
 * The [CUSTOM] button's assignment — block `f0` (SYSTEM), type `06`.
 *
 * ```
 * → f0 06              GET_CAPABILITY — the assignable codes
 * ← f1 06 01 02 01 00 03 00 02 00 01 21 02 31 03 00 31 01 33 22 32 32 01 00 34
 * → f6 06              GET_PARAM
 * ← f7 06 01 <v>       RET_PARAM
 * → f8 06 01 <v>       SET_PARAM
 * ← f9 06 01 <v>       NTFY_PARAM
 * ```
 *
 * Decoded 2026-08-16 evening — this is what #955 went looking for, after the morning's
 * attempt sent nothing at all.
 *
 * ⚠ **THE WRITE DOES NOT WORK FROM THIS CODE, AND IT DOES WORK FROM THE VENDOR APP.**
 * `f8 06 01 31` is acked and then ignored: no `f9` notify arrives and [get] still
 * reads the old value, across a plain session, a session that sent the commit below,
 * and a session that read [capabilities] first. Sony Headphones Connect sending the
 * identical bytes gets `99 01 02 01` back within 400 ms and the change sticks. So
 * something about *its* session is the difference, and what that is has not been
 * found. ⚠ Do not present this as a working setting.
 *
 * ⚠ **This is NOT the same failure as multipoint.** There the vendor app fails too;
 * here it succeeds and we do not. Merging the two would lose the one asymmetry that
 * says where to look next.
 */
object SonyButton {
    const val TYPE: Byte = 0x06

    const val GET_CAPABILITY: Byte = 0xf0.toByte()
    const val RET_CAPABILITY: Byte = 0xf1.toByte()
    const val GET: Byte = 0xf6.toByte()
    const val RET: Byte = 0xf7.toByte()
    const val SET: Byte = 0xf8.toByte()
    const val NOTIFY: Byte = 0xf9.toByte()

    /**
     * ⚠ **Two of the three the XM4's menu offers.** "Amazon Alexa" was in the dropdown
     * and was never selected, so it has no code here and an unknown value decodes to
     * null rather than to a guess. The capability reply carries `21 31 33 22 32 32 34`
     * among its bytes, which is more codes than this enum names — enumerating them
     * from that string would be reading a list this repo has not proven is one.
     */
    enum class Action(
        val code: Byte,
    ) {
        AMBIENT_SOUND_CONTROL(0x00),
        DIGITAL_ASSISTANT(0x31),
    }

    fun capabilities(): ByteArray = byteArrayOf(GET_CAPABILITY, TYPE)

    fun get(): ByteArray = byteArrayOf(GET, TYPE)

    fun set(action: Action): ByteArray = byteArrayOf(SET, TYPE, 0x01, action.code)

    /**
     * ⚠ The vendor app sends this **after** the device answers a [set] with
     * `99 01 02 01`, and only once the owner has agreed to the reconnect its dialog
     * warns about. Carried because it is part of the observed exchange; on its own it
     * does nothing, which is measured, not assumed.
     */
    fun commitReconnect(): ByteArray = byteArrayOf(0x98.toByte(), 0x01, 0x02, 0x01)

    fun state(payload: ByteArray): Action? {
        if (payload.size < 4) return null
        if (payload[0] != RET && payload[0] != NOTIFY) return null
        if (payload[1] != TYPE) return null
        return Action.entries.firstOrNull { it.code == payload[3] }
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
 * causes: the Sony's reply may describe a *different* parameter than the one written,
 * and the Bose answers with a flags byte whose value never equals the one written.
 * Either would report every write as failed — or every write as fine — if the reply
 * were treated as the answer.
 *
 * This is the check that caught the XM4 refusing multipoint outright. A driver that
 * had trusted the reply would have reported a confirmed write that never happened.
 */
fun MultipointDriver.setMultipoint(t: Transport, on: Boolean): Confirmation<Boolean> {
    writeMultipoint(t, on)
    val after = readMultipoint(t) ?: return Confirmation.Unverifiable
    return if (after == on) Confirmation.Confirmed else Confirmation.Contradicted(after)
}
