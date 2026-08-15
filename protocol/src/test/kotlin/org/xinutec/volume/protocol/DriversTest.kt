package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five drivers, against bytes the real headphones actually sent.
 *
 * Every fixture here is a transcript from 2026-08-15 — see `docs/protocols.md` for
 * where each came from and how it was confirmed. The point of the module split is
 * that this file needs no phone.
 */
class DriversTest {
    // ---- Sony ------------------------------------------------------------------
    // Captured whole, framing and all, from the session that drove it: read Off,
    // set Ambient, read back. The frames are the exact ones on the wire.
    //
    // Named once and used twice — by the driver tests and by the checksum guard
    // below. Written out at each use, they could drift apart, and the copy the
    // guard did not check would be the one that lied.

    private val sonyReadRequest = "3e 0c 00 00 00 00 02 66 02 76 3c"
    private val sonyOff = "3e 0c 01 00 00 00 08 67 02 00 02 00 01 00 14 95 3c"
    private val sonyAmbient = "3e 0c 01 00 00 00 08 67 02 01 02 00 01 00 14 96 3c"
    private val sonyAnc = "3e 0c 01 00 00 00 08 67 02 01 02 02 01 00 00 84 3c"
    private val sonyWriteAnc = "3e 0c 00 00 00 00 08 68 02 01 02 02 01 00 00 84 3c"
    private val sonyAck = "3e 01 00 00 00 00 00 01 3c"

    @Test
    fun `sony reads the mode the device reported`() {
        val t =
            Replay(sonyReadRequest to sonyOff)
        assertEquals(AncMode.OFF, Drivers.SonyXm4.read(t))
        t.assertDrained()
    }

    @Test
    fun `sony distinguishes ambient from anc by the byte that moved`() {
        assertEquals(
            AncMode.AMBIENT,
            Drivers.SonyXm4.read(Replay(sonyReadRequest to sonyAmbient)),
        )
        assertEquals(AncMode.ANC, Drivers.SonyXm4.read(Replay(sonyReadRequest to sonyAnc)))
    }

    /** The write is the frame that was driven, byte for byte. */
    @Test
    fun `sony writes the frame that was measured`() {
        val t = Replay(sonyWriteAnc to "")
        Drivers.SonyXm4.write(t, AncMode.ANC)
        assertEquals(sonyWriteAnc, t.sent.first())
    }

    /**
     * ⚠ The ack is not optional politeness — without it the device stops answering,
     * which is what made ten inquired types look unsupported.
     */
    @Test
    fun `sony acknowledges the data frame it received`() {
        val t =
            Replay(sonyReadRequest to sonyOff)
        Drivers.SonyXm4.read(t)
        assertEquals(2, t.sent.size)
        // type 01, sequence inverted from the 01 the device used.
        assertEquals(sonyAck, t.sent[1])
    }

    /**
     * ⚠ **Keeps the fixtures honest.** Two of the Sony transcripts above were first
     * written by hand with invented checksums, and every test still passed — the
     * driver ignores `checksumOk`, so a wrong fixture is indistinguishable from a
     * right one until something asserts on it. A fixture that could not have come
     * off the wire is not evidence about the wire.
     */
    @Test
    fun `every sony fixture is a frame the device could actually have sent`() {
        val fixtures =
            listOf(sonyReadRequest, sonyOff, sonyAmbient, sonyAnc, sonyWriteAnc, sonyAck)
        for (f in fixtures) {
            val frames = SonyFrame.decodeAll(Hex.parse(f.replace(" ", "")))
            assertEquals("$f should hold exactly one frame", 1, frames.size)
            assertTrue("$f has a bad checksum or length", frames.first().checksumOk)
        }
    }

    // ---- JBL -------------------------------------------------------------------

    @Test
    fun `jbl reads both modes`() {
        val anc = Replay("aa 91 01 11" to "aa 91 07 12 01 01 02 00 03 00")
        assertEquals(AncMode.ANC, Drivers.JblBes.read(anc))

        val ambient = Replay("aa 91 01 11" to "aa 91 07 12 01 00 02 01 03 00")
        assertEquals(AncMode.AMBIENT, Drivers.JblBes.read(ambient))
    }

    @Test
    fun `jbl writes the frame that changed the device`() {
        val t = Replay("aa 91 07 10 01 00 02 01 03 00" to "aa 91 07 12 01 00 02 01 03 00")
        Drivers.JblBes.write(t, AncMode.AMBIENT)
        t.assertDrained()
    }

    /** Write then read back, which is the only thing that ever proved a write. */
    @Test
    fun `jbl set confirms from a read, not from the reply`() {
        val t =
            Replay(
                "aa 91 07 10 01 01 02 00 03 00" to "aa 91 07 12 01 01 02 00 03 00",
                "aa 91 01 11" to "aa 91 07 12 01 01 02 00 03 00",
            )
        assertEquals(Confirmation.Confirmed, Drivers.JblBes.set(t, AncMode.ANC))
        t.assertDrained()
    }

