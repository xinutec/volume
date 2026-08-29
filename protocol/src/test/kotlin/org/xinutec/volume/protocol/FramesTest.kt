package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠ **The point of [Frames.describe] is to be READ before a frame is sent**, so the
 * thing under test is whether it says something a person can check against what they
 * meant. Every case here therefore asserts the WORDS, not that a call returned.
 *
 * ⚠ **It must never invent a name it does not have.** Half of these pin the honest
 * fallback: an unknown command has to read as unknown, because "aa 42: set" is a
 * sentence somebody will trust.
 */
class FramesTest {
    private fun bytes(hex: String) =
        hex
            .replace(" ", "")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    @Test
    fun `a gesture write reads as the binding it makes`() {
        // aa 77 03 <SET> <gesture> <action> — left button, twice -> TalkThru
        val g = Gesture.LEFT_DOUBLE_TAP.wire
        val a = GestureAction.entries.first { !it.volume && it != GestureAction.NONE }
        val hex = "aa 77 03 00 %02x %02x".format(g, a.wire)
        val said = Frames.describe(null, bytes(hex))
        assertTrue(said, said.contains(Gesture.LEFT_DOUBLE_TAP.label))
        assertTrue(said, said.contains(a.label))
        assertTrue(said, said.contains("→"))
    }

    @Test
    fun `a gesture write that clears a binding says so`() {
        val hex = "aa 77 03 00 %02x 00".format(Gesture.LEFT_TAP.wire)
        val said = Frames.describe(null, bytes(hex))
        assertTrue(said, said.contains(GestureAction.NONE.label))
    }

    @Test
    fun `a bose frame names its block function and operator`() {
        val said = Frames.describe(Channels.SPP, bytes("01 06 01 00"))
        assertTrue(said, said.contains("01"))
        assertTrue(said, said.contains("06"))
        assertTrue(said, said.lowercase().contains("get"))
    }

    @Test
    fun `the bose operators are all named`() {
        val operators =
            listOf(
                "00" to "set",
                "01" to "get",
                "02" to "set",
                "03" to "status",
                "04" to "error",
                "05" to "start",
                "06" to "result",
                "07" to "processing",
            )
        for ((op, word) in operators) {
            val said = Frames.describe(Channels.SPP, bytes("01 06 $op 00")).lowercase()
            assertTrue("operator $op -> $said", said.contains(word))
        }
    }

    /**
     * ⚠ **The honest fallback is the important case.** A describe() that guesses is
     * worse than none: it is read INSTEAD of the hex, so a wrong name sends the wrong
     * frame with a confident label on it.
     */
    @Test
    fun `an unknown BES command is reported as unknown, not guessed`() {
        val said = Frames.describe(null, bytes("aa 42 01 01")).lowercase()
        assertTrue(said, said.contains("42"))
        assertTrue(said, said.contains("unknown") || said.contains("undecoded"))
    }

    @Test
    fun `an empty payload says there is nothing to send`() {
        assertTrue(Frames.describe(null, ByteArray(0)).lowercase().contains("nothing"))
    }

    @Test
    fun `a BES read and a BES write are told apart`() {
        val read = Frames.describe(null, bytes("aa 77 01 01")).lowercase()
        val write = Frames.describe(null, bytes("aa 77 03 00 06 00")).lowercase()
        assertEquals(true, read.contains("read"))
        assertEquals(true, write.contains("write") || write.contains("→"))
    }
}
