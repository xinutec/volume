package org.xinutec.volume.protocol

/**
 * What one device reported when asked for everything it has: one [Setting] per row
 * on its card.
 *
 * A row is present when the device has the setting and answered for it. Nothing
 * here can be half a row — a preset menu with no current preset, a language list
 * with no switch — because each row carries everything it draws.
 */
class Settings(
    rows: List<Setting>,
    /**
     * Whether this device was actually ASKED for settings.
     *
     * ⚠ **Empty because nobody asked and empty because nothing answered are different
     * facts.** One sentence for both told the owner of a JBL with six decoded settings
     * that nothing was decoded for it, when every read had failed on a stale link. The
     * same shape as [NoMode], one layer up.
     */
    val attempted: Boolean,
) {
    /** In card order, whatever order the device's reads produced them in. */
    val rows: List<Setting> = rows.sortedBy { it.kind }

    init {
        require(rows.map { it.kind }.toSet().size == rows.size) { "a row appears twice: $rows" }
    }

    /**
     * Whether there is anything to draw. ⚠ Actions are not readings: a card holding only
     * a power-off button or a rename has no settings section to put them in.
     */
    val any: Boolean
        get() = rows.any { it.reading }

    /** The row of type [R], when the device has it. */
    inline fun <reified R : Setting> get(): R? = rows.firstNotNullOfOrNull { it as? R }

    override fun equals(other: Any?): Boolean =
        other is Settings && rows == other.rows && attempted == other.attempted

    override fun hashCode(): Int = 31 * rows.hashCode() + attempted.hashCode()

    override fun toString(): String = "Settings(rows=$rows, attempted=$attempted)"

    companion object {
        /** A device this app has no settings reads for. */
        val NONE = Settings(emptyList(), attempted = false)
    }
}

/**
 * Which row a [Setting] is, in the order the card draws them.
 *
 * ⚠ Declaration order IS the card's order, so moving an entry moves the row.
 */
enum class SettingKind {
    PRESET_EQ,
    TONE,
    CURVE_EQ,
    IDLE_TIMER,
    SPATIAL,
    VOICE_AWARE,
    SMART_TALK,
    LOW_VOLUME_EQ,
    SMART_AV,
    FIND_BUDS,
    AUTO_PLAY,
    BALANCE,
    PSAP,
    VOICE_PROMPTS,
    NAME,
    CONNECTIONS,
    CNC,
    STANDBY,
    SELF_VOICE,
    ADVANCED_ANC,
    LE_AUDIO,
    AURACAST,
    CODEC,
    POWER_OFF,
    BATTERY,
    LOUDNESS,
    BUD_BATTERY,
    JLAB_EQ,
    SAFE_HEARING,
    JLAB_TOUCH,
    GESTURES,
    VOLUME_LIMIT,
    CNC_PERSISTENCE,
    MULTIPOINT,
    DSEE,
    PAUSE_ON_REMOVAL,
    SPEAK_TO_CHAT,
    CHAT_DETAIL,
    TOUCH_PANEL,
    VOICE_GUIDANCE,
    FOCUS_ON_VOICE,
    AUTO_OFF,
    SOUND_QUALITY,
    BUTTON,
}

/**
 * Why a setting is shown without a control.
 *
 * ⚠ **Two facts, and the screen once asserted the stronger one for both.** The note
 * under multipoint and the CUSTOM button read *"this pair will not let anything change
 * it — not even its own app"*. True of multipoint, measured; false of the button,
 * which Sony's app changes freely and only this repo could not (#965).
 */
enum class RefusalReason {
    /** The device refuses everyone, its own app included. Multipoint, measured. */
    DEVICE,

    /** ⚠ Only us. The vendor app succeeds with the identical bytes — see #965. */
    THIS_APP,
}

/**
 * Whether a row's value can be changed from here, for the rows where that differs by
 * device or by the device's state.
 *
 * ⚠ **Presence means "this device has it", not "we can change it".** The XM4 answers
 * `d6 d2` and `f6 06` and then ignores the matching writes, while the QC45 accepts
 * both.
 */
