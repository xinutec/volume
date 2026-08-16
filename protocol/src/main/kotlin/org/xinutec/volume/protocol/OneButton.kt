package org.xinutec.volume.protocol

/**
 * What a single button does, when there are five headphones and three modes.
 *
 * A Quick Settings tile is one control with no room to choose: no device picker, no
 * mode list, one tap. Both of those choices — *which* pair and *which* mode — are
 * decisions rather than I/O, so they are here and tested, and the tile only performs
 * them.
 */
object OneButton {
    /**
     * The pair a tap should act on.
     *
     * ⚠ **The audio follows one device, so the button must too.** With two pairs
     * connected, acting on "the first" would silently change the ANC of the one not
     * in your ears — the failure is invisible, because the tile would report success
     * about the wrong headphones. So the device playing audio wins; only when the
     * system names none of them does the sole connected pair get it by default.
     *
     * @param connected drivable pairs that are actually here, in list order.
     * @param active the system's current audio route, if it names one of them.
     */
    fun target(connected: List<String>, active: String?): String? =
        when {
            active != null && active in connected -> active
            connected.size == 1 -> connected.single()
            else -> null
        }

    /**
     * The mode to move to, cycling in the order the driver declares.
     *
     * ⚠ **Never the mode it is already in.** A tap that resolves to a no-op is
     * indistinguishable from a broken write — the same trap that had the QC45's mode
     * selection written off as inert once already.
     *
     * @param current null when the device cannot report one (the JLab), in which
     *   case the cycle has no anchor and the first mode is the only honest answer —
     *   it is at least a *change* from something.
     */
    fun next(modes: List<AncMode>, current: AncMode?): AncMode {
        require(modes.isNotEmpty()) { "a device with no modes has no button" }
        val at = modes.indexOf(current)
        if (at < 0) return modes.first()
        return modes[(at + 1) % modes.size]
    }
}
