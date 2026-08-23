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
 * ✅ **The third byte is the feature's settingType** — see [SonySwitch], which was
 * written once the SDK named it. This one is `ConnectionModeSettingType.SOUND_CONNECTION`
 * = `00`, where auto-off's is `AutoPowerOffParameterType.ACTIVE_AND_SELECTIME_ID` = `01`.
 * The tables differ per feature, so `00` here and `01` there is not an inconsistency.
 *
 * ⚠ This supersedes an earlier note saying "whatever that byte counts, it is not the same
 * thing in both". It is the same *kind* of thing in both; the frames were right and the
 * explanation was missing. Nothing on the wire changed.
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
 * A Sony setting that is simply on or off: `<command> <inquiredType> <settingType> <value>`.
 *
 * ✅ **That shape is not a guess from mirroring replies — it is the vendor SDK's own
 * argument list**, read out of `com.sony.songpal.mdr` on 2026-08-23 with
 * [scripts/smali_enum.py]. Every byte position lands on a named enum:
 *
 * | position | enum | example |
 * | --- | --- | --- |
 * | inquiredType | `SystemInquiredType` / `AudioInquiredType` | `03` CONTROL_BY_WEARING |
 * | settingType | one per feature, usually a single entry | `ControlByWearingSettingType.ON_OFF` = `00` |
 * | value | one per feature, `00`/`01` | `ControlByWearingSettingValue.ON` = `01` |
 *
 * ⚠ **The settingType byte is the one that was previously carried without a name**, and
 * the reason it is a parameter here rather than a constant `00`: [SonySoundQuality] sends
 * `00` where [SonyAutoOff] sends `01`, and the file used to say only that "whatever that
 * byte counts, it is not the same thing in both". It is the same *kind* of thing in both —
 * a per-feature type selector — and the two features simply have different tables.
 * `AutoPowerOffParameterType` has no `00` at all; its single entry is
 * `01 ACTIVE_AND_SELECTIME_ID`, which is exactly the byte that frame carries.
 *
 * ✅ **Two settings already driven on hardware confirm the reading**, which is what makes
 * it usable for settings that have not been: [SonyAutoOff]'s values `11`/`10` are
 * `AutoPowerOffElementId.POWER_OFF_DISABLE` and `POWER_OFF_WHEN_REMOVED_FROM_EARS`, and
 * [SonySoundQuality]'s `00`/`01` are `ConnectionModeSettingValue.SOUND_QUALITY_PRIOR` and
 * `CONNECTION_QUALITY_PRIOR`. Both were decoded from captures long before the SDK was
 * read, and the SDK agrees with both.
 *
 * ⚠ It is still the APK's word about the *app*. The three instances below have had their
 * **reads** confirmed against the XM4 and against Sound Connect's screens; their **writes**
 * are marked at each instance and must not be described as working until one lands.
 */
class SonySwitch(
    /** `*_GET_PARAM` for this block — `f6` SYSTEM, `e6` AUDIO. */
    private val getCmd: Byte,
    /** `*_RET_PARAM` — the answer to [get]. */
    private val retCmd: Byte,
    /** `*_SET_PARAM` — always [getCmd] + 2 on this device, and written out anyway. */
    private val setCmd: Byte,
    /** `*_NTFY_PARAM` — unsolicited, and also the echo after a [set]. */
    private val notifyCmd: Byte,
    /** `SystemInquiredType` or `AudioInquiredType`. */
    val type: Byte,
    /** The type byte a [get] is answered with — the feature's *SettingType* table. */
    private val readType: Byte,
    /**
     * The type byte a [set] must carry, and the one its notify comes back with.
     *
     * ⚠ **Usually the same as [readType], and for Speak-to-Chat it is NOT** — see
     * [SonySpeakToChat]. Defaulting them equal would have been the tidier signature and
     * would have re-hidden the bug this exists for.
     */
    private val writeType: Byte,
) {
    /**
     * The command bytes that would answer a [get] or a [set] — everything else in the
     * reply window belongs to something else. See `exchangeFramed`, which needs this
     * because the XM4 volunteers unrelated notifications mid-conversation.
     */
    val answers: ByteArray get() = byteArrayOf(retCmd, notifyCmd)

    fun get(): ByteArray = byteArrayOf(getCmd, type)

    fun set(on: Boolean): ByteArray = byteArrayOf(setCmd, type, writeType, onOff(on))

    private fun onOff(on: Boolean): Byte = if (on) 0x01 else 0x00

    /**
     * ⚠ **A RET and a NOTIFY do not carry the same type byte**, which is why this
     * chooses the expected one from the command rather than checking a single field.
     * The vendor app has two separate parser classes for exactly this reason.
     *
     * ⚠ The type byte is checked at all because an extended-parameter reply carries a
     * different table in that position — a decoder that skipped straight to byte 3
     * would read a Speak-to-Chat *sensitivity* as an on/off.
     */
    fun state(payload: ByteArray): Boolean? {
        if (payload.size < 4) return null
        val expected =
            when (payload[0]) {
                retCmd -> readType
                notifyCmd -> writeType
                else -> return null
            }
        if (payload[1] != type || payload[2] != expected) return null
        return when (payload[3]) {
            0x00.toByte() -> false
            0x01.toByte() -> true
            else -> null
        }
    }
}

/** `f6` SYSTEM_GET_PARAM · `f7` RET · `f8` SET · `f9` NTFY. */
private fun systemSwitch(type: Byte, readType: Byte, writeType: Byte) =
    SonySwitch(
        getCmd = 0xf6.toByte(),
        retCmd = 0xf7.toByte(),
        setCmd = 0xf8.toByte(),
        notifyCmd = 0xf9.toByte(),
        type = type,
        readType = readType,
        writeType = writeType,
    )