sealed interface Writability {
    data object Writable : Writability

    /** The device reports it and nothing in this repo writes it. */
    data object NoWriter : Writability

    /**
     * ⚠ **Rendered as a value, never as a control.** A switch that flips and springs
     * back is what the XM4's multipoint does in Sony's own app.
     */
    data class Refused(
        val reason: RefusalReason,
    ) : Writability

    /** Writable, but not in the state the device is in now; the row says which state. */
    data object NotNow : Writability
}

/** One row on a device's settings card. */
sealed interface Setting {
    val kind: SettingKind

    /** False for an action, which is not something the device reported. */
    val reading: Boolean get() = true
}

/**
 * Sony's equaliser, and the LIVE PRO 2's preset index: an id, and the levels after it.
 *
 * ⚠ EQ is three different shapes and is deliberately not unified — see [ToneRow] and
 * [CurveEqRow]. A single type would have to invent an id for one of them.
 */
data class PresetEqRow(
    val eq: EqSetting,
    /** The band centres, for the sliders; empty on a device with no levels. */
    val bands: List<Int>,
    /** The preset ids to offer, in the device's order; never another vendor's ids. */
    val presets: List<Int>,
    /**
     * What to call each id, when the vendor has a table for it.
     *
     * ⚠ **Per device, because the ids COLLIDE.** `04` is Rock in the JBL's `aa a2`
     * table space and User in its `aa 40` preset space. An id with no name here is
     * shown as its number, never with somebody else's name.
     */
    val names: Map<Int, String>,
) : Setting {
    override val kind get() = SettingKind.PRESET_EQ
}

/**
 * The QC45's three signed bands. ⚠ **No preset on the wire at all**: Bose Music's
 * preset buttons are the app writing three numbers.
 */
data class ToneRow(
    val tone: BoseBands,
) : Setting {
    override val kind get() = SettingKind.TONE
}

/** The JBL's drawn curve, for the reason [EqCurve] gives. */
data class CurveEqRow(
    val curve: EqCurve,
) : Setting {
    override val kind get() = SettingKind.CURVE_EQ
}

/** ⚠ The JBL's power-off timer, which is not [AutoOffRow]'s rule — see [TimedOff]. */
data class IdleTimerRow(
    val timer: TimedOff,
) : Setting {
    override val kind get() = SettingKind.IDLE_TIMER
}

/**
 * Spatial sound — the switch and the mode it renders for.
 *
 * ⚠ One value, because the JBL takes both in one frame. ⚠⚠ **The JLab is the
 * opposite** — `74` and `52` are separate writes — and shares this row because the
 * card is the same two questions; `DeviceController.setSpatial` issues two writes.
 */
data class SpatialRow(
    val value: Spatial,
    /** ⚠ **The JLab has no Game.** A chip whose write this repo refuses is worse than none. */
    val modes: List<SpatialMode>,
) : Setting {
    override val kind get() = SettingKind.SPATIAL
}

/** VoiceAware — one value for the same reason [SpatialRow] is one. */
data class VoiceAwareRow(
    val value: VoiceAware,
) : Setting {
    override val kind get() = SettingKind.VOICE_AWARE
}

/** Smart Talk — the switch and how long it holds TalkThru after you stop. */
data class SmartTalkRow(
    val value: SmartTalk,
) : Setting {
    override val kind get() = SettingKind.SMART_TALK
}

/** Low Volume Dynamic EQ — a plain switch. */
data class LowVolumeEqRow(
    val on: Boolean,
) : Setting {
    override val kind get() = SettingKind.LOW_VOLUME_EQ
}

/**
 * Smart Audio & Video — ⚠ **three states, not a switch plus a mode**; [SmartAv] says
 * why.
 */
data class SmartAvRow(
    val value: SmartAv,
    /**
     * The modes this model has. ⚠ A LIVE PRO 2 has two, and a chip whose payload this
     * repo cannot name would write nothing.
     */
    val options: List<SmartAv>,
) : Setting {
    override val kind get() = SettingKind.SMART_AV
}

