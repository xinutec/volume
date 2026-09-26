package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfirmTest {
    @Test
    fun `a read-back equal to the request confirms it`() {
        assertEquals(Confirmation.Confirmed, confirm(want = 3, after = 3))
    }

    @Test
    fun `a mismatch carries what was read, not what was asked for`() {
        assertEquals(Confirmation.Contradicted(5), confirm(want = 3, after = 5))
    }

    @Test
    fun `no read-back cannot confirm`() {
        assertEquals(Confirmation.Unverifiable, confirm(want = 3, after = null))
    }

    @Test
    fun `a judged read-back carries the whole reading when it fails`() {
        val table = listOf(1, 2)
        assertEquals(Confirmation.Confirmed, confirmBy(table) { 2 in it })
        assertEquals(Confirmation.Contradicted(table), confirmBy(table) { 9 in it })
        assertEquals(Confirmation.Unverifiable, confirmBy<List<Int>>(null) { true })
    }
}