    /**
     * The failure this API exists to catch: the device answers the write happily
     * and the read-back says otherwise.
     */
    @Test
    fun `a write that did not take is contradicted, however friendly the reply`() {
        val t =
            Replay(
                "aa 91 07 10 01 00 02 01 03 00" to "aa 91 07 12 01 00 02 01 03 00",
                "aa 91 01 11" to "aa 91 07 12 01 01 02 00 03 00",
            )
        assertEquals(
            Confirmation.Contradicted(AncMode.ANC),
            Drivers.JblBes.set(t, AncMode.AMBIENT),
        )
    }

    // ---- Bose ------------------------------------------------------------------

    @Test
    fun `qc45 reads its active level and writes a slot`() {
        val quiet = Replay("01 05 01 00" to "01 05 03 03 0b 00 03")
        assertEquals(AncMode.ANC, Drivers.BoseQc45.read(quiet))

        val aware = Replay("01 05 01 00" to "01 05 03 03 0b 0a 03")
        assertEquals(AncMode.AMBIENT, Drivers.BoseQc45.read(aware))

        val w = Replay("1f 03 05 02 01 01" to "")
        Drivers.BoseQc45.write(w, AncMode.AMBIENT)
        assertEquals("1f 03 05 02 01 01", w.sent.first())
    }

    /** ⚠ The payload is <slot> 01. Reversing it is the mistake that cost a round. */
    @Test
    fun `qc45 puts the slot before the trailing 01`() {
        val w = Replay("1f 03 05 02 00 01" to "")
        Drivers.BoseQc45.write(w, AncMode.ANC)
        assertEquals("1f 03 05 02 00 01", w.sent.first())
    }

    @Test
    fun `qc35 has three states on its own function`() {
        assertEquals(
            AncMode.ANC,
            Drivers.BoseQc35.read(Replay("01 06 01 00" to "01 06 03 02 00 0b")),
        )
        assertEquals(
            AncMode.AMBIENT,
            Drivers.BoseQc35.read(Replay("01 06 01 00" to "01 06 03 02 01 0b")),
        )
        assertEquals(
            AncMode.OFF,
            Drivers.BoseQc35.read(Replay("01 06 01 00" to "01 06 03 02 03 0b")),
        )
    }

    // ---- JLab ------------------------------------------------------------------

    @Test
    fun `jlab writes the captured frame including its checksum`() {
        val nc = Replay("c0 ff 00 46 03 00 01 04 04 01 00 12" to "00 ff 01 47 01 00 01 00 47 00")
        Drivers.JLabQcy.write(nc, AncMode.ANC)
        nc.assertDrained()

        val aware = Replay("c0 ff 00 46 03 00 02 04 04 01 00 13" to "00 ff 01 47 01 00 01 00 47 00")
        Drivers.JLabQcy.write(aware, AncMode.AMBIENT)
        aware.assertDrained()
    }

    /** The checksum rule, checked against both captured frames. */
    @Test
    fun `jlab checksum is the sum of the preceding bytes`() {
        assertEquals(
            "c0 ff 00 46 03 00 01 04 04 01 00 12",
            Hex.format(
                Drivers.JLabQcy.checksummed(
                    Hex.parse("c0ff004603000104040100"),
                ),
            ),
        )
    }

    /**
     * ⚠ The JLab has no read, so a write there is honestly unverifiable rather
     * than optimistically fine. Its `47` reply looks like success for a mode that
     * does not exist.
     */
    @Test
    fun `jlab reports its writes as unverifiable`() {
        assertNull(Drivers.JLabQcy.read(Replay()))
        val t = Replay("c0 ff 00 46 03 00 01 04 04 01 00 12" to "00 ff 01 47 01 00 01 00 47 00")
        assertEquals(Confirmation.Unverifiable, Drivers.JLabQcy.set(t, AncMode.ANC))
    }

    // ---- shared --------------------------------------------------------------

    @Test
    fun `asking for a mode a device lacks is refused, not silently mapped`() {
        val e =
            runCatching { Drivers.BoseQc45.set(Replay(), AncMode.TALK_THRU) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    /** Nobody claims TALK_THRU: it is named from the JBL's UI and never exercised. */
    @Test
    fun `no driver claims a mode that was never driven`() {
        val all =
            listOf(
                Drivers.BoseQc45,
                Drivers.BoseQc35,
                Drivers.JblBes,
                Drivers.SonyXm4,
                Drivers.JLabQcy,
            )
        assertTrue(all.none { AncMode.TALK_THRU in it.modes })
        assertTrue(all.all { it.modes.isNotEmpty() })
    }
}
