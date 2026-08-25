package org.xinutec.volume.protocol

/**
 * The eight gestures the M2 reports, in the order it reports them.
 *
 * ⚠ **`GestureType` in the SDK has twenty-three entries; the device answers with
 * eight.** The others are for hardware this model does not have — swipes, dials, a mic
 * button — so they are not modelled here. Their wire values are in `docs/protocols.md`
 * if a different JBL ever needs them.
 *
 * ⚠ **LEFT_TAP is a physical BUTTON.** The M2's left cup has no touch surface at all,
 * which cost two rounds of "the write is dead" before the button was pressed. Only the
 * right cup is a touch panel.
 *
 * ⚠ **"Every right-cup gesture refuses writes" was WRONG and stood here for a week.**
 * `aa 77 03 00 0a 06` was accepted at 23:26 on 2026-08-17. Refusals are per ACTION and
 * per gesture, not per cup: `09` wants `0a`, not the `08` play/pause that was tried, and
 * a whole side was written off on the strength of one badly chosen action.
 */
enum class Gesture(
    val wire: Byte,
    val label: String,
) {
    LEFT_TAP(0x06, "left button"),
    LEFT_DOUBLE_TAP(0x07, "left button, twice"),
    LEFT_TRIPLE_TAP(0x08, "left button, three times"),
    LEFT_HOLD(0x0c, "left button, held"),
    RIGHT_TAP(0x09, "right tap"),
    RIGHT_DOUBLE_TAP(0x0a, "right tap, twice"),
    RIGHT_TRIPLE_TAP(0x0b, "right tap, three times"),
    RIGHT_HOLD(0x0e, "right tap, held"),
    ;

    companion object {
        fun of(wire: Byte): Gesture? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * What a gesture can be bound to — `GestureActionType`, read out of the SDK's own
 * `values_Action` array rather than counted.
 *
 * ⚠ **THREE of these change the volume, not two**, and the third is why this table is
 * transcribed rather than derived: `VOLUME_CONTROL` is `0x56`, far outside the tidy
 * `01`/`02` pair, and it only became visible once the array was read. [volume] marks
 * all three and [offerable] is what any UI may show — see the test that holds it.
 *
 * ⚠ **The ordinal is NOT the wire value past `0x0d`.** `values_Action` is the identity
 * up to there and then runs *downward* from `0x60`.
 *
 * ⚠ **AND THE ARRAY IS NOT THE WHOLE SPACE.** This file's docs have now published three
 * wrong tails: `a0…ac`, then `0e`–`16` correcting it, then `54`–`60` correcting that
 * from the SDK array. The device then used **`a1`** for the voice assistant, which is
 * in none of them — and the vendor's own `product_gesture_config.json` lists
 * `activateNativeVoiceAssistant` as `0xA1` and `eQOnOff` as `0xC8`, both outside the
 * array. So `values_Action` is *an* encoding, not *the* encoding, and the only values
 * to state with confidence are the ones a device has been seen to accept.
 */
enum class GestureAction(
    val wire: Byte,
    val label: String,
    val volume: Boolean = false,
) {
    NONE(0x00, "nothing"),
    VOLUME_UP(0x01, "volume up", volume = true),
    VOLUME_DOWN(0x02, "volume down", volume = true),
    AMBIENT(0x03, "ambient aware"),
    TALK_THRU(0x04, "TalkThru"),
    NEXT_TRACK(0x05, "next track"),
    PREVIOUS_TRACK(0x06, "previous track"),
    ANC(0x07, "noise cancelling"),
    PLAY_PAUSE(0x08, "play / pause"),
    ANC_AMBIENT_AWARE(0x09, "noise cancelling + ambient"),
    PLAY_PAUSE_DISMISS_VA(0x0a, "play / pause, dismiss assistant"),
    ANC_AMBIENT(0x0b, "cycle noise cancelling and ambient"),
    ANC_OFF(0x0c, "noise cancelling off"),
    AMBIENT_OFF(0x0d, "ambient aware off"),
    GAME_CHAT_BALANCE(0x57, "game/chat balance"),
    VOLUME_CONTROL(0x56, "volume control", volume = true),
    MIC_MUTE(0x55, "mute the mic"),
    LED_STATUS(0x54, "LED"),
    CANCEL_ASSISTANT(0x60, "dismiss the assistant"),
    TALK_TO_ASSISTANT(0x5f, "talk to the assistant"),

    /**
     * ⚠ **`a1`, and it is NOT in `values_Action` at all** — measured 2026-08-17 by
     * letting the vendor app assign its own Touch Panel bundle and reading the map
     * back. The device put `a1` on right tap-and-hold while its screen said
     * "Activating Native Voice Assistant".
     *
     * This is the only assistant value here with a device behind it. The `5f`/`60`
     * above come from the SDK array and have never been accepted by this unit.
     */
    ACTIVATE_ASSISTANT(0xa1.toByte(), "activate the voice assistant"),
    ;

    companion object {
        fun of(wire: Byte): GestureAction? = entries.firstOrNull { it.wire == wire }

        /**
         * Everything a control may be bound to from this app.
         *
         * ⚠ Derived from [volume], never hand-listed — the whole reason `0x56` was a
         * near miss is that a hand-kept list of "the volume ones" said two.
         */
        val offerable: List<GestureAction> get() = entries.filterNot { it.volume }
    }
}

/**
 * JBL gestures — `aa 77`, read 2026-08-16 and written 2026-08-17.
 *
 * ```
 * → aa 77 02 01 ff              ← aa 77 <1+2N> 02 [<gesture> <action>]…
 * → aa 77 03 00 <g> <a>         ← aa 77 03 02 <g> <action it actually took>
 * → aa 77 <1+2N> 00 [<g> <a>]…  the whole map at once
 * ```
 *
 * The length is `1 + 2N`: the operator, then a pair per gesture. That is
 * `CmdGen.genSetGestureInBach77` — `combine()` writes the payload length at index 2 —
 * and the read reply's own `11` = 1 + 16 agrees with it.
 *
 * ⚠ **A REFUSED ACTION IS SILENTLY COERCED TO [GestureAction.NONE], WHICH CLEARS THE
 * BINDING.** The device does not reject the frame; it answers with a status naming the
 * gesture and `00`. So a failed write is *destructive*, not inert, and [changed] exists
 * so a caller can tell "it took" from "it wiped what was there". Measured: the left
 * button accepts eight actions and refuses `03`, `07`, `08` and every assistant — a
 * subset with no pattern, since `07` ANC is refused where `0c` ANC-off is taken.
 *
 * ⚠ `ff` is a read sentinel with no place in the gesture table, and `aa 71`/`aa 72` are
 * the SDK's *old* single-gesture pair. `aa 77` does both jobs here.
 */
object JblGestures {
    const val CMD: Byte = 0x77

    private const val GET: Byte = 0x01
    private const val SET: Byte = 0x00
    private const val STATUS: Byte = 0x02

    /** ⚠ Not a [Gesture]: the wire uses `ff` for ALL, and the table has no such entry. */
    private const val EVERY: Byte = 0xff.toByte()

    fun get(): ByteArray = byteArrayOf(Bes.HEADER, CMD, 0x02, GET, EVERY)

    fun set(g: Gesture, a: GestureAction): ByteArray =
        byteArrayOf(Bes.HEADER, CMD, 0x03, SET, g.wire, a.wire)

    /**
     * Every binding in a status frame, or null if this is not one.
     *
     * ⚠ Pairs whose gesture or action is unknown are DROPPED rather than guessed, and
     * an odd trailing byte makes the whole frame null: a map with a bogus row in it is
     * worse than no map, because it renders as a fact.
     */
    fun state(reply: ByteArray): Map<Gesture, GestureAction>? {
        if (reply.size < 4) return null
        if (reply[0] != Bes.HEADER || reply[1] != CMD || reply[3] != STATUS) return null
        val len = reply[2].toInt() and 0xff
        if (len < 1 || reply.size < 3 + len) return null
        val pairs = len - 1
        if (pairs % 2 != 0) return null
        return buildMap {
            for (i in 0 until pairs step 2) {
                val g = Gesture.of(reply[4 + i]) ?: continue
                val a = GestureAction.of(reply[5 + i]) ?: continue
                put(g, a)
            }
        }
    }

    /**
     * What a write actually did: the action the device reports for [g], or null if the
     * reply was not a status frame for it.
     */
    fun changed(reply: ByteArray, g: Gesture): GestureAction? = state(reply)?.get(g)
}

/**
 * What a gesture write actually did.
 *
 * ⚠ **Four outcomes rather than [Confirmation]'s three, because a refusal here DESTROYS
 * something.** The device answers a refused action with `<gesture> 00` — a perfectly
 * well-formed status frame saying the binding is now empty. So "it did not take" and "it
 * took the previous value away" are the same wire event, and a writer that reports
 * `Contradicted` for both is telling the owner their button still works when it does not.
 */
sealed interface GestureWrite {
    /** The device took it. */
    data class Took(
        val action: GestureAction,
    ) : GestureWrite

    /**
     * Refused, and the binding that was there is back.
     *
     * ⚠ [restored] is what the device REPORTS after the second write, not what was asked
     * for — the restore is a write like any other and is believed the same way.
     */
    data class RefusedAndRestored(
        val wanted: GestureAction,
        val restored: GestureAction,
    ) : GestureWrite

    /**
     * ⚠ **Refused, and the restore did not stick either — the binding is now empty.**
     *
     * The loud case. It has never been observed, and it must still exist: the restore is
     * a second write down the same path that just refused one, and assuming it works
     * because it usually does is how a silent wipe gets reported as a tidy refusal.
     */
    data class RefusedAndLost(
        val wanted: GestureAction,
        val was: GestureAction,
    ) : GestureWrite

    /** No status frame came back, so nothing is known — including whether it wrote. */
    data object Unanswered : GestureWrite
}
