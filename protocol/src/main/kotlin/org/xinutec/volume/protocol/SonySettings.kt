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
     * `AssignableSettingsPreset`, in full.
     *
     * ⚠ **Naming every code is safe; OFFERING every code is not.** The list a device
     * actually allows comes from [presets], parsed out of its own `f0 06` reply — the
     * XM4 allows three of these. ⚠ `VOLUME_CONTROL` is why that distinction is a rule
     * rather than a preference: a button editor built from this enum would put a volume
     * control on a device that never offered one.
     *
     * ⚠ This enum said "two of the three the menu offers" and called `31`
     * `DIGITAL_ASSISTANT` until 2026-08-24, when the capability grammar was decoded from
     * Sony's own parser and the reply turned out to name its values exactly.
     */
    enum class Action(
        val code: Byte,
    ) {
        AMBIENT_SOUND_CONTROL(0x00),
        VOLUME_CONTROL(0x10),
        PLAYBACK_CONTROL(0x20),
        VOICE_RECOGNITION(0x30),
        GOOGLE_ASSISTANT(0x31),
        AMAZON_ALEXA(0x32),
        TENCENT_XIAOWEI(0x33),
        NO_FUNCTION(0xff.toByte()),
    }

    /**
     * What this device will actually accept for its key, from `f1 06`.
     *
     * The reply nests three deep and every length is carried, so this walks rather than
     * indexes — decoded from Sony's own parser, and the XM4's 23 bytes come out exactly:
     *
     * ```
     * f1 06 <numKeys>
     *   per key:    <key> <keyType> <defaultPreset> <numPresets>
     *   per preset: <preset> <numActions>
     *   per action: <action> <function>
     * ```
     *
     * ⚠ **Returns empty rather than a default when it cannot parse.** An editor with no
     * options is obviously broken; an editor showing every code in [Action] is not, and
     * would be offering a volume control nobody advertised.
     */
    fun presets(capability: ByteArray): List<Action> {
        if (capability.size < 3) return emptyList()
        if (capability[0] != RET_CAPABILITY || capability[1] != TYPE) return emptyList()
        val out = mutableListOf<Action>()
        var i = 3
        repeat(capability[2].toInt() and 0xff) {
            // <key> <keyType> <defaultPreset> <numPresets>
            if (i + 3 >= capability.size) return out
            val presetCount = capability[i + 3].toInt() and 0xff
            i += 4
            repeat(presetCount) {
                if (i + 1 >= capability.size) return out
                val code = capability[i]
                Action.entries.firstOrNull { it.code == code }?.let(out::add)
                // skip this preset's <action> <function> pairs
                i += 2 + 2 * (capability[i + 1].toInt() and 0xff)
            }
        }
        return out
    }

    fun capabilities(): ByteArray = byteArrayOf(GET_CAPABILITY, TYPE)

    fun get(): ByteArray = byteArrayOf(GET, TYPE)

    fun set(action: Action): ByteArray = byteArrayOf(SET, TYPE, 0x01, action.code)

    /**
     * `94 01 00` — ALERT_SET_STATUS · FIXED_MESSAGE · ENABLE.
     *
     * ✅ **THE FRAME THAT SOLVED #965.** The XM4 sends no alert to a peer that has not
     * asked for one, and [set] does not commit until its alert is answered. So without
     * this, a button write is acked and silently dropped — which for eight days read as
     * the device refusing this app in particular. It draws no reply of its own.
     */
    fun subscribeAlerts(): ByteArray = byteArrayOf(0x94.toByte(), 0x01, 0x00)

    /**
     * Answer the device's `99 01 02 01` — `AlertAction.POSITIVE` or `NEGATIVE`.
     *
     * ⚠ **The last byte is a different enum in each direction.** The device's `99` ends
     * with `AlertActionType` (whether the dialog has two buttons); this ends with
     * `AlertAction` (which button). Both read `01`, which hid the difference.
     *
     * ⚠ **A positive answer KILLS THE LINK, and that is success.** The device commits and
     * reconnects at once, so the write of this frame reports a broken pipe while its bytes
     * land. Driven both ways 2026-08-24: negative gives an orderly `f9` and no disconnect.
     */
    fun answer(yes: Boolean): ByteArray =
        byteArrayOf(0x98.toByte(), 0x01, 0x02, if (yes) 0x01 else 0x00)

    /** True if this is the device asking about a key-assign change — `99 01 02 …`. */
    fun asksAboutKeyAssign(payload: ByteArray): Boolean =
        payload.size >= 3 &&
            payload[0] == 0x99.toByte() &&
            payload[1] == 0x01.toByte() &&
            payload[2] == 0x02.toByte()

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

