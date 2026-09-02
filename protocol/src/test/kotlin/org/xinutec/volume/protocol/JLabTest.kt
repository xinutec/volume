package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JLab's own protocol, against bytes the earbuds actually sent.
 *
 * Every reply fixture here is a whole RFCOMM payload from the four captures of
 * 2026-09-01, copied off the wire rather than written to match the code — the
 * distinction that `docs/captures.md` exists to enforce. The request fixtures are this
 * repo's own frames, checked byte for byte against what `com.jlab.app` sent.
 */
class JLabTest {
    private fun bytes(hex: String) =
        hex
            .trim()
            .split(" ")
            .map { it.toInt(16).toByte() }
            .toByteArray()

    // ---- battery ---------------------------------------------------------------
    // ✅ Three readings from ONE capture, in order. They are the warrant for both the
    // offsets and the plain-percent scale: two cells cannot drift apart by accident.

    private val battery90x90 = "00 ff 01 31 01 00 5a 5a 04 00 02 00 00 ea"
    private val battery90x80 = "00 ff 01 31 01 00 5a 50 04 00 02 00 00 e0"
    private val battery80x80 = "00 ff 01 31 01 00 50 50 04 00 02 00 00 d6"

    /** ⚠ The same nine-byte body under `b1 = 03`, which is why `b1` is not a length. */
    private val batteryB1Is3 = "00 ff 01 31 03 00 5a 5a 04 00 02 00 00 ec"

    /**
     * ✅ **Left is byte 6, and this is the reading that settled it** — 70/60, taken
     * beside the vendor app's own two icons, whose `L` fill measured 34 px against `R`'s
     * 30. Kept as a fixture because the evidence for the order is a render this test
     * cannot hold: if [BudBattery.left] ever swaps, the number here stops matching the
     * screenshot the decision was made from.
     */
    private val battery70x60 = "00 ff 01 31 01 00 46 3c 04 00 02 00 00 b8"

    @Test
    fun `left is the earlier byte, as the vendor app's own icons showed`() {
        val b = JLabBattery.state(bytes(battery70x60))!!
        assertEquals(70, b.left.percent)
        assertEquals(60, b.right.percent)
    }

    @Test
    fun `battery reads both cells`() {
        val b = JLabBattery.state(bytes(battery90x80))
        assertNotNull(b)
        assertEquals(90, b!!.left.percent)
        assertEquals(80, b.right.percent)
    }

    @Test
    fun `battery follows the pair as they drift apart and back together`() {
        val seen =
            listOf(battery90x90, battery90x80, battery80x80).map {
                val b = JLabBattery.state(bytes(it))!!
                b.left.percent to b.right.percent
            }
        assertEquals(listOf(90 to 90, 90 to 80, 80 to 80), seen)
    }

    /**
     * ⚠ **The guard on the whole framing claim.** If anything here ever starts reading
     * the byte after the command as a length, this fixture decodes differently from its
     * `b1 = 01` twin — and they carry identical battery levels on the wire.
     */
    @Test
    fun `the byte after the command is not a length`() {
        assertEquals(
            JLabBattery.state(bytes(battery90x90))!!.left.percent,
            JLabBattery.state(bytes(batteryB1Is3))!!.left.percent,
        )
    }

    @Test
    fun `charging is unknown rather than false`() {
        val b = JLabBattery.state(bytes(battery90x90))!!
        assertNull(b.left.charging)
        assertNull(b.right.charging)
    }

    // ---- spatial audio ---------------------------------------------------------
    // ✅ These two are the ABLATION that named `76`: the same cold enumeration taken
    // with the feature on and off, differing in one byte of one frame.

    private val spatialOn = "00 ff 01 76 01 00 01 00 00 6b"
    private val spatialOff = "00 ff 01 76 01 00 00 00 00 6b"

    /** The `74` write's own replies, which echo — unlike `53`'s, three fixtures below. */
    private val spatialAckOn = "00 ff 01 75 01 00 01 00 00 6b"
    private val spatialAckOff = "00 ff 01 75 01 00 00 00 00 6b"

