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
    /** Fresh per test: the Sony driver carries a sequence bit across calls. */
    private val sony = Drivers.SonyXm4()

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
        assertEquals(AncMode.OFF, sony.read(t))
        t.assertDrained()
    }

    @Test
    fun `sony distinguishes ambient from anc by the byte that moved`() {
        // A fresh driver each time, as a fresh connection gets: the sequence bit is
        // per-session state, so reusing one here would ask with the other bit.
        assertEquals(
            AncMode.AMBIENT,
            Drivers.SonyXm4().read(Replay(sonyReadRequest to sonyAmbient)),
        )
        assertEquals(AncMode.ANC, Drivers.SonyXm4().read(Replay(sonyReadRequest to sonyAnc)))
    }

    /** The write is the frame that was driven, byte for byte. */
    @Test
    fun `sony writes the frame that was measured`() {
        val t = Replay(sonyWriteAnc to "")
        sony.write(t, AncMode.ANC)
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
        sony.read(t)
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
     * ✅ **The read, found 2026-08-16.** Frames captured from `com.jlab.app` opening
     * cold with the device in each state, then driven from this code.
     *
     * ⚠ **The mode is the seventh byte.** The two after it read `04 04` in either
     * ANC mode and `00 00` with ANC off, so they are not a constant to key on.
     */
    @Test
    fun `jlab reads back each of its three modes`() {
        val request = "c0 ff 00 44 00 00 01 00 04"
        assertEquals(
            AncMode.ANC,
            Drivers.JLabQcy.read(Replay(request to "00 ff 01 45 03 00 01 04 04 00 4f 00")),
        )
        assertEquals(
            AncMode.AMBIENT,
            Drivers.JLabQcy.read(Replay(request to "00 ff 01 45 03 00 02 04 04 00 50 00")),
        )
        assertEquals(
            AncMode.OFF,
            Drivers.JLabQcy.read(Replay(request to "00 ff 01 45 03 00 00 00 00 00 46 00")),
        )
    }

    /**
     * ⚠ **The `47` reply is not the answer, and this is why the read matters.** A
     * mode that does not exist draws the identical `47`, so a write is confirmed by
     * reading the state back and never by what the write returned.
     */
    @Test
    fun `a jlab write is confirmed by the read, not by its own reply`() {
        val t =
            Replay(
                "c0 ff 00 46 03 00 02 04 04 01 00 13" to "00 ff 01 47 01 00 01 00 47 00",
                "c0 ff 00 44 00 00 01 00 04" to "00 ff 01 45 03 00 02 04 04 00 50 00",
            )
        assertEquals(Confirmation.Confirmed, Drivers.JLabQcy.set(t, AncMode.AMBIENT))
        t.assertDrained()
    }

    /** And a write the device ignored is now catchable rather than invisible. */
    @Test
    fun `a jlab write that did not take is contradicted`() {
        val t =
            Replay(
                "c0 ff 00 46 03 00 02 04 04 01 00 13" to "00 ff 01 47 01 00 01 00 47 00",
                "c0 ff 00 44 00 00 01 00 04" to "00 ff 01 45 03 00 01 04 04 00 4f 00",
            )
        assertEquals(
            Confirmation.Contradicted(AncMode.ANC),
            Drivers.JLabQcy.set(t, AncMode.AMBIENT),
        )
    }

    /** ⚠ Off is `00` and was measured only once a read existed to check it with. */
    @Test
    fun `jlab off is a real mode now that it can be read back`() {
        assertTrue(AncMode.OFF in Drivers.JLabQcy.modes)
        val t =
            Replay(
                "c0 ff 00 46 03 00 00 04 04 01 00 11" to "00 ff 01 47 01 00 01 00 47 00",
                "c0 ff 00 44 00 00 01 00 04" to "00 ff 01 45 03 00 00 00 00 00 46 00",
            )
        assertEquals(Confirmation.Confirmed, Drivers.JLabQcy.set(t, AncMode.OFF))
    }

    /** An unknown mode byte reads as "not understood", not as the nearest match. */
    @Test
    fun `an unexercised jlab mode byte decodes to null`() {
        val t = Replay("c0 ff 00 44 00 00 01 00 04" to "00 ff 01 45 03 00 07 04 04 00 55 00")
        assertNull(Drivers.JLabQcy.read(t))
    }

    /**
     * ⚠ **The sequence bit alternates, and a repeat is silently discarded.** Two
     * frames with the same one make the device treat the second as a
     * retransmission; the read then returns nothing and the screen says "reports no
     * mode", which is what a device with genuinely no read command says. A
     * hard-coded 00 survived every single-question session and broke the moment an
     * opener was put in front of the read.
     */
    @Test
    fun `sony alternates its sequence bit across a session`() {
        val d = Drivers.SonyXm4()
        val t =
            Replay(
                "3e 0c 00 00 00 00 02 00 00 0e 3c" to "3e 0c 01 00 00 00 04 01 00 70 00 82 3c",
                "3e 0c 01 00 00 00 02 66 02 77 3c" to
                    "3e 0c 00 00 00 00 08 67 02 01 02 02 01 00 00 83 3c",
            )
        d.prepare(t)
        assertEquals(AncMode.ANC, d.read(t))
        t.assertDrained()
    }

    /**
     * ⚠ The regression this exists for. Without the opener the Sony answers a bare
     * ACK, which the driver reports as "no mode" — indistinguishable on screen from
     * the JLab, which genuinely has no read. It survived every earlier test because
     * the link was always one the probe had already opened a session on.
     */
    @Test
    fun `sony opens a session before it asks anything`() {
        val t =
            Replay("3e 0c 00 00 00 00 02 00 00 0e 3c" to "3e 0c 01 00 00 00 04 01 00 70 00 82 3c")
        sony.prepare(t)
        t.assertDrained()
    }

    /** Nobody else needs one, and pretending otherwise would cost a round trip. */
    @Test
    fun `the other drivers need no opener`() {
        for (d in listOf(Drivers.BoseQc45, Drivers.BoseQc35, Drivers.JblBes, Drivers.JLabQcy)) {
            val t = Replay()
            d.prepare(t)
            assertEquals("${d.javaClass.simpleName} sent something", 0, t.sent.size)
        }
    }

    // ---- the name the device holds ---------------------------------------------

    /**
     * ⚠ The point of asking at all. Android's bonded record for this phone's QC35
     * is "LE-Pippijn Headphon" — the LE advertisement's truncation — while the
     * headphones report the name their owner actually set.
     */
    @Test
    fun `bose reports the name its owner gave it`() {
        // The real reply, read off the QC35: length 0x12 covers a leading 00 and
        // then seventeen characters. An invented fixture without that byte passed
        // against a parser that was wrong on the device.
        val t =
            Replay(
                "01 02 01 00" to
                    "01 02 03 12 00 50 69 70 70 69 6a 6e 20 42 6f 73 65 20 51 43 33 35",
            )
        assertEquals("Pippijn Bose QC35", Drivers.BoseQc35.name(t))
    }

    @Test
    fun `a bose that answers with an error frame reports no name`() {
        assertNull(Drivers.BoseQc45.name(Replay("01 02 01 00" to "01 02 04 01 04")))
        assertNull(Drivers.BoseQc45.name(Replay("01 02 01 00" to "")))
    }

    /** The JBL puts its name first in the device-info reply, NUL-terminated. */
    @Test
    fun `jbl reports its name from the device info reply`() {
        val t =
            Replay(
                "aa 11 00" to
                    "aa 12 24 4a 42 4c 20 54 4f 55 52 20 4f 4e 45 20 4d 32 00 85 20 00 3c",
            )
        assertEquals("JBL TOUR ONE M2", Drivers.JblBes.name(t))
    }

    /** Devices that will not say so are honest about it rather than inventing one. */
    @Test
    fun `a driver with no name command returns null`() {
        assertNull(Drivers.JLabQcy.name(Replay()))
        assertNull(sony.name(Replay()))
    }

    // ---- shared --------------------------------------------------------------

    @Test
    fun `asking for a mode a device lacks is refused, not silently mapped`() {
        val e =
            runCatching { Drivers.BoseQc45.set(Replay(), AncMode.TALK_THRU) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    /**
     * ⚠ **TALK_THRU is claimed by exactly one driver, and only since it was driven.**
     * This test used to assert that *nobody* claimed it, as a guard against naming a
     * mode from a vendor's UI without ever sending it. On 2026-08-16 it was sent to
     * the JBL and confirmed against that app's own selector, so the guard is now
     * "only the device it was proved on" rather than "nobody".
     */
    @Test
    fun `only the driver it was proved on claims talk thru`() {
        val all =
            listOf(
                Drivers.BoseQc45,
                Drivers.BoseQc35,
                Drivers.JblBes,
                sony,
                Drivers.JLabQcy,
            )
        assertEquals(
            listOf(Drivers.JblBes),
            all.filter { AncMode.TALK_THRU in it.modes },
        )
        assertTrue(all.all { it.modes.isNotEmpty() })
    }

    /**
     * ⚠ **The JBL reported TalkThru as OFF until 2026-08-16.** [Drivers.JblBes.read]
     * checked the first two TLV slots and fell through to OFF, so a mode the device
     * was really in rendered as the one state it cannot be put into from here — and
     * nothing noticed, because nothing had ever set the third slot.
     */
    @Test
    fun `jbl reads talk thru rather than falling through to off`() {
        val talk = Replay("aa 91 01 11" to "aa 91 07 12 01 00 02 00 03 01")
        assertEquals(AncMode.TALK_THRU, Drivers.JblBes.read(talk))

        val off = Replay("aa 91 01 11" to "aa 91 07 12 01 00 02 00 03 00")
        assertEquals(AncMode.OFF, Drivers.JblBes.read(off))
    }

    /** The frame that was driven, byte for byte. */
    @Test
    fun `jbl writes talk thru into the third slot`() {
        val t =
            Replay(
                "aa 91 07 10 01 00 02 00 03 01" to "aa 91 07 12 01 00 02 00 03 01",
                "aa 91 01 11" to "aa 91 07 12 01 00 02 00 03 01",
            )
        assertEquals(Confirmation.Confirmed, Drivers.JblBes.set(t, AncMode.TALK_THRU))
        t.assertDrained()
    }

    /** 20:38:39, right after the write that turned it on. */
    @Test
    fun `jbl reads its auto power off`() {
        val t = Replay("aa 21 01 33" to "aa 22 04 33 01 1e 00")
        assertEquals(TimedOff(on = true, minutes = 30), Drivers.JblBes.readAutoOff(t))
        t.assertDrained()
    }

    /**
     * ⚠ **The write's own reply is an ack and is not consulted.**
     *
     * `aa 00 02 33 00` says the frame arrived. Here the device is made to lie — it
     * acks and then reports the setting unchanged — and the driver must return what
     * the re-read said, not what the ack implied. This is the shape of the mistake
     * that `Confirmation` exists for.
     */
    @Test
    fun `jbl auto power off is believed from the re-read, not the ack`() {
        val t =
            Replay(
                "aa 33 03 01 1e 00" to "aa 00 02 33 00",
                "aa 21 01 33" to "aa 22 04 33 00 1e 00",
            )
        Drivers.JblBes.writeAutoOff(t, TimedOff(on = true, minutes = 30))
        assertEquals(TimedOff(on = false, minutes = 30), Drivers.JblBes.readAutoOff(t))
        t.assertDrained()
    }

    /**
     * The curve write reads first, sends the app's frame, and re-reads.
     *
     * ⚠ **The read up front is not a spare round trip.** [JblEq.set] copies thirteen
     * unexplained bytes out of that reply, so dropping it would mean inventing them —
     * and the assertion below is that what went out is the vendor app's frame, which
     * is only true because they were copied.
     */
    @Test
    fun `jbl writes a curve built from the frame it just read`() {
        val jazz = JBL_CURVES.first { it.first == "Jazz" }.second
        val t =
            Replay(
                "aa a2 02 01 ff" to JblFrames.FLAT,
                JblFrames.spaced(JblFrames.JAZZ_SENT) to "aa 00 02 a2 00",
                "aa a2 02 01 ff" to JblFrames.JAZZ_ECHO,
            )
        assertEquals(jazz, Drivers.JblBes.writeCurve(t, jazz.table, jazz.bands.map { it.gain }))
        t.assertDrained()
    }
}
