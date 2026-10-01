package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every JBL reply is found wherever it lands in the buffer.
 *
 * ⚠ The chip volunteers `aa 25` battery every ten seconds, and when it arrives before
 * the answer a decoder reading offset 0 returns null and the row vanishes (#1154). These
 * were the reads and writes that still decoded at offset 0.
 */
class BesAskTest {
    private val battery = "aa 25 02 01 50 "

    @Test
    fun `the LIVE PRO 2's in-ear read survives a battery frame in front`() {
        val t = Replay("aa 21 01 41" to battery + "aa 22 03 41 01 00")
        assertEquals(InEar(left = true, right = false), Drivers.JblLivePro2.readInEar(t))
    }

    @Test
    fun `the LIVE PRO 2's eq read survives a battery frame in front`() {
        val t = Replay("aa 21 01 34" to battery + "aa 22 02 34 05")
        assertEquals(EqSetting(5, emptyList()), Drivers.JblLivePro2.readEq(t))
    }

    @Test
    fun `an M2 write whose reply is the state survives a battery frame in front`() {
        val on = Spatial(on = true, mode = SpatialMode.MOVIE)
        val sent = Hex.format(JblSpatial.set(on).bytes)
        val reply = sent.replaceRange(9, 11, "02")
        val t = Replay(sent to battery + reply)
        assertEquals(Confirmation.Confirmed, Drivers.JblBes.setSpatial(t, on))
    }
}
