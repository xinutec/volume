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

    override fun exchange(packet: ByteArray): ByteArray {
        val hex = Hex.format(packet)
        sent += hex
        check(at < steps.size) { "unexpected extra exchange: $hex" }
        val (expected, reply) = steps[at++]
        assertEquals("exchange ${at - 1} sent the wrong bytes", expected, hex)
        return Hex.parse(reply.replace(" ", ""))
    }

    override fun send(packet: ByteArray) {
        sent += Hex.format(packet)
    }

    /** Fail if the driver stopped early — a missing write is as wrong as a bad one. */
    fun assertDrained() {
        assertEquals("driver did not perform every recorded exchange", steps.size, at)
    }
}
