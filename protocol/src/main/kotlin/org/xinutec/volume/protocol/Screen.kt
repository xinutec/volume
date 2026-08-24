package org.xinutec.volume.protocol

/**
 * What the screen shows for one headphone, and how it changes.
 *
 * Pure, and here rather than in `:app`, because the awkward parts of this UI are
 * not drawing — they are *which of several truths to show*. A device can be
 * unreachable, reachable but unidentified, or driven-but-unable-to-confirm, and
 * each is a different sentence. Getting those wrong is a bug a screenshot would
 * not reveal, so they are decided in tested code and the composable only renders.
 */
data class DeviceCard(
    /** The bonded name, which is what the owner recognises even when renamed. */
    val name: String,
    val address: String,
    val state: DeviceState,
    /**
     * What this device reported when asked for its settings, or null if never asked.
     *
     * ⚠ **On the CARD, not on [DeviceState.Ready], and that is the fix for a real
     * bug.** It lived on `Ready` first, and every write transitions the card
     * `Ready → Busy → Ready` — [DeviceState.Busy] has no settings field, so the
     * values were structurally destroyed by every ANC change and every refresh. The
     * section then showed a "reading…" spinner that could never resolve, because the
     * read is only triggered by opening it. Settings describe the headphones, not
     * the state of this moment's connection, so they belong to the thing that
     * persists across those transitions.
     */
    val settings: Settings? = null,
) {
    /** Modes to offer, empty until we know what it is. */
    val offer: List<AncMode>
        get() = (state as? DeviceState.Ready)?.modes.orEmpty()
}

/**
 * The settings a device has beyond ANC.
 *
 * ⚠ **Presence in [Settings] means "this device has it", not "we can change it".**
 * Those came apart on 2026-08-16: the XM4 answers `d6 d2` and `f6 06` perfectly well
 * and then ignores the matching writes, while the QC45 accepts both. So each field
 * is a value the device reported, and [refuses] says which of them will not move.
 */
enum class SettingKind {
    EQ,
    MULTIPOINT,
    AUTO_OFF,
    SOUND_QUALITY,
    BUTTON,
    DSEE,
    PAUSE_ON_REMOVAL,
    SPEAK_TO_CHAT,
    CHAT_DETAIL,
    TOUCH_PANEL,
    FOCUS_ON_VOICE,
}

/**
 * Why a setting is shown without a control.
 *
 * ⚠ **One boolean was carrying two different facts, and the screen asserted the
 * stronger one for both.** `refuses` held MULTIPOINT and BUTTON together, and the note
 * under them read *"this pair will not let anything change it — not even its own app"*.
 * That is true of multipoint, measured. It is **false of the [CUSTOM] button**, which
 * Sony's app changes freely and only this repo cannot — the asymmetry that is the whole
 * content of #965. So the app was telling its owner something untrue about their own
 * hardware, in the one place the reasoning was supposed to be visible.
 */
enum class RefusalReason {
    /** The device refuses everyone, its own app included. Multipoint, measured. */
    DEVICE,

    /** ⚠ Only us. The vendor app succeeds with the identical bytes — see #965. */
    THIS_APP,
}

/** Focus on Voice, and whether the device is in the mode that lets it move. */
data class Focus(
    val on: Boolean?,
    val settable: Boolean,
)

/**
 * What one device reported when asked for everything it has.
 *
 * A null field means **not asked, or this device has no such setting** — the two are
 * the same for rendering, because a device that has never been read shows nothing
 * either way, and [DeviceState.Busy] is what says a read is in flight.
 *
 * ⚠ EQ is two different shapes and is deliberately not unified. Sony has an opaque
 * preset id with the levels following it; the QC45 has three signed band values and
 * **no preset on the wire at all**, because Bose Music's preset buttons are the app
 * writing three numbers. A single type would have to invent an id for one of them.
 */
