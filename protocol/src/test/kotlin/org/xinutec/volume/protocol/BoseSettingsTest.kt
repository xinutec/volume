package org.xinutec.volume.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    // ---- through the driver ------------------------------------------------

    /**
     * 11:25:33 end to end — the three writes Bose Music sent for Bass Boost, and the
     * three statuses it drew, in order. ⚠ The first two replies still read flat; only
     * the last carries the change, which is why the driver takes the last one.
     */
    @Test
    fun `the driver walks the three bands and reads the state off the last reply`() {
        val t =
            Replay(
                "01 07 02 02 00 02" to "01 07 03 0c f6 0a 00 00 f6 0a 00 01 f6 0a 00 02",
                "01 07 02 02 00 01" to "01 07 03 0c f6 0a 00 00 f6 0a 00 01 f6 0a 00 02",
                "01 07 02 02 08 00" to "01 07 03 0c f6 0a 08 00 f6 0a 00 01 f6 0a 00 02",
            )
        assertEquals(BoseEq.BASS_BOOST, Drivers.BoseQc45.writeEq(t, BoseEq.BASS_BOOST))
        t.assertDrained()
    }

    /** The 2026-08-15 sweep's own reply to `01 07`, with the EQ flat. */
    @Test
    fun `the driver reads the EQ`() {
        val t = Replay("01 07 01 00" to "01 07 03 0c f6 0a 00 00 f6 0a 00 01 f6 0a 00 02")
        assertEquals(BoseEq.FLAT, Drivers.BoseQc45.readEq(t))
    }

    /**
     * 11:26:47's status, which is also what the 2026-08-15 sweep saw at rest — the
     * shortcut was on Hear Battery Level both days.
     */
    @Test
    fun `the driver reads the action button`() {
        val t = Replay("01 09 01 00" to "01 09 03 0b 80 09 03 00 01 40 08 00 00 00 80")
        assertEquals(BoseButton.Action.HEAR_BATTERY_LEVEL, Drivers.BoseQc45.readButton(t))
    }

    /** 11:26:38, and the status it drew. */
    @Test
    fun `the driver sets the action button and reads the action back`() {
        val t =
            Replay(
                "01 09 02 03 80 09 10" to "01 09 03 0b 80 09 10 00 01 40 08 00 00 00 80",
            )
        assertEquals(
            BoseButton.Action.SPOTIFY,
            Drivers.BoseQc45.writeButton(t, BoseButton.Action.SPOTIFY),
        )
    }

    // ---- Framing -----------------------------------------------------------

    @Test
    fun `encode writes the length from the payload`() {
        assertArrayEquals(
            bytes("010702020800"),
            BoseFrame.encode(0x01, 0x07, BoseFrame.SET_GET, bytes("0800")),
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

/**
 * The QC35's settings block, all of it read by one `01 01` GET_ALL.
 *
 * ⚠ **The bytes here are the ones the device actually sent**, captured from Bose
 * Connect's own connect on 2026-08-26 and reproduced from this repo's probe. The values
 * are labelled by the vendor app's screens, not by this repo — which is the discipline
 * the QC35's ANC table lacked when all three of its mode bytes turned out inverted.
 */
class BoseAllSettingsTest {
    /** Exactly what `01 01 05 00` drew back from the headphones. */
    private val getAll =
        Hex.parse(
            "01 01 07 00 " +
                "01 02 03 12 00 50 69 70 70 69 6a 6e 20 42 6f 73 65 20 51 43 33 35 " +
                "01 03 03 05 a1 00 04 cf de " +
                "01 04 03 01 3c " +
                "01 06 03 02 01 0b " +
                "01 09 03 04 10 04 02 07 " +
                "01 0b 03 03 01 02 0f " +
                "01 01 06 00",
        )

    @Test
    fun `the reply is seven frames, not one`() {
        // The whole reason BoseFrame.frames exists. A single-frame decoder sees the
        // Processing header and nothing else.
        val frames = BoseFrame.frames(getAll)
        assertEquals(8, frames.size)
        assertEquals(BoseFrame.PROCESSING, frames.first()[2])
        assertEquals(BoseFrame.RESULT, frames.last()[2])
    }

    @Test
    fun `every setting comes out of the one exchange`() {
        val all = BoseAllSettings.state(getAll)
        assertEquals(BoseStandby(60), all?.standby)
        assertEquals(SidetoneLevel.MEDIUM, all?.sidetone)
        assertEquals(true, all?.voicePrompts)
    }

    @Test
    fun `a truncated buffer yields the frames it really holds, not a guess`() {
        // ⚠ The walk stops at a frame that runs past the buffer rather than hunting for
        // the next plausible header — inventing frames out of payload bytes is how a
        // splitter turns a short read into confident nonsense.
        val cut = getAll.copyOfRange(0, 20)
        val frames = BoseFrame.frames(cut)
        assertTrue(frames.size < 8)
        for (f in frames) assertTrue(f.size >= 4)
    }

    @Test
    fun `standby is unsigned, because three hours does not fit a signed byte`() {
        // b4 is 180; read as a Kotlin Byte it is -76.
        assertEquals(BoseStandby(180), BoseStandbyTimer.state(Hex.parse("b4")))
        assertEquals(BoseStandby(0), BoseStandbyTimer.state(Hex.parse("00")))
        assertEquals(true, BoseStandbyTimer.state(Hex.parse("00"))?.never)
    }

    @Test
    fun `standby refuses the auto-power-down shape rather than misreading it`() {
        // ⚠ Payload of 2+ with bit 0 of [1] set is a BOOLEAN in Bose Connect's parser,
        // not a duration. Taking [0] regardless would report this as a 1-minute timer.
        assertNull(BoseStandbyTimer.state(Hex.parse("01 01")))
        // …and the ordinary two-byte case without that bit is still a duration.
        assertEquals(BoseStandby(1), BoseStandbyTimer.state(Hex.parse("01 00")))
    }

    @Test
    fun `self voice reads the level byte, not the persist flag`() {
        // Both driven from the vendor app: Medium then Low, with byte 0 unchanged.
        assertEquals(SidetoneLevel.MEDIUM, BoseSidetone.level(Hex.parse("01 02 0f")))
        assertEquals(SidetoneLevel.LOW, BoseSidetone.level(Hex.parse("01 03 0f")))
        // Reading byte 0 would call the first of those HIGH.
        assertNotEquals(SidetoneLevel.HIGH, BoseSidetone.level(Hex.parse("01 02 0f")))
    }

    @Test
    fun `voice prompts is bit 5, and the language shares the byte`() {
        // a1 = on, US English. The language occupies the low five bits, so a decoder
        // that tested the whole byte for zero would call every language "on".
        assertEquals(true, BoseVoicePrompts.enabled(Hex.parse("a1")))
        assertEquals(false, BoseVoicePrompts.enabled(Hex.parse("81")))
    }

    @Test
    fun `the standby write names the value in a payload, not in the length`() {
        // ⚠ `01 04 02 14` is operator 02 with a LENGTH of 0x14 and no payload; the
        // device answers 04 01 01 bad argument. Hit for real on 2026-08-25.
        assertEquals("01 04 02 01 14", Hex.format(BoseStandbyTimer.set(20)))
        assertEquals("01 04 02 01 00", Hex.format(BoseStandbyTimer.set(0)))
    }
}

/** Battery and the prompt language — the two rows the card was missing. */
class BoseBatteryAndLanguageTest {
    @Test
    fun `the QC35 reports a level and says nothing about charging`() {
        // ⚠ null, not false. `02 05` CHARGER_DETECT is function-not-supported on this
        // device, so there is no charging state to report and inventing one would put a
        // reading on the card that the headphones never sent.
        val b = BoseBattery.state(Hex.parse("02 02 03 01 64"))
        assertEquals(100, b?.percent)
        assertNull(b?.charging)
    }

    @Test
    fun `a level above 100 is refused rather than drawn`() {
        // The byte is whatever arrives; 0xff is what an absent reading looks like on the
        // QC45's own battery frame, and a 255% battery is worse than none.
        assertNull(BoseBattery.state(Hex.parse("02 02 03 01 ff")))
    }

    @Test
    fun `battery is found inside a batched reply, not just at the front`() {
        // ⚠ The whole reason for the splitter: this arrived glued behind another frame.
        val b = BoseBattery.state(Hex.parse("01 01 06 00 02 02 03 01 64"))
        assertEquals(100, b?.percent)
    }

    @Test
    fun `the language is the low five bits, and UK is one bit from US`() {
        // a1 is what this unit sends; Bose Connect renders it "English (U.S.)".
        assertEquals(
            BoseVoicePromptLanguage.US_ENGLISH,
            BoseVoicePromptLanguage.of(Hex.parse("a1")),
        )
        // ⚠ a0 is UK English — the neighbouring value, and the one an off-by-one lands on.
        assertEquals(
            BoseVoicePromptLanguage.UK_ENGLISH,
            BoseVoicePromptLanguage.of(Hex.parse("a0")),
        )
        // The enum stops at 21; five bits can hold more than the table knows.
        assertNull(BoseVoicePromptLanguage.of(Hex.parse("bf")))
    }

    @Test
    fun `the language comes out of GET_ALL alongside the switch`() {
        val all = BoseAllSettings.state(Hex.parse("01 03 03 05 a1 00 04 cf de"))
        assertEquals(true, all?.voicePrompts)
        assertEquals(BoseVoicePromptLanguage.US_ENGLISH, all?.promptLanguage)
    }
}