    @Test
    fun `spatial audio reads both ways`() {
        assertEquals(true, JLabSpatial.state(bytes(spatialOn)))
        assertEquals(false, JLabSpatial.state(bytes(spatialOff)))
    }

    /** ⚠ `76` answers with its OWN id; a decoder expecting `77` reads nothing. */
    @Test
    fun `spatial audio reply keeps the request id`() {
        assertEquals(JLabSpatial.ASK, bytes(spatialOn)[3])
    }

    @Test
    fun `spatial audio writes the frame the vendor app sent`() {
        assertEquals("c0 ff 00 74 01 00 01 01 00 36", JLabSpatial.set(true).bytes.hex())
        assertEquals("c0 ff 00 74 01 00 00 01 00 35", JLabSpatial.set(false).bytes.hex())
    }

    /**
     * ⚠⚠ **The regression this shipped once.** These are the two payloads exactly as the
     * earbuds sent them on 2026-09-01, in order: `44`'s answer arrived 20 ms after its
     * own window closed, so the NEXT read — `76` — was handed both. A decoder testing
     * offset 3 sees `45`, returns null, and the card silently loses its Spatial Audio row
     * while reporting the ANC read as a link failure. The device was answering both.
     */
    @Test
    fun `a reply is found behind the previous read's late answer`() {
        val misaligned = bytes("00 ff 01 45 03 00 01 04 04 00 4f 00 " + spatialOn)
        assertEquals(true, JLabSpatial.state(misaligned))
    }

    /** ⚠ And the battery broadcast, which arrives every ten seconds unasked. */
    @Test
    fun `a reply is found behind an unsolicited battery broadcast`() {
        val misaligned = bytes("$battery80x80 ${spatialOff.substring(0)}")
        assertEquals(false, JLabSpatial.state(misaligned))
    }

    /** ⚠ Absent is still absent — scanning must not invent a match. */
    @Test
    fun `a buffer without the wanted reply still reads null`() {
        assertNull(JLabSpatial.state(bytes(battery80x80)))
        assertNull(JLabEq.state(bytes(battery80x80)))
    }

    // ---- spatial mode ----------------------------------------------------------

    private val modeMusic = "00 ff 01 51 02 00 00 00 00 00 51"

    @Test
    fun `spatial mode reads Music`() {
        assertEquals(SpatialMode.MUSIC, JLabSpatialMode.state(bytes(modeMusic)))
    }

    @Test
    fun `spatial mode writes the frames the vendor app sent`() {
        assertEquals(
            "c0 ff 00 52 01 00 00 01 00 13",
            JLabSpatialMode.set(SpatialMode.MUSIC)!!.bytes.hex(),
        )
        assertEquals(
            "c0 ff 00 52 01 00 01 01 00 14",
            JLabSpatialMode.set(SpatialMode.MOVIE)!!.bytes.hex(),
        )
    }

    /**
     * ⚠ **GAME is refused, not coerced.** The device's app offers two tiles; returning
     * a Music frame for Game would report success for a mode it was never put into.
     */
    @Test
    fun `spatial mode refuses a mode this device does not have`() {
        assertNull(JLabSpatialMode.set(SpatialMode.GAME))
        assertNull(JLabSpatialMode.of(0x02))
    }

    /** ⚠ Not [SpatialMode]'s own wire values — the JBL numbers the same idea from 1. */
    @Test
    fun `spatial mode does not share the JBL table`() {
        assertEquals(SpatialMode.MUSIC, JLabSpatialMode.of(0x00))
        assertEquals(SpatialMode.MOVIE, JLabSpatialMode.of(0x01))
        assertEquals(0x01.toByte(), SpatialMode.MUSIC.wire)
    }

    // ---- safe hearing ----------------------------------------------------------
    // ✅ The two cold enumerations that name it: `Default` on 2026-09-01 09:47 and again
    // at 10:12, then `85 dB Limit` at 11:21. One variable, one byte.

    private val safeHearingDefault = "00 ff 01 67 01 00 00 00 00 66"
    private val safeHearing85 = "00 ff 01 67 01 00 02 00 00 68"

