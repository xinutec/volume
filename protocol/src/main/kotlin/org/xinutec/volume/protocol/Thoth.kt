package org.xinutec.volume.protocol

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the Mac answers, unless it has been told otherwise.
 *
 * ⚠ A LAN address, so it moves when the lease does — a dead `192.168.1.133` in a
 * sibling repo's deploy script is what that looks like a year later. `:app` persists
 * an override and offers it exactly when this one has stopped working, which is the
 * only moment anybody wants to see it.
 */
const val THOTH_PORT = 8089

const val THOTH_DEFAULT_HOST = "192.168.1.81:$THOTH_PORT"

/** How much of the Mac is answering. */
enum class ThothReach {
    /** Asked, nothing back yet — first paint after the card appears. */
    LOOKING,

    /** Answering, and the state below is its. */
    LIVE,

    /** Not answering: off this network, asleep, or the service is down. */
    AWAY,
}

/** An output device the Mac can play through. */
data class ThothDevice(
    val name: String,
    val uid: String,
    /** A Multi-Output group rather than a speaker — never a left/right candidate. */
    val aggregate: Boolean,
)

/** A device the Mac can record from. */
data class ThothInputDevice(
    val name: String,
    val uid: String,
)

/**
 * The system default input, and the standing policy about it.
 *
 * The pin exists because macOS re-points the default input at whatever connects — a
 * Bluetooth speaker's hands-free mic will take it — and the pin puts it back.
 */
data class ThothInput(
    val devices: List<ThothInputDevice>,
    /** UID of the live default, or `""` when there is none. */
    val current: String,
    /** UID the server re-asserts, or `""` for no pin. */
    val pinned: String,
)

/** Which call a pick of a new input device has to make. */
enum class InputPick {
    /** No pin standing: switch the default and leave policy alone. */
    SET,

    /**
     * ⚠ A pin is standing, so the pick has to MOVE it.
     *
     * Setting the default while pinned is undone within a second or two by the
     * server's own guard — the choice would spring back and read as a bug.
     */
    REPIN,
}

/** The stereo pair: two speakers driven as one output. */
data class ThothPair(
    /** UID of the left speaker, or `""` when unset. */
    val left: String,
    val right: String,
    /** True = the pair is split L/R; false = both speakers play everything. */
    val stereo: Boolean,
    /** −1 hard left … 0 centre … +1 hard right. */
    val balance: Double,
    /** 0…1, the louder side's level. */
    val volume: Double,
    /** The Multi-Output group exists and is the thing being driven. */
    val active: Boolean,
)

/** An arcade cabinet's volume. */
data class ThothCabinet(
    val host: String,
    val status: CabinetStatus,
    /** 0…1, present only when there is a control to read. */
    val volume: Double?,
    /** The ALSA step behind [volume]. */
    val raw: Int?,
    /**
     * The step count [raw] is measured against.
     *
     * ⚠ A SCALE, not a limit — how many steps the cabinet's own mixer offers, so
     * that a raw value can be read as a percentage.
     */
    val steps: Int?,
)

/** What a cabinet is doing. */
enum class CabinetStatus {
    ONLINE,

    /**
     * Up, but silent since it booted: its softvol control does not exist yet, so
     * there is no level to read. It plays unattenuated until one is set, and
     * setting one is what brings the control into being.
     */
    NO_CONTROL,

    OFFLINE,

    /** Reachable, but not in `known_hosts` — a key to check, not a cabinet to hide. */
    UNVERIFIED,

    /**
     * A status this app does not know.
     *
     * ⚠ Deliberately not folded into [OFFLINE]. A server that grows a state would
     * otherwise render as "off", which is a sentence about the cabinet rather than
     * about us, and nobody would ever go looking.
     */
    UNKNOWN,
    ;

    companion object {
        fun of(wire: String): CabinetStatus =
            when (wire) {
                "online" -> ONLINE
                "no-control" -> NO_CONTROL
                "offline" -> OFFLINE
                "unverified" -> UNVERIFIED
                else -> UNKNOWN
            }
    }
}

/**
 * The devices that can be a left or a right.
 *
 * ⚠ The pair's own Multi-Output group is in the same list as its members, and it is a
 * perfectly good output — it just cannot be a member of itself. Offering it would let
 * somebody build a group containing the group.
 */
