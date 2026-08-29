package org.xinutec.volume.protocol

/**
 * Say what a frame MEANS, so it can be read before it is sent.
 *
 * ⚠ **This exists because hex is not checkable by a person.** `aa 77 03 00 06 05` and
 * `aa 77 03 00 05 06` differ by a transposition and bind a different button to a
 * different action; "left button → next track" does not. #1038 asked for it as
 * dry-run-by-default, and the value is entirely in the reading, not in the sending.
 *
 * ⚠⚠ **An unknown command MUST read as unknown.** This text is consulted INSTEAD of the
 * bytes — that is the point of it — so a guessed name does not merely mislead, it sends
 * the wrong frame wearing a confident label. Every path here either names something it
 * can derive or says plainly that it cannot. There is no "probably".
 */
object Frames {
    /**
     * The BES commands this repo has actually decoded.
     *
     * ⚠ **Hand-maintained, and that is a known cost** — a new `JblXxx` object added
     * without a line here reads as "unknown command", which is the SAFE direction: it
     * degrades to honest hex rather than to a wrong name. Deriving it would need
     * reflection over the objects, which this module deliberately does not use.
     */
    private val BES_NAMES =
        mapOf(
            0x21 to "status get",
            0x22 to "status reply",
            0x25 to "battery",
            0x77 to "gestures",
            0x82 to "smart audio/video get",
            0x83 to "smart audio/video reply",
            0x91 to "ANC",
            0x93 to "voice prompts",
            0x94 to "serial number",
            0x98 to "VoiceAware",
            0x99 to "environment noise check",
            0x9a to "ear canal test",
            0x9b to "multi status",
            0x9d to "spatial sound",
            0x9e to "low volume dynamic EQ",
            0x9f to "smart talk",
            0xa0 to "sound amplification (PSAP)",
            0xa1 to "Personi-Fi",
            0xa2 to "EQ curve",
            0xa5 to "max volume limiter",
            0xa8 to "left/right balance",
            0xb0 to "LE audio",
            0xb1 to "feature get/set",
        )

    /** The BMAP operators, from `docs/bose-read-surface.md`. */
    private val BOSE_OPERATORS =
        mapOf(
            0x00 to "set",
            0x01 to "get",
            0x02 to "set and get",
            0x03 to "status",
            0x04 to "error",
            0x05 to "start",
            0x06 to "result",
            0x07 to "processing",
        )

    /**
     * A sentence describing [payload] as sent to [uuid], or to GATT when that is null.
     *
     * [table2] distinguishes the Sony's two command tables, as in [Hazards.check] — the
     * same byte means different things on each and the payload cannot say which.
     */
    fun describe(uuid: String?, payload: ByteArray, table2: Boolean = false): String =
        when {
            payload.isEmpty() -> "nothing to send"
            uuid.equals(Channels.SONY, ignoreCase = true) -> sony(payload, table2)
            uuid.equals(Channels.SPP, ignoreCase = true) -> bose(payload)
            payload[0] == Bes.HEADER -> bes(payload)
            else -> "${payload.size} bytes, no framing recognised: ${hex(payload)}"
        }

    private fun bose(payload: ByteArray): String {
        if (payload.size < 4) return "a short Bose frame: ${hex(payload)}"
        val block = payload[0].toInt() and 0xff
        val fn = payload[1].toInt() and 0xff
        val op = payload[2].toInt() and 0xff
        val len = payload[3].toInt() and 0xff
        val opName = BOSE_OPERATORS[op] ?: "operator %02x (unknown)".format(op)
        return "Bose block %02x function %02x, %s, %d payload byte%s"
            .format(block, fn, opName, len, if (len == 1) "" else "s")
    }

    private fun bes(payload: ByteArray): String {
        val cmd =
            payload.getOrNull(1)?.toInt()?.and(0xff)
                ?: return "a lone aa header, nothing to send"
        gesture(payload)?.let { return it }
        val name =
            BES_NAMES[cmd]?.let { "$it (aa %02x)".format(cmd) }
                ?: "aa %02x — unknown command".format(cmd)
        val shape =
            when {
                payload.size == 4 && payload[2] == 0x01.toByte() -> "read"
                payload.getOrNull(3) == 0x00.toByte() -> "write"
                else -> "${payload.size - 3} payload byte(s)"
            }
        return "$name, $shape"
    }

    /**
     * `aa 77 03 <SET> <gesture> <action>` spelled out as the binding it makes.
     *
     * ⚠ **The action is the SIXTH byte.** Reading index 4 gets the GESTURE, which is the
     * off-by-one that once let all three volume bindings past [Hazards] — see its note.
     */
    private fun gesture(payload: ByteArray): String? {
        if (payload.getOrNull(1) != JblGestures.CMD) return null
        val which = payload.getOrNull(4) ?: return null
        val action = payload.getOrNull(5) ?: return null
        val g = Gesture.entries.firstOrNull { it.wire == which } ?: return null
        val a = GestureAction.entries.firstOrNull { it.wire == action } ?: return null
        return "gestures (aa 77), write: ${g.label} → ${a.label}"
    }

    private fun sony(payload: ByteArray, table2: Boolean): String {
        val cmd = payload[0].toInt() and 0xff
        val table = if (table2) "table 2" else "table 1"
        return "Sony $table command %02x, %d byte%s: %s"
            .format(cmd, payload.size, if (payload.size == 1) "" else "s", hex(payload))
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }
}
