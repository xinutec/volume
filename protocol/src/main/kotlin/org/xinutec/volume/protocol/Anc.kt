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
 * rather than an optimisation. The Bose ANC write is transactional — an
 * operator-`05` Start and then the edit — and Sony answers `GET_PARAM` only inside
 * a session where its frames are acknowledged; both
 * return plausible, wrong-looking nothing when each packet gets a fresh socket.
 * Three separate wrong conclusions came from missing that, so the interface makes
 * a session the only thing you can hold.
 */
interface Transport {
    /** Write [packet] and return whatever arrived in the reply window. */
    fun exchange(packet: ByteArray): ByteArray

    /** Write [packet] and do not wait. For protocol acks, which draw no reply. */
    fun send(packet: ByteArray)

    /**
     * [exchange], but acknowledging each frame **while the window is still open**.
     *
     * ⚠ **The XM4 is stop-and-wait: it withholds its next DATA frame until the current
     * one is acked.** Acking after [exchange] returns therefore guarantees that a window
     * opening with a volunteered frame contains *only* that frame — the device is waiting
     * on a reply that cannot come until the window it would have filled is already over.
     * That is #1107, and it is why a session that saw one unsolicited notification ran
     * one behind for the rest of its life while the device retransmitted every ~600 ms.
     *
     * [acksFor] is handed everything received so far and returns one ack per frame, in
     * order, so the list only grows; a transport sends whatever part of it has not gone
     * out yet. Returning an empty list is how a device with no acks in its protocol
     * — the JBL, both Bose — says so.
     */
    fun exchange(packet: ByteArray, acksFor: (ByteArray) -> List<ByteArray>): ByteArray

    /**
     * Read **without** sending, for what the device volunteered or answered late.
     *
     * ⚠ **This exists because [exchange]'s window is not the device's schedule.** The
     * XM4 emits notifications nobody asked for — changing DSEE makes it announce the
     * upscaling *effect* as well — and one landing in a reply window pushes the real
     * answer into the next one. Without a way to read again, a driver either returns
     * the wrong frame or reports a successful write as unconfirmable. Both happened,
     * the second one in the shipped app (#1107).
     *
     * Returns empty when nothing arrived, which is an ordinary outcome and not an
     * error — a caller must bound its own retries rather than loop until this is
     * non-empty.
     */
    fun receive(): ByteArray
}

/** One headphone family's ANC control, in terms of [AncMode]. */
interface AncDriver {
    /** The subset of [AncMode] this device implements. */
    val modes: Set<AncMode>

    /**
     * The mode the device reports, or **null when it cannot be read** — which is a
     * real state, not a failure, and the screen must not spin on it.
     *
     * ⚠ **No device here is in that state any more.** The JLab was the example for
     * months, on the reasoning that its app tracked the mode locally; that was
     * disproved on 2026-08-16 — the app draws whatever the device is actually in —
     * and its read was found the same evening. Null now means a device nobody has
     * found the read for **yet**, which is a claim about this repo, not about it.
     */
    fun read(t: Transport): AncMode?

    /**
     * Whether [read] is implemented at all.
     *
     * ⚠ **True for every driver here, and it still has to be asked.** Without it the
     * only evidence about a null mode is the null itself, which is what let a dead link
     * be reported as a device that cannot be read — see [NoMode]. A driver that has no
     * read overrides this to false and says so honestly; the default is the common case
     * and the one that must not be assumed.
     */
    val reads: Boolean get() = true

    /** Send the mode. ⚠ Whether it took is [set]'s job, never the reply's. */
    fun write(t: Transport, mode: AncMode)

    /**
     * Open the conversation, once per [Transport].
     *
     * ⚠ Some protocols need a session established before they answer anything, and
     * the failure is silent: the Sony returns a bare ACK to a read it would
     * otherwise answer, which reads as "this device has no mode". Its driver got
     * away without this for as long as the link happened to be an old one, and
     * started returning null the first time the app met a freshly connected pair.
     */
    fun prepare(t: Transport) {}

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

/**
 * What happened when a write was checked.
 *
 * Generic in what was read back because the discipline is not about ANC: an EQ
 * preset, a multipoint flag and a mode all have the same three outcomes, and a
 * second copy of this taxonomy would be a second place for "it replied, so it
 * worked" to creep back in.
 */
sealed interface Confirmation<out T> {
    /** The device read back as asked. */
    data object Confirmed : Confirmation<Nothing>

    /** It read back as something else — [actual] — so the write did not take. */
    data class Contradicted<T>(
        val actual: T,
    ) : Confirmation<T>

    /**
     * Nothing could be read back, so the write cannot be checked from here.
     *
     * ⚠ **Must never render as success.** It was the JLab's normal answer until its
     * read was found, and that device returns an identical `47` for a mode that does
     * not exist — so treating this as fine would have laundered exactly the
     * uncertainty it exists to carry.
     */
    data object Unverifiable : Confirmation<Nothing>
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
fun AncDriver.set(t: Transport, mode: AncMode): Confirmation<AncMode> {
    require(mode in modes) { "$mode is not one of $modes" }
    write(t, mode)
    val after = read(t) ?: return Confirmation.Unverifiable
    return if (after == mode) Confirmation.Confirmed else Confirmation.Contradicted(after)
}