    @Test
    fun `safe hearing reads the two levels it was measured at`() {
        assertEquals(
            JLabSafeHearing.Level.DEFAULT,
            JLabSafeHearing.state(bytes(safeHearingDefault)),
        )
        assertEquals(JLabSafeHearing.Level.DB_85, JLabSafeHearing.state(bytes(safeHearing85)))
    }

    /**
     * ⚠⚠ **The direction, asserted so a refactor cannot quietly invert it.** `02` is the
     * MOST protective setting and `00` the least, so anything treating these numbers as
     * loudness has the control backwards — on the one row where backwards is harmful.
     */
    @Test
    fun `a higher safe hearing value is a lower ceiling`() {
        assertTrue(
            JLabSafeHearing.Level.DB_85.wire > JLabSafeHearing.Level.DEFAULT.wire,
        )
        assertEquals(
            listOf(
                JLabSafeHearing.Level.DEFAULT,
                JLabSafeHearing.Level.DB_95,
                JLabSafeHearing.Level.DB_85,
            ),
            JLabSafeHearing.Level.entries.toList(),
        )
    }

    /**
     * ✅ **The three writes exactly as the vendor app sent them**, 2026-09-01.
     *
     * ⚠ This test replaced one asserting that no writer existed. That guard did its job:
     * shipping read-only first meant adding the writer was a decision Pippijn took
     * explicitly, rather than something that appeared in a refactor.
     */
    @Test
    fun `safe hearing writes the frames the vendor app sent`() {
        assertEquals(
            "c0 ff 00 68 01 00 00 01 00 29",
            JLabSafeHearing.set(JLabSafeHearing.Level.DEFAULT).bytes.hex(),
        )
        assertEquals(
            "c0 ff 00 68 01 00 01 01 00 2a",
            JLabSafeHearing.set(JLabSafeHearing.Level.DB_95).bytes.hex(),
        )
        assertEquals(
            "c0 ff 00 68 01 00 02 01 00 2b",
            JLabSafeHearing.set(JLabSafeHearing.Level.DB_85).bytes.hex(),
        )
    }

    // ---- equaliser -------------------------------------------------------------

    private val eqCurrent = "00 ff 01 49 0b 00 03 78 78 5a 78 78 78 5a 78 78 78 06 00 d8"
    private val eqPresets =
        "00 ff 01 71 1e 00 " +
            "78 78 78 78 78 78 78 78 78 78 " +
            "78 78 78 78 78 78 78 78 78 78 " +
            "78 78 78 78 78 78 78 78 78 78 " +
            "78 78 5a 78 78 78 5a 78 78 78 00 00 7c"

    @Test
    fun `eq reads the live curve and its preset`() {
        val c = JLabEq.state(bytes(eqCurrent))
        assertNotNull(c)
        assertEquals(3, c!!.preset)
        assertEquals(listOf(120, 120, 90, 120, 120, 120, 90, 120, 120, 120), c.levels)
    }

    /**
     * ✅ **The corroboration that makes the preset index a decode.** `49` says preset 3,
     * and preset 3 inside `71` is byte-identical to `49`'s curve — while the vendor app
     * had **Custom**, the fourth of four, ticked.
     */
    @Test
    fun `the live curve is the preset the device names`() {
        val current = JLabEq.state(bytes(eqCurrent))!!
        val all = JLabEq.allPresets(bytes(eqPresets))
        assertNotNull(all)
        assertEquals(JLabEq.PRESETS, all!!.size)
        assertEquals(current.levels, all[current.preset])
    }

    /**
     * ⚠ The other three are flat, which is why selecting one RAISES two bands.
     *
     * ✅ **Corroborated against the earbuds on 2026-09-02**, not just this capture: `71`
     * was read live beside two preset writes and came back `[flat, flat, flat, cut]` both
     * times — so the fixture still describes the device, and a preset write does not
     * rewrite the stored curves.
     */
    @Test
    fun `the three stored presets are flat`() {
        val all = JLabEq.allPresets(bytes(eqPresets))!!
        for (p in 0 until 3) assertTrue(all[p].all { it == 120 })
    }

    @Test
    fun `eq names one frequency per band`() {
        assertEquals(JLabEq.BANDS, JLabEq.HZ.size)
    }

