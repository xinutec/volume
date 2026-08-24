package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fixtures are **real frames** from the 2026-08-16 capture of Sony Headphones
 * Connect (`docs/sony-settings.md`), payloads only — the `3e0c…3c` framing and the
 * acks are stripped.
 *
 * ⚠ Hand-written fixtures have certified two bugs in this repo already (a Sony
 * checksum and a Bose name without its leading `00`), so nothing here is invented.
 */
class SonyEqTest {
    private fun bytes(hex: String) =
        hex
            .replace(" ", "")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    @Test
    fun `setting a preset is four bytes`() {
        assertEquals("5801a100", SonyEq.set(0xa1).joinToString("") { "%02x".format(it) })
    }

    /**
     * Captured at 18:57:24 on 2026-08-24, the last frame of a band dragged back up
     * to +6 in Sony Headphones Connect.
     *
     * ⚠ **The `ff` is the assertion.** Byte for byte this is what the driver had
     * been sending for a day except for that one field, where it put the slot's own
     * id — and the XM4 acked and dropped every one of those. If this test ever goes
     * green with a preset id there, the levels have stopped moving.
     */
    @Test
    fun `a levels write carries UNSPECIFIED, never the selected preset`() {
        val f = SonyEq.setLevels(listOf(3, 0, 0, 2, 4, 6))
        assertEquals("5801ff060d0a0a0c0e10", f.joinToString("") { "%02x".format(it) })
    }

    /** Captured at 11:01:41: preset `a1`, six levels, all flat. */
    @Test
    fun `a flat preset decodes to all zero dB`() {
        val s = SonyEq.state(bytes("5901a1060a0a0a0a0a0a"))!!
        assertEquals(0xa1, s.preset)
        assertEquals(listOf(0, 0, 0, 0, 0, 0), s.levels)
    }

    /**
     * ⚠ Captured at 11:02:00, and the one that proves the offset. Raw
     * `00 0e 0d 0b 0c 00` is not a plausible dB curve; minus ten it is
     * −10, +4, +3, +1, +2, −10, which is.
     */
    @Test
    fun `levels are offset by ten, so the wire is unsigned and the domain is not`() {
        val s = SonyEq.state(bytes("59011706000e0d0b0c00"))!!
        assertEquals(0x17, s.preset)
        assertEquals(listOf(-10, 4, 3, 1, 2, -10), s.levels)
    }

    /** ⚠ Six, not five: CLEAR BASS is the first, and the app draws it separately. */
    @Test
    fun `there are six levels for five drawn bands`() {
        assertEquals(6, SonyEq.state(bytes("5901a1060a0a0a0a0a0a"))!!.levels.size)
    }

    /**
     * ⚠ A capture is full of frames that are not the reply. Decoding one
     * optimistically is how "answers nothing" becomes a confident wrong reading.
     */
    @Test
    fun `frames that are not an EQ state decode to null`() {
        assertNull(SonyEq.state(bytes("6702010201000a"))) // an ANC state
        assertNull(SonyEq.state(bytes("5a016a"))) // the neighbouring opcode
        assertNull(SonyEq.state(bytes("5901"))) // truncated
        assertNull(SonyEq.state(bytes("5901a1060a0a"))) // short of its count
    }

    /**
     * Captured at 11:01:41. The frequencies match the app's own axis labels, which
     * is the independent check — they were not read off this frame.
     *
     * ⚠ **The UNESCAPED payload**, as [SonyFrame.decodeAll] returns it. This fixture
     * was originally the wire bytes, ending `01 3d 2e 80`, and asserted 15662 for the
     * top band — a number the driver could never produce, close enough to "16k" to
     * survive review. See [SonyFrameTest] for the frame it comes out of.
     */
    @Test
    fun `the capability frame carries the band centre frequencies`() {
        val f = SonyEq.bands(bytes("5b01061000010101900103e80109c401189c013e80"))
        assertEquals(listOf(400, 1000, 2500, 6300, 16000), f)
    }

    @Test
    fun `a frame that is not the capability yields nothing`() {
        assertEquals(emptyList<Int>(), SonyEq.bands(bytes("5901a1060a0a0a0a0a0a")))
    }
}
