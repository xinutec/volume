package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModeTableTest {
    private val table = ModeTable("a pair", AncMode.OFF to 0x00, AncMode.ANC to 0x01)

    @Test
    fun `every mode it offers reads back from its own byte`() {
        for (m in table.modes) assertEquals(m, table.mode(table.byte(m)))
    }

    @Test
    fun `a byte it does not hold is no mode`() {
        assertNull(table.mode(0x05))
        assertNull(table.mode(null))
    }

    /** ⚠ Thrown before a frame exists, so the controller says "not sent" and keeps the link. */
    @Test(expected = IllegalArgumentException::class)
    fun `a mode it lacks is refused`() {
        table.byte(AncMode.AMBIENT)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `two modes cannot share a byte`() {
        ModeTable("a pair", AncMode.OFF to 0x00, AncMode.ANC to 0x00)
    }
}
