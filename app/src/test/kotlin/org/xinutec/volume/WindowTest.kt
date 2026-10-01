package org.xinutec.volume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xinutec.volume.protocol.Bes
import org.xinutec.volume.protocol.OutFrame

/** The reply window both transports read through. */
class WindowTest {
    /** Hands out [chunks] one per call, then nothing; records how long each wait was. */
    private class Source(
        vararg chunks: String,
    ) {
        private val left = ArrayDeque(chunks.map { it.toByteArray() })
        val waits = mutableListOf<Long>()

        fun next(waitMs: Long): ByteArray? {
            waits += waitMs
            return left.removeFirstOrNull()
        }
    }

    @Test
    fun `chunks are joined until a quiet wait`() {
        val s = Source("ab", "cd")
        assertEquals("abcd", String(window(1_000, 50, s::next)))
        // Two chunks, then one wait that came back empty and closed the window.
        assertEquals(3, s.waits.size)
    }

    @Test
    fun `nothing yet is not quiet — the window waits out its whole length`() {
        val waits = mutableListOf<Long>()
        val got =
            window(120, 50, { w ->
                waits += w
                Thread.sleep(w)
                null
            })
        assertEquals(0, got.size)
        assertTrue("waited ${waits.sum()} ms", waits.sum() >= 100)
    }

    @Test
    fun `a complete reply ends the window at once`() {
        val s = Source("ab", "cd", "ef")
        assertEquals("abcd", String(window(1_000, 50, s::next) { it.size >= 4 }))
    }

    @Test
    fun `each ack is sent once, as it is found`() {
        val sent = mutableListOf<OutFrame>()
        val ack = Bes.encode(0x01)
        val onData = acking({ got -> List(got.size / 2) { ack } }, sent::add)
        val s = Source("ab", "cd")
        window(1_000, 50, s::next, onData)
        assertEquals(2, sent.size)
    }
}
