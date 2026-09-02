package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Most of these pin the framing this code *implements*, and on their own they would
 * only prove the file self-consistent — a green suite plus a silent headphone once
 * meant exactly that.
 *
 * The two at the end are different: they decode a **captured XM4 frame** whose
 * length, checksum and escape are consistent under one reading only, which is what
 * turned the hypothesis into a measurement.
 */
class SonyFrameTest {
    @Test
    fun `encodes the documented shape`() {
        val f = SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, 0, Hex.parse("0000"))
        // 3e | 0c 00 | 00 00 00 02 | 00 00 | sum | 3c
        assertEquals(SonyFrame.START, f.bytes.first())
        assertEquals(SonyFrame.END, f.bytes.last())
        assertEquals("3e 0c 00 00 00 00 02 00 00 0e 3c", Hex.format(f.bytes))
    }

    @Test
    fun `length is big-endian over four bytes`() {
        val f = SonyFrame.encode(SonyFrame.TYPE_DATA, 0, ByteArray(258))
        assertEquals("00 00 01 02", Hex.format(f.bytes, 3, 7))
    }

    @Test
    fun `round-trips through decode`() {
        val payload = Hex.parse("6802010f")
        val wire = SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, 1, payload)
        val frames = SonyFrame.decodeAll(wire.bytes)
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
        assertEquals(1, wire.bytes.count { it == SonyFrame.END })
        val frames = SonyFrame.decodeAll(wire.bytes)
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
        val wire = SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, 0, Hex.parse("0000")).bytes
        wire[wire.size - 2] = (wire[wire.size - 2] + 1).toByte()
        val frames = SonyFrame.decodeAll(wire)
        assertEquals(1, frames.size)
        assertFalse(frames[0].checksumOk)
    }

    @Test
    fun `several frames in one read are all returned`() {
        val a = SonyFrame.encode(SonyFrame.TYPE_ACK, 0, ByteArray(0))
        val b = SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, 1, Hex.parse("42"))
        assertEquals(2, SonyFrame.decodeAll(a.bytes + b.bytes).size)
    }

    /** A truncated tail is dropped rather than half-decoded, so the caller can read on. */
    @Test
    fun `a partial trailing frame is ignored`() {
        val whole = SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, 0, Hex.parse("0000"))
        val truncated = whole.bytes + Hex.parse("3e0c00")
        assertEquals(1, SonyFrame.decodeAll(truncated).size)
    }

    /**
     * The frame that settled the framing: the XM4's band table, 2026-08-16 11:01:41,
     * copied whole off the wire.
     *
     * ⚠ **It decides escaping, length and checksum at once, and only one reading of
     * all three is consistent.** Twenty-two bytes arrive but the header declares
     * twenty-one; the checksum `54` holds over the unescaped body and not the escaped
     * one. So `3d 2e` is an escaped `3e`, the length counts the payload after
     * unescaping, and the sum is taken before escaping — which is what [SonyFrame]
     * had assumed from an unrelated project, unverified, for as long as no captured
     * frame happened to contain a marker byte.
     */
    @Test
    fun `the captured band table decodes, and its escape is what makes it consistent`() {
        val wire =
            Hex.parse(
                "3e0c00000000155b01061000010101900103e80109c401189c013d2e80543c",
            )
        val f = SonyFrame.decodeAll(wire).single()
        assertEquals(SonyFrame.TYPE_DATA_MDR, f.type)
        assertTrue("length and checksum agree only after unescaping", f.checksumOk)
        assertEquals(21, f.payload.size)
        assertEquals(
            "5b 01 06 10 00 01 01 01 90 01 03 e8 01 09 c4 01 18 9c 01 3e 80",
            Hex.format(f.payload),
        )
        // And the payload the frame really carried says 16 kHz exactly, not 15662.
        assertEquals(listOf(400, 1000, 2500, 6300, 16000), SonyEq.bands(f.payload))
    }

    /** Re-encoding the decoded payload reproduces the captured bytes, escape and all. */
    @Test
    fun `the captured frame round-trips`() {
        val wire =
            Hex.parse(
                "3e0c00000000155b01061000010101900103e80109c401189c013d2e80543c",
            )
        val f = SonyFrame.decodeAll(wire).single()
        assertEquals(Hex.format(wire), Hex.format(SonyFrame.encode(f.type, f.seq, f.payload).bytes))
    }

    @Test
    fun `hex parsing accepts the separators a person actually types`() {
        assertEquals("3e 0c 00", Hex.format(Hex.parse("3E:0C:00")))
        assertEquals("3e 0c 00", Hex.format(Hex.parse("3e 0c 00")))
        assertEquals("3e 0c 00", Hex.format(Hex.parse("3e0c00")))
    }
}