data class Settings(
    val eq: EqSetting? = null,
    val bands: List<Int> = emptyList(),
    val tone: BoseBands? = null,
    /** The JBL's drawn curve — a third EQ shape, for the reason [EqCurve] gives. */
    val curve: EqCurve? = null,
    val multipoint: Boolean? = null,
    val autoOff: AutoOff? = null,
    /** ⚠ The JBL's timer, which is not [autoOff]'s rule — see [TimedOff]. */
    val timedOff: TimedOff? = null,
    /**
     * The JBL's Max Volume Limiter — **shown, never written**.
     *
     * ⚠ Not in [refuses]: the device does not refuse this and its own app changes it
     * freely. It is read-only because it is hearing protection, which is this repo's
     * choice and is worth saying out loud rather than leaving as an absent control.
     */
    val volumeLimit: Boolean? = null,
    /**
     * The JBL's Spatial Sound — the switch and the mode it renders for.
     *
     * ⚠ One field, not two, because the device takes both in one frame: there is no
     * write that changes the switch without also naming a mode. Splitting them in the
     * UI would invent a state the headphones cannot be in.
     */
    val spatial: Spatial? = null,
    /** VoiceAware — one field for the same reason [spatial] is one. */
    val voiceAware: VoiceAware? = null,
    /** Smart Talk — the switch and how long it holds TalkThru after you stop. */
    val smartTalk: SmartTalk? = null,
    /** Low Volume Dynamic EQ — a plain switch, unlike its neighbours on this device. */
    val lowVolumeEq: Boolean? = null,
    /**
     * Smart Audio & Video — ⚠ **three states, not a switch plus a mode.**
     *
     * [SmartAv] says why: the device has no enable byte, and off carries the Audio
     * family's numbers even while Video is lit. A switch here would be this repo
     * inventing a state the headphones cannot hold.
     */
    val smartAv: SmartAv? = null,
    /**
     * What each control on the headphones does — **shown, not yet editable**.
     *
     * ⚠ Read-only for a reason, not for lack of a writer: a refused action is coerced
     * to `NONE`, so an editor that offers every action would silently WIPE a binding
     * whenever the device declines one. [JblGestures] has the measurement; #1039 is
     * the editor.
     */
    val gestures: Map<Gesture, GestureAction>? = null,
    /** How much charge is left — read, never written, because there is nothing to write. */
    val battery: Battery? = null,
    /** Auto Play & Pause — pauses when you take them off. */
    val autoPlay: Boolean? = null,
    /** Left/right balance; ⚠ the switch is offered, the level only carried. */
    val balance: Balance? = null,
    /**
     * Personal Sound Amplification — **shown, never written.**
     *
     * ⚠ The second control on this device whose job is to make things louder, so it
     * gets [volumeLimit]'s treatment rather than a switch. [JblPsap] has the reasoning
     * and the resolution of the contradiction that kept it off the screen until now.
     */
    val psap: Boolean? = null,
    val soundQuality: SoundQuality? = null,
    val button: String? = null,
    /** DSEE Extreme — `true` is `UpscalingSettingValue.AUTO`, not a generic "on". */
    val dsee: Boolean? = null,
    /** Pause when the headphones come off. ⚠ Not [autoOff], which powers them down. */
    val pauseOnRemoval: Boolean? = null,
    val speakToChat: Boolean? = null,
    /**
     * Speak-to-Chat's sensitivity, voice focus and mode-out time.
     *
     * ⚠ **One value, because the device sends one frame.** See [ChatDetail] — writing a
     * field means writing all three, so they cannot be separate settings here without
     * inviting a caller to reset the two it did not mean to touch.
     */
    val chatDetail: ChatDetail? = null,
    /**
     * The XM4's touch sensor control panel, on or off.
     *
     * ⚠ **Not the [CUSTOM] button** — that is [SettingKind.BUTTON] and is #965. This is
     * whether the panel responds at all.
     */
    val touchPanel: Boolean? = null,
    /**
     * Focus on Voice — **readable always, settable only in ambient mode.**
     *
     * ⚠ A fourth kind of "no control", and it is none of the other three: not refused,
     * not a hearing choice, not an editor that would wipe data. The XM4 accepts the
     * frame in ANC and silently ignores the byte, which is how the tidy-up after the
     * first hardware test left it switched on. [focusOnVoiceSettable] is what the
     * screen asks, so the reason lives here rather than being re-derived in the UI.
     */
    val focusOnVoice: Boolean? = null,
    /** Whether the device is in the ambient mode [focusOnVoice] requires. */
    val focusOnVoiceSettable: Boolean = false,
    /**
     * Settings this device reports but will not let this app change.
     *
     * ⚠ **Rendered as a value, never as a control.** A switch that flips and springs
     * back is exactly what the XM4's multipoint does in Sony's own app, and offering
     * one here would be this repo's oldest mistake in a new place — a reply that is
     * not an answer. Showing the value read-only is honest and still useful; you can
     * see multipoint is off, you just cannot change it from here.
     */
    val refuses: Map<SettingKind, RefusalReason> = emptyMap(),
    /**
     * Whether this device was actually ASKED for settings.
     *
     * ⚠ **Empty because nobody asked and empty because nothing answered are different
     * facts**, and the card had one sentence for both: *"Nothing beyond noise
     * cancelling is decoded for this pair yet"* — which is about how far this repo has
     * got, and was shown for a JBL with six decoded settings whose reads had all failed
     * on a stale link. The same shape as [NoMode], one layer up.
     *
     * False for a device this app has no settings reads for, where the old sentence is
     * exactly right.
     */
    val attempted: Boolean = false,
) {
    /**
     * Whether there is anything at all to draw.
     *
     * ⚠ **Every field has to be listed here, and one was not.** `spatial` was added
     * without it, which hides the whole section for a device reporting *only* that —
     * on the JBL the EQ and timer reads carry it, so the omission was invisible and
     * would have surfaced as "settings vanished" the first time an earlier read failed.
     */
    val any: Boolean
        get() =
            eq != null || tone != null || curve != null || multipoint != null ||
                autoOff != null || timedOff != null || soundQuality != null ||
                button != null || volumeLimit != null || spatial != null ||
                voiceAware != null || smartTalk != null || lowVolumeEq != null ||
                smartAv != null || gestures != null || battery != null ||
                autoPlay != null || balance != null || psap != null ||
                dsee != null || pauseOnRemoval != null || speakToChat != null ||
                chatDetail != null || touchPanel != null ||
                focusOnVoice != null

    fun writable(kind: SettingKind): Boolean = kind !in refuses

    /** Why [kind] has no control, or null when it has one. */
    fun refusal(kind: SettingKind): RefusalReason? = refuses[kind]
}