/** How hard the XM4 listens before deciding you are talking. */
enum class ChatSensitivity {
    /** `00` — the device picks. */
    AUTO,

    /** `01`. */
    HIGH,

    /** `02`. */
    LOW,
}

/**
 * How long after you stop talking Speak-to-Chat waits before it hands the music back.
 *
 * ⚠ **The seconds are the DEVICE'S numbers, not a guess.** `f0 05` ends with a four-byte
 * array read at a fixed offset by Sony's own capability parser, indexed by this enum's
 * ordinal. The XM4 answered `0f 1e 3c 00`.
 */
enum class ModeOutTime(
    val seconds: Int,
) {
    /** `00`. */
    FAST(15),

    /** `01`. */
    MID(30),

    /** `02`. */
    SLOW(60),

    /** `03` — stays in Speak-to-Chat until you tap out of it. */
    NONE(0),
}

/**
 * The three Speak-to-Chat detail settings, which share **one** frame.
 *
 * ⚠ **They are read and written as a unit and must stay that way.** The payload is
 * `<sensitivity> <voiceFocus> <modeOutTime>` with no field selector, so writing one
 * means sending all three — a caller that filled in a default for the other two would
 * quietly reset them. Modelled as a single value for that reason, not for tidiness.
 */
data class ChatDetail(
    val sensitivity: ChatSensitivity,
    val voiceFocus: Boolean,
    val modeOutTime: ModeOutTime,
)

/**
 * Speak-to-Chat's detail settings — `fa`/`fc` SYSTEM_*_EXTENDED_PARAM, type `05`.
 *
 * ✅ **All three driven on the XM4 2026-08-24**, each confirmed by an independent `fa 05`
 * read and every one restored:
 *
 * ```
 * → fa 05              ← fb 05 00 00 00 01   AUTO · focus off · MID
 * → fc 05 00 01 00 01  ← fd 05 00 01 00 01   sensitivity HIGH
 * → fc 05 00 02 01 02  ← fd 05 00 02 01 02   LOW · focus on · SLOW
 * → fc 05 00 00 00 01  ← fd 05 00 00 00 01   restored
 * ```
 *
 * ⚠ **This is the first `fa`/`fc` frame family this repo sends**, and it behaves like the
 * ordinary param frames: the notify echoes the value, so a write is confirmable from its
 * own reply as well as by re-reading.
 *
 * ⚠ **They take while Speak-to-Chat itself is OFF.** Unlike Focus on Voice, which is
 * silently ignored outside ambient mode, these are not gated on the feature being on — so
 * a write that appears to do nothing here is a real failure, not a mode problem.
 *
 * ⚠ The leading `00` is `SmartTalkingModeDetailSettingType.TYPE_1`, the only value there
 * is. It is a payload selector, not a setting.
 */
object SonyChatDetail {
    const val TYPE: Byte = 0x05

    const val GET: Byte = 0xfa.toByte()
    const val RET: Byte = 0xfb.toByte()
    const val SET: Byte = 0xfc.toByte()
    const val NOTIFY: Byte = 0xfd.toByte()

    /** `SmartTalkingModeDetailSettingType.TYPE_1`. */
    private const val DETAIL: Byte = 0x00

    fun get(): ByteArray = byteArrayOf(GET, TYPE)

    fun set(detail: ChatDetail): ByteArray =
        byteArrayOf(
            SET,
            TYPE,
            DETAIL,
            detail.sensitivity.ordinal.toByte(),
            if (detail.voiceFocus) 0x01 else 0x00,
            detail.modeOutTime.ordinal.toByte(),
        )

    /**
     * ⚠ Unknown bytes yield null, field by field. A sensitivity this build has no name
     * for must not read as [ChatSensitivity.AUTO] — the whole frame is refused instead,
     * because a partly-understood value would be written back whole.
     */
    fun state(payload: ByteArray): ChatDetail? {
        if (payload.size < 6) return null
        if (payload[0] != RET && payload[0] != NOTIFY) return null
        if (payload[1] != TYPE || payload[2] != DETAIL) return null
        val sensitivity = ChatSensitivity.entries.getOrNull(payload[3].toInt()) ?: return null
        val focus =
            when (payload[4]) {
                0x00.toByte() -> false
                0x01.toByte() -> true
                else -> return null
            }
        val out = ModeOutTime.entries.getOrNull(payload[5].toInt()) ?: return null
        return ChatDetail(sensitivity, focus, out)
    }
}

