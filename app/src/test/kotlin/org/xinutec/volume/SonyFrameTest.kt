package org.xinutec.volume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These pin the framing this code *implements*. They do NOT establish that the
 * framing is Sony's — that is what the probe is for, and until a capture says
 * otherwise every expectation here is derived from [SonyFrame]'s own hypothesis.
 * A green suite plus a silent headphone means this file is self-consistent and
 * wrong.
 */
class SonyFrameTest {
    @Test
    fun `encodes the documented shape`() {
        val f = SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, 0, Hex.parse("0000"))
        // 3e | 0c 00 | 00 00 00 02 | 00 00 | sum | 3c
        assertEquals(SonyFrame.START, f.first())
        assertEquals(SonyFrame.END, f.last())
        assertEquals("3e 0c 00 00 00 00 02 00 00 0e 3c", Hex.format(f))
    }

    @Test
    fun `length is big-endian over four bytes`() {
        val f = SonyFrame.encode(SonyFrame.TYPE_DATA, 0, ByteArray(258))
        assertEquals("00 00 01 02", Hex.format(f, 3, 7))
    }

    @Test
    fun `round-trips through decode`() {
        val payload = Hex.parse("6802010f")
        val wire = SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, 1, payload)
        val frames = SonyFrame.decodeAll(wire)
        assertEquals(1, frames.size)
        assertEquals(SonyFrame.TYPE_DATA_MDR, frames[0].type)
        assertEquals(1.toByte(), frames[0].seq)
        assertTrue(frames[0].checksumOk)
        assertEquals(Hex.format(payload), Hex.format(frames[0].payload))
    }

    /** A payload byte equal to a marker must not be able to end the frame early. */
    @Test
    fun `escapes marker bytes and survives the round trip`() {
        val payload = Hex.parse("3e3c3d")
        val wire = SonyFrame.encode(SonyFrame.TYPE_DATA, 0, payload)
        assertEquals(1, wire.count { it == SonyFrame.END })
        val frames = SonyFrame.decodeAll(wire)
        assertEquals(1, frames.size)
        assertEquals(Hex.format(payload), Hex.format(frames[0].payload))
        assertTrue(frames[0].checksumOk)
    }

    @Test
    fun `escape and unescape are inverses over every byte value`() {
        val all = ByteArray(256) { it.toByte() }
        assertEquals(Hex.format(all), Hex.format(SonyFrame.unescape(SonyFrame.escape(all))))
    }

    @Test
    fun `a corrupted checksum is reported, not thrown`() {
        val wire = SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, 0, Hex.parse("0000"))
        wire[wire.size - 2] = (wire[wire.size - 2] + 1).toByte()
        val frames = SonyFrame.decodeAll(wire)
        assertEquals(1, frames.size)
        assertFalse(frames[0].checksumOk)
    }

    @Test
    fun `several frames in one read are all returned`() {
        val a = SonyFrame.encode(SonyFrame.TYPE_ACK, 0, ByteArray(0))
        val b = SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, 1, Hex.parse("42"))
        assertEquals(2, SonyFrame.decodeAll(a + b).size)
    }

    /** A truncated tail is dropped rather than half-decoded, so the caller can read on. */
    @Test
    fun `a partial trailing frame is ignored`() {
        val whole = SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, 0, Hex.parse("0000"))
        val truncated = whole + Hex.parse("3e0c00")
        assertEquals(1, SonyFrame.decodeAll(truncated).size)
    }

    @Test
    fun `hex parsing accepts the separators a person actually types`() {
        assertEquals("3e 0c 00", Hex.format(Hex.parse("3E:0C:00")))
        assertEquals("3e 0c 00", Hex.format(Hex.parse("3e 0c 00")))
        assertEquals("3e 0c 00", Hex.format(Hex.parse("3e0c00")))
    }
}
