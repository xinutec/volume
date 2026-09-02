package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals

/**
 * A [Transport] that replays a recorded exchange, so a driver can be tested
 * without a phone, a pairing, or the headphones being switched on.
 *
 * ⚠ **These fixtures are real bytes**, copied from the snoop captures and probe
 * output of 2026-08-15 — not invented to match the code. A hand-written fixture
 * only proves the driver agrees with whoever wrote the fixture, which in this repo
 * has been wrong about a device's behaviour more often than the device has.
 *
 * The recording is strict about what was *sent*: a driver that changes a byte
 * fails here rather than silently drifting from the measurement. What comes back
 * is whatever the device actually said, including its noise.
 */
class Replay(
    private vararg val steps: Pair<String, String>,
) : Transport {
    private var at = 0

    /** Everything the driver sent, so a test can assert on writes with no reply. */
    val sent = mutableListOf<String>()

    /**
     * ⚠ **A Sony-framed fixture is checked the moment it is used, not in a list.**
     *
     * `DriversTest` has a guard that walks named fixtures and asserts their checksums,
     * because two transcripts were once written by hand with invented ones and every
     * test still passed. On 2026-08-23 that guard was found to cover **16 of 36** frame
     * literals in the file — the rest are written inline in `Replay(...)` calls, where
     * nothing looked at them, and one of those had a checksum that could not occur
     * (`9a` where the bytes sum to `99`).
     *
     * A list someone has to remember to extend is the wrong shape for this. Validating
     * here catches every fixture, including ones not yet written.
     *
     * ⚠ Only frames that look Sony-framed — `3e … 3c`. The JBL and Bose fixtures are
     * bare payloads with no checksum to check, and must pass through untouched.
     */
    private fun validate(hex: String) {
        for (frame in Regex("3e(?: [0-9a-f]{2})+? 3c").findAll(hex).map { it.value }) {
            val b = SonyFrame.unescape(Hex.parse(frame.replace(" ", "")))
            if (b.size < 9) continue
            val body = b.copyOfRange(1, b.size - 1)
            val declared =
                ((body[2].toInt() and 0xff) shl 24) or
                    ((body[3].toInt() and 0xff) shl 16) or
                    ((body[4].toInt() and 0xff) shl 8) or
                    (body[5].toInt() and 0xff)
            assertEquals(
                "$frame declares a payload length it does not carry",
                declared,
                body.size - 7,
            )
            val sum = body.dropLast(1).fold(0) { a, x -> a + (x.toInt() and 0xff) } and 0xff
            assertEquals(
                "$frame has a checksum the device could not have sent",
                sum,
                body.last().toInt() and 0xff,
            )
        }
    }

    /**
     * ⚠ **The acks land in [sent], in the order a real transport would emit them** —
     * after the packet that drew the reply and before the next one. On the wire they go
     * out mid-window rather than at its end, but nothing observable here distinguishes
     * those, and recording them is what lets a test assert the driver acks at all.
     */
    override fun exchange(packet: OutFrame, acksFor: (ByteArray) -> List<OutFrame>): ByteArray {
        val reply = exchange(packet)
        acksFor(reply).forEach(::send)
        return reply
    }

    override fun exchange(packet: OutFrame): ByteArray {
        val hex = Hex.format(packet.bytes)
        sent += hex
        check(at < steps.size) { "unexpected extra exchange: $hex" }
        val (expected, reply) = steps[at++]
        validate(reply)
        assertEquals("exchange ${at - 1} sent the wrong bytes", expected, hex)
        return Hex.parse(reply.replace(" ", ""))
    }

    override fun send(packet: OutFrame) {
        sent += Hex.format(packet.bytes)
    }

    /**
     * Frames the device volunteers, handed out one per [receive].
     *
     * ⚠ Set by the test, not by the constructor, because these are **not** replies to
     * anything — pairing them with a request in [steps] would model the very thing
     * that is wrong about the real device.
     */
    var volunteered: List<String> = emptyList()

    private var handed = 0

    /** Empty once [volunteered] runs out, which is what a quiet device looks like. */
    override fun receive(): ByteArray {
        if (handed >= volunteered.size) return ByteArray(0)
        return Hex.parse(volunteered[handed++].replace(" ", ""))
    }

    /** Fail if the driver stopped early — a missing write is as wrong as a bad one. */
    fun assertDrained() {
        assertEquals("driver did not perform every recorded exchange", steps.size, at)
    }
}
