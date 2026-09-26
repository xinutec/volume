package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/** A setter reaches a driver only through these, so membership is what keeps frames off the wrong device. */
class CapabilitiesTest {
    private val sony = Drivers.SonyXm4()
    private val all: List<AncDriver> =
        listOf(
            Drivers.BoseQc45,
            Drivers.BoseQc35,
            Drivers.BoseRevolve,
            Drivers.JblBes,
            Drivers.JblLivePro2,
            sony,
            Drivers.JLabQcy,
        )

    @Test
    fun `the shared BES settings are the two JBLs' only`() {
        assertEquals(
            listOf(Drivers.JblBes, Drivers.JblLivePro2),
            all.filter { it is JblSharedSettings },
        )
    }

    @Test
    fun `spatial sound is the Tour One M2's and the JLab's`() {
        assertEquals(listOf(Drivers.JblBes, Drivers.JLabQcy), all.filter { it is SpatialDriver })
    }

    @Test
    fun `smart audio and video is both JBLs'`() {
        assertEquals(
            listOf(Drivers.JblBes, Drivers.JblLivePro2),
            all.filter { it is SmartAvDriver },
        )
    }
}
