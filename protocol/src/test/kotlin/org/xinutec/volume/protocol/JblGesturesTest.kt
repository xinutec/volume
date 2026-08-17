package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JBL gesture map — read 2026-08-16, written and confirmed by ear 2026-08-17.
 *
 * Every frame here is off the wire except where a test says it is synthetic and why.
 */
class JblGesturesTest {
    /** The map as the M2 shipped, unchanged across every read this repo has taken. */
    private val asShipped = "aa771102060b070408000c0009000a000b000e00"

    @Test
    fun `the shipped map decodes to the two bindings the app shows`() {
        val map = JblGestures.state(Hex.parse(asShipped))!!
        assertEquals(8, map.size)
        assertEquals(GestureAction.ANC_AMBIENT, map[Gesture.LEFT_TAP])
        assertEquals(GestureAction.TALK_THRU, map[Gesture.LEFT_DOUBLE_TAP])
        assertEquals(GestureAction.NONE, map[Gesture.RIGHT_TAP])
        assertEquals(6, map.count { it.value == GestureAction.NONE })
    }

    @Test
    fun `the frames are the ones the vendor app builds`() {
        assertEquals("aa770201ff", hex(JblGestures.get()))
        // What was actually sent on 2026-08-17, and what restored it.
        assertEquals(
            "aa7703000605",
            hex(JblGestures.set(Gesture.LEFT_TAP, GestureAction.NEXT_TRACK)),
        )
        assertEquals(
            "aa770300060b",
            hex(JblGestures.set(Gesture.LEFT_TAP, GestureAction.ANC_AMBIENT)),
        )
    }

    /**
     * ⚠ **The reply to a write says what the device DID, which is not what was asked.**
     *
     * Both frames below are real. The first is `06 → 0b` taken; the second is `06 → 07`
     * refused, and the device answered `00` — clearing the binding rather than
     * rejecting the frame.
     */
    @Test
    fun `a refused write reports NONE, not the action asked for`() {
        assertEquals(
            GestureAction.ANC_AMBIENT,
            JblGestures.changed(Hex.parse("aa770302060b"), Gesture.LEFT_TAP),
        )
        assertEquals(
            GestureAction.NONE,
            JblGestures.changed(Hex.parse("aa7703020600"), Gesture.LEFT_TAP),
        )
    }

    /** A status about one gesture says nothing about another. */
    @Test
    fun `a single-gesture status does not answer for the rest`() {
        assertNull(JblGestures.changed(Hex.parse("aa770302060b"), Gesture.RIGHT_TAP))
    }

    /**
     * ⚠ **THREE actions are volume and the offer must derive that, not list it.**
     *
     * `56` VOLUME_CONTROL is the one a hand-kept list misses: it is nowhere near
     * `01`/`02`, and it only appeared when the SDK's `values_Action` array was read
     * instead of counted from the enum's ordinals. This test is the guard on the whole
     * hearing rule for gestures — if it ever passes with a volume action offerable,
     * a UI can bind a button to the volume.
     */
    @Test
    fun `no volume action is ever offerable`() {
        assertTrue(GestureAction.offerable.none { it.volume })
        assertFalse(GestureAction.VOLUME_UP in GestureAction.offerable)
        assertFalse(GestureAction.VOLUME_DOWN in GestureAction.offerable)
        assertFalse(GestureAction.VOLUME_CONTROL in GestureAction.offerable)
        assertEquals(3, GestureAction.entries.count { it.volume })
        assertEquals(0x56.toByte(), GestureAction.VOLUME_CONTROL.wire)
    }

    /**
     * ⚠ The wire values past `0x0d` are NOT the enum ordinals, and this file's docs
     * published two different wrong tails before the array settled it.
     */
    @Test
    fun `the assistant actions sit where the SDK array puts them`() {
        assertEquals(0x60.toByte(), GestureAction.CANCEL_ASSISTANT.wire)
        assertEquals(0x5f.toByte(), GestureAction.TALK_TO_ASSISTANT.wire)
        assertEquals(0x54.toByte(), GestureAction.LED_STATUS.wire)
    }

    /**
     * ⚠ A frame for another command must not decode as a gesture map.
     *
     * `aa 9f 11 02 …` is synthetic — Smart Talk's command with this command's length
     * and operator, and a payload of bytes that ARE valid gesture/action pairs. Only
     * the command check can refuse it, and with that check removed it reads as a map.
     */
    @Test
    fun `another command is not read as a gesture map`() {
        assertNull(JblGestures.state(Hex.parse("aa9f1102060b070408000c0009000a000b000e00")))
        // A set is not a status.
        assertNull(JblGestures.state(Hex.parse("aa771100060b070408000c0009000a000b000e00")))
    }

    /**
     * ⚠ An odd number of payload bytes means the frame is not what it claims, and the
     * whole map is refused rather than the last pair being dropped — a map with a row
     * invented or lost renders as a fact about the headphones.
     */
    @Test
    fun `a truncated or odd frame is refused outright`() {
        assertNull(JblGestures.state(Hex.parse("aa77100206")))
        assertNull(JblGestures.state(Hex.parse("aa770402060b07")))
    }

    /** Unknown pairs are dropped, not guessed — the rest of the map still stands. */
    @Test
    fun `a pair nobody has seen is dropped rather than invented`() {
        // `1f` is not a gesture this device reports; `06 0b` beside it is.
        val map = JblGestures.state(Hex.parse("aa770502060b1f03"))!!
        assertEquals(mapOf(Gesture.LEFT_TAP to GestureAction.ANC_AMBIENT), map)
    }

    private fun hex(b: ByteArray) = Hex.format(b).replace(" ", "")
}