sealed interface DeviceState {
    /** Bonded and known to be drivable, but nothing has been opened yet. */
    data object Idle : DeviceState

    data class Busy(
        val what: String,
    ) : DeviceState

    /**
     * Connected and driving.
     *
     * @param mode null when the mode cannot be read, which is a real state and not
     *   "unknown yet" — a spinner there would wait forever. ⚠ The JLab was the
     *   example until its read was found on 2026-08-16; no device here is in that
     *   state now, so null means "nobody has found the read yet".
     */
    data class Ready(
        val model: String,
        val modes: List<AncMode>,
        val mode: AncMode?,
        val note: Note? = null,
    ) : DeviceState

    /** Not drivable, with the reason kept rather than flattened to "error". */
    data class Unavailable(
        val why: String,
    ) : DeviceState
}

/**
 * Why there is nothing to show.
 *
 * ⚠ **These were one sentence, and it was false in four of the five cases.** An
 * empty list rendered "No headphones bonded to this phone" whatever had produced
 * it — with the radio off, with permission refused, with five pairs bonded and
 * none switched on. The worst is [BLUETOOTH_OFF]: `bondedDevices` reads as empty
 * when the adapter is disabled, so the app blamed its owner's pairing for its own
 * blindness, and the sentence sent them to the one settings screen that could not
 * help. Measured on 2026-08-16: thirteen bonded devices, that sentence on screen.
 *
 * The distinctions are here rather than in `:app` because they are a *decision*
 * about which fact is true, and each carries a different thing for the owner to
 * do. The words for them are the caller's, like [note]'s labels.
 */
