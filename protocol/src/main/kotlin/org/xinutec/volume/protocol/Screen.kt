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
}

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
    val multipoint: Boolean? = null,
    val autoOff: AutoOff? = null,
    val soundQuality: SoundQuality? = null,
    val button: String? = null,
    /**
     * Settings this device reports but will not let this app change.
     *
     * ⚠ **Rendered as a value, never as a control.** A switch that flips and springs
     * back is exactly what the XM4's multipoint does in Sony's own app, and offering
     * one here would be this repo's oldest mistake in a new place — a reply that is
     * not an answer. Showing the value read-only is honest and still useful; you can
     * see multipoint is off, you just cannot change it from here.
     */
    val refuses: Set<SettingKind> = emptySet(),
) {
    /** Whether there is anything at all to draw. */
    val any: Boolean
        get() =
            eq != null || tone != null || multipoint != null ||
                autoOff != null || soundQuality != null || button != null

    fun writable(kind: SettingKind): Boolean = kind !in refuses
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
     * @param mode null when the device has no read command, which is a real state
     *   and not "unknown yet" — the JLab never reports one, and a spinner there
     *   would wait forever.
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
