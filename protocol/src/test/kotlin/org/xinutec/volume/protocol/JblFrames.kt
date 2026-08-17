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
     * 20:35:50 — `aa a2 02 01 c9`, a longer table in the very same record shape.
     *
     * ⚠ Kept because it is the frame a size-only guard decodes as an equaliser.
     */
    const val TABLE_C9 =
        "aaa2c40002c90000000030000000000000000012000000000000007a4301016766e63f00" +
            "00fa4302010000000000007a440201000000000000fa4402010000000000007a4503" +
            "01676606410080bb450401000010410000fa450501cdccac4000401c460602676606" +
            "4100803b4601000000000000007a430101000000000000fa4302010000000000007a" +
            "440201000000000000fa4402010000000000007a450301000000000080bb45040200" +
            "0000400000fa4501010000000000401c4606020000000000803b4601"

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

    /** As [Replay] wants them: lowercase, space-separated. */
    fun spaced(hex: String): String = Hex.format(Hex.parse(hex))
}