enum class Emptiness {
    /**
     * Nothing has been asked yet — the first frame, before the first refresh.
     *
     * ⚠ Not a fact about the phone, and the only one here that is not. It exists
     * because the invariant forces the opening screen to say something, and every
     * other answer would be a claim we have not checked. It must not read like
     * one: the right rendering is "looking", not a verdict.
     */
    LOOKING,

    /** No Bluetooth on this device at all — nothing to be done. */
    NO_ADAPTER,

    /** The radio is off. ⚠ Indistinguishable from [NONE_BONDED] by bonded set alone. */
    BLUETOOTH_OFF,

    /** We may not ask who is bonded, so we cannot know. */
    NOT_PERMITTED,

    /** Nothing is paired with this phone. */
    NONE_BONDED,

    /** Pairs are known, none is switched on. The ordinary empty. */
    NONE_CONNECTED,

    /** Something is connected, but nothing this app can drive — a speaker, a laptop. */
    NONE_DRIVABLE,
}

/** The screen, in the order the list is drawn. */
data class Screen(
    val cards: List<DeviceCard>,
    /**
     * Why [cards] is empty — required exactly when it is.
     *
     * ⚠ The invariant is enforced rather than documented, because the defect this
     * replaces was a caller emitting an empty list and leaving the reason to be
     * guessed downstream. There is no default: a default is how one of these
     * becomes a lie about the other five.
     */
    val emptiness: Emptiness? = null,
) {
    init {
        require(cards.isEmpty() == (emptiness != null)) {
            "an empty screen must say why it is empty, and a populated one must not"
        }
    }

    /** Replace one card by address, leaving the rest and the order alone. */
    fun with(address: String, state: DeviceState): Screen =
        copy(cards = cards.map { if (it.address == address) it.copy(state = state) else it })

    /**
     * Attach what a device reported when asked for its settings.
     *
     * ⚠ **Only touches a card that is [DeviceState.Ready], and silently leaves the
     * rest.** A settings read takes seconds, and in that time its device can go — at
     * which point the card is already `Unavailable` and writing settings onto it
     * would resurrect a dead one with a full set of controls.
     */
    fun withSettings(address: String, settings: Settings): Screen =
        copy(
            cards =
                cards.map {
                    if (it.address == address && it.state is DeviceState.Ready) {
                        it.copy(settings = settings)
                    } else {
                        it
                    }
                },
        )

    /**
     * Take the name the device itself reports.
     *
     * ⚠ Kept out of [with] deliberately: the name arrives once, from a different
     * read than the mode, and folding it into every state update would mean every
     * caller had to carry a name it does not have.
     */
    fun renamed(address: String, name: String): Screen =
        copy(cards = cards.map { if (it.address == address) it.copy(name = name) else it })

    /**
     * Bring the list in line with what is [present] now, **keeping what is known**.
     *
     * ⚠ Not a rebuild. Headphones come and go while the app is open, and rebuilding
     * would throw away every card's state — the mode read, the name the device
     * reported, the note explaining why one of them cannot confirm — and then open
     * every session again to learn it back. So: an address already on screen keeps
     * its card untouched, a new one arrives [DeviceState.Idle], and one that has
     * gone is dropped.
     *
     * @param present address to bonded name, in the order to draw them.
     * @param whenEmpty what to say if [present] is empty — asked for up front so
     *   that the caller, which is the only thing that knows whether the radio is
     *   off or the room is simply quiet, cannot decline to answer.
     */
    fun reconciled(present: List<Pair<String, String>>, whenEmpty: Emptiness): Screen {
        val known = cards.associateBy { it.address }
        return Screen(
            present.map { (address, name) ->
                known[address] ?: DeviceCard(name, address, DeviceState.Idle)
            },
            emptiness = whenEmpty.takeIf { present.isEmpty() },
        )
    }
}

/** How loudly a note should read. */
enum class NoteKind {
    /** True but unwelcome: the write went out and cannot be checked. */
    CAUTION,