/**
 * Find My Buds, drawn from which buds report themselves in an ear.
 *
 * ⚠ **So the card can REFUSE rather than warn.** The tone is deliberately piercing and
 * the vendor app guards it with a modal; this device answers the question directly, so
 * a bud that says it is worn gets no button.
 */
data class FindBudsRow(
    val inEar: InEar,
) : Setting {
    override val kind get() = SettingKind.FIND_BUDS
}

/** Auto Play & Pause — pauses when you take them off. */
data class AutoPlayRow(
    val on: Boolean,
) : Setting {
    override val kind get() = SettingKind.AUTO_PLAY
}

/** Left/right balance; ⚠ the switch is offered, the level only carried. */
data class BalanceRow(
    val value: Balance,
) : Setting {
    override val kind get() = SettingKind.BALANCE
}

/**
 * Personal Sound Amplification — **shown, never written**: it makes things louder.
 * [JblPsap] has the reasoning.
 */
data class PsapRow(
    val on: Boolean,
) : Setting {
    override val kind get() = SettingKind.PSAP
}

/**
 * Voice prompts' switch, and the language they are spoken in.
 *
 * ⚠ **Writable on Bose, [Writability.NoWriter] on the JBL.** Its neighbouring
 * sub-commands of `aa 93` reach the language, which that vendor pushes as a file over
 * its DFU path, and this repo does no OTA work.
 */
data class VoicePromptsRow(
    val on: Boolean,
    val writability: Writability,
    val language: BoseVoicePromptLanguage? = null,
    /**
     * The languages it *will* speak — ⚠ **its list, not our enum**. Each unit offers a
     * different subset, and the absent ones are not the ones you would guess.
     */
    val languages: List<BoseVoicePromptLanguage> = emptyList(),
) : Setting {
    override val kind get() = SettingKind.VOICE_PROMPTS
}

/**
 * Rename, offered on a device whose name this repo can write.
 *
 * ⚠⚠ [held] is the name the **headphones** hold, not [DeviceCard.name]. That one is
 * Android's bonded record, which a rename over this protocol does not touch: without
 * this, a rename that works and one that does nothing look identical. Null when the
 * device would not say; the card then falls back to the bonded name.
 */
data class NameRow(
    val held: String?,
) : Setting {
    override val kind get() = SettingKind.NAME
    override val reading get() = false
}

/**
 * What the headphones are paired with, and whether they are advertising for a new one.
 *
 * ⚠ **The list is read only.** Connecting, disconnecting and forgetting sit in block
 * `04` beside CLEAR_DEVICE_LIST, and none has been watched being made. Pairing is the
 * exception, and its frame was captured.
 */
data class ConnectionsRow(
    val devices: List<BoseDevice>,
    val pairing: Boolean?,
) : Setting {
    override val kind get() = SettingKind.CONNECTIONS
}

/**
 * The QC45's ANC mode table — see [CncModes] for why it is not [AncMode].
 *
 * ⚠ **Not cacheable across a card open**: the headphones' button moves the selection.
 */
data class CncRow(
    val cnc: CncModes,
) : Setting {
    override val kind get() = SettingKind.CNC
}

/**
 * Bose's standby timer. ⚠ Not [IdleTimerRow]: "Never" is the value `0`, so a switch
 * beside the number would be a state the headphones cannot be in.
 */
data class StandbyRow(
    val standby: BoseStandby,
) : Setting {
    override val kind get() = SettingKind.STANDBY
}

/**
 * Bose's Self Voice. ⚠ Its write takes `<persist> <level>`; the plain SET_GET this
 * once guessed would have sent a malformed frame.
 */
data class SelfVoiceRow(
    val level: SidetoneLevel,
) : Setting {
    override val kind get() = SettingKind.SELF_VOICE
}

/**
 * Customize ANC — **read, never written.** The levels have no established scale, and
 * finding the range on hearing hardware is not free.
 */