fun List<ThothDevice>.speakers(): List<ThothDevice> = filter { !it.aggregate }

/** Up, and therefore settable. */
val ThothCabinet.reachable: Boolean
    get() = status == CabinetStatus.ONLINE || status == CabinetStatus.NO_CONTROL

/**
 * The percentage to show.
 *
 * A cabinet with no control plays unattenuated, so it sits at 100 — the level it is
 * actually at, not a placeholder for one we could not read.
 */
val ThothCabinet.percent: Int
    get() =
        when {
            status == CabinetStatus.ONLINE && volume != null -> (volume * 100).roundToInt()
            status == CabinetStatus.NO_CONTROL -> 100
            else -> 0
        }

/** What to say about a cabinet that has no slider, or null when it has one. */
val ThothCabinet.note: String?
    get() =
        when (status) {
            CabinetStatus.ONLINE -> null
            CabinetStatus.NO_CONTROL -> "silent since boot — set a level to fix one"
            CabinetStatus.OFFLINE -> "off"
            CabinetStatus.UNVERIFIED -> "host key not known"
            CabinetStatus.UNKNOWN -> "unrecognised status"
        }

/** 0…1 as a percentage, for a control that works in whole percent. */
val ThothPair.volumePercent: Int
    get() = (volume * 100).roundToInt()

/** −1…+1 as −100…+100. */
val ThothPair.balancePercent: Int
    get() = (balance * 100).roundToInt()

/**
 * Balance from a coarse and a fine control, as −1…+1.
 *
 * Two controls because one is unusable at both jobs: the interesting range is a few
 * percent either side of centre, and a single slider spanning ±100 cannot be nudged
 * that finely with a thumb.
 */
fun balanceOf(coarse: Int, fine: Int): Double = max(-1.0, min(1.0, (coarse + fine) / 100.0))

/** Which call a pick of a new input device has to make, given the standing pin. */
fun ThothInput.pickIs(): InputPick = if (pinned.isEmpty()) InputPick.SET else InputPick.REPIN

/**
 * Everything the Mac card draws, and every decision behind it.
 *
 * Same arrangement as [Screen]: `:app` renders this and decides nothing. Which
 * sentence an unreachable Mac deserves, whether a cabinet has a level worth showing,
 * how far the volume control may travel — all of it is worked out here, where it is
 * tested without a phone or a network.
 */
data class ThothScreen(
    /** Where this was asked, so the sentence about a failure can name it. */
    val host: String,
    val reach: ThothReach,
    val pair: ThothPair?,
    /** Real speakers only — a group cannot be a member of itself. */
    val outputs: List<ThothDevice>,
    val input: ThothInput?,
    val cabinets: List<ThothCabinet>,
    /**
     * The last thing the server refused, in its own words, or null.
     *
     * ⚠ On the screen rather than in a log. thoth refuses a request by saying why,
     * so a client that swallows the sentence turns an explained refusal into a
     * control that silently did nothing. ⚠ The volume ceiling was the one refusal
     * that happened in ordinary use and it was removed on 2026-09-12; what is left
     * is malformed bodies and unknown cabinets.
     */
    val refusal: String? = null,
) {
    /**
     * The one line to show instead of controls, or null when there are controls.
     *
     * ⚠ [ThothReach.LIVE] with no pair is a real state, not an impossible one: the
     * pair read can fail on its own while the rest answers. It reads as away rather
     * than drawing a card with nothing in it.
     */
    val trouble: String?
        get() =
            when {
                reach == ThothReach.LOOKING -> "Looking for the Mac at $host…"
                reach == ThothReach.AWAY -> "Not reachable at $host — off this network?"
                pair == null -> "Reached $host, but it did not describe its speakers"
                else -> null
            }

    /** Nothing came back, so nothing about the Mac is known yet. */
    val blank: Boolean
        get() = trouble != null

    companion object {
        fun looking(host: String) =
            ThothScreen(host, ThothReach.LOOKING, null, emptyList(), null, emptyList())

        fun away(host: String) =
            ThothScreen(host, ThothReach.AWAY, null, emptyList(), null, emptyList())
    }
}
