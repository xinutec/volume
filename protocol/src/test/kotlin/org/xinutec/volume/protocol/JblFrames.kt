package org.xinutec.volume.protocol

/**
 * Whole `aa a2` frames off the wire, 2026-08-16 (`docs/captures.md`).
 *
 * Shared by [JblSettingsTest] and [DriversTest] rather than pasted into both: 120
 * bytes of hex copied twice is 120 chances for the copy to be the thing under test.
 *
 * ⚠ Taken with `tshark -e btatt.value` — the JBL is GATT, so the `data.data` filter
 * that works for the Sony and the Bose returns nothing at all for it.
 */
object JblFrames {
    /** 20:35:49 — the answer to `aa a2 02 01 ff`, as the headphones were found. */
    const val FLAT =
        "aaa274000200000000003000000000000000000a010000000000000042010100000000" +
            "000080420101000000000000fa4201010000000000007a430101000000000000fa43" +
            "01010000000000007a440101000000000000fa4401010000000000007a4501010000" +
            "00000000fa4501010000000000007a4601"

    /** 20:36:48 — what the vendor app sent for JAZZ. */
    const val JAZZ_SENT =
        "aaa274000001000000003000000000000000000a0100008040000000420101000000400000" +
            "804201010000803f0000fa4201010000204000007a4301010000c0bf0000fa430101" +
            "0000c0bf00007a440101000000000000fa4401010000803f00007a45010100000040" +
            "0000fa4501010000804000007a4601"

    /** …and the echo it drew back, differing only in the operator byte. */
    const val JAZZ_ECHO =
        "aaa274000201000000003000000000000000000a0100008040000000420101000000400000" +
            "804201010000803f0000fa4201010000204000007a4301010000c0bf0000fa430101" +
            "0000c0bf00007a440101000000000000fa4401010000803f00007a45010100000040" +
            "0000fa4501010000804000007a4601"

    /**
     * A `c9` table in the shape the device sends — **synthesised, not captured**.
     *
     * ⚠ Kept because it is the frame a size-only guard decodes as an equaliser: 196
     * bytes of records against a curve's 116, so "big enough" passes it.
     *
     * ⚠⚠ **The captured frame was here until 2026-09-11 and must not come back.**
     * `c9` is PERSONIFY_EQ, so its gains are one person's hearing compensation, and
     * this repo is public. Every byte below is fixed by the protocol — the 18 bands
     * are 9 per ear on the test's own 250 Hz–12 kHz grid — and every gain is zero,
     * which is what makes it safe to commit and still exactly what the guard must
     * reject.
     */
    const val TABLE_C9 =
        "aaa2c40002c90000000030000000000000000012010000000000007a430101000000" +
            "000000fa4301010000000000007a440101000000000000fa4401010000000000007a" +
            "450101000000000080bb450101000000000000fa4501010000000000401c46010100" +
            "00000000803b4601010000000000007a430101000000000000fa4301010000000000" +
            "007a440101000000000000fa4401010000000000007a450101000000000080bb4501" +
            "01000000000000fa4501010000000000401c4601010000000000803b4601"

    /** 2026-08-17 09:02 — `aa b1 03 00 01 00` answered: LE Audio is off. */
    const val FEATURE_LE_AUDIO_OFF = "aab10402010100"

    /** …and `aa b1 03 00 02 00`: Auracast is on. */
    const val FEATURE_AURACAST_ON = "aab10402020101"

    /**
     * 09:05 — key `03`'s answer, **glued to an unsolicited battery frame**.
     *
     * ⚠ This is the frame that says a reader must stop at the length byte. Read to
     * the end of the buffer and `aa 25 0d …` parses as three more key/value triples.
     */
    const val FEATURE_03_OFF_THEN_BATTERY =
        "aab10402030100aa250d0100004646ffffffffffffffff"

    /**
     * 2026-08-17 11:11:28 / :35 / :43 — Movie, Game, Music, each from ONE tap on the
     * mode tile with no toggling in between. The three replies differ in the last byte
     * and nowhere else, which is what makes the mode byte a measurement rather than a
     * reading of one frame.
     */
    const val SPATIAL_MOVIE_ON = "aa9d03020102"
    const val SPATIAL_GAME_ON = "aa9d03020103"
    const val SPATIAL_MUSIC_ON = "aa9d03020101"

    /**
     * 11:11:53 — the switch turned off, and 10:40:01, the cold-launch read.
     *
     * ⚠ Identical frames from a *write* and from a *get*, which is the point: the mode
     * byte survives the feature being switched off, so `off` does not mean `mode 00`.
     */
    const val SPATIAL_OFF_MUSIC = "aa9d03020001"

    /**
     * 2026-08-17 12:13:41 / :45 / :46 — Low, High, Mid, from Pippijn dragging the bar.
     *
     * ⚠ The only way these could be got: the bar takes a gesture, and two runs that
     * tried to reach it by tapping produced clean logs and no level traffic.
     */
    const val VOICEAWARE_LOW_ON = "aa9803020101"
    const val VOICEAWARE_HIGH_ON = "aa9803020301"
    const val VOICEAWARE_MID_ON = "aa9803020201"

    /** 10:40:01, the cold-launch read: Mid, and off. */
    const val VOICEAWARE_MID_OFF = "aa9803020200"

    /**
     * 2026-08-17 09:20 and 10:40 — the same frame twice: Smart Talk off, hold 5 s.
     *
     * ⚠ This is the frame a whole session mistook for VoiceAware's. It is why every
     * decoder in `JblSettings.kt` checks the command byte rather than the shape.
     */
    const val SMART_TALK_OFF_5S = "aa9f03020005"

    /** As [Replay] wants them: lowercase, space-separated. */
    fun spaced(hex: String): String = Hex.format(Hex.parse(hex))
}