/** `d6` GENERAL_SETTING_GET_PARAM · `d7` RET · `d8` SET · `d9` NTFY. */
private fun generalSwitch(type: Byte) =
    SonySwitch(
        getCmd = 0xd6.toByte(),
        retCmd = 0xd7.toByte(),
        setCmd = 0xd8.toByte(),
        notifyCmd = 0xd9.toByte(),
        type = type,
        // `GsSettingType.BOOLEAN_TYPE`, and the same byte both ways — unlike
        // [SonySpeakToChat], which reads and writes with different tables.
        readType = 0x01,
        writeType = 0x01,
    )

/**
 * **Touch sensor control panel** — `GsInquiredType.GENERAL_SETTING1`.
 *
 * ✅ **Driven on the XM4 2026-08-24**, `00` → `01` → `00`, each step confirmed by a `d9`
 * notify and an independent `d6 d1` read.
 *
 * ⚠ **The device names this setting itself, so it is not a guess.** `d0 d1` answers
 * `d1 d1 02 13 "TOUCH_PANEL_SETTING" 00 01 00` — the key, then `GsSettingType.BOOLEAN_TYPE`.
 * Sony's own string for it is "Touch sensor control panel", and its description is
 * "Turning on this function allows you to use the headphones to control playback, adjust
 * volume, receive/end phone calls, and more" — so **on means enabled**, and this pair
 * reads `00`, meaning the panel is currently off.
 *
 * ⚠ **This is NOT the [CUSTOM] button and must not be merged with it.** That one is
 * `f8 06` and is refused for us alone (#965). This is the whole panel on or off.
 *
 * ⚠ **Nor is it multipoint, which shares the `d8 <type> 01 <v>` frame family and is
 * refused by the device for everyone.** They differ only in the type byte — `d1` here,
 * `d2` there — and one being refused says nothing about the other. Measured: `d8 d1 01 01`
 * is accepted and takes effect, so the `d8` family is not blanket-refused.
 */
val SonyTouchPanel = generalSwitch(type = 0xd1.toByte())

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
 * Sony battery — `10` COMMON_GET_BATTERY_LEVEL, `11` RET, `13` NTFY.
 *
 * ```
 * → 10 00              BatteryInquiredType.BATTERY
 * ← 11 00 50 00        80%, BatteryChargingStatus.NOT_CHARGING
 * ```
 *
 * ✅ **Read on the XM4 2026-08-23 and cross-checked**: the reply was `11 00 50 00` and
 * Sound Connect's own card read **80%** at the same moment. Every byte lands on a named
 * SDK enum, so this is not a scale inferred from one sample.
 *
 * ⚠ **`00` is BatteryInquiredType.BATTERY, the single-cell question**, and it is the
 * only one this model answers. `01` LEFT_RIGHT_BATTERY and `02` CRADLE_BATTERY are for
 * earbuds and a case; the XM4 declares neither — `15`/`17`/`18` are absent from the 22
 * functions it lists. Asking for them here would be inventing cells.
 *
 * ⚠ **Unlike the JBL's, this has to be ASKED.** [JblBattery] arrives unbidden every ten
 * seconds; nothing here has seen a `13` NTFY from the XM4, so a card that waited for one
 * would stay blank. That is why [get] exists and why the driver reads it per refresh.
 */
object SonyBattery {
    /** `BatteryInquiredType.BATTERY` — the whole-headphone cell. */
    const val TYPE: Byte = 0x00

    const val GET: Byte = 0x10
    const val RET: Byte = 0x11
    const val NOTIFY: Byte = 0x13

    private const val NOT_CHARGING: Byte = 0x00
    private const val CHARGING: Byte = 0x01

    fun get(): ByteArray = byteArrayOf(GET, TYPE)

    /**
     * ⚠ **`f0` UNKNOWN is not "not charging"**, and an unrecognised status yields null
     * rather than a cheerful default. `BatteryChargingStatus` has exactly three values
     * and the third means the device does not know — reporting that as "on battery"
     * would be this repo inventing a fact the headphones declined to state.
     */
    fun state(payload: ByteArray): Battery? {
        if (payload.size < 4) return null
        if (payload[0] != RET && payload[0] != NOTIFY) return null
        if (payload[1] != TYPE) return null
        val percent = payload[2].toInt() and 0xff
        if (percent > 100) return null
        return when (payload[3]) {
            NOT_CHARGING -> Battery(percent = percent, charging = false)
            CHARGING -> Battery(percent = percent, charging = true)
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
