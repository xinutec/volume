package org.xinutec.volume.protocol

/**
 * How long to keep a headphone's control channel open after finishing with it.
 *
 * ⚠ **The channels are not ours to keep.** Holding one is a live radio link per
 * device, for as long as it is held — and the app used to hold every one of them
 * until it was destroyed, backgrounded or not.
 *
 * So a session is a *lease*, not a possession: taken to do a thing, held while that
 * is plausibly still going on, then given back. The window is generous because a
 * reopen is not free — a second on RFCOMM, and far more on a device that must be
 * found by an LE scan first, so a short lease taxes the next tap for nothing.
 *
 * ⚠ **This is NOT for coexisting with the vendors' apps.** That was the original
 * reason and it no longer applies: those apps are to be uninstalled once this one
 * replaces them. Do not shorten the lease on their account — nothing is waiting for
 * the channel, so the only cost of holding it is power, and the only cost of
 * dropping it is latency the owner feels.
 *
 * Pure and here rather than in `:app` because "may I close this yet" is a decision
 * with an edge that a wall-clock test would only find by luck: work that is *in
 * flight* must never be swept, however long it has run. [begin]/[end] bracket that,
 * and an address between them is untouchable.
 */
class Leases(
    private val idleMs: Long,
) {
    private val idleSince = mutableMapOf<String, Long>()
    private val working = mutableSetOf<String>()

    /** Work is starting on [address]; it cannot expire until [end]. */
    fun begin(address: String) {
        working += address
        idleSince -= address
    }

    /** Work has finished; the idle clock starts now. */
    fun end(address: String, now: Long) {
        working -= address
        idleSince[address] = now
    }

    /**
     * Addresses whose lease has run out.
     *
     * ⚠ Never includes anything between [begin] and [end]. A long read is not an
     * idle channel, and closing one mid-exchange surfaces as a protocol fault —
     * the failure would look like the device misbehaving rather than like us.
     */
    fun expired(now: Long): Set<String> =
        idleSince
            .filterKeys { it !in working }
            .filterValues { now - it >= idleMs }
            .keys
            .toSet()

    /** Forget [address] entirely — it disconnected, or we closed it ourselves. */
    fun forget(address: String) {
        working -= address
        idleSince -= address
    }

    /** Whether anything is currently held, so a sweep can stop rescheduling. */
    fun idle(): Boolean = idleSince.isEmpty() && working.isEmpty()
}
