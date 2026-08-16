package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Sony EQ driver, replayed against **whole framed exchanges** from the
 * 2026-08-16 capture — `3e … 3c` and all, checksums included, copied out of
 * `tshark` rather than assembled here.
 *
 * ⚠ **This driver has never spoken to a headphone.** Every byte below is Sony
 * Headphones Connect's, and the tests prove the driver would say the same thing —
 * not that the XM4 answers it. That is still ahead, and needs the hardware.
 */
class SonyEqDriverTest {
    /** Fresh per test: the sequence bit is per instance and alternates. */
    private val sony = Drivers.SonyXm4()

    /**
     * 10:58:21, the app's own read on connecting. ⚠ This is the frame that turns
     * `56`/`57` from a prediction off the SDK's blocks-of-ten into a measurement.
     *
     * And the independent check on the whole decode: the owner had the XM4 on a
     * Custom with CLEAR BASS at **+3**, and the first level here is `0d` — 13, minus
     * the offset of ten. The reading was not taken from this frame.
     */
    @Test
    fun `the read is the frame the vendor app sends, and CLEAR BASS is the first level`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 02 56 01 65 3c" to
                    "3e 0c 01 00 00 00 0a 57 01 a2 06 0d 0a 0a 0c 0e 10 62 3c",
            )
        val eq = sony.readEq(t)!!
        assertEquals(0xa2, eq.preset)
        assertEquals(listOf(3, 0, 0, 2, 4, 6), eq.levels)
        t.assertDrained()
    }

    /**
     * 11:01:40. ⚠ Two frames arrive in one window — the ack `3e 01 01 …` first, then
     * the notify. A driver that took the first DATA-looking thing gets the ack, whose
     * payload is empty, and reports the device as silent.
     */
    @Test
    fun `a preset write is answered by an ack and then the resulting state`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 04 58 01 a1 00 0a 3c" to
                    "3e 01 01 00 00 00 00 02 3c" +
                    "3e 0c 01 00 00 00 0a 59 01 a1 06 0a 0a 0a 0a 0a 0a 54 3c",
            )
        val after = sony.writeEq(t, 0xa1)!!
        assertEquals(0xa1, after.preset)
        assertEquals(listOf(0, 0, 0, 0, 0, 0), after.levels)
    }

    /** ⚠ The device's DATA frame must be acked, or the session stops answering. */
    @Test
    fun `the driver acks the notify it received`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 04 58 01 a1 00 0a 3c" to
                    "3e 01 01 00 00 00 00 02 3c" +
                    "3e 0c 01 00 00 00 0a 59 01 a1 06 0a 0a 0a 0a 0a 0a 54 3c",
            )
        sony.writeEq(t, 0xa1)
        // Type 01, sequence inverted from the frame acked — exactly what the app sent
        // back at 11:01:41.294.
        assertEquals("3e 01 00 00 00 00 00 01 3c", t.sent.last())
    }

    /**
     * ⚠ **The write's own reply is enough, and asking again would be a second round
     * trip for an answer already given.** It counts as evidence only because the
     * preset is compared — see the contradicted case below.
     */
    @Test
    fun `a preset that reads back as itself is confirmed, in one exchange`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 04 58 01 a1 00 0a 3c" to
                    "3e 01 01 00 00 00 00 02 3c" +
                    "3e 0c 01 00 00 00 0a 59 01 a1 06 0a 0a 0a 0a 0a 0a 54 3c",
            )
        assertEquals(Confirmation.Confirmed, sony.setEq(t, 0xa1))
        t.assertDrained()
    }

    /**
     * The same real frames, asked for a preset they do not carry. ⚠ This is the case
     * that a reply-means-success reading gets wrong: the device answered, promptly,
     * with a full and perfectly well-formed state — for the preset it already had.
     */
    @Test
    fun `a device that answers with a different preset contradicts the write`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 04 58 01 17 00 80 3c" to
                    "3e 01 01 00 00 00 00 02 3c" +
                    "3e 0c 01 00 00 00 0a 59 01 a1 06 0a 0a 0a 0a 0a 0a 54 3c",
            )
        val c = sony.setEq(t, 0x17)
        assertEquals(Confirmation.Contradicted(EqSetting(0xa1, List(6) { 0 })), c)
    }

    /**
     * 11:01:59, a preset that is not flat. ⚠ Raw `00 0e 0d 0b 0c 00` is not a
     * plausible dB curve; minus ten it is, which is what pins the offset.
     */
    @Test
    fun `a preset with a curve comes back as dB`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 04 58 01 17 00 80 3c" to
                    "3e 01 01 00 00 00 00 02 3c" +
                    "3e 0c 01 00 00 00 0a 59 01 17 06 00 0e 0d 0b 0c 00 c0 3c",
            )
        assertEquals(
            EqSetting(0x17, listOf(-10, 4, 3, 1, 2, -10)),
            sony.writeEq(t, 0x17),
        )
    }

    /**
     * 11:01:40–41, the app's real sequence: set a preset, then ask for the band
     * table. ⚠ **Both sent frames are verbatim from the capture**, which is only
     * possible because the sequence bit lands where the app's did — `00` then `01`.
     * Had the driver's counter been per subsystem rather than per session, the second
     * frame would not match, and on the device it would have been discarded silently.
     */
    @Test
    fun `a preset change followed by the band table is the app's own sequence`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 04 58 01 a1 00 0a 3c" to
                    "3e 01 01 00 00 00 00 02 3c" +
                    "3e 0c 01 00 00 00 0a 59 01 a1 06 0a 0a 0a 0a 0a 0a 54 3c",
                "3e 0c 01 00 00 00 02 5a 01 6a 3c" to
                    "3e 0c 00 00 00 00 15 5b 01 06 10 00 01 01 01 90 01 03 e8 01 09 c4 " +
                    "01 18 9c 01 3d 2e 80 54 3c",
            )
        assertEquals(0xa1, sony.writeEq(t, 0xa1)!!.preset)
        // ⚠ 16000, not 15662 — the 3d 2e in that frame is an escaped 3e.
        assertEquals(listOf(400, 1000, 2500, 6300, 16000), sony.bands(t))
        t.assertDrained()
    }

    /**
     * ⚠ A bare ACK is what an EQ read draws outside a session — the same failure that
     * made `66 02` look like "this device has no ANC". It must decode to nothing
     * rather than to an empty or default setting.
     */
    @Test
    fun `an ack alone is not a state`() {
        val t = Replay("3e 0c 00 00 00 00 02 56 01 65 3c" to "3e 01 01 00 00 00 00 02 3c")
        assertNull(sony.readEq(t))
    }

    /**
     * ⚠ **One driver, one counter, both subsystems.** The EQ shares the ANC path's
     * sequence bit because they share the session, so an EQ read straight after an
     * ANC read must not repeat it. Getting this per subsystem would look fine here
     * and be dropped silently by the device — and a dropped read reports as "this
     * headphone has no EQ", which is a thing some headphones truthfully say.
     */
    @Test
    fun `the sequence bit is per session, not per subsystem`() {
        val d = Drivers.SonyXm4()
        val t =
            Replay(
                "3e 0c 00 00 00 00 02 00 00 0e 3c" to "3e 0c 01 00 00 00 04 01 00 70 00 82 3c",
                "3e 0c 01 00 00 00 02 66 02 77 3c" to
                    "3e 0c 00 00 00 00 08 67 02 01 02 02 01 00 00 83 3c",
                "3e 0c 00 00 00 00 02 56 01 65 3c" to
                    "3e 0c 01 00 00 00 0a 57 01 a2 06 0d 0a 0a 0c 0e 10 62 3c",
            )
        d.prepare(t)
        assertEquals(AncMode.ANC, d.read(t))
        assertEquals(0xa2, d.readEq(t)!!.preset)
        t.assertDrained()
    }
}