    /** It did not work. */
    PROBLEM,
}

data class Note(
    val text: String,
    val kind: NoteKind,
)

/**
 * Why a card has no mode to show — two different facts that arrive as the same null.
 *
 * ⚠ **They were one sentence, and it was a claim about the HEADPHONES.** A card with
 * `mode == null` said *"this one reports no mode; it can be set but not read"*, written
 * when the JLab was believed to have no read command. Every driver here has one now, so
 * the only way to reach that sentence is a read that did not come back — and on
 * 2026-08-17 a stale GATT link produced exactly that on the JBL, whose mode reads fine
 * and six of whose settings are decoded. The app stated a permanent limitation of the
 * hardware where the truth was a dead link and a relaunch fixed it.
 *
 * The distinction is not cosmetic: one of these is a fact to accept and the other is a
 * thing to retry, and a sentence that cannot tell them apart teaches its reader to
 * ignore both.
 */
enum class NoMode {
    /**
     * The driver has no read command at all.
     *
     * ⚠ A claim about this repo, never about the device — see [AncDriver.read]. No
     * driver here is in this state, which is precisely why the other case needs a
     * sentence of its own.
     */
    NO_READ,

    /** There is a read and it did not answer. Transient; retrying is the move. */
    UNANSWERED,
}

/**
 * Which of [NoMode] applies, or null when there is a mode and nothing to explain.
 *
 * [reads] is [AncDriver.reads] — asked of the driver rather than guessed from the null,
 * which is the whole point.
 */
fun noMode(reads: Boolean, mode: AncMode?): NoMode? =
    when {
        mode != null -> null
        reads -> NoMode.UNANSWERED
        else -> NoMode.NO_READ
    }

/**
 * What to say after a write, or **null when there is nothing to say**.
 *
 * ⚠ Two rules, and the first was got wrong on the first render: a *confirmed*
 * write needs no note at all — the selected control already says which mode is on,
 * so a line repeating it is noise that trains the eye to skip the line that
 * matters. And ⚠ `Unverifiable` must never read as success: it is the JLab's
 * normal case, whose reply looks identical for a mode that does not exist, so a
 * silent success there would launder exactly the uncertainty this type carries.
 *
 * [label] is passed in so the vendors' words ("Noise cancelling") stay in the UI
 * and out of here, while the *decision* of whether to speak stays testable.
 */
fun Confirmation<AncMode>.note(requested: AncMode, label: (AncMode) -> String): Note? =
    when (this) {
        is Confirmation.Confirmed -> {
            null
        }

        is Confirmation.Contradicted -> {
            Note("asked for ${label(requested)}, it reports ${label(actual)}", NoteKind.PROBLEM)
        }

        is Confirmation.Unverifiable -> {
            Note("${label(requested)} sent — this one cannot confirm it", NoteKind.CAUTION)
        }
    }

/** The mode to show after a set, which is only known when it was confirmed. */
fun Confirmation<AncMode>.resulting(requested: AncMode): AncMode? =
    when (this) {
        is Confirmation.Confirmed -> requested
        is Confirmation.Contradicted -> actual
        is Confirmation.Unverifiable -> null
    }

/**
 * The same rule as [note], for a setting that is not a mode.
 *
 * ⚠ Separate from [note] rather than made generic over it, because the *wording*
 * differs where it matters: a contradicted ANC write means the headphones are in a
 * mode you did not ask for, and a contradicted settings write on these devices means
 * the device refused outright — [Settings.refuses] is populated from exactly this.
 *
 * [describe] renders the value, and stays in `:app` with the rest of the words.
 */
fun <T> Confirmation<T>.settingNote(describe: (T) -> String): Note? =
    when (this) {
        is Confirmation.Confirmed -> {
            null
        }

        is Confirmation.Contradicted -> {
            Note("this pair refused that; it still reports ${describe(actual)}", NoteKind.PROBLEM)
        }

        is Confirmation.Unverifiable -> {
            Note("sent — this one cannot confirm it", NoteKind.CAUTION)
        }
    }
