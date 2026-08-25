package org.xinutec.volume.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    // The three on/off settings. ⚠ The `Reply` fixtures are the payloads the XM4
    // actually returned on 2026-08-23 — `e7 02 00 00`, `f7 03 00 01`, `f7 05 00 00`,
    // each cross-checked against Sound Connect's own screen the same minute. The
    // request and SET frames are this repo's, and the framing of all of them was
    // computed outside Kotlin so the guard below is not the encoder marking its own
    // homework.
    private val dseeGet = "3e 0c 00 00 00 00 02 e6 02 f6 3c"
    private val dseeOffReply = "3e 0c 00 00 00 00 04 e7 02 00 00 f9 3c"
    private val dseeSetAuto = "3e 0c 00 00 00 00 04 e8 02 00 01 fb 3c"
    private val dseeAutoReply1 = "3e 0c 01 00 00 00 04 e7 02 00 01 fb 3c"
    private val dseeGet1 = "3e 0c 01 00 00 00 02 e6 02 f7 3c"
    private val wearGet = "3e 0c 00 00 00 00 02 f6 03 07 3c"
    private val wearOnReply = "3e 0c 00 00 00 00 04 f7 03 00 01 0b 3c"
    private val chatGet = "3e 0c 00 00 00 00 02 f6 05 09 3c"
    private val chatOffReply = "3e 0c 00 00 00 00 04 f7 05 00 00 0c 3c"

    // ⚠ `f8 05 01 01`, not `f8 05 00 01` — Speak-to-Chat writes with a different type
    // table than it reads with, and the `00` form is acked and does nothing.
    private val chatSetOn = "3e 0c 00 00 00 00 04 f8 05 01 01 0f 3c"

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
            listOf(
                sonyReadRequest,
                sonyOff,
                sonyAmbient,
                sonyAnc,
                sonyWriteAnc,
                sonyAck,
                dseeGet,
                dseeOffReply,
                dseeSetAuto,
                dseeAutoReply1,
                dseeGet1,
                wearGet,
                wearOnReply,
                chatGet,
                chatOffReply,
                chatSetOn,
            )
        for (f in fixtures) {
            val frames = SonyFrame.decodeAll(Hex.parse(f.replace(" ", "")))
            assertEquals("$f should hold exactly one frame", 1, frames.size)
            assertTrue("$f has a bad checksum or length", frames.first().checksumOk)
        }
    }

    /**
     * The three reads that were confirmed against the XM4 and against the vendor
     * app's own screens on 2026-08-23. Each fixture is the payload the device sent.
     */
    @Test
    fun `sony reads each on-off setting as the device answered it`() {
        assertEquals(
            false,
            Drivers.SonyXm4().readSwitch(Replay(dseeGet to dseeOffReply), SonyDsee),
        )
        assertEquals(
            true,
            Drivers.SonyXm4().readSwitch(Replay(wearGet to wearOnReply), SonyPauseOnRemoval),
        )
        assertEquals(
            false,
            Drivers.SonyXm4().readSwitch(Replay(chatGet to chatOffReply), SonySpeakToChat),
        )
    }

    /**
     * ⚠ **The regression this exists for is a crossed wire, not a wrong byte.**
     * Pause-on-removal and Speak-to-Chat are both SYSTEM settings, so they share all
     * four command bytes — `f6`/`f7`/`f8`/`f9` — and differ only in the type byte. A
     * decoder that checked the command and skipped to the value would report
     * Speak-to-Chat's state as pause-on-removal's, and both were read in the same
     * session, so the two would have looked consistent.
     */
    @Test
    fun `no sony switch decodes another switch's reply`() {
        val replies =
            mapOf(
                "dsee" to Hex.parse("e7020000"),
                "wear" to Hex.parse("f7030001"),
                "chat" to Hex.parse("f7050000"),
            )
        val switches =
            mapOf("dsee" to SonyDsee, "wear" to SonyPauseOnRemoval, "chat" to SonySpeakToChat)
        for ((theirs, reply) in replies) {
            for ((mine, switch) in switches) {
                val got = switch.state(reply)
                if (mine == theirs) {
                    assertNotNull("$mine should decode its own reply", got)
                } else {
                    assertNull("$mine decoded $theirs's reply as $got", got)
                }
            }
        }
    }

    /**
     * ⚠ **An extended-parameter reply must not read as an on/off.** Speak-to-Chat's
     * sensitivity and mode-out time live on `fb` SYSTEM_RET_EXTENDED_PARAM with their
     * own tables, where a `01` in the value position means HIGH or MID rather than
     * "on". Rejecting it is the settingType check doing its job.
     */
    @Test
    fun `sony ignores a reply whose setting type is not the one asked for`() {
        assertNull(SonySpeakToChat.state(Hex.parse("fb050001")))
        assertNull(SonySpeakToChat.state(Hex.parse("f7050101")))
    }

    /**
     * ⚠ **Speak-to-Chat reads with one type table and writes with another**, and sending
     * the read's byte in a write is accepted, acked, and does nothing. That cost an hour
     * and a wrong entry in the docs calling the device refusing — it was not.
     *
     * `SmartTalkingModeSettingType.ON_OFF` = `00` answers a get;
     * `SmartTalkingModeParameterType.MODE_ON_OFF` = `01` is what a set must carry.
     * Both frames below were driven on the XM4 on 2026-08-23.
     */
    @Test
    fun `sony speak-to-chat writes a different type byte than it reads`() {
        assertArrayEquals(Hex.parse("f8050101"), SonySpeakToChat.set(true))
        assertArrayEquals(Hex.parse("f8050100"), SonySpeakToChat.set(false))
        // the RET, which uses the other table
        assertEquals(false, SonySpeakToChat.state(Hex.parse("f7050000")))
        // the NOTIFY, which uses the write's
        assertEquals(true, SonySpeakToChat.state(Hex.parse("f9050101")))
    }

    /**
     * The touch panel, in the bytes the XM4 actually exchanged on 2026-08-24.
     *
     * ⚠ **The type byte is the ONLY thing separating this from multipoint**, which the
     * device refuses for everyone including Sony's own app. `d1` is the touch panel and
     * takes writes; `d2` is multipoint and does not. A driver that got the byte wrong
     * would look exactly like the refusal, so this pins both.
     */
    @Test
    fun `sony touch panel is general setting d1, not multipoint d2`() {
        assertArrayEquals(Hex.parse("d6d1"), SonyTouchPanel.get())
        assertArrayEquals(Hex.parse("d8d10101"), SonyTouchPanel.set(true))
        assertArrayEquals(Hex.parse("d8d10100"), SonyTouchPanel.set(false))
        // the RET and the NOTIFY, both as measured
        assertEquals(false, SonyTouchPanel.state(Hex.parse("d7d10100")))
        assertEquals(true, SonyTouchPanel.state(Hex.parse("d9d10101")))
        // ⚠ multipoint's own frame must NOT decode here
        assertNull(SonyTouchPanel.state(Hex.parse("d7d20101")))
    }

    /**
     * Speak-to-Chat's detail frame, in the bytes the XM4 exchanged on 2026-08-24.
     *
     * ⚠ **The whole value goes out every time**, so this asserts the round trip rather
     * than a field: a set built from a state must reproduce the frame that state came
     * from, or a caller changing one chip silently rewrites the other two.
     */
    @Test
    fun `sony speak-to-chat detail is one frame carrying three settings`() {
        assertArrayEquals(Hex.parse("fa05"), SonyChatDetail.get())
        // the real frame: TYPE_1, AUTO, focus off, MID
        val measured = SonyChatDetail.state(Hex.parse("fb 05 00 00 00 01"))!!
        assertEquals(ChatSensitivity.AUTO, measured.sensitivity)
        assertEquals(false, measured.voiceFocus)
        assertEquals(ModeOutTime.MID, measured.modeOutTime)
        assertEquals(30, measured.modeOutTime.seconds)
        // round trip: what was read rebuilds what was sent
        assertArrayEquals(Hex.parse("fc 05 00 00 00 01"), SonyChatDetail.set(measured))
        // the notify is an answer too, and the LOW/on/SLOW frame that was driven
        val other = SonyChatDetail.state(Hex.parse("fd 05 00 02 01 02"))!!
        assertEquals(ChatSensitivity.LOW, other.sensitivity)
        assertEquals(true, other.voiceFocus)
        assertEquals(60, other.modeOutTime.seconds)
        // ⚠ an unknown byte refuses the WHOLE frame rather than defaulting one field
        assertNull(SonyChatDetail.state(Hex.parse("fb0500ff0001")))
        assertNull(SonyChatDetail.state(Hex.parse("fb050000ff01")))
        // ⚠ and so does a frame one byte short, rather than reading past it
        assertNull(SonyChatDetail.state(Hex.parse("fb05000000")))
    }

    /** The other two agree in both directions, and must keep doing so. */
    @Test
    fun `sony dsee and pause use one type byte both ways`() {
        assertArrayEquals(Hex.parse("e8020001"), SonyDsee.set(true))
        assertArrayEquals(Hex.parse("f8030000"), SonyPauseOnRemoval.set(false))
        assertEquals(true, SonyDsee.state(Hex.parse("e9020001")))
        assertEquals(false, SonyPauseOnRemoval.state(Hex.parse("f9030000")))
    }

    /**
     * ⚠ **Confirmation comes from the read-back, never from the echo.** [SonyButton]
     * is why: that write is acked and simply does not take. This asserts the driver
     * really asks again — the [Replay] drains only if both exchanges happen.
     */
    @Test
    fun `sony confirms a switch write by reading it back`() {
        val d = Drivers.SonyXm4()
        val t =
            Replay(
                dseeSetAuto to dseeAutoReply1,
                dseeGet1 to "3e 0c 00 00 00 00 04 e7 02 00 01 fa 3c",
            )
        assertEquals(Confirmation.Confirmed, d.setSwitch(t, SonyDsee, true))
        t.assertDrained()
    }

    /** The other outcome: the device echoed the change and then did not keep it. */
    @Test
    fun `sony contradicts a switch write the device did not keep`() {
        val d = Drivers.SonyXm4()
        val t =
            Replay(
                chatSetOn to "3e 0c 01 00 00 00 04 f9 05 01 01 11 3c",
                "3e 0c 01 00 00 00 02 f6 05 0a 3c" to
                    "3e 0c 00 00 00 00 04 f7 05 00 00 0c 3c",
            )
        assertEquals(Confirmation.Contradicted(false), d.setSwitch(t, SonySpeakToChat, true))
        t.assertDrained()
    }

    /**
     * ⚠ **The XM4 volunteers notifications, and one of them cost a working write.**
     * Driving `e8 02 00 01` on 2026-08-23 made the device emit `17`
     * COMMON_NTFY_UPSCALING_EFFECT as well as the `e9` that answered the write, and the
     * `17` arrived in the *next* read's window. The driver took the last DATA frame,
     * got a frame about something else, and reported the write — which had in fact
     * taken — as unverifiable.
     *
     * The window below is the one the device really sent, byte for byte.
     */
    @Test
    fun `sony does not mistake a volunteered notification for its answer`() {
        val shadowed = "3e 0c 00 00 00 00 04 17 00 02 00 29 3c 3e 01 00 00 00 00 00 01 3c"
        assertNull(Drivers.SonyXm4().readSwitch(Replay(dseeGet to shadowed), SonyDsee))
    }

    /**
     * And what the fix actually buys, which needs the stray notification to arrive
     * **after** the answer rather than before it: `lastOrNull` returns the `17` here
     * and the read comes out null, where selecting the frame that answers the question
     * returns the `e7`.
     *
     * ⚠ **The first fixture written for this had the two the other way round**, where
     * taking the last DATA frame already lands on the answer — so it passed with the
     * fix removed and proved nothing. The ablation is what caught that, not review.
     */
    @Test
    fun `sony picks the frame that answers it, not merely the last one`() {
        val answerThenStray =
            "3e 0c 00 00 00 00 04 e7 02 00 00 f9 3c 3e 0c 00 00 00 00 04 17 00 02 00 29 3c"
        assertEquals(
            false,
            Drivers.SonyXm4().readSwitch(Replay(dseeGet to answerThenStray), SonyDsee),
        )
    }

    /**
     * ✅ Focus on Voice, driven on the XM4 on 2026-08-23. The three frames below are the
     * ones that were on the wire, checksums and all.
     */
    @Test
    fun `sony sets focus on voice by moving one byte of the anc frame`() {
        val d = Drivers.SonyXm4()
        val t =
            Replay(
                "3e 0c 00 00 00 00 02 66 02 76 3c" to
                    "3e 0c 00 00 00 00 08 67 02 01 02 00 01 00 14 95 3c",
                "3e 0c 01 00 00 00 08 68 02 01 02 00 01 01 14 98 3c" to
                    "3e 0c 01 00 00 00 08 69 02 01 02 00 01 01 14 99 3c",
                "3e 0c 00 00 00 00 02 66 02 76 3c" to
                    "3e 0c 00 00 00 00 08 67 02 01 02 00 01 01 14 96 3c",
            )
        assertEquals(Confirmation.Confirmed, d.setFocusOnVoice(t, true))
        t.assertDrained()
    }

    /**
     * ⚠ **The regression that a read-back caught and a trusted write would not have.**
     * Sending `AsmId.NORMAL` while the XM4 is in ANC is accepted and ignored — the tidy-up
     * after the hardware test did exactly that and left Focus on Voice switched on. So
     * this refuses in ANC rather than sending a frame that will be dropped, and reports
     * the value that is actually there.
     */
    @Test
    fun `sony will not set focus on voice while noise cancelling is engaged`() {
        val d = Drivers.SonyXm4()
        val t =
            Replay(
                "3e 0c 00 00 00 00 02 66 02 76 3c" to
                    "3e 0c 00 00 00 00 08 67 02 01 02 02 01 00 00 83 3c",
            )
        assertEquals(Confirmation.Contradicted(false), d.setFocusOnVoice(t, true))
        t.assertDrained()
    }

    /** And it reads in either mode — only the write is mode-bound. */
    @Test
    fun `sony reads focus on voice out of the frame it already fetches`() {
        assertEquals(
            true,
            Drivers.SonyXm4().readFocusOnVoice(
                Replay(
                    "3e 0c 00 00 00 00 02 66 02 76 3c" to
                        "3e 0c 00 00 00 00 08 67 02 01 02 00 01 01 14 96 3c",
                ),
            ),
        )
    }

    /**
     * ⚠ **One read, both facts.** The controller asked twice at first — once for the
     * value and once for the mode — which is a wasted `66 02` on a device where every
     * extra exchange is another chance to end up one window behind (#1107).
     */
    @Test
    fun `sony reads focus on voice and its settability from one frame`() {
        val ambient =
            Replay(
                "3e 0c 00 00 00 00 02 66 02 76 3c" to
                    "3e 0c 00 00 00 00 08 67 02 01 02 00 01 01 14 96 3c",
            )
        assertEquals(Focus(on = true, settable = true), Drivers.SonyXm4().readFocus(ambient))
        ambient.assertDrained()

        // In ANC the value is still readable; only the control goes away.
        val anc =
            Replay(
                "3e 0c 00 00 00 00 02 66 02 76 3c" to
                    "3e 0c 00 00 00 00 08 67 02 01 02 02 01 00 00 83 3c",
            )
        assertEquals(Focus(on = false, settable = false), Drivers.SonyXm4().readFocus(anc))

        // ⚠ And OFF is not ambient either — `on` is false there, so the mode check
        // cannot key on the nc byte alone.
        val off =
            Replay(
                "3e 0c 00 00 00 00 02 66 02 76 3c" to
                    "3e 0c 00 00 00 00 08 67 02 00 02 00 01 00 14 94 3c",
            )
        assertEquals(false, Drivers.SonyXm4().readFocus(off).settable)
    }

    /**
     * ✅ **#1107, the fix.** The XM4 volunteers frames, and one arriving before the
     * reply used to end the read with nothing — which is how tapping DSEE in the app
     * reported "sent, this one cannot confirm it" for a write that had landed.
     *
     * Here the exchange window holds only the volunteered `17`, and the real `e7`
     * arrives on the next read.
     */
    @Test
    fun `sony reads again when a volunteered frame arrives before the answer`() {
        val t =
            Replay(
                dseeGet to "3e 0c 00 00 00 00 04 17 00 02 00 29 3c 3e 01 00 00 00 00 00 01 3c",
            )
        t.volunteered = listOf("3e 0c 00 00 00 00 04 e7 02 00 00 f9 3c")
        assertEquals(false, Drivers.SonyXm4().readSwitch(t, SonyDsee))
    }

    /**
     * ⚠ **And it gives up.** Bounded at [Drivers.SonyXm4] EXTRA_READS — a device that
     * has stopped answering must produce a null, not a stall, because this runs on the
     * settings path with someone waiting.
     */
    @Test
    fun `sony stops reading again once its budget is spent`() {
        val stray = "3e 0c 00 00 00 00 04 17 00 02 00 29 3c"
        val t = Replay(dseeGet to stray)
        t.volunteered = List(9) { stray }
        assertNull(Drivers.SonyXm4().readSwitch(t, SonyDsee))
        // 1 exchange + at most EXTRA_READS receives, not all nine.
        assertTrue("spent ${t.sent.size} sends", t.sent.size <= 5)
    }

    /**
     * ⚠ **Every DATA frame is acked, not just the one returned.** Sony asks for one per
     * frame; the old code acked only its chosen reply, so a volunteered notification
     * sharing the window was left unacknowledged.
     *
     * ⚠ **This is NOT what causes the desync, and this comment used to say it was.** The
     * probe was given the identical fix and still ran one behind (#1107, measured
     * 2026-08-23 20:10). Acking every frame is correct because the device asks for it,
     * and for no other reason.
     */
    @Test
    fun `sony acks every data frame in the window`() {
        // ⚠ Different sequence bits, as consecutive device frames really have — so the
        // two acks are not interchangeable and a test that acked one twice would fail.
        val two =
            "3e 0c 00 00 00 00 04 17 00 02 00 29 3c 3e 0c 01 00 00 00 04 e7 02 00 00 fa 3c"
        val t = Replay(dseeGet to two)
        assertEquals(false, Drivers.SonyXm4().readSwitch(t, SonyDsee))
        // the request, then one ack per DATA frame, each inverting that frame's bit
        assertEquals(3, t.sent.size)
        assertEquals("3e 01 01 00 00 00 00 02 3c", t.sent[1])
        assertEquals(sonyAck, t.sent[2])
    }

    /**
     * ✅ **A write retires what it left behind.** The XM4 answers `e8 02 …` with its own
     * `e9`, and then volunteers `17` COMMON_NTFY_UPSCALING_EFFECT a moment later. That
     * second frame used to be collected by the FIRST exchange of the settings refresh
     * that runs next — which is how a tap left the device on, the switch drawn on, and
     * the row's own label saying "off".
     *
     * Here the stray is delivered after the confirming read, and must be consumed AND
     * acknowledged rather than left for the next caller.
     */
    @Test
    fun `sony settles the frames its write leaves in flight`() {
        val d = Drivers.SonyXm4()
        val t =
            Replay(
                dseeSetAuto to dseeAutoReply1,
                dseeGet1 to "3e 0c 00 00 00 00 04 e7 02 00 01 fa 3c",
            )
        t.volunteered = listOf("3e 0c 01 00 00 00 04 17 00 02 00 2a 3c")
        assertEquals(Confirmation.Confirmed, d.setSwitch(t, SonyDsee, true))
        t.assertDrained()
        // ⚠ The stray is ACKED, not merely swallowed — the device asks for one ack per
        // DATA frame. ⚠ It is NOT what fixes the desync; see the note above.
        assertEquals(sonyAck, t.sent.last())
    }

    /**
     * ✅ The frame the XM4 really sent on 2026-08-23, alongside Sound Connect reading
     * **80%** on the same card at the same moment.
     */
    @Test
    fun `sony reads the battery it reported`() {
        val t =
            Replay(
                "3e 0c 00 00 00 00 02 10 00 1e 3c" to
                    "3e 0c 00 00 00 00 04 11 00 50 00 71 3c",
            )
        assertEquals(Battery(percent = 80, charging = false), Drivers.SonyXm4().readBattery(t))
        t.assertDrained()
    }

    /**
     * ⚠ **`f0` is `BatteryChargingStatus.UNKNOWN` and must not read as "on battery".**
     * The device is saying it does not know; a decoder that defaulted to false would
     * put a confident "80%" on the card on the strength of a shrug. Same rule as
     * [SonyAutoOff]'s unknown value byte.
     */
    @Test
    fun `sony refuses a battery whose charging status it does not know`() {
        assertNull(SonyBattery.state(Hex.parse("110050f0")))
        // and a level over 100, which BatteryInquiredType has no cell for
        assertNull(SonyBattery.state(Hex.parse("1100ff00")))
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
     * ⚠ **A driver must not REPORT a mode it cannot be ASKED for.**
     *
     * The JBL offered no OFF for a week and no test could tell, because every check
     * on it asked whether a mode it *does* offer round-trips — and absence is not a
     * frame. [Drivers.JblBes.read] decoded the all-zero reply as [AncMode.OFF] the
     * whole time, so the card could draw "off" as the current state with no chip to
     * return to it.
     *
     * ⚠ **The obvious test is the wrong one**, and writing it is how this arrived at
     * the right one: *"every driver offers OFF"* fails on the **QC45**, which
     * genuinely has none — its ANC is a slot table of Quiet and Aware, and Bose Music
     * offers no way to stop it either. That test would have asserted a claim about
     * hardware that is false, which is worse than the bug it was chasing.
     *
     * So the invariant is the asymmetry itself, over every state each device is known
     * to report. It holds for the QC45 (whose [Drivers.BoseQc45.read] cannot return
     * OFF) and would have failed on the JBL. ⚠ The fixtures are the captured ones
     * used above; a mode reachable only by a reply nobody has seen is out of scope
     * here, as it is everywhere else in this file.
     */
    @Test
    fun `no driver reports a mode it does not offer`() {
        val states =
            listOf(
                Triple(Drivers.BoseQc45, "01 05 01 00" to "01 05 03 03 0b 00 03", AncMode.ANC),
                Triple(Drivers.BoseQc45, "01 05 01 00" to "01 05 03 03 0b 0a 03", AncMode.AMBIENT),
                Triple(Drivers.BoseQc35, "01 06 01 00" to "01 06 03 02 00 0b", AncMode.ANC),
                Triple(Drivers.BoseQc35, "01 06 01 00" to "01 06 03 02 01 0b", AncMode.AMBIENT),
                Triple(Drivers.BoseQc35, "01 06 01 00" to "01 06 03 02 03 0b", AncMode.OFF),
                Triple(
                    Drivers.JblBes,
                    "aa 91 01 11" to "aa 91 07 12 01 01 02 00 03 00",
                    AncMode.ANC,
                ),
                Triple(
                    Drivers.JblBes,
                    "aa 91 01 11" to "aa 91 07 12 01 00 02 01 03 00",
                    AncMode.AMBIENT,
                ),
                Triple(
                    Drivers.JblBes,
                    "aa 91 01 11" to "aa 91 07 12 01 00 02 00 03 01",
                    AncMode.TALK_THRU,
                ),
                // ⚠ The state that had nothing to return to it until 2026-08-23.
                Triple(
                    Drivers.JblBes,
                    "aa 91 01 11" to "aa 91 07 12 01 00 02 00 03 00",
                    AncMode.OFF,
                ),
                Triple(
                    Drivers.JLabQcy,
                    "c0 ff 00 44 00 00 01 00 04" to "00 ff 01 45 03 00 01 04 04 00 4f 00",
                    AncMode.ANC,
                ),
                Triple(
                    Drivers.JLabQcy,
                    "c0 ff 00 44 00 00 01 00 04" to "00 ff 01 45 03 00 02 04 04 00 50 00",
                    AncMode.AMBIENT,
                ),
                Triple(
                    Drivers.JLabQcy,
                    "c0 ff 00 44 00 00 01 00 04" to "00 ff 01 45 03 00 00 00 00 00 46 00",
                    AncMode.OFF,
                ),
            )
        for ((driver, exchange, mode) in states) {
            val what = "${driver.javaClass.simpleName} on ${exchange.second}"
            // The fixture has to still decode, or the second assertion proves nothing.
            assertEquals(what, mode, driver.read(Replay(exchange)))
            assertTrue("$what reports $mode and does not offer it", mode in driver.modes)
        }
        // ⚠ Sony carries per-session sequence state, so it needs a driver of its own
        // rather than a shared one — the same reason the read tests above build one.
        for (fixture in listOf(sonyOff to AncMode.OFF, sonyAnc to AncMode.ANC)) {
            val driver = Drivers.SonyXm4()
            assertEquals(fixture.second, driver.read(Replay(sonyReadRequest to fixture.first)))
            assertTrue("sony reports ${fixture.second}", fixture.second in driver.modes)
        }
    }

    /**
     * The frame that will go on the wire for OFF, asserted before it is sent.
     *
     * ⚠ **There are two candidate writers and this is the cheaper one.** The vendor
     * app sends `aa 91 01 13` (`genSetANCModeOFF`, named in the SDK); ours sets all
     * three TLV slots to zero through the same sub-op `10` every other mode uses.
     * Both are plausible and only the device settles it — ⚠ and a refusal here looks
     * like every other refusal on this protocol: an ack, and the old state on
     * read-back. Which is why [Drivers.JblBes.set] confirms with a real `aa 91 01 11`
     * rather than with the reply.
     */
    @Test
    fun `jbl writes off as three zero slots`() {
        val t =
            Replay(
                "aa 91 07 10 01 00 02 00 03 00" to "aa 91 07 12 01 00 02 00 03 00",
                "aa 91 01 11" to "aa 91 07 12 01 00 02 00 03 00",
            )
        assertEquals(Confirmation.Confirmed, Drivers.JblBes.set(t, AncMode.OFF))
        t.assertDrained()
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

    /**
     * ⚠ **Pins that the driver sends the byte, not just that the constant is right.**
     * The frame is three bytes and the command sits two away from `aa 95` factory
     * reset, so what actually leaves the transport is the thing worth asserting —
     * and it is the one write on this device that cannot be checked by reading back.
     */
    @Test
    fun `jbl power off sends aa 97 00 and nothing else`() {
        val t = Replay("aa 97 00" to "aa 00 02 97 00")
        Drivers.JblBes.powerOff(t)
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
