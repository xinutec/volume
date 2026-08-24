package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---- Sound Quality Mode ------------------------------------------------

    /** 18:08:33, the vendor app's connect-time read. Seq `01`, so `prepare` runs first. */
    @Test
    fun `sound quality reads as prioritise quality`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 02 00 00 0e 3c" to "3e 0c 01 00 00 00 04 01 00 70 00 82 3c",
                "3e 0c 01 00 00 00 02 e6 01 f6 3c" to "3e 0c 00 00 00 00 04 e7 01 00 00 f8 3c",
            )
        sony.prepare(t)
        assertEquals(SoundQuality.QUALITY, sony.readSoundQuality(t))
        t.assertDrained()
    }

    /** 18:16:54 — and ⚠ its notify echoes the value, so this one confirms itself. */
    @Test
    fun `sound quality is set to stable and echoed back`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 02 00 00 0e 3c" to "3e 0c 01 00 00 00 04 01 00 70 00 82 3c",
                "3e 0c 01 00 00 00 04 e8 01 00 01 fb 3c" to
                    "3e 01 00 00 00 00 00 01 3c" +
                    "3e 0c 01 00 00 00 04 e9 01 00 01 fc 3c",
            )
        sony.prepare(t)
        assertEquals(SoundQuality.STABLE, sony.writeSoundQuality(t, SoundQuality.STABLE))
        t.assertDrained()
    }

    /** 18:20:00, the inverse. A fresh driver starts at seq `00`, which is what this is. */
    @Test
    fun `sound quality is set back to quality`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 04 e8 01 00 00 f9 3c" to
                    "3e 01 01 00 00 00 00 02 3c" +
                    "3e 0c 00 00 00 00 04 e9 01 00 00 fa 3c",
            )
        assertEquals(
            SoundQuality.QUALITY,
            Drivers.SonyXm4().writeSoundQuality(t, SoundQuality.QUALITY),
        )
        t.assertDrained()
    }

    /** ⚠ The third byte is `00` here and `01` everywhere else — don't normalise it. */
    @Test
    fun `sound quality set carries the zero byte the capture has`() {
        assertEquals("e8 01 00 01", Hex.format(SonySoundQuality.set(SoundQuality.STABLE)))
        assertEquals("e8 01 00 00", Hex.format(SonySoundQuality.set(SoundQuality.QUALITY)))
    }

    // ---- the [CUSTOM] button -----------------------------------------------

    /** 18:08:33, read straight after the vendor app assigned Digital assistant. */
    @Test
    fun `button reads as the action the app assigned`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 02 f6 06 0a 3c" to "3e 0c 01 00 00 00 04 f7 06 01 31 40 3c",
            )
        assertEquals(SonyButton.Action.GOOGLE_ASSISTANT, Drivers.SonyXm4().readButton(t))
        t.assertDrained()
    }

    /** 18:09:48, after the app put it back. */
    @Test
    fun `button reads as ambient sound control once restored`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 02 f6 06 0a 3c" to "3e 0c 01 00 00 00 04 f7 06 01 00 0f 3c",
            )
        assertEquals(SonyButton.Action.AMBIENT_SOUND_CONTROL, Drivers.SonyXm4().readButton(t))
        t.assertDrained()
    }

    /**
     * ✅ **The whole #965 sequence, in the bytes the XM4 sent on 2026-08-24.**
     *
     * ⚠ **The subscription is the first frame and the reason this works at all.** Remove
     * it and the device answers the `f8 06` with a bare ack and never asks anything —
     * which is what this repo saw for eight days and read as a refusal.
     */
    @Test
    fun `a button write subscribes to alerts and hands the question back`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 02 00 00 0e 3c" to "3e 0c 01 00 00 00 04 01 00 70 00 82 3c",
                "3e 0c 00 00 00 00 04 f8 06 01 31 40 3c" to
                    "3e 01 01 00 00 00 00 02 3c" +
                    "3e 0c 01 00 00 00 04 99 01 02 01 ae 3c",
            )
        sony.prepare(t)
        assertEquals(
            ButtonWrite.Asks,
            sony.beginButtonWrite(t, SonyButton.Action.GOOGLE_ASSISTANT),
        )
        t.assertDrained()
        // ⚠ The subscription draws no reply, so it is a `send` and consumes no step —
        // it shows up only here. Its absence is exactly the bug this fixes, so assert it.
        assertTrue("94 01 00" in t.sent.joinToString(" "))
    }

    /** 18:08:16, the notify that arrives only after the commit — and it does decode. */
    @Test
    fun `the notify after the commit carries the new action`() {
        assertEquals(
            SonyButton.Action.GOOGLE_ASSISTANT,
            SonyButton.state(Hex.parse("f9060131")),
        )
        assertEquals(
            SonyButton.Action.AMBIENT_SOUND_CONTROL,
            SonyButton.state(Hex.parse("f9060100")),
        )
    }

    /**
     * ⚠ **`21` is an ACTION code, not a preset**, and the two live in the same reply.
     *
     * This test used to assert `33` decoded to null as well, on the grounds that Amazon
     * Alexa "was in the menu and never selected". That was true of the menu and false of
     * the protocol: `AssignableSettingsPreset` names all eight, and [SonyButton.Action]
     * now does too. What must still decode to null is a byte from the neighbouring
     * enum — `21` is `LONG_PRESS_THEN_ACTIVATE`, which is not a thing a key can be set to.
     */
    @Test
    fun `an action code is not a preset and decodes to null`() {
        assertNull(SonyButton.state(Hex.parse("f7060121")))
        assertNull(SonyButton.state(Hex.parse("f7060134")))
        // and the real ones do decode
        assertEquals(SonyButton.Action.TENCENT_XIAOWEI, SonyButton.state(Hex.parse("f7060133")))
    }

    /** The alert frames — the subscription and both answers. */
    @Test
    fun `the alert frames are the ones the app sent`() {
        assertEquals("94 01 00", Hex.format(SonyButton.subscribeAlerts()))
        assertEquals("98 01 02 01", Hex.format(SonyButton.answer(true)))
        assertEquals("98 01 02 00", Hex.format(SonyButton.answer(false)))
        assertTrue(SonyButton.asksAboutKeyAssign(Hex.parse("99010201")))
        // ⚠ a different alert message must NOT be read as this one
        assertFalse(SonyButton.asksAboutKeyAssign(Hex.parse("99010601")))
    }

    /**
     * ⚠ The capability read answers us perfectly well — which is half of why the
     * *write* being ignored is strange, and is the frame #965 starts from. Its reply
     * is deliberately **not** decoded: it plainly holds more action codes than the two
     * that were exercised, and turning that byte string into a list would be inventing
     * a structure rather than reading one.
     */
    @Test
    fun `the capability frame is asked for but its reply is not decoded`() {
        assertEquals("f0 06", Hex.format(SonyButton.capabilities()))
        val reply = "f1 06 01 02 01 00 03 00 02 00 01 21 02 31 03 00 31 01 33 22 32 32 01 00 34"
        assertNull(SonyButton.state(Hex.parse(reply)))
    }

    /**
     * The capability reply names the three presets this key allows, and no others.
     *
     * ⚠ **`VOLUME_CONTROL` is the point of this test.** It is a legal
     * `AssignableSettingsPreset` and the XM4 does not offer it — an editor built from the
     * enum instead of from this reply would put a volume control on the card. The 23
     * bytes are the XM4's own, read 2026-08-23.
     */
    @Test
    fun `sony button offers what the device advertises, not what the enum contains`() {
        val reply = "f1 06 01 02 01 00 03 00 02 00 01 21 02 31 03 00 31 01 33 22 32 32 01 00 34"
        assertEquals(
            listOf(
                SonyButton.Action.AMBIENT_SOUND_CONTROL,
                SonyButton.Action.GOOGLE_ASSISTANT,
                SonyButton.Action.AMAZON_ALEXA,
            ),
            SonyButton.presets(Hex.parse(reply)),
        )
        // ⚠ unparseable gives NOTHING, never the whole enum
        assertEquals(emptyList<SonyButton.Action>(), SonyButton.presets(Hex.parse("f106")))
        assertEquals(emptyList<SonyButton.Action>(), SonyButton.presets(Hex.parse("f7060100")))
        // truncated mid-walk stops rather than reading past the end
        assertEquals(
            listOf(SonyButton.Action.AMBIENT_SOUND_CONTROL),
            SonyButton.presets(Hex.parse("f1 06 01 02 01 00 03 00 02 00 01 21 02 31")),
        )
    }
}
