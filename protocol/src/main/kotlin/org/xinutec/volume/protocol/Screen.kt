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
) {
    /** Modes to offer, empty until we know what it is. */
    val offer: List<AncMode>
        get() = (state as? DeviceState.Ready)?.modes.orEmpty()
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

/** The screen, in the order the list is drawn. */
data class Screen(
    val cards: List<DeviceCard>,
) {
    /** Replace one card by address, leaving the rest and the order alone. */
    fun with(address: String, state: DeviceState): Screen =
        copy(cards = cards.map { if (it.address == address) it.copy(state = state) else it })

    /**
     * Take the name the device itself reports.
     *
     * ⚠ Kept out of [with] deliberately: the name arrives once, from a different
     * read than the mode, and folding it into every state update would mean every
     * caller had to carry a name it does not have.
     */
    fun renamed(address: String, name: String): Screen =
        copy(cards = cards.map { if (it.address == address) it.copy(name = name) else it })
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
fun Confirmation.note(requested: AncMode, label: (AncMode) -> String): Note? =
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
fun Confirmation.resulting(requested: AncMode): AncMode? =
    when (this) {
        is Confirmation.Confirmed -> requested
        is Confirmation.Contradicted -> actual
        is Confirmation.Unverifiable -> null
    }
