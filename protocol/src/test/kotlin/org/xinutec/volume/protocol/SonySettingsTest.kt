package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Auto power off and multipoint, replayed from whole framed exchanges in the
 * 2026-08-16 capture. As with the EQ, ⚠ **none of this has been sent to a
 * headphone** — the bytes are the vendor app's.
 */
class SonySettingsTest {
    private val sony = Drivers.SonyXm4()

    // ---- auto power off ----------------------------------------------------

    /** 10:58:22, in the connect-time conversation. Seq `01`, so `prepare` runs first. */
    @Test
    fun `auto off is read at connect`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 02 00 00 0e 3c" to "3e 0c 01 00 00 00 04 01 00 70 00 82 3c",
                "3e 0c 01 00 00 00 02 f6 04 09 3c" to
                    "3e 0c 00 00 00 00 05 f7 04 01 10 00 1d 3c",
            )
        sony.prepare(t)
        assertEquals(AutoOff.WHEN_REMOVED, sony.readAutoOff(t))
        t.assertDrained()
    }

    /** 11:05:05 and 11:05:12 — the change and its inverse, both captured whole. */
    @Test
    fun `auto off is set and echoed back`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 05 f8 04 01 11 00 1f 3c" to
                    "3e 01 01 00 00 00 00 02 3c" +
                    "3e 0c 01 00 00 00 05 f9 04 01 11 00 21 3c",
                "3e 0c 01 00 00 00 05 f8 04 01 10 00 1f 3c" to
                    "3e 01 00 00 00 00 00 01 3c" +
                    "3e 0c 00 00 00 00 05 f9 04 01 10 00 1f 3c",
            )
        assertEquals(AutoOff.NEVER, sony.writeAutoOff(t, AutoOff.NEVER))
        assertEquals(AutoOff.WHEN_REMOVED, sony.writeAutoOff(t, AutoOff.WHEN_REMOVED))
        t.assertDrained()
    }

    /**
     * ⚠ Only `10` and `11` were ever exercised. A third value must read as "not
     * understood", not fall through to whichever branch is written last — the XM4's
     * menu had no timer, and a model that does would land here.
     */
    @Test
    fun `an unexercised auto-off value is not guessed at`() {
        assertNull(SonyAutoOff.state(Hex.parse("f7 04 01 05 00")))
        assertNull(SonyAutoOff.state(Hex.parse("59 01 a1 06")))
    }

    // ---- multipoint --------------------------------------------------------

    /** 10:58:23, read at connect and off at the time. */
    @Test
    fun `multipoint is read with its own get`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 02 00 00 0e 3c" to "3e 0c 01 00 00 00 04 01 00 70 00 82 3c",
                "3e 0c 01 00 00 00 02 d6 d2 b7 3c" to "3e 0c 00 00 00 00 04 d7 d2 01 00 ba 3c",
            )
        sony.prepare(t)
        assertEquals(false, sony.readMultipoint(t))
        t.assertDrained()
    }

    /**
     * ⚠ **The whole point.** 11:06:34: the write goes out, and what comes back is
     * `99 01 06 01` — a `90`-block notification about a different parameter. It is
     * well-formed, prompt, and says nothing about `d2`. A driver reading its reply
     * would decode nothing and call the device silent; one reading it loosely would
     * call `06` the new value.
     *
     * ⚠ The read-back `d7 d2 01 **01**` is the **one constructed frame in this
     * file** — the real one at 10:58:23 ends `00`, and multipoint was never read
     * while it was on. One value byte changed, checksum recomputed; nothing else
     * here is anything but captured.
     */
    @Test
    fun `the reply to a multipoint write is about a different setting`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 04 d8 d2 01 01 bc 3c" to
                    "3e 01 01 00 00 00 00 02 3c" +
                    "3e 0c 01 00 00 00 04 99 01 06 01 b2 3c",
                "3e 0c 01 00 00 00 02 d6 d2 b7 3c" to "3e 0c 00 00 00 00 04 d7 d2 01 01 bb 3c",
            )
        assertEquals(Confirmation.Confirmed, sony.setMultipoint(t, on = true))
        t.assertDrained()
    }

    /** And the notification itself decodes to nothing, rather than to a state. */
    @Test
    fun `the ninety-block notification is not a multipoint state`() {
        assertNull(SonyMultipoint.state(Hex.parse("99010601")))
        assertEquals(false, SonyMultipoint.state(Hex.parse("d9d20100")))
    }

    /**
     * 11:06:44's real reply, read back against a device that did not take the write.
     * ⚠ This is the case the capture cannot show and the driver must still get right.
     */
    @Test
    fun `a multipoint write that did not take is contradicted`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 04 d8 d2 01 01 bc 3c" to
                    "3e 01 01 00 00 00 00 02 3c" +
                    "3e 0c 01 00 00 00 04 99 01 06 01 b2 3c",
                "3e 0c 01 00 00 00 02 d6 d2 b7 3c" to "3e 0c 00 00 00 00 04 d7 d2 01 00 ba 3c",
            )
        assertEquals(Confirmation.Contradicted(false), sony.setMultipoint(t, on = true))
    }

    // ---- the same interface, the other vendor ------------------------------

    /**
     * The QC45's frames, 11:27:36 and 11:27:46. ⚠ Its status byte is `07`/`06`, never
     * the `01`/`00` written — the same "the reply is not the answer" hazard as the
     * Sony, arrived at from a completely different direction.
     */
    @Test
    fun `bose drives multipoint through the same interface`() {
        val on =
            Replay(
                "01 0a 02 01 01" to "01 0a 03 01 07",
                "01 0a 01 00" to "01 0a 03 01 07",
            )
        assertEquals(Confirmation.Confirmed, Drivers.BoseQc45.setMultipoint(on, on = true))
        on.assertDrained()

        val off =
            Replay(
                "01 0a 02 01 00" to "01 0a 03 01 06",
                "01 0a 01 00" to "01 0a 03 01 06",
            )
        assertEquals(Confirmation.Confirmed, Drivers.BoseQc45.setMultipoint(off, on = false))
        off.assertDrained()
    }
}
