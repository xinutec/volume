package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeasesTest {
    private val a = "E4:58:BC:3E:9D:AA"
    private val b = "80:99:E7:F9:D0:61"

    @Test
    fun `a lease expires once it has been idle long enough`() {
        val leases = Leases(idleMs = 5_000)
        leases.begin(a)
        leases.end(a, now = 1_000)
        assertTrue(leases.expired(now = 5_999).isEmpty())
        assertEquals(setOf(a), leases.expired(now = 6_000))
    }

    /**
     * ⚠ **The edge this class exists for.** A read can take twenty-five seconds when
     * the device has to be found by an LE scan first. Sweeping on wall-clock alone
     * would close the channel underneath it, and the resulting failure would read as
     * the headphones misbehaving rather than as us hanging up on them.
     */
    @Test
    fun `work in flight never expires, however long it runs`() {
        val leases = Leases(idleMs = 1_000)
        leases.begin(a)
        assertTrue(leases.expired(now = 1_000_000).isEmpty())
        leases.end(a, now = 1_000_000)
        assertTrue(leases.expired(now = 1_000_000).isEmpty())
        assertEquals(setOf(a), leases.expired(now = 1_001_000))
    }

    /** Using it again puts the clock back — a burst of taps is one interaction. */
    @Test
    fun `beginning again resets an idle lease`() {
        val leases = Leases(idleMs = 5_000)
        leases.begin(a)
        leases.end(a, now = 0)
        leases.begin(a)
        assertTrue(leases.expired(now = 100_000).isEmpty())
        leases.end(a, now = 100_000)
        assertEquals(setOf(a), leases.expired(now = 105_000))
    }

    @Test
    fun `leases are independent`() {
        val leases = Leases(idleMs = 5_000)
        leases.begin(a)
        leases.end(a, now = 0)
        leases.begin(b)
        assertEquals(setOf(a), leases.expired(now = 10_000))
    }

    @Test
    fun `forgetting removes it from both idle and in-flight`() {
        val leases = Leases(idleMs = 1_000)
        leases.begin(a)
        leases.forget(a)
        assertTrue(leases.idle())
        leases.begin(b)
        leases.end(b, now = 0)
        leases.forget(b)
        assertTrue(leases.expired(now = 100_000).isEmpty())
        assertTrue(leases.idle())
    }

    @Test
    fun `nothing held reads as idle, so a sweep can stop rescheduling`() {
        val leases = Leases(idleMs = 1_000)
        assertTrue(leases.idle())
        leases.begin(a)
        assertFalse(leases.idle())
        leases.end(a, now = 0)
        assertFalse(leases.idle())
        leases.forget(a)
        assertTrue(leases.idle())
    }

    /** Exactly at the boundary it has expired — the comparison is inclusive. */
    @Test
    fun `the boundary belongs to expiry`() {
        val leases = Leases(idleMs = 5_000)
        leases.begin(a)
        leases.end(a, now = 0)
        assertEquals(setOf(a), leases.expired(now = 5_000))
    }
}