data class AdvancedAncRow(
    val value: AdvancedAnc,
) : Setting {
    override val kind get() = SettingKind.ADVANCED_ANC
}

/**
 * LE Audio — **read, never written: writing it renegotiates the audio link this app is
 * talking over.** [JblFeature.set] exists and is tested, and nothing calls it.
 */
data class LeAudioRow(
    val on: Boolean,
) : Setting {
    override val kind get() = SettingKind.LE_AUDIO
}

/**
 * Auracast — read, never written, for a weaker reason than [LeAudioRow]: nothing here
 * has established what flipping it does.
 */
data class AuracastRow(
    val on: Boolean,
) : Setting {
    override val kind get() = SettingKind.AURACAST
}

/**
 * The codec the link negotiated. ⚠ **Not a setting**: the two ends agree on it, and
 * what an owner can choose is [SoundQualityRow].
 */
data class CodecRow(
    val codec: String,
) : Setting {
    override val kind get() = SettingKind.CODEC
}

/** Switch the pair off — offered where this repo can. An action, not a [reading]. */
data object PowerOffRow : Setting {
    override val kind get() = SettingKind.POWER_OFF
    override val reading get() = false
}

/** How much charge is left — read, never written. */
data class BatteryRow(
    val battery: Battery,
    /**
     * True when the JBL's two cup slots disagreed in the frame [battery] came from.
     *
     * ⚠ **Then [battery] is ONE cup and nothing can say which** — see [JblBattery].
     * Null on every other device: nothing else reports two slots.
     */
    val cupsDiffer: Boolean? = null,
) : Setting {
    override val kind get() = SettingKind.BATTERY
}

/**
 * How loud it is, on the device's own scale — see [BoseVolume].
 *
 * ⚠⚠ **Shown, never written.** A volume is never raised above where it was found, so
 * a writer would be a decision.
 */
data class LoudnessRow(
    val loudness: BoseLoudness,
) : Setting {
    override val kind get() = SettingKind.LOUDNESS
}

/**
 * Two cells, for earbuds that report them separately. ⚠ **Not [BatteryRow] with one
 * number**: the JLab's two levels were watched drifting apart.
 */
data class BudBatteryRow(
    val battery: BudBattery,
) : Setting {
    override val kind get() = SettingKind.BUD_BATTERY
}

/**
 * The JLab's equaliser, and its four stored curves so a chip knows what to send.
 *
 * ⚠⚠ **`4a` moves the PRESET INDEX and the ten level bytes do NOT land.** `49`'s
 * levels are the CUSTOM slot's, not the selected preset's, so [presets] is what a
 * caller must read to know what a chip will send — see [JLabEq]. Its stored presets are
 * flat while the live curve has two bands cut, so selecting one may RAISE those bands.
 */
data class JLabEqRow(
    val curve: JLabCurve,
    val presets: List<List<Int>>?,
) : Setting {
    override val kind get() = SettingKind.JLAB_EQ
}

/**
 * The JLab's Safe Hearing ceiling — written, at the owner's explicit request.
 *
 * ⚠⚠ [JLabSafeHearing.Level.DEFAULT] is the LEAST protective, so a caller that sorts
 * these as loudness has them backwards.
 */
data class SafeHearingRow(
    val level: JLabSafeHearing.Level,
) : Setting {
    override val kind get() = SettingKind.SAFE_HEARING
}

/**
 * What each tap on the JLab does — **read only**: its own app draws this screen inert,
 * so no writer has ever been captured.
 */
data class JLabTouchRow(
    val bindings: Map<Pair<JLabTouch.Side, JLabTouch.Tap>, JLabTouch.Action>,
) : Setting {
    override val kind get() = SettingKind.JLAB_TOUCH
}

/**
 * What each control on the headphones does — read, and editable.
 *
 * ⚠ **Editing is only safe because the writer puts a refused action back.** The device
 * coerces one it declines to `NONE`; `Drivers.JblBes.writeGesture` restores it, and
 * [GestureWrite] names the outcomes.
 */
