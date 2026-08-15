package org.xinutec.volume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepTest {
    @Test
    fun `range parses a span and a single byte`() {
        assertEquals(0..0x12, Sweep.range("00-12"))
        assertEquals(4..4, Sweep.range("04"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an inverted range is refused`() {
        Sweep.range("12-00")
    }

    @Test
    fun `bose sweeps the block-by-function grid`() {
        val p = Sweep.bose(0..1, 0..2)
        assertEquals(6, p.size)
        assertEquals("00 00 01 00", Hex.format(p.first()))
        assertEquals("01 02 01 00", Hex.format(p.last()))
    }

    /**
     * The safety property, asserted rather than commented: every packet a Bose
     * sweep emits is operator 0x01 (Get) with a zero-length body. If this test
     * fails, the sweep has become capable of writing to the headphones.
     */
    @Test
    fun `every bose sweep packet is a zero-length GET`() {
        for (p in Sweep.bose(0..0x12, 0..0x0f)) {
            assertEquals(4, p.size)
            assertEquals("operator must be Get in ${Hex.format(p)}", 0x01.toByte(), p[2])
            assertEquals("length must be zero in ${Hex.format(p)}", 0x00.toByte(), p[3])
        }
    }

    /**
     * Fast Pair carries no operator, so a zero length is all the shape there is. It
     * is asserted to keep the packets predictable, NOT as a safety property — see the
     * ring warning on [Sweep.fastPair].
     */
    @Test
    fun `every fast pair sweep packet has a zero length`() {
        for (p in Sweep.fastPair(0..0x0f, 0..0x0f)) {
            assertEquals(4, p.size)
            assertEquals("length hi must be zero in ${Hex.format(p)}", 0x00.toByte(), p[2])
            assertEquals("length lo must be zero in ${Hex.format(p)}", 0x00.toByte(), p[3])
        }
    }

    /** Messages seen on the wire must be reproducible by the sweep. */
    @Test
    fun `the sweep reproduces messages seen on the wire`() {
        val bose = Sweep.bose(0..0, 1..1)
        assertTrue("00 01 01 00" in bose.map { Hex.format(it) })
        val fastPair = Sweep.fastPair(4..7, 0x10..0x11)
        assertTrue("07 10 00 00" in fastPair.map { Hex.format(it) })
        assertTrue("04 11 00 00" in fastPair.map { Hex.format(it) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an unknown protocol is refused rather than defaulted`() {
        Sweep.packets("sony", 0..1, 0..1)
    }
}