/** `e6` AUDIO_GET_PARAM · `e7` RET · `e8` SET · `e9` NTFY. */
private fun audioSwitch(type: Byte, readType: Byte, writeType: Byte) =
    SonySwitch(
        getCmd = 0xe6.toByte(),
        retCmd = 0xe7.toByte(),
        setCmd = 0xe8.toByte(),
        notifyCmd = 0xe9.toByte(),
        type = type,
        readType = readType,
        writeType = writeType,
    )

/**
 * **DSEE Extreme** — `AudioInquiredType.UPSCALING` (`e2` in the device's own function
 * list), settings type `02`.
 *
 * ```
 * → e6 02              read
 * ← e7 02 00 00        UpscalingSettingValue.OFF
 * → e8 02 00 01        write AUTO
 * ```
 *
 * ✅ **Read on the XM4 2026-08-23 and confirmed**: `e7 02 00 00`, and Sound Connect's
 * DSEE Extreme row read **Off** at the same moment.
 *
 * ⚠ **`true` is `UpscalingSettingValue.AUTO`, not a generic "on"** — the table has
 * exactly `00 OFF` and `01 AUTO`, so the switch is two-state and the app draws it as a
 * toggle, but the name it is toggling to is AUTO.
 *
 * ⚠ **Do not read DSEE's state from `14`/`15` UPSCALING_INDICATOR.** That answered
 * `15 00 02 00` — `UpscalingEffectStatus` INVALID — in the same session, which is about
 * whether upscaling is *doing anything to the current stream*, not whether the setting
 * is on. Two fields, one switch.
 */
val SonyDsee = audioSwitch(type = 0x02, readType = 0x00, writeType = 0x00)

/**
 * **Pause when headphones are removed** — `SystemInquiredType.CONTROL_BY_WEARING` (`f3`),
 * settings type `03`.
 *
 * ```
 * → f6 03              read
 * ← f7 03 00 01        ControlByWearingSettingValue.ON
 * → f8 03 00 00        write OFF
 * ```
 *
 * ✅ **Read on the XM4 2026-08-23 and confirmed**: `f7 03 00 01`, and the app's
 * "Pause when headphones are removed" row read **On**.
 *
 * ⚠ **Not the same thing as [SonyAutoOff].** That one is `f4` AUTO_POWER_OFF and switches
 * the headphones *off*; this one pauses playback. Both are wearing-sensor features and the
 * XM4 has both, which is precisely why they are easy to conflate.
 */
val SonyPauseOnRemoval = systemSwitch(type = 0x03, readType = 0x00, writeType = 0x00)

/**
 * **Speak-to-Chat** — `SystemInquiredType.SMART_TALKING_MODE` (`f5`), settings type `05`.
 *
 * ```
 * → f6 05              read
 * ← f7 05 00 00        SettingType.ON_OFF,      SmartTalkingModeSettingValue.OFF
 * → f8 05 01 01        write ON
 * ← f9 05 01 01        ParameterType.MODE_ON_OFF ⚠ a DIFFERENT table in the same slot
 * ```
 *
 * ✅ **Driven both ways on the XM4 2026-08-23 18:50, worn**, and restored to Off.
 *
 * ⚠ **THE READ AND THE WRITE USE DIFFERENT TYPE TABLES, and this is the only setting
 * here that does.** The reply to a [get] carries `SmartTalkingModeSettingType.ON_OFF`
 * = `00`; a [set] must carry `SmartTalkingModeParameterType.MODE_ON_OFF` = `01`. Sony's
 * app has two separate payload classes for it — `ve0.c` parses the RET with SettingType,
 * `ve0.d` builds the SET with ParameterType — which is the shape this file now mirrors.
 *
 * ⚠ **`f8 05 00 01` — the same byte as the read — is accepted, acked, and silently does
 * nothing.** That is what was sent first, and for an hour this file said the XM4 refused
 * Speak-to-Chat, next to multipoint and the [CUSTOM] button. It does not. The device even
 * said so: it answered the bad SET with `f9 05 01 00`, echoing a `01` where a `00` had
 * been sent, and that transposition was read as a malformed echo rather than as the
 * device naming the table it actually wanted.
 *
 * ⚠ **The generalisation is what failed, not the byte.** [SonySwitch] was built from
 * three settings that all happened to use one type byte in both directions, and a fourth
 * was then assumed to. Two agreeing samples are not a rule.
 *
 * ⚠ **The sensitivity and mode-out time are NOT reachable through this.** They live on
 * `fa`/`fc` SYSTEM_*_EXTENDED_PARAM with their own tables — `DetectionSensitivity`
 * (`00` AUTO, `01` HIGH, `02` LOW) and `ModeOutTime` (`00` FAST, `01` MID, `02` SLOW,
 * `03` NONE). Nothing here has sent an extended-parameter frame, and [state] rejects one
 * rather than decoding its first byte as an on/off.
 *
 * ⚠ **Turning this ON changes what the headphones do to audio when you talk**, which is
 * the one setting in this file with an effect the wearer cannot miss. Restore it.
 *
 * ⚠ **It also needs the headphones ON A HEAD to be worth testing** — not because the
 * write is refused off-head, which was checked and is false, but because the XM4 powers
 * itself off shortly after removal when auto-off is WHEN_REMOVED.
 */
val SonySpeakToChat = systemSwitch(type = 0x05, readType = 0x00, writeType = 0x01)

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
