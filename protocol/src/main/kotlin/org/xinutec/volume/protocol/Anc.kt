package org.xinutec.volume.protocol

/**
 * What the app asks for, in the same words on every headphone.
 *
 * Deliberately not a superset of every vendor's vocabulary. The Bose QC45 has
 * eleven levels and named slots, the Sony twenty ambient steps, the JBL a
 * TalkThru; a shared enum that tried to hold all of that would be a union nobody
 * can render. [AncDriver.modes] says what a given device does with these four, and
 * anything finer stays behind the vendor's own driver.
 */
enum class AncMode {
    /** Noise cancelling and ambient pass-through both off. */
    OFF,

    /** Noise cancelling on. */
    ANC,

    /** Outside sound passed through. */
    AMBIENT,

    /** Voice-focused pass-through. JBL only, and unmeasured — see its driver. */
    TALK_THRU,
}

/**
 * A byte channel to one headphone, already open.
 *
 * ⚠ **One instance is one CONNECTION, not one packet**, and that is load-bearing
 * rather than an optimisation. Bose edits are transactional, and Sony answers
 * `GET_PARAM` only inside a session where its frames are acknowledged — both
 * return plausible, wrong-looking nothing when each packet gets a fresh socket.
 * Three separate wrong conclusions came from missing that, so the interface makes
 * a session the only thing you can hold.
 */
interface Transport {
    /** Write [packet] and return whatever arrived in the reply window. */
    fun exchange(packet: ByteArray): ByteArray

    /** Write [packet] and do not wait. For protocol acks, which draw no reply. */
    fun send(packet: ByteArray)
}

/** One headphone family's ANC control, in terms of [AncMode]. */
interface AncDriver {
    /** The subset of [AncMode] this device implements. */
    val modes: Set<AncMode>

    /**
     * The mode the device reports, or **null when it has no read command** — which
     * is a real state, not a failure: the JLab has none, and its app appears to
     * track the mode locally.
     */
    fun read(t: Transport): AncMode?

    /** Send the mode. ⚠ Whether it took is [set]'s job, never the reply's. */
    fun write(t: Transport, mode: AncMode)

    /**
     * The name the **device** holds, or null if it will not say.
     *
     * ⚠ Not the same string as the bonded record, and usually better. Android's
     * bonded name for this phone's QC35 is "LE-Pippijn Headphon" — the LE
     * advertisement's truncation of it — while the headphones themselves report
     * "Pippijn Bose QC35". Showing the former is showing a Bluetooth artefact to
     * someone who named their headphones something else.
     */
    fun name(t: Transport): String? = null
}

/** What happened when a write was checked. */
sealed interface Confirmation {
    /** The device read back as asked. */
    data object Confirmed : Confirmation

    /** It read back as something else — [actual] — so the write did not take. */
    data class Contradicted(
        val actual: AncMode,
    ) : Confirmation

    /** This device has no read command, so the write cannot be checked from here. */
    data object Unverifiable : Confirmation
}

/**
 * Write, then read back, and say which happened.
 *
 * ⚠ **A reply is not a confirmation, and this exists so that cannot be forgotten.**
 * Every device here answers a bad write as cheerfully as a good one: the JLab
 * returns the identical `47` to a mode that does not exist, and Bose echoes the
 * *unchanged* state when a transactional write is sent without its opening packet.
 * Both read exactly like success. The only evidence a setting moved is reading it
 * back — or, where that is impossible, saying so out loud rather than assuming.
 */
fun AncDriver.set(t: Transport, mode: AncMode): Confirmation {
    require(mode in modes) { "$mode is not one of $modes" }
    write(t, mode)
    val after = read(t) ?: return Confirmation.Unverifiable
    return if (after == mode) Confirmation.Confirmed else Confirmation.Contradicted(after)
}
