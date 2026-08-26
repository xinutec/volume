package org.xinutec.volume.protocol

/**
 * Why a payload was refused, in the owner's terms.
 *
 * ⚠ [what] names the command, [why] names the consequence. Both, because "refused
 * `aa 95`" tells nobody anything and "this erases the pairing" without the bytes cannot
 * be checked against the docs.
 */
data class Refusal(
    val what: String,
    val why: String,
)

/**
 * The payloads this repo will not send unless it is told to, twice.
 *
 * ⚠ **This is a boundary check, not a driver check, and that is the point.** The probe
 * exists to send arbitrary bytes — hand-typed, at a terminal, at speed. Every safety
 * argument in this repo so far has lived in whichever caller happened to be careful:
 * `probe.sh` validates its hex, `JblGestures.offerable` hides the volume actions from
 * the UI. Neither helps someone who calls `am start-foreground-service` directly, which
 * is what a session actually does when it is chasing something.
 *
 * ⚠ **A deny-list is a floor, never a ceiling.** It knows the four destructive commands
 * that have been *found*; the BES table has hundreds and most are unread. Refusing to
 * sweep remains the rule, and this does not license one.
 *
 * ⚠ **Nothing here is silent.** A refusal returns a [Refusal] to be shown and the send
 * does not happen; it is never downgraded to a no-op or a warning that scrolls past.
 * Overriding is deliberate and per-call — see the probe's `force` extra.
 */
object Hazards {
    /**
     * `aa 95` — BES factory reset.
     *
     * ⚠ **This is why the BES protocol is never swept.** A sweep walks command bytes,
     * and `95` is in the middle of the range; the JBL would come back unpaired, with its
     * gestures, EQ and Personi-Fi profile gone. The profile has no getter, so that one is
     * not recoverable by reading it back first.
     */
    private const val BES_FACTORY_RESET: Byte = 0x95.toByte()

    /**
     * Bose `04 07` — CLEAR_DEVICE_LIST.
     *
     * ⚠ Block `04` is where the pairing list lives, and a Bose frame is
     * `<block> <fn> <operator> <len>`. A writer that took block and function as
     * parameters would be one typo from this, which is why none exists.
     */
    private const val BOSE_DEVICE_BLOCK: Byte = 0x04
    private const val BOSE_CLEAR_LIST: Byte = 0x07

    /**
     * Bose `04 03` — REMOVE_DEVICE, added 2026-08-26.
     *
     * ⚠ **`04 07` was guarded and this was not**, for the whole time both were known.
     * It unpairs **one** device rather than all of them, which sounds milder and is
     * not: the argument is a BD_ADDR, and the address most easily to hand is the
     * phone's own — `04 04` LIST_DEVICES hands it back, and `04 05` INFO already takes
     * it as a parameter. So the natural way to write a "forget this device" button is
     * also the way to unpair the device the app is talking over.
     *
     * ⚠ Found while asking whether the vendor app's **Connections** screen could be
     * built here. It could — and the first thing that screen wants is a remove button.
     * Guarding it before anything is built is the point; a refusal added after a
     * writer exists is a refusal added after the mistake is reachable.
     */
    private const val BOSE_REMOVE_DEVICE: Byte = 0x03

    /**
     * Sony table-2 `38` PERI_SET_PARAM, whose `ConnectivityActionType` is
     * `00 DISCONNECT · 01 CONNECT · 02 UNPAIR`.
     *
     * ⚠ **`02` is the hazard, and it is a PARAMETER rather than a command** — so the
     * command byte alone cannot tell you whether a frame is safe. This is exactly the
     * shape that makes sweeping the `30`–`3d` block unacceptable: the destructive value
     * is reached by varying an argument, not by walking the table.
     */
    private const val SONY_PERI_SET: Byte = 0x38
    private const val SONY_UNPAIR: Byte = 0x02

    /** Sony `a8` — the PLAYBACK_CONTROLLER setter, which reaches the volume. */
    private const val SONY_PLAYBACK_SET: Byte = 0xa8.toByte()

    /**
     * Inspect a payload bound for [uuid], or for GATT when that is null.
     *
     * [table2] distinguishes the Sony's two command tables, because `38` means different
     * things on each and a payload byte cannot say which was meant.
     *
     * ⚠ The fall-through is BES, where the factory reset lives — GATT and anything
     * unrecognised land there. Guessing WIDE is the safe direction: the cost of a false
     * refusal is one `force`, the cost of a miss is a wiped device.
     */
    fun check(uuid: String?, payload: ByteArray, table2: Boolean = false): Refusal? =
        when {
            payload.isEmpty() -> null
            uuid.equals(Channels.SONY, ignoreCase = true) -> sony(payload, table2)
            uuid.equals(Channels.SPP, ignoreCase = true) -> bose(payload)
            else -> bes(payload)
        }

    private fun sony(payload: ByteArray, table2: Boolean): Refusal? {
        if (table2 && payload[0] == SONY_PERI_SET && payload.getOrNull(2) == SONY_UNPAIR) {
            return Refusal(
                "Sony PERI_SET_PARAM with ConnectivityActionType 02",
                "this UNPAIRS the headphones; they would have to be re-paired by hand",
            )
        }
        if (!table2 && payload[0] == SONY_PLAYBACK_SET) {
            return Refusal(
                "Sony PLAYBACK_SET_PARAM (a8)",
                "this block reaches the VOLUME — never send it to headphones being worn",
            )
        }
        return null
    }

    private fun bose(payload: ByteArray): Refusal? {
        if (payload[0] != BOSE_DEVICE_BLOCK) return null
        return when (payload.getOrNull(1)) {
            BOSE_CLEAR_LIST -> {
                Refusal(
                    "Bose 04 07 CLEAR_DEVICE_LIST",
                    "this erases the pairing list, including this phone",
                )
            }

            BOSE_REMOVE_DEVICE -> {
                Refusal(
                    "Bose 04 03 REMOVE_DEVICE",
                    "this unpairs the device it names, and the address most easily to " +
                        "hand is this phone's own",
                )
            }

            else -> {
                null
            }
        }
    }

    private fun bes(payload: ByteArray): Refusal? {
        if (payload[0] != Bes.HEADER) return null
        if (payload.getOrNull(1) == BES_FACTORY_RESET) {
            return Refusal(
                "BES aa 95 factory reset",
                "this wipes gestures, EQ and the Personi-Fi hearing profile — and the " +
                    "profile has no getter, so it cannot be read back first",
            )
        }
        return besGesture(payload)
    }

    /**
     * A gesture write that binds a button to a volume change.
     *
     * ⚠ **Derived from [GestureAction.volume], never hand-listed.** Three actions change
     * the volume and the third — `0x56` VOLUME_CONTROL — sits far outside the tidy
     * `01`/`02` pair and was invisible until the vendor app's own array was read. A list
     * written out here would say two, and be wrong in exactly the way that matters.
     */
    private fun besGesture(payload: ByteArray): Refusal? {
        if (payload.getOrNull(1) != JblGestures.CMD) return null
        // ⚠ `aa 77 03 <SET> <gesture> <action>` — the action is the SIXTH byte. Reading
        // index 4 gets the GESTURE, which silently matched nothing and let all three
        // volume bindings through; the test that named every action caught it.
        val action = payload.getOrNull(5) ?: return null
        val named = GestureAction.entries.firstOrNull { it.wire == action } ?: return null
        return if (named.volume) {
            Refusal(
                "JBL gesture bound to ${named.label}",
                "a button that changes the volume is not offered by this app",
            )
        } else {
            null
        }
    }
}
