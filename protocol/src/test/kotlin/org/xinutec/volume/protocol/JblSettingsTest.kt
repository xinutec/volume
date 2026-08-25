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
     * The three the app offers, driven on hardware 2026-08-25 10:06 and read back at
     * each step — `3c` and `78` took, and `1e` restored.
     *
     * ⚠ **Pins the TRAILER against being composed from the minutes.** `78` is 120 and
     * a 16-bit little-endian timeout would put its high byte exactly where that `00`
     * sits, so the two readings agree on every value this app can send. A future edit
     * that "fixes" the trailer would pass a read-back test and change nothing until
     * someone asked for four hours.
     */
    @Test
    fun `every offered duration writes the byte and leaves the trailer alone`() {
        assertEquals(listOf(30, 60, 120), JBL_IDLE_MINUTES)
        assertEquals("aa3303003c00", hex(JblAutoOff.set(TimedOff(on = false, minutes = 60))))
        assertEquals("aa3303007800", hex(JblAutoOff.set(TimedOff(on = false, minutes = 120))))
        assertEquals("aa3303013c00", hex(JblAutoOff.set(TimedOff(on = true, minutes = 60))))
        assertEquals(TimedOff(on = false, minutes = 120), JblAutoOff.state(bytes("aa220433007800")))
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

    /**
     * Both halves of the 2026-08-25 ablation, and the concatenated frame that ended it.
     *
     * ⚠ **The `aa 25` tail is real traffic, not a crafted case.** The restore's reply came
     * back as `aa 93 02 05 01 aa 25 0d …` — an unsolicited battery notification glued on.
     * A reader that scanned to the end of the buffer would take `aa` as the payload and
     * call voice prompts on when they were off, or the reverse.
     */
    @Test
    fun `voice prompts reads its switch and stops at the length byte`() {
        assertEquals(true, JblVoicePrompts.state(bytes("aa93020501")))
        assertEquals(false, JblVoicePrompts.state(bytes("aa93020500")))
        assertEquals(
            true,
            JblVoicePrompts.state(bytes("aa93020501aa250d0100003232ffffffffffffffff")),
        )
    }

    /**
     * ⚠ **`aa 93 01 01` is a DIFFERENT sub-command and never moved.** For a week this row
     * was filed under that frame; toggling the switch left it at `02 01 00 00 08` both
     * times. Decoding it as the switch would report a constant as a setting.
     */
    @Test
    fun `the other aa 93 sub-command is not the voice prompt switch`() {
        assertNull(JblVoicePrompts.state(bytes("aa93050201000008")))
    }

    /**
     * Customize ANC, read 2026-08-17 and again 2026-08-25 byte-identically.
     *
     * ⚠ **`a1` is the assertion that matters.** It is the only key outside `01`–`08` and
     * it arrives LAST, so a reader that walks fixed offsets, stops at a contiguous key
     * range, or treats a high byte as a terminator loses the ambient level — and loses it
     * while returning a perfectly well-formed object for the other three.
     */
    @Test
    fun `customize anc decodes four sparse key value pairs`() {
        assertEquals(
            AdvancedAnc(
                tuning = AncTuning.ADAPTIVE,
                manualLevel = 7,
                ambientLevel = 7,
                leakageCompensation = 1,
            ),
            JblAdvancedAnc.state(bytes("aa910922010104070501a107")),
        )
    }

    /**
     * ⚠ **A short frame must not be read past its end**, and the length byte is the
     * vendor's rather than this buffer's. `0d` claims six pairs where three are present;
     * trusting it walks off the array.
     */
    @Test
    fun `a customize anc frame that overclaims its length is refused`() {
        assertNull(JblAdvancedAnc.state(bytes("aa910d22010104070501")))
    }

    /**
     * ⚠ The `aa 91` MODE reply has the same command byte and a different grammar — fixed
     * slots, sub-command `12`. Decoding it here would report an ANC tuning built out of
     * the three mode slots.
     */
    @Test
    fun `the anc mode reply is not customize anc`() {
        assertNull(JblAdvancedAnc.state(bytes("aa910712010002000300")))
    }

    /** 2026-08-16 23:29, sent once by agreement — the whole command, with no payload. */
    @Test
    fun `power off is three bytes`() {
        assertEquals("aa9700", hex(JblPowerOff.off()))
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
        // ✅ Both frames are now REAL: driven 23:29:49 and 23:29:58, once, by
        // agreement. The off frame is what the connect sweep could not supply —
        // there both payload bytes were `01`, so index 4 and index 5 were
        // indistinguishable and the SDK's offset was the only evidence. Here index
        // 5 moves and index 4 does not, which is that offset measured.
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

    /**
     * ⚠ The preset list is only worth having if its indices line up with the ids the
     * device actually sends, so that is what is asserted — not its length.
     */
    @Test
    fun `the preset names are indexed by the id on the wire`() {
        assertEquals("Off", JBL_EQ_PRESETS[JblEq.curve(flat)!!.table])
        assertEquals("Jazz", JBL_EQ_PRESETS[JblEq.curve(bytes(JblFrames.JAZZ_ECHO))!!.table])
    }

    @Test
    fun `the feature keys read what the headphones answered`() {
        // Both frames are off the wire, 2026-08-17, and they disagree in the value
        // byte only — which is what makes them worth having as a pair.
        val leAudio = bytes(JblFrames.FEATURE_LE_AUDIO_OFF)
        val auracast = bytes(JblFrames.FEATURE_AURACAST_ON)
        assertEquals(false, JblFeature.state(leAudio, JblFeature.LE_AUDIO))
        assertEquals(true, JblFeature.state(auracast, JblFeature.AURACAST))
    }

    /**
     * ⚠ Asking the wrong key must be null, not the other key's value.
     *
     * A get answers about one key, so a reader that ignored the key byte would report
     * Auracast's state under LE Audio's name and be right half the time.
     */
    @Test
    fun `a key that is not in the frame is unknown, not false`() {
        val leAudio = bytes(JblFrames.FEATURE_LE_AUDIO_OFF)
        val auracast = bytes(JblFrames.FEATURE_AURACAST_ON)
        assertNull(JblFeature.state(auracast, JblFeature.LE_AUDIO))
        assertNull(JblFeature.state(leAudio, JblFeature.AURACAST))
    }

    /**
     * The real glued reply decodes to key `03` and ignores the battery frame behind it.
     *
     * ⚠ This one does NOT prove the length bound, and saying so matters: with the
     * bound removed it still answers null for `0x25`, because the battery frame's
     * `0d` reads as a 13-byte value that runs off the end and fails the size check
     * anyway. It is here because it is the frame that actually arrived — see
     * [a trailing frame that parses as a triple is still out of bounds] for the
     * assertion that the bound is load-bearing.
     */
    @Test
    fun `a concatenated battery frame decodes as its first frame`() {
        val glued = bytes(JblFrames.FEATURE_03_OFF_THEN_BATTERY)
        assertEquals(false, JblFeature.state(glued, 0x03))
        assertNull(JblFeature.state(glued, 0x25))
    }

    /**
     * ⚠ **The length bound, measured by ablation.**
     *
     * `05 01 01` behind a complete `aa b1` status is a well-formed key/size/value
     * triple in its own right. Walk to the end of the buffer and key `05` "exists"
     * and reads true; stop at the length byte and it is correctly unknown. Removing
     * the bound flips this assertion and nothing else in this file, which is the
     * whole reason it is written down separately.
     */
    @Test
    fun `a trailing frame that parses as a triple is still out of bounds`() {
        val trap = bytes("aab10402010100050101")
        assertEquals(false, JblFeature.state(trap, JblFeature.LE_AUDIO))
        assertNull(JblFeature.state(trap, 0x05))
    }

    @Test
    fun `the feature frames are the ones the vendor app builds`() {
        // Byte-identical to GetSetFeatureCmd.getLeAudioStatus / getAuracastStatus,
        // and getLeAudioStatus is the one confirmed against the device.
        assertEquals("aab103000100", hex(JblFeature.get(JblFeature.LE_AUDIO)))
        assertEquals("aab103000200", hex(JblFeature.get(JblFeature.AURACAST)))
        // setLeAudioStatus(true) — built, never sent. See [JblFeature.set].
        assertEquals("aab10401010101", hex(JblFeature.set(JblFeature.LE_AUDIO, true)))
        assertEquals("aab10401010100", hex(JblFeature.set(JblFeature.LE_AUDIO, false)))
    }

    // ---- spatial sound -----------------------------------------------------

    /** 11:11:28 / :35 / :43 — three picks, three replies, one byte apart. */
    @Test
    fun `the spatial mode is read from the last byte`() {
        val movie = bytes(JblFrames.SPATIAL_MOVIE_ON)
        val game = bytes(JblFrames.SPATIAL_GAME_ON)
        val music = bytes(JblFrames.SPATIAL_MUSIC_ON)
        assertEquals(Spatial(on = true, mode = SpatialMode.MOVIE), JblSpatial.state(movie))
        assertEquals(Spatial(on = true, mode = SpatialMode.GAME), JblSpatial.state(game))
        assertEquals(Spatial(on = true, mode = SpatialMode.MUSIC), JblSpatial.state(music))
    }

    /**
     * 11:11:53 and the 10:40:01 cold read, which are the same bytes.
     *
     * ⚠ Off still carries a mode. A reader that treated `on = false` as "no mode" would
     * lose the user's choice every time the feature was switched off.
     */
    @Test
    fun `switched off still names a mode`() {
        assertEquals(
            Spatial(on = false, mode = SpatialMode.MUSIC),
            JblSpatial.state(bytes(JblFrames.SPATIAL_OFF_MUSIC)),
        )
    }

    /** Byte-identical to what the vendor app sent for each tile. */
    @Test
    fun `the spatial frames are the ones the vendor app builds`() {
        assertEquals("aa9d03000102", hex(JblSpatial.set(Spatial(true, SpatialMode.MOVIE))))
        assertEquals("aa9d03000103", hex(JblSpatial.set(Spatial(true, SpatialMode.GAME))))
        assertEquals("aa9d03000101", hex(JblSpatial.set(Spatial(true, SpatialMode.MUSIC))))
        assertEquals("aa9d03000001", hex(JblSpatial.set(Spatial(false, SpatialMode.MUSIC))))
        assertEquals("aa9d0101", hex(JblSpatial.get()))
    }

    /**
     * ⚠ A frame for another command must not be read as a spatial one.
     *
     * ⚠ **The obvious version of this test proved nothing and was replaced.** It used
     * the real Smart Talk reply `aa 9f 03 02 00 05`, which has the same length and
     * shape and differs in the command byte — but deleting the command check from
     * [JblSpatial.state] left it passing, because `05` is not a mode and the *mode*
     * check rejected it. Ablated and confirmed: the assertion could not fail.
     *
     * No captured frame can do this job. Smart Talk's last byte is its timeout, only
     * ever `05`, `0f` or `14`, and none of those is a valid mode — so the frame below
     * is SYNTHETIC on purpose: Smart Talk's command with a byte that would decode as
     * Music. It is the only shape that isolates the command check, and with that check
     * removed it reads as `on, MUSIC`.
     */
    @Test
    fun `another command with the same shape is refused`() {
        assertNull(JblSpatial.state(bytes("aa9f03020101")))
        // And the real one, which is refused for a different reason — both are wanted.
        assertNull(JblSpatial.state(bytes("aa9f03020005")))
        // The set operator is not the status operator, and a set is not a reply.
        assertNull(JblSpatial.state(bytes("aa9d03000101")))
        // Truncated: the mode byte is missing.
        assertNull(JblSpatial.state(bytes("aa9d030201")))
    }

    /** An unknown mode is not silently turned into Music. */
    @Test
    fun `a mode byte nobody has seen reads as null`() {
        assertNull(JblSpatial.state(bytes("aa9d03020104")))
        assertNull(JblSpatial.state(bytes("aa9d03020100")))
    }

    // ---- voiceaware --------------------------------------------------------

    @Test
    fun `the voiceaware level is read`() {
        val low = bytes(JblFrames.VOICEAWARE_LOW_ON)
        val high = bytes(JblFrames.VOICEAWARE_HIGH_ON)
        val mid = bytes(JblFrames.VOICEAWARE_MID_ON)
        assertEquals(VoiceAware(on = true, level = VoiceLevel.LOW), JblVoiceAware.state(low))
        assertEquals(VoiceAware(on = true, level = VoiceLevel.HIGH), JblVoiceAware.state(high))
        assertEquals(VoiceAware(on = true, level = VoiceLevel.MID), JblVoiceAware.state(mid))
    }

    /**
     * The cold-launch read, which is the frame that carried the answer all along.
     *
     * ⚠ Its `02` was written up as an unexplained constant for as long as this row has
     * been in the docs. Nothing was wrong with the frame; nobody had moved the slider,
     * so the level and a constant were indistinguishable.
     */
    @Test
    fun `switched off still names a level`() {
        assertEquals(
            VoiceAware(on = false, level = VoiceLevel.MID),
            JblVoiceAware.state(bytes(JblFrames.VOICEAWARE_MID_OFF)),
        )
    }

    @Test
    fun `the voiceaware frames are the ones the vendor app builds`() {
        assertEquals("aa9803000101", hex(JblVoiceAware.set(VoiceAware(true, VoiceLevel.LOW))))
        assertEquals("aa9803000301", hex(JblVoiceAware.set(VoiceAware(true, VoiceLevel.HIGH))))
        assertEquals("aa9803000201", hex(JblVoiceAware.set(VoiceAware(true, VoiceLevel.MID))))
        assertEquals("aa9803000200", hex(JblVoiceAware.set(VoiceAware(false, VoiceLevel.MID))))
        assertEquals("aa980101", hex(JblVoiceAware.get()))
    }

    /**
     * ⚠ Same discipline as the spatial one, and for the same reason.
     *
     * `aa 9d 03 02 01 01` is Spatial Sound: same length, same operator, and its bytes
     * are a VALID level and a valid on — so only the command byte separates them. A
     * real captured frame from another command would not isolate this, which is why
     * this one is chosen rather than found.
     */
    @Test
    fun `a spatial frame is not read as voiceaware`() {
        assertNull(JblVoiceAware.state(bytes("aa9d03020101")))
        assertNull(JblVoiceAware.state(bytes("aa9803000201")))
        assertNull(JblVoiceAware.state(bytes("aa98030202")))
    }

    /** `04` is not a level, and must not become one. */
    @Test
    fun `a level byte nobody has seen reads as null`() {
        assertNull(JblVoiceAware.state(bytes("aa9803020401")))
        assertNull(JblVoiceAware.state(bytes("aa9803020001")))
    }

    // ---- smart talk --------------------------------------------------------

    /**
     * The captured frame, which is also the one this repo once drove by mistake.
     *
     * ⚠ Off still names a hold, exactly as [SPATIAL_OFF_MUSIC] still names a mode —
     * so the same reasoning applies: `off` is not `no timeout`.
     */
    @Test
    fun `smart talk carries its hold while switched off`() {
        assertEquals(
            SmartTalk(on = false, timeout = TalkTimeout.SEC_5),
            JblSmartTalk.state(bytes(JblFrames.SMART_TALK_OFF_5S)),
        )
    }

    @Test
    fun `the smart talk frames are the ones the vendor app builds`() {
        assertEquals("aa9f03000105", hex(JblSmartTalk.set(SmartTalk(true, TalkTimeout.SEC_5))))
        assertEquals("aa9f0300010f", hex(JblSmartTalk.set(SmartTalk(true, TalkTimeout.SEC_15))))
        assertEquals("aa9f03000114", hex(JblSmartTalk.set(SmartTalk(true, TalkTimeout.SEC_20))))
        assertEquals("aa9f03000005", hex(JblSmartTalk.set(SmartTalk(false, TalkTimeout.SEC_5))))
        assertEquals("aa9f0101", hex(JblSmartTalk.get()))
    }

    /**
     * ⚠ Chosen, not captured, for the reason the spatial version of this spells out.
     *
     * `aa 98 03 02 01 05` is VoiceAware's command wearing Smart Talk's shape, with an
     * `on` byte and a byte that IS a valid hold — so nothing but the command check can
     * reject it. Ablated: delete that check and this reads as `on, 5 s`.
     */
    @Test
    fun `another command with the same shape is not read as smart talk`() {
        assertNull(JblSmartTalk.state(bytes("aa9803020105")))
        // A set is not a reply, and a truncated frame is not a short one.
        assertNull(JblSmartTalk.state(bytes("aa9f03000105")))
        assertNull(JblSmartTalk.state(bytes("aa9f030200")))
    }

    /** `0a` is not one of the three holds the device offers. */
    @Test
    fun `a hold nobody has seen reads as null`() {
        assertNull(JblSmartTalk.state(bytes("aa9f0302010a")))
        assertNull(JblSmartTalk.state(bytes("aa9f03020100")))
    }

    // ---- low volume dynamic eq ---------------------------------------------

    @Test
    fun `the low volume eq switch is read`() {
        assertEquals(true, JblLowVolumeEq.state(bytes("aa9e020201")))
        assertEquals(false, JblLowVolumeEq.state(bytes("aa9e020200")))
    }

    @Test
    fun `the low volume eq frames are the ones the vendor app builds`() {
        assertEquals("aa9e020001", hex(JblLowVolumeEq.set(on = true)))
        assertEquals("aa9e020000", hex(JblLowVolumeEq.set(on = false)))
        assertEquals("aa9e0101", hex(JblLowVolumeEq.get()))
    }

    /**
     * ⚠ Same discipline again: `aa 9f 02 02 01` is Smart Talk's command in this
     * command's shape, and every byte but the command is one this reader accepts.
     *
     * The length check earns its place separately — `aa 9e 03 02 01` would put the
     * payload where a three-byte command keeps its first field.
     */
    @Test
    fun `another command with the same shape is not read as low volume eq`() {
        assertNull(JblLowVolumeEq.state(bytes("aa9f020201")))
        assertNull(JblLowVolumeEq.state(bytes("aa9e030201")))
        assertNull(JblLowVolumeEq.state(bytes("aa9e020001")))
        assertNull(JblLowVolumeEq.state(bytes("aa9e0202")))
    }

    // ---- smart audio & video -----------------------------------------------

    /** The three frames the vendor app sends, and nothing else. */
    @Test
    fun `the smart av frames are the ones the vendor app builds`() {
        assertEquals("aa8108000135009600ffff", hex(JblSmartAv.set(SmartAv.AUDIO)))
        assertEquals("aa8108c5002e005000ffff", hex(JblSmartAv.set(SmartAv.VIDEO)))
        assertEquals("aa810800013500e600ffff", hex(JblSmartAv.set(SmartAv.OFF)))
        assertEquals("aa8200", hex(JblSmartAv.get()))
    }

    @Test
    fun `the smart av state is read from the whole payload`() {
        assertEquals(SmartAv.AUDIO, JblSmartAv.state(bytes("aa8308000135009600ffff")))
        assertEquals(SmartAv.VIDEO, JblSmartAv.state(bytes("aa8308c5002e005000ffff")))
        assertEquals(SmartAv.OFF, JblSmartAv.state(bytes("aa830800013500e600ffff")))
    }

    /**
     * ⚠ **Off and Audio differ in ONE byte**, `e6` against `96`, and they are otherwise
     * the same six numbers. A reader matching a prefix, or any byte but the third pair,
     * cannot tell them apart — so the whole payload is compared.
     */
    @Test
    fun `off and audio are separated by their third value`() {
        assertEquals(SmartAv.OFF, JblSmartAv.state(bytes("aa830800013500e600ffff")))
        assertEquals(SmartAv.AUDIO, JblSmartAv.state(bytes("aa8308000135009600ffff")))
    }

    /**
     * ⚠ A payload nobody has captured is null, not the nearest match.
     *
     * `a0` is the value a stated prediction said Video-off would carry. It never
     * appeared on the wire, and a reader that rounded to the closest known frame would
     * have reported it as a state the device was not in — which is how the prediction
     * would have been "confirmed" by its own decoder.
     */
    @Test
    fun `an unknown payload is not rounded to the nearest state`() {
        assertNull(JblSmartAv.state(bytes("aa830800013500a000ffff")))
        // The set command is not the status command.
        assertNull(JblSmartAv.state(bytes("aa8108000135009600ffff")))
        assertNull(JblSmartAv.state(bytes("aa83080001350096")))
    }

    private fun bytes(s: String) = Hex.parse(s)

    private fun hex(b: ByteArray) = Hex.format(b).replace(" ", "")
}