data class GesturesRow(
    val bindings: Map<Gesture, GestureAction>,
) : Setting {
    override val kind get() = SettingKind.GESTURES
}

/**
 * The JBL's Max Volume Limiter — **shown, never written**. The device and its app
 * change it freely; it is read-only because it is hearing protection.
 */
data class VolumeLimitRow(
    val on: Boolean,
) : Setting {
    override val kind get() = SettingKind.VOLUME_LIMIT
}

/**
 * Bose `01 0e`, named for keeping the noise setting. ⚠ The name is the vendor's and
 * power-cycling showed mode and level return either way; the card says so.
 */
data class CncPersistenceRow(
    val on: Boolean,
) : Setting {
    override val kind get() = SettingKind.CNC_PERSISTENCE
}

/** Two devices at once. ⚠ The XM4 refuses it to everyone, the QC45 takes it. */
data class MultipointRow(
    val on: Boolean,
    val writability: Writability,
) : Setting {
    override val kind get() = SettingKind.MULTIPOINT
}

/** DSEE Extreme — `true` is `UpscalingSettingValue.AUTO`, not a generic "on". */
data class DseeRow(
    val on: Boolean,
    val writability: Writability,
) : Setting {
    override val kind get() = SettingKind.DSEE
}

/** Pause when the headphones come off. ⚠ Not [AutoOffRow], which powers them down. */
data class PauseOnRemovalRow(
    val on: Boolean,
    val writability: Writability,
) : Setting {
    override val kind get() = SettingKind.PAUSE_ON_REMOVAL
}

data class SpeakToChatRow(
    val on: Boolean,
    val writability: Writability,
) : Setting {
    override val kind get() = SettingKind.SPEAK_TO_CHAT
}

/**
 * Speak-to-Chat's sensitivity, voice focus and mode-out time. ⚠ **One value, because
 * the device sends one frame** — see [ChatDetail].
 */
data class ChatDetailRow(
    val detail: ChatDetail,
) : Setting {
    override val kind get() = SettingKind.CHAT_DETAIL
}

/** The XM4's touch sensor panel, on or off. ⚠ Not the CUSTOM button, [SonyButtonRow]. */
data class TouchPanelRow(
    val on: Boolean,
    val writability: Writability,
) : Setting {
    override val kind get() = SettingKind.TOUCH_PANEL
}

/** Voice guidance. ⚠ **Switching it ON can make the headphones speak.** */
data class VoiceGuidanceRow(
    val on: Boolean,
    val writability: Writability,
) : Setting {
    override val kind get() = SettingKind.VOICE_GUIDANCE
}

/**
 * Focus on Voice — **readable always, settable only in ambient mode**, which is
 * [Writability.NotNow] outside it. The XM4 accepts the frame in ANC and silently
 * ignores the byte.
 */
data class FocusOnVoiceRow(
    val on: Boolean,
    val writability: Writability,
) : Setting {
    override val kind get() = SettingKind.FOCUS_ON_VOICE
}

/** Sony's rule about powering off once removed. */
data class AutoOffRow(
    val mode: AutoOff,
) : Setting {
    override val kind get() = SettingKind.AUTO_OFF
}

data class SoundQualityRow(
    val mode: SoundQuality,
) : Setting {
    override val kind get() = SettingKind.SOUND_QUALITY
}

/**
 * The XM4's CUSTOM key.
 *
 * ⚠ [options] is **the device's list, not the enum**: [SonyButton.Action] contains
 * `VOLUME_CONTROL`, and the XM4 does not offer it.
 */
data class SonyButtonRow(
    val action: SonyButton.Action,
    val options: List<SonyButton.Action>,
) : Setting {
    override val kind get() = SettingKind.BUTTON
}

/** The QC45's action button. */
data class BoseButtonRow(
    val action: BoseButton.Action,
) : Setting {
    override val kind get() = SettingKind.BUTTON
}