    // ---- the writer ------------------------------------------------------------
    // ⚠ **The write had no test at all until 2026-09-02**, while shipping and being
    // driven against the earbuds. These pin the bytes; what the DEVICE does with them
    // is a separate question and [JLabEq] carries the answer.

    /**
     * ✅ **The captured frame, reproduced byte for byte.** Taken in the safe direction —
     * one band dragged DOWN in the vendor app, 16k from `78` to `50` — which is the only
     * `4a` anybody has watched `com.jlab.app` send.
     */
    @Test
    fun `the write reproduces the captured band drag`() {
        val levels = listOf(0x78, 0x78, 0x5a, 0x78, 0x78, 0x78, 0x5a, 0x78, 0x78, 0x50)
        val f = JLabEq.set(preset = 3, levels = levels)
        assertNotNull(f)
        assertEquals(
            "c0 ff 00 4a 0b 00 03 78 78 5a 78 78 78 5a 78 78 50 01 00 64",
            f!!.toString(),
        )
    }

    /**
     * ⚠⚠ **What a `eq 1` tap actually puts on the wire**, and the frame behind the
     * 2026-09-02 finding: the card sends slot 0's OWN contents, which [eqPresets] shows
     * are flat, and the device answered with the CUT curve still in place. So this frame
     * is established and the device's response to it is not — see [JLabEq].
     */
    @Test
    fun `selecting eq 1 sends slot zero's flat curve`() {
        val f = JLabEq.set(preset = 0, levels = List(JLabEq.BANDS) { 0x78 })
        assertNotNull(f)
        assertEquals(
            "c0 ff 00 4a 0b 00 00 78 78 78 78 78 78 78 78 78 78 01 00 c5",
            f!!.toString(),
        )
    }

    /**
     * ⚠ A short curve is REFUSED rather than padded — padding would write whatever
     * followed it into somebody's treble.
     */
    @Test
    fun `a curve that is not ten bands is refused`() {
        assertNull(JLabEq.set(preset = 0, levels = List(JLabEq.BANDS - 1) { 0x78 }))
        assertNull(JLabEq.set(preset = 0, levels = List(JLabEq.BANDS + 1) { 0x78 }))
        assertNull(JLabEq.set(preset = 0, levels = List(JLabEq.BANDS) { 0x100 }))
    }

    // ---- touch map -------------------------------------------------------------

    private val touch =
        "00 ff 01 4d 1b 00 " +
            "01 01 03 01 02 02 01 03 01 01 04 0b 01 05 08 01 06 07 " +
            "02 01 03 02 02 02 02 03 01 02 04 0b 02 05 08 02 06 07 05 00 e2"

    @Test
    fun `touch map decodes twelve entries`() {
        val m = JLabTouch.state(bytes(touch))
        assertNotNull(m)
        assertEquals(JLabTouch.ENTRIES, m!!.size)
    }

    /**
     * ✅ Row for row against the vendor app's own Touch Controls screen, which is the
     * whole warrant for the action table.
     */
    @Test
    fun `touch map matches the screen it was read beside`() {
        val m = JLabTouch.state(bytes(touch))!!
        val expected =
            mapOf(
                JLabTouch.Tap.ONE_TAP to JLabTouch.Action.PLAY_PAUSE,
                JLabTouch.Tap.TWO_TAPS to JLabTouch.Action.NEXT_TRACK,
                JLabTouch.Tap.THREE_TAPS to JLabTouch.Action.LAST_TRACK,
                JLabTouch.Tap.LONG_PRESS to JLabTouch.Action.NOISE_CONTROL,
                JLabTouch.Tap.SWIPE_DOWN to JLabTouch.Action.VOLUME_DOWN,
                JLabTouch.Tap.SWIPE_UP to JLabTouch.Action.VOLUME_UP,
            )
        for (side in JLabTouch.Side.entries) {
            for ((tap, action) in expected) assertEquals(action, m[side to tap])
        }
    }

