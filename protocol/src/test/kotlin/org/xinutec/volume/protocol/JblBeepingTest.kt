package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/** The vendor SDK's `values_ear_beeping`: 00 stop right, 01 stop left, 10 start right, 11 start left. */
class JblBeepingTest {
    @Test
    fun `each bud and each direction is its own frame`() {
        assertEquals("aa 36 01 11", JblBeeping.set(Bud.LEFT, on = true).toString())
        assertEquals("aa 36 01 10", JblBeeping.set(Bud.RIGHT, on = true).toString())
        assertEquals("aa 36 01 01", JblBeeping.set(Bud.LEFT, on = false).toString())
        assertEquals("aa 36 01 00", JblBeeping.set(Bud.RIGHT, on = false).toString())
    }

    @Test
    fun `a bud is worn exactly when its side reads in`() {
        val w = InEar(left = true, right = false)
        assertEquals(true, w.worn(Bud.LEFT))
        assertEquals(false, w.worn(Bud.RIGHT))
    }
}
