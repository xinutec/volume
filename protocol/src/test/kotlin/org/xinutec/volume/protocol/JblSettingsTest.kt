package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The JBL's auto power off and equaliser, replayed from the 2026-08-16 capture.
 *
 * ⚠ **Every hex string here is a whole frame off the wire**, taken with
 * `tshark -e btatt.value` — the JBL is GATT, so `data.data` is empty for it and a
 * filter written for the Sony returns nothing at all. Times are in the timeline in
 * `docs/captures.md`.
 */
class JblSettingsTest {
    // ---- auto power off ----------------------------------------------------

    /** 20:32:12 (off) and 20:38:39 (on), either side of a write from this code. */
    @Test
    fun `auto off is read`() {
        assertEquals(TimedOff(on = false, minutes = 30), JblAutoOff.state(bytes("aa220433001e00")))
        assertEquals(TimedOff(on = true, minutes = 30), JblAutoOff.state(bytes("aa220433011e00")))
    }

    /** 20:37:55 and 20:40:14 — what this code actually sent, and it took. */
    @Test
    fun `auto off is written as the app writes it`() {
        assertEquals("aa3303011e00", hex(JblAutoOff.set(TimedOff(on = true, minutes = 30))))
        assertEquals("aa3303001e00", hex(JblAutoOff.set(TimedOff(on = false, minutes = 30))))
    }

    /**
     * ⚠ The field byte is checked, not just the shape.
     *
     * `aa 22 02 31 01` is ANC, and these arrive unsolicited as well as in answer.
     * Decoding one as auto-off would report the headphones as switching themselves
     * off in half an hour on the strength of a frame about noise cancelling.
     */
    @Test
    fun `a status frame for another field is not auto off`() {
        assertNull(JblAutoOff.state(bytes("aa22023101")))
        assertNull(JblAutoOff.state(bytes("aa220433")))
    }

    // ---- equaliser ---------------------------------------------------------

    /** 20:35:49 — the answer to `aa a2 02 01 ff`, as the headphones were found. */
    private val flat = bytes(JblFrames.FLAT)

    @Test
    fun `the flat curve decodes to the app's own axis`() {
        val c = JblEq.curve(flat)!!
        assertEquals(JBL_HZ, c.bands.map { it.hz })
        assertEquals(List(10) { 0f }, c.bands.map { it.gain })
        assertEquals(0, c.table)
    }

    @Test
    fun `jazz decodes to the gains the app drew`() {
        val c = JblEq.curve(bytes(JblFrames.JAZZ_ECHO))!!
        assertEquals(
            listOf(4f, 2f, 1f, 2.5f, -1.5f, -1.5f, 0f, 1f, 2f, 4f),
            c.bands.map { it.gain },
        )
        assertEquals(1, c.table)
    }

    /**
     * ✅ **The whole point of building a write from the frame that was read.**
     *
     * Given the flat curve the device reported, asking for the JAZZ gains produces
     * the vendor app's frame **byte for byte** — including the thirteen bytes nobody
     * here can explain and the trailing `01` the length byte does not count. That is
     * the strongest evidence available that the encoder is right: it was not written
     * to match the app's frame, and it matches the app's frame.
     */
    @Test
    fun `a write is byte-identical to the vendor app's`() {
        val jazz = JBL_CURVES.first { it.first == "Jazz" }.second
        assertEquals(
            JblFrames.JAZZ_SENT,
            hex(JblEq.set(flat, jazz.table, jazz.bands.map { it.gain })!!),
        )
    }

    /**
     * The restore path: flat written back over jazz is the frame the device started
     * on, from the table byte onward. ⚠ Only the operator differs, `00` for a write
     * where the read carried `02`, which is the one byte a write must change.
     */
    @Test
    fun `writing flat back rebuilds the frame the device was found in`() {
        val built = hex(JblEq.set(bytes(JblFrames.JAZZ_ECHO), 0, List(10) { 0f })!!)
        assertEquals(JblFrames.FLAT.drop(10), built.drop(10))
        assertEquals("00", built.substring(8, 10))
    }

    /**
     * ⚠ **A template must be something the device SAID, not something we sent.**
     *
     * [JblFrames.JAZZ_SENT] is a perfectly well-formed curve frame that differs from
     * the echo only in its operator byte, and building from it is refused. That looks
     * pedantic until it is the reason a write is never composed from another write:
     * the chain would then carry an operator nobody read back, and the one thing this
     * encoder promises is that every byte it does not understand came off the wire.
     */
    @Test
    fun `a frame we sent is not a template for the next one`() {
        assertNull(JblEq.curve(bytes(JblFrames.JAZZ_SENT)))
        assertNull(JblEq.set(bytes(JblFrames.JAZZ_SENT), 0, List(10) { 0f }))
    }

    /**
     * ⚠ **The 196-byte `c9` table is not a user curve**, and it is LONGER than one.
     *
     * A guard that only checked the array was big enough would pass this and decode
     * ten of someone else's records as the equaliser — a plausible-looking answer to
     * a question about something else, which is the failure this repo keeps meeting.
     * The length byte is what separates them.
     */
    @Test
    fun `another aa a2 table is refused`() {
        assertNotNull(JblEq.curve(flat))
        assertNull(JblEq.curve(bytes(JblFrames.TABLE_C9)))
    }

    @Test
    fun `a curve is not built from the wrong number of gains`() {
        assertNull(JblEq.set(flat, 0, List(5) { 0f }))
        assertNull(JblEq.set(bytes("aa22023101"), 0, List(10) { 0f }))
    }

    /** The named curves are the two whose bytes exist, and they are those bytes. */
    @Test
    fun `the offered curves are the captured ones`() {
        assertEquals(listOf("Flat", "Jazz"), JBL_CURVES.map { it.first })
        assertEquals(JblEq.curve(flat), JBL_CURVES.first().second)
        assertEquals(JblEq.curve(bytes(JblFrames.JAZZ_ECHO)), JBL_CURVES[1].second)
    }

    // ---- max volume limiter -------------------------------------------------

    /**
     * 22:14:26 — from the vendor app's OWN connect sweep, never driven by us.
     *
     * ⚠ **Both payload bytes are `01`, so this frame alone cannot say which is the
     * status.** The offset comes from the SDK's `SafeSoundCmd`, calibrated against
     * `SpeakToChatCmd` whose two offsets match Smart Talk's driven values. Reading a
     * single agreeing byte as a mapping is what made `38` look like Auto Play & Pause
     * earlier the same day; this is that mistake refused a second time.
     */
    @Test
    fun `the volume limiter is read, and its offset is the SDK's`() {
        assertEquals(true, JblSafeSound.state(bytes("aaa503020101")))
        assertEquals(false, JblSafeSound.state(bytes("aaa503020100")))
        assertEquals("aaa50101", hex(JblSafeSound.get()))
    }

    /** ⚠ There is no writer, and that is the design — see [JblSafeSound]. */
    @Test
    fun `a foreign frame is not a volume limit`() {
        assertNull(JblSafeSound.state(bytes("aaa00702010002640300")))
        assertNull(JblSafeSound.state(bytes("aaa50302")))
    }

    private fun bytes(s: String) = Hex.parse(s)

    private fun hex(b: ByteArray) = Hex.format(b).replace(" ", "")
}