    /**
     * ⚠ **Both sides carry the same map, which is why neither is called left or right.**
     * This asserts the sameness rather than an assignment: if a future capture ever
     * differs between sides, this fails and the naming question becomes answerable.
     */
    @Test
    fun `the two sides are indistinguishable in every capture so far`() {
        val m = JLabTouch.state(bytes(touch))!!
        for (tap in JLabTouch.Tap.entries) {
            assertEquals(m[JLabTouch.Side.FIRST to tap], m[JLabTouch.Side.SECOND to tap])
        }
    }

    /** ⚠ An unknown action drops its entry, not the map. */
    @Test
    fun `an unrecognised action costs one entry`() {
        val corrupted = bytes(touch).copyOf().also { it[8] = 0x7f }
        assertEquals(JLabTouch.ENTRIES - 1, JLabTouch.state(corrupted)!!.size)
    }

    // ---- framing ---------------------------------------------------------------

    @Test
    fun `every read is the frame the vendor app sent`() {
        assertEquals("c0 ff 00 30 00 00 01 00 f0", JLabBattery.get().bytes.hex())
        assertEquals("c0 ff 00 48 00 00 01 00 08", JLabEq.get().bytes.hex())
        assertEquals("c0 ff 00 4c 00 00 01 00 0c", JLabTouch.get().bytes.hex())
        assertEquals("c0 ff 00 50 00 00 01 00 10", JLabSpatialMode.get().bytes.hex())
        assertEquals("c0 ff 00 70 00 00 01 00 30", JLabEq.presets().bytes.hex())
        assertEquals("c0 ff 00 76 00 00 01 00 36", JLabSpatial.get().bytes.hex())
    }

    /**
     * ✅ **The request rule, which unlike the reply's is real** — and is needed, because
     * the app packs three requests into one payload. This is that payload, split by
     * walking forward until the sum matches.
     */
    @Test
    fun `the request checksum splits a packed payload`() {
        val packed =
            bytes(
                "c0 ff 00 4c 00 00 01 00 0c c0 ff 00 48 00 00 01 00 08 c0 ff 00 76 00 00 01 00 36",
            )
        val frames = mutableListOf<ByteArray>()
        var i = 0
        while (i < packed.size) {
            val end =
                (i + 5..packed.size).first { e ->
                    packed
                        .copyOfRange(i, e - 1)
                        .fold(0) { a, b -> a + (b.toInt() and 0xff) }
                        .toByte() == packed[e - 1]
                }
            frames += packed.copyOfRange(i, end)
            i = end
        }
        assertEquals(3, frames.size)
        assertEquals(listOf(0x4c.toByte(), 0x48.toByte(), 0x76.toByte()), frames.map { it[3] })
    }

    /**
     * ⚠⚠ **The strongest evidence that a reply's last byte is not a checksum at all**:
     * across `74`'s two writes, `75`'s two acks and `76`'s two states — six frames whose
     * payload byte takes both values — the trailing byte is `6b` every time. A checksum
     * over the content cannot be constant while the content changes.
     */
    @Test
    fun `the reply trailer does not vary with the payload`() {
        val tail =
            listOf(spatialOn, spatialOff, spatialAckOn, spatialAckOff).map { bytes(it).last() }
        assertEquals(listOf<Byte>(0x6b, 0x6b, 0x6b, 0x6b), tail)
    }

    /**
     * ⚠⚠ **The reply checksum has NO rule, and this records which way each command
     * falls** so nobody re-derives it. Seven close at Σ−2; five close at no offset over
     * any prefix. A commit on 2026-09-01 claimed the −2 held universally — it had been
     * checked frame-by-frame on a capture where the `31` broadcast repeats sixty times.
     */
    @Test
    fun `the reply checksum closes for some commands and not others`() {
        fun closes(hex: String): Boolean {
            val f = bytes(hex)
            return (5..f.size).any { e ->
                val sum = f.copyOfRange(0, e - 1).fold(0) { a, b -> a + (b.toInt() and 0xff) }
                ((sum - 2) and 0xff).toByte() == f[e - 1]
            }
        }
        assertTrue(closes(battery90x90))
        assertTrue(closes(modeMusic))
        assertTrue(!closes(spatialOn))
        assertTrue(!closes(eqCurrent))
        assertTrue(!closes(touch))
    }

    private fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }
}
