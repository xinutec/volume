package org.xinutec.volume.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠ **BD_ADDRs in these fixtures are REDACTED to `aa bb cc dd ee ff`.** This repo is
 * public and the address that appeared here was the phone's own — a device that travels
 * with its owner. Every other byte is as captured; the substitution is confined to the
 * six address bytes, which no decode under test looks at.
 *
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

    // ---- volume, 05 05 ------------------------------------------------------

    /**
     * ✅ **Both bytes agree with Android on the QC35, which is what settles the order.**
     * `dumpsys audio` reported `Max: 25` and `bt_a2dp: 18` for `STREAM_MUSIC` at the
     * moment this frame was read, 2026-09-03. One matching byte would be luck.
     */
    @Test
    fun `volume reads the scale and the level, in that order`() {
        val v = BoseVolume.state(bytes("0505 03 02 19 12"))
        assertNotNull(v)
        assertEquals(25, v!!.steps)
        assertEquals(18, v.level)
    }

    /** ⚠ The Revolve counts to 100, so a caller rendering [BoseLoudness.level] as a percent is
     * right on one device by accident and wrong on the other. */
    @Test
    fun `the revolve is on a different scale entirely`() {
        val v = BoseVolume.state(bytes("0505 03 02 64 24"))
        assertNotNull(v)
        assertEquals(100, v!!.steps)
        assertEquals(36, v.level)
    }

    /** ⚠ A level above its own maximum is a misread frame, not a quiet device. */
    @Test
    fun `a level above the scale is refused`() {
        assertNull(BoseVolume.state(bytes("0505 03 02 12 19")))
        assertNull(BoseVolume.state(bytes("0505 03 02 00 00")))
    }

    /**
     * ⚠⚠ **THE ABSENCE OF A VOLUME WRITER IS A DECISION AND THIS TEST IS ITS RECORD.**
     * The rule is that a volume is never raised above where it was found, so it ships
     * read-only first and adding a writer has to be a deliberate act rather than a
     * refactor — exactly how `JLabSafeHearing` was handled before Pippijn asked for it.
     * ⚠ If this test is deleted, say who asked and when, in the commit.
     */
    @Test
    fun `there is no volume writer`() {
        val writers =
            BoseVolume::class.java.methods
                .map { it.name }
                .filter { it.startsWith("set") || it == "write" }
        assertTrue("BoseVolume grew a writer: $writers", writers.isEmpty())
    }

    // ---- EQ ----------------------------------------------------------------

    /** 11:25:33, the three frames Bose Music sent for its "Bass Boost" button. */
    @Test
    fun `bass boost is the three writes the vendor app sent`() {
        assertEquals(
            listOf("010702020002", "010702020001", "010702020800"),
            BoseEq.setAll(BoseEq.BASS_BOOST).map { hex(it.bytes) },
        )
    }

    /** 11:25:41. ⚠ Treble Boost is +6 where Bass Boost is +8; that is what was sent. */
    @Test
    fun `treble boost is plus six, and it is not symmetric with bass boost`() {
        assertEquals(
            listOf("010702020602", "010702020001", "010702020000"),
            BoseEq.setAll(BoseEq.TREBLE_BOOST).map { hex(it.bytes) },
        )
    }

    /** 11:25:49 — "Reset" is three zeroes, not a command of its own. */
    @Test
    fun `reset is flat`() {
        assertEquals(
            listOf("010702020002", "010702020001", "010702020000"),
            BoseEq.setAll(BoseEq.FLAT).map { hex(it.bytes) },
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
        assertEquals("010a0100", hex(BoseMultipoint.get().bytes))
        assertEquals("010a020101", hex(BoseMultipoint.set(on = true).bytes))
        assertEquals("010a020100", hex(BoseMultipoint.set(on = false).bytes))
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
        assertEquals(false, BoseMultipoint.state(bytes("010a0301060404030701aabbccddeeff")))
    }

    // ---- Action button -----------------------------------------------------

    /** 11:26:38 and 11:26:47. */
    @Test
    fun `the action button shortcut is set by its code`() {
        assertEquals("01090203800910", hex(BoseButton.set(BoseButton.Action.SPOTIFY).bytes))
        assertEquals(
            "01090203800903",
            hex(BoseButton.set(BoseButton.Action.HEAR_BATTERY_LEVEL).bytes),
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
            BoseFrame.encode(0x01, 0x07, BoseFrame.SET_GET, bytes("0800")).bytes,
        )
        assertArrayEquals(bytes("010a0100"), BoseFrame.encode(0x01, 0x0a, BoseFrame.GET).bytes)
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
        assertEquals("01 04 02 01 14", Hex.format(BoseStandbyTimer.set(20).bytes))
        assertEquals("01 04 02 01 00", Hex.format(BoseStandbyTimer.set(0).bytes))
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

/**
 * The writes, against the frames Bose Connect actually sent.
 *
 * ⚠ **Captured, not derived.** Before 2026-08-26 this repo's stated expectation was that
 * `01 0b` takes a one-byte payload "by the shape of every other setting on this block".
 * It takes two. Shipping that guess would have sent a malformed frame and read the
 * device's refusal as a fact about what it permits.
 */
class BoseWritesTest {
    @Test
    fun `self voice sends persist AND level, which is two payload bytes`() {
        // 01 0b 02 02 01 03 — exactly what the vendor app sent for Medium -> Low.
        assertEquals(
            "01 0b 02 02 01 03",
            Hex.format(BoseWrites.sidetone(0x01, SidetoneLevel.LOW).bytes),
        )
        assertEquals(
            "01 0b 02 02 01 02",
            Hex.format(BoseWrites.sidetone(0x01, SidetoneLevel.MEDIUM).bytes),
        )
    }

    @Test
    fun `the voice-prompt write carries the language in the same byte`() {
        // 21 = on, US English. 01 = off, US English. Both captured.
        assertEquals(
            "01 03 02 01 21",
            Hex.format(
                BoseWrites.voicePrompts(0x21, true, BoseVoicePromptLanguage.US_ENGLISH).bytes,
            ),
        )
        assertEquals(
            "01 03 02 01 01",
            Hex.format(
                BoseWrites.voicePrompts(0x21, false, BoseVoicePromptLanguage.US_ENGLISH).bytes,
            ),
        )
    }

    @Test
    fun `turning prompts on does not silently reset the language`() {
        // ⚠ The failure this guards: a write of a bare 0x20 for "on" is language 00,
        // UK English — one bit from the US English this unit uses, and inaudible to
        // anyone not listening for the accent.
        val french = BoseWrites.voicePrompts(0x00, true, BoseVoicePromptLanguage.FRENCH)
        assertEquals("01 03 02 01 22", Hex.format(french.bytes))
        assertNotEquals("01 03 02 01 20", Hex.format(french.bytes))
    }

    @Test
    fun `the undecoded high bits are carried rather than dropped`() {
        // ⚠ This test asserted the OPPOSITE until 2026-08-28 — that bit 7 is the
        // device's and is never written back — on the reasoning that the vendor app
        // writes 21 where the device reads a1. True of bit 7, which the device restores
        // by itself. Bit 6 it does not restore: a QC45 read e1 before any write to this
        // function and a1 after one, through a re-enable and a power cycle.
        //
        // ⚠ **This is not a proof that carrying the bit would have saved it.** That
        // experiment died with the bit — the same unit refuses to set bit 6 from zero.
        // What is asserted here is only that the write no longer discards a field whose
        // meaning nobody knows.
        assertEquals(
            "01 03 02 01 c1",
            Hex.format(
                BoseWrites
                    .voicePrompts(
                        0xe1.toByte(),
                        false,
                        BoseVoicePromptLanguage.US_ENGLISH,
                    ).bytes,
            ),
        )
        // ...and writing back the state it already holds is byte-for-byte a no-op, which
        // is what lets the driver skip the write entirely. See BoseVoicePrompts.set.
        assertEquals(
            "01 03 02 01 e1",
            Hex.format(
                BoseWrites
                    .voicePrompts(
                        0xe1.toByte(),
                        true,
                        BoseVoicePromptLanguage.US_ENGLISH,
                    ).bytes,
            ),
        )
    }

    @Test
    fun `the supported languages are the device's thirteen, not the enum's twenty-two`() {
        // 00 04 cf de, as this unit answers.
        val all = BoseAllSettings.state(Hex.parse("01 03 03 05 a1 00 04 cf de"))
        val got = all?.supportedLanguages.orEmpty()
        assertEquals(13, got.size)
        // ⚠ The pairs that look inseparable are not: US present, UK absent.
        assertTrue(BoseVoicePromptLanguage.US_ENGLISH in got)
        assertFalse(BoseVoicePromptLanguage.UK_ENGLISH in got)
        assertTrue(BoseVoicePromptLanguage.MEXICAN_SPANISH in got)
        assertFalse(BoseVoicePromptLanguage.EUROPEAN_SPANISH in got)
    }
}

/**
 * The paired list, and the byte that was written down as a count and is not one.
 *
 * ⚠ **Every fixture here is a real reply with the BD_ADDRs redacted** to `aa…` and
 * `dd…`; this repo is public and the two real addresses were the phone's and the Mac's.
 * Only the six address bytes are substituted.
 */
class BoseDevicesTest {
    @Test
    fun `one paired device makes a count and a bitmask indistinguishable`() {
        // This is the whole reason the wrong reading survived: with a single entry,
        // "count = 1" and "bit 0 set" are the same byte.
        val one = BoseDevices.state(Hex.parse("04 04 03 07 01 aa aa aa aa aa aa"))
        assertEquals(1, one.size)
        assertTrue(one[0].connected)
    }

    @Test
    fun `two devices prove byte zero is a bitmask, not a count`() {
        // ⚠ A count would read 02 here. It reads 03.
        val both =
            BoseDevices.state(Hex.parse("04 04 03 0d 03 aa aa aa aa aa aa dd dd dd dd dd dd"))
        assertEquals(2, both.size)
        assertTrue(both[0].connected)
        assertTrue(both[1].connected)
    }

    @Test
    fun `disconnecting one leaves it in the list and clears only its bit`() {
        // The measurement that settled it: same list, byte 0 falls 03 -> 01.
        val after =
            BoseDevices.state(Hex.parse("04 04 03 0d 01 aa aa aa aa aa aa dd dd dd dd dd dd"))
        assertEquals(2, after.size)
        assertTrue(after[0].connected)
        assertFalse(after[1].connected)
    }

    @Test
    fun `the name comes out of INFO past its three status bytes`() {
        // 04 05 reply: <6 address bytes> <3 status> <utf-8 name>
        val n =
            BoseDevices.name(
                Hex.parse("04 05 03 10 aa aa aa aa aa aa 03 02 03 50 69 78 65 6c 20 39"),
            )
        assertEquals("Pixel 9", n)
    }

    @Test
    fun `INFO is keyed by the address, which is what the frame carries`() {
        assertEquals(
            "04 05 01 06 aa aa aa aa aa aa",
            Hex.format(BoseDevices.info(Hex.parse("aa aa aa aa aa aa")).bytes),
        )
    }

    @Test
    fun `pairing mode is a START transaction and its reply is a RESULT`() {
        // ⚠ Captured from Bose Connect's CONNECT NEW. The Set-shaped guess would have
        // been 04 08 02 01 01.
        assertEquals("04 08 05 01 01", Hex.format(BosePairing.enter().bytes))
        // A RESULT, not a STATUS — asking for the wrong operator returns nothing.
        assertEquals(true, BosePairing.on(Hex.parse("04 08 06 02 01 01")))
        assertEquals(false, BosePairing.on(Hex.parse("04 08 06 02 00 01")))
    }
}

/**
 * Forgetting a device, and the refusal that makes it safe to offer at all.
 *
 * ⚠ **The danger is not a typo, it is the protocol**: `04 03` on a *connected* device
 * disconnects it too — the capture showed the headphones answering with unsolicited
 * `04 02` frames. So the phone this app talks over must never be a candidate, and the
 * check that guarantees it is "is this entry connected", which needs no knowledge of
 * which address is ours. Android hands out a fake adapter address, and whether `04 09`
 * names the SPP peer or the active audio device has never been tested — so the obvious
 * check is the one that cannot be made.
 */
class BoseForgetTest {
    private fun replay(vararg pairs: Pair<String, String>) = Replay(*pairs)

    @Test
    fun `the write is a START carrying the address`() {
        assertEquals(
            "04 03 05 06 dd dd dd dd dd dd",
            Hex.format(BoseForget.frame(Hex.parse("dd dd dd dd dd dd")).bytes),
        )
    }

    @Test
    fun `a connected device is refused, and nothing is sent`() {
        // Two entries, both connected (mask 03). The second is the target.
        val t =
            replay(
                "04 04 01 00" to "04 04 03 0d 03 aa aa aa aa aa aa dd dd dd dd dd dd",
                // ⚠ Length 0f = 6 address + 3 status + 6 name. Written as 10 first,
                // and BoseFrame.payload refused the frame rather than decoding a name
                // out of it — the length-vs-size check catching a bad FIXTURE, which is
                // the same guard that stops a real reply being read into the next frame.
                "04 05 01 06 dd dd dd dd dd dd" to
                    "04 05 03 0f dd dd dd dd dd dd 01 01 01 4c 61 70 74 6f 70",
            )
        val out = Drivers.BoseQc35.forget(t, "dd dd dd dd dd dd")
        assertEquals(Forget.Connected("Laptop"), out)
        // ⚠ The load-bearing assertion: no 04 03 left the app.
        assertTrue(t.sent.none { it.startsWith("04 03") })
    }

    @Test
    fun `a disconnected device is forgotten and confirmed by re-reading`() {
        // ⚠ THREE exchanges: list, write, list again. The third is the point — the
        // Result echo repeats the address it was handed whether or not the entry went,
        // so only a fresh list says anything.
        val t =
            replay(
                "04 04 01 00" to "04 04 03 0d 01 aa aa aa aa aa aa dd dd dd dd dd dd",
                "04 03 05 06 dd dd dd dd dd dd" to "04 03 06 06 dd dd dd dd dd dd",
                "04 04 01 00" to "04 04 03 07 01 aa aa aa aa aa aa",
            )
        assertEquals(Forget.Forgot, Drivers.BoseQc35.forget(t, "dd dd dd dd dd dd"))
        assertTrue(t.sent.any { it.startsWith("04 03") })
    }

    @Test
    fun `a device the headphones still list is reported, not assumed gone`() {
        // The device answered the write and kept the entry. Trusting the echo would
        // call this a success.
        val t =
            replay(
                "04 04 01 00" to "04 04 03 0d 01 aa aa aa aa aa aa dd dd dd dd dd dd",
                "04 03 05 06 dd dd dd dd dd dd" to "04 03 06 06 dd dd dd dd dd dd",
                "04 04 01 00" to "04 04 03 0d 01 aa aa aa aa aa aa dd dd dd dd dd dd",
            )
        assertEquals(Forget.StillThere, Drivers.BoseQc35.forget(t, "dd dd dd dd dd dd"))
    }

    @Test
    fun `an address that is not in the list is never sent`() {
        val t = replay("04 04 01 00" to "04 04 03 07 01 aa aa aa aa aa aa")
        assertEquals(Forget.Unverifiable, Drivers.BoseQc35.forget(t, "dd dd dd dd dd dd"))
        assertTrue(t.sent.none { it.startsWith("04 03") })
    }
}

/**
 * The rule that lets a read stop when the protocol is finished rather than when the
 * device has been quiet for 400 ms.
 *
 * ⚠ **Every case here is about NOT stopping too early.** Stopping late costs latency;
 * stopping early costs a truncated reply that decodes as a missing setting — which is
 * the failure #1154 is about, arriving by a different route.
 */
class BoseTerminatesTest {
    @Test
    fun `a GET ends at its STATUS`() {
        assertTrue(BoseFrame.terminates(Hex.parse("01 04 01 00"), Hex.parse("01 04 03 01 3c")))
    }

    @Test
    fun `a GET answered with RESULT ends too`() {
        // ⚠ 04 08 PAIRING_MODE answers a GET with 06 RESULT, not 03 STATUS. Measured on
        // the wire: with RESULT missing from the GET set, that one exchange kept timing
        // out at 418 ms while every other fell to ~13 ms.
        assertTrue(BoseFrame.terminates(Hex.parse("04 08 01 00"), Hex.parse("04 08 06 02 00 03")))
    }

    @Test
    fun `a GET also ends at an ERROR, because that is an answer`() {
        assertTrue(BoseFrame.terminates(Hex.parse("01 15 01 00"), Hex.parse("01 15 04 01 04")))
    }

    @Test
    fun `a START does NOT end at the Processing frame`() {
        // ⚠ The truncation this rule exists to avoid: 01 01 GET_ALL opens with 07
        // PROCESSING and only finishes at 06 RESULT, eight frames later. Ending on the
        // first block-and-function match would return the header and nothing else.
        val sent = Hex.parse("01 01 05 00")
        assertFalse(BoseFrame.terminates(sent, Hex.parse("01 01 07 00")))
        assertFalse(
            BoseFrame.terminates(sent, Hex.parse("01 01 07 00 01 04 03 01 3c")),
        )
        assertTrue(
            BoseFrame.terminates(sent, Hex.parse("01 01 07 00 01 04 03 01 3c 01 01 06 00")),
        )
    }

    @Test
    fun `another function's reply does not end this exchange`() {
        // The device volunteers frames — 05 01 SOURCE arrived unasked mid-removal. A
        // rule keyed on operator alone would let someone else's status close our read.
        assertFalse(
            BoseFrame.terminates(Hex.parse("01 04 01 00"), Hex.parse("05 01 03 02 00 02")),
        )
    }

    @Test
    fun `an unknown operator falls back to the timeout rather than guessing`() {
        // ⚠ False, not true. 01 07 and 01 08 answer NOTHING on this device; a rule that
        // called an empty buffer "finished" would turn a silence worth noticing into a
        // decode failure.
        assertFalse(BoseFrame.terminates(Hex.parse("01 04 03 00"), Hex.parse("01 04 03 01 3c")))
        assertFalse(BoseFrame.terminates(Hex.parse("01 04 01 00"), ByteArray(0)))
    }
}

/**
 * Renaming, and the asymmetry that a mirrored setter would have got wrong.
 *
 * ⚠ `docs/captures.md` records that "every vendor here mirrors its getter", which is true
 * of the FRAME and not of this PAYLOAD. The read reply leads with a byte that is not part
 * of the name; the write does not carry it. A setter built by echoing the reply would have
 * renamed the headphones to `NUL` + the name — visible on every phone they pair with, and
 * not obviously this app's doing.
 */
class BoseNameTest {
    @Test
    fun `the write carries the name and no leading byte`() {
        // Captured from Bose Connect renaming to "BoseTest1" and back.
        assertEquals(
            "01 02 02 09 42 6f 73 65 54 65 73 74 31",
            Hex.format(BoseName.set("BoseTest1")!!.bytes),
        )
    }

    @Test
    fun `the read skips the byte the write never sends`() {
        // Same name, both directions: reply length 0a against a write length 09. The
        // driver's reader must drop that first byte, and the writer must not add it.
        val t = Replay("01 02 01 00" to "01 02 03 0a 00 42 6f 73 65 54 65 73 74 31")
        assertEquals("BoseTest1", Drivers.BoseQc35.name(t))
    }

    @Test
    fun `writing then reading round-trips the exact name`() {
        // ⚠ The read is a SEPARATE exchange, not the SET_GET's echo — an echo repeats
        // what it was handed whether or not the device kept it.
        val t =
            Replay(
                "01 02 02 09 42 6f 73 65 54 65 73 74 31" to
                    "01 02 03 0a 00 42 6f 73 65 54 65 73 74 31",
                "01 02 01 00" to "01 02 03 0a 00 42 6f 73 65 54 65 73 74 31",
            )
        assertEquals("BoseTest1", Drivers.BoseQc35.writeName(t, "BoseTest1"))
    }

    @Test
    fun `an empty name is refused rather than sent`() {
        // A zero-length payload would be a frame the device has never been given.
        assertNull(BoseName.set(""))
    }

    @Test
    fun `a name too long for the length byte is refused, not truncated`() {
        // ⚠ Cutting it to fit would rename the headphones to something nobody typed.
        assertNull(BoseName.set("x".repeat(256)))
        assertNotNull(BoseName.set("x".repeat(255)))
    }

    @Test
    fun `multi-byte characters are counted in BYTES, not characters`() {
        // The length byte is a byte count; "é" is two bytes in UTF-8.
        assertEquals("01 02 02 02 c3 a9", Hex.format(BoseName.set("é")!!.bytes))
    }

    /**
     * ⚠ The frame that was SENT on 2026-08-28 and dropped the link (ACL 1 → 0), with the
     * address replaced — this repo is public and a real BD_ADDR does not go in a fixture.
     */
    @Test
    fun `disconnect is a start carrying the address, like forget`() {
        val addr = Hex.parse("aa bb cc dd ee ff")
        assertEquals(
            Hex.parse("04 02 05 06 aa bb cc dd ee ff").toList(),
            BoseDisconnect.frame(addr).bytes.toList(),
        )
    }

    @Test
    fun `disconnect is function 02 and not its destructive neighbours`() {
        // ⚠ 03 is REMOVE_DEVICE and 07 is CLEAR_DEVICE_LIST. This asserts the byte that
        // separates "drop the link" from "forget the pairing".
        assertEquals(0x02, BoseDisconnect.FN.toInt())
    }

    /**
     * ⚠ The exact exchange driven on a QC45 2026-08-28 and restored: read `01`, write
     * `00`, read `00`, write `01`, read `01`.
     */
    @Test
    fun `cnc persistence writes the byte and reads it straight back`() {
        assertEquals(
            Hex.parse("01 0e 02 01 00").toList(),
            BoseCncPersistence.set(false).bytes.toList(),
        )
        assertEquals(
            Hex.parse("01 0e 02 01 01").toList(),
            BoseCncPersistence.set(true).bytes.toList(),
        )
        assertEquals(true, BoseCncPersistence.state(Hex.parse("01 0e 03 01 01")))
        assertEquals(false, BoseCncPersistence.state(Hex.parse("01 0e 03 01 00")))
    }

    @Test
    fun `cnc persistence compares the whole byte, unlike multipoint's flags`() {
        // ⚠ Multipoint reads 06 off and 07 on, so it MASKS bit 0. This one tests the whole
        // byte for 1, which is what Bose Music's own parser does, so a hypothetical 03
        // reads false here and would read true under a mask. ⚠ 03 has never been seen from
        // this device — the row exists to pin the difference between the two functions,
        // not to claim anything about what 03 would mean.
        assertEquals(false, BoseCncPersistence.state(Hex.parse("01 0e 03 01 03")))
        assertEquals(true, BoseMultipoint.state(Hex.parse("01 0a 03 01 07")))
    }
}
