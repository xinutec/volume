package org.xinutec.volume.protocol

import org.junit.Assert.assertThrows
import org.junit.Test

/** A value that does not fit what the device takes is refused before a frame exists. */
class WireRangesTest {
    @Test
    fun `sony eq levels stay inside the device's range`() {
        SonyEq.setLevels(listOf(-10, 0, 10))
        assertThrows(IllegalArgumentException::class.java) { SonyEq.setLevels(listOf(0, 11)) }
        assertThrows(IllegalArgumentException::class.java) { SonyEq.setLevels(listOf(-11)) }
    }

    @Test
    fun `values read back as one byte cannot be built wider`() {
        Balance(on = true, level = 0x64)
        TimedOff(on = true, minutes = 120)
        assertThrows(IllegalArgumentException::class.java) { Balance(on = true, level = 256) }
        assertThrows(IllegalArgumentException::class.java) { TimedOff(on = true, minutes = 300) }
        assertThrows(IllegalArgumentException::class.java) { TimedOff(on = true, minutes = -1) }
    }

    @Test
    fun `only the offered standby times and named presets are sent`() {
        BoseStandbyTimer.set(180)
        JblEqPreset.set(0x04)
        assertThrows(IllegalArgumentException::class.java) { BoseStandbyTimer.set(7) }
        assertThrows(IllegalArgumentException::class.java) { JblEqPreset.set(0x09) }
    }
}
