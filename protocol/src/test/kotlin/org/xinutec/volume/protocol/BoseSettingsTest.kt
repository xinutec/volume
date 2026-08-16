package org.xinutec.volume.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are **whole real frames** from the 2026-08-16 Bose Music capture
 * (`docs/captures.md`), copied out of `tshark` — not written to match the code.
 *
 * The decode was arrived at by pairing each Set with the Status it drew, so these
 * tests are the argument itself: change the reading and the captured Set no longer
 * explains the captured Status.
 */
class BoseSettingsTest {
    private fun bytes(hex: String) = Hex.parse(hex.replace(" ", ""))

    private fun hex(b: ByteArray) = Hex.format(b).replace(" ", "")

    // ---- EQ ----------------------------------------------------------------

    /** 11:25:33, the three frames Bose Music sent for its "Bass Boost" button. */
    @Test
    fun `bass boost is the three writes the vendor app sent`() {
        assertEquals(
            listOf("010702020002", "010702020001", "010702020800"),
            BoseEq.setAll(BoseEq.BASS_BOOST).map(::hex),
        )
    }

    /** 11:25:41. ⚠ Treble Boost is +6 where Bass Boost is +8; that is what was sent. */
    @Test
    fun `treble boost is plus six, and it is not symmetric with bass boost`() {
        assertEquals(
            listOf("010702020602", "010702020001", "010702020000"),
            BoseEq.setAll(BoseEq.TREBLE_BOOST).map(::hex),
        )
    }

    /** 11:25:49 — "Reset" is three zeroes, not a command of its own. */
    @Test
    fun `reset is flat`() {
        assertEquals(
            listOf("010702020002", "010702020001", "010702020000"),
            BoseEq.setAll(BoseEq.FLAT).map(::hex),
        )
    }

    /**
     * The pairing that proves the layout: the Status drawn by the Bass Boost writes
     * differs from the flat one in exactly the band those writes named.
     */
    @Test
    fun `the status echoes every band, and only the written one moved`() {
        assertEquals(BoseEq.FLAT, BoseEq.state(bytes("0107030cf60a0000f60a0001f60a0002")))
        assertEquals(BoseEq.BASS_BOOST, BoseEq.state(bytes("0107030cf60a0800f60a0001f60a0002")))
        assertEquals(BoseEq.TREBLE_BOOST, BoseEq.state(bytes("0107030cf60a0000f60a0001f60a0602")))
    }

    /** 11:25:41, mid-run: both boosts held at once, before the app zeroed the bass. */
    @Test
    fun `two bands can be raised at once`() {
        assertEquals(
            BoseBands(bass = 8, mid = 0, treble = 6),
            BoseEq.state(bytes("0107030cf60a0800f60a0001f60a0602")),
        )
    }

    /**
     * ⚠ The band is the **fourth** byte of each group and the level the third. Handed
     * the groups shuffled, a positional reading returns the wrong band's level; this
     * one does not, which is the whole reason it is a map.
     */
    @Test
    fun `bands are indexed by their own byte, not by position`() {
        assertEquals(
            BoseEq.state(bytes("0107030cf60a0800f60a0001f60a0602")),
            BoseEq.state(bytes("0107030cf60a0602f60a0001f60a0800")),
        )
    }

    /** ⚠ `f6` is −10, not 246. The wire is signed here, unlike Sony's offset bytes. */
    @Test
    fun `levels are signed`() {
        assertEquals(
            BoseBands(bass = -10, mid = 0, treble = 10),
            BoseEq.state(bytes("0107030cf60af600f60a0001f60a0a02")),
        )
    }

    @Test
    fun `a level outside the range is refused rather than truncated`() {
        val e = runCatching { BoseEq.set(BoseEq.BASS, 11) }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e is IllegalArgumentException)
    }

    @Test
    fun `frames that are not an EQ status decode to null`() {
        assertNull(BoseEq.state(bytes("010a030106"))) // multipoint
        assertNull(BoseEq.state(bytes("01070300"))) // empty status
        assertNull(BoseEq.state(bytes("0107030cf60a0000f60a0001"))) // short of its length
        assertNull(BoseEq.state(bytes("0107030800000000f60a0001"))) // only two bands
    }

    // ---- Multipoint --------------------------------------------------------

    @Test
    fun `multipoint get and set are the captured frames`() {
        assertEquals("010a0100", hex(BoseMultipoint.get()))
        assertEquals("010a020101", hex(BoseMultipoint.set(on = true)))
        assertEquals("010a020100", hex(BoseMultipoint.set(on = false)))
    }

    /**
     * ⚠ 11:27:36 and 11:27:46. The status is `07` on and `06` off — **neither equals
     * the byte that was written**, so a driver that compared them would report every
     * write as having failed.
     */
    @Test
    fun `multipoint state is bit zero of a flags byte, not the value written`() {
        assertEquals(true, BoseMultipoint.state(bytes("010a030107")))
        assertEquals(false, BoseMultipoint.state(bytes("010a030106")))
    }

    /**
     * 11:28:43, verbatim: two frames arrived in one reply window. ⚠ The trailing
     * `04 04 …` is a paired-device record, and a decoder that ran to the end of the
     * buffer would fold it into the multipoint payload.
     */
    @Test
    fun `a second frame in the same window is not read as payload`() {
        assertEquals(false, BoseMultipoint.state(bytes("010a0301060404030701fc4116e09d2a")))
    }

    // ---- Action button -----------------------------------------------------

    /** 11:26:38 and 11:26:47. */
    @Test
    fun `the action button shortcut is set by its code`() {
        assertEquals("01090203800910", hex(BoseButton.set(BoseButton.Action.SPOTIFY)))
        assertEquals(
            "01090203800903",
            hex(BoseButton.set(BoseButton.Action.HEAR_BATTERY_LEVEL)),
        )
    }

    @Test
    fun `the shortcut status names the action`() {
        assertEquals(
            BoseButton.Action.SPOTIFY,
            BoseButton.state(bytes("0109030b8009100001400800000080")),
        )
        assertEquals(
            BoseButton.Action.HEAR_BATTERY_LEVEL,
            BoseButton.state(bytes("0109030b8009030001400800000080")),
        )
    }

    /**
     * ⚠ The QC45 offers more shortcuts than the two that were driven. An unexercised
     * code must read as unknown, not as the nearest thing in the enum — this repo has
     * already once decoded a device's silence into a confident answer.
     */
    @Test
    fun `an unexercised action code decodes to null`() {
        assertNull(BoseButton.state(bytes("0109030b8009070001400800000080")))
    }

    // ---- Framing -----------------------------------------------------------

    @Test
    fun `encode writes the length from the payload`() {
        assertArrayEquals(
            bytes("010702020800"),
            BoseFrame.encode(0x01, 0x07, BoseFrame.SET, bytes("0800")),
        )
        assertArrayEquals(bytes("010a0100"), BoseFrame.encode(0x01, 0x0a, BoseFrame.GET))
    }

    /** An Error frame is not a Status, and `04 01 05` means the function exists. */
    @Test
    fun `an error frame is not decoded as a status`() {
        assertNull(BoseEq.state(bytes("01070401" + "05")))
        assertArrayEquals(
            bytes("05"),
            BoseFrame.payload(bytes("0107040105"), 0x01, 0x07, BoseFrame.ERROR),
        )
    }
}
