package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ⚠ **Half of these assert that something is ALLOWED, deliberately.** A guard that
 * refuses everything passes every "it refuses X" test ever written, and this repo has
 * already been caught by a suite whose passing cases were all negatives — a grammar
 * matching nothing satisfied all of them. The reads below are the ones this app sends
 * constantly; if any of them starts being refused, the guard has become a wall.
 */
class HazardsTest {
    private fun bytes(hex: String) =
        hex
            .replace(" ", "")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    @Test
    fun `the BES factory reset is refused`() {
        val r = Hazards.check(null, bytes("aa 95 00"))
        assertNotNull("aa 95 must never go out by accident", r)
        assertEquals(true, r!!.why.contains("wipes"))
    }

    @Test
    fun `Bose CLEAR_DEVICE_LIST is refused`() {
        assertNotNull(Hazards.check(Channels.SPP, bytes("04 07 02 00")))
    }

    /** ⚠ The hazard is the PARAMETER, not the command — `38` alone is not it. */
    @Test
    fun `Sony unpair is refused, and the same command with another action is not`() {
        assertNotNull(Hazards.check(Channels.SONY, bytes("38 01 02"), table2 = true))
        assertNull(Hazards.check(Channels.SONY, bytes("38 01 01"), table2 = true))
        assertNull(Hazards.check(Channels.SONY, bytes("38 01 00"), table2 = true))
    }

    /**
     * ⚠ **`38` is PERI_SET_PARAM only on table 2.** On table 1 the same byte is
     * something else entirely, so refusing it there would be a guess dressed as safety.
     */
    @Test
    fun `the Sony unpair check does not fire on table one`() {
        assertNull(Hazards.check(Channels.SONY, bytes("38 01 02"), table2 = false))
    }

    /**
     * ⚠ All three volume actions, and the third is the point: `0x56` VOLUME_CONTROL sits
     * outside the `01`/`02` pair and a hand-written list said two.
     */
    @Test
    fun `every gesture action that changes the volume is refused`() {
        val volume = GestureAction.entries.filter { it.volume }
        assertEquals(3, volume.size)
        for (a in volume) {
            val frame = JblGestures.set(Gesture.LEFT_TAP, a)
            assertNotNull("${a.label} binds a volume change", Hazards.check(null, frame))
        }
    }

    @Test
    fun `a gesture action that does not touch the volume is allowed`() {
        for (a in GestureAction.entries.filterNot { it.volume }) {
            val frame = JblGestures.set(Gesture.LEFT_TAP, a)
            assertNull("${a.label} is ordinary", Hazards.check(null, frame))
        }
    }

    /** The reads this app makes on every settings load must stay allowed. */
    @Test
    fun `ordinary traffic is not refused`() {
        assertNull(Hazards.check(Channels.SONY, SonyEq.get()))
        assertNull(Hazards.check(Channels.SONY, SonyEq.setLevels(listOf(0, 0, 0, 0, 0, 0))))
        assertNull(Hazards.check(Channels.SONY, SonyBattery.get()))
        assertNull(Hazards.check(Channels.SONY, SonyVoiceGuidance.set(true), table2 = true))
        assertNull(Hazards.check(Channels.SPP, BoseEq.get()))
        assertNull(Hazards.check(null, JblGestures.get()))
        assertNull(Hazards.check(null, JblPowerOff.off()))
    }

    /**
     * ⚠ **The two bytes that must never be confused**, and they are two apart. Switching
     * the JBL off costs a walk to the headphones; `aa 95` costs the gestures, the
     * equaliser and a hearing profile with no getter. This asserts the guard separates
     * them rather than refusing the neighbourhood.
     */
    @Test
    fun `power off is allowed and the factory reset beside it is not`() {
        assertNull(Hazards.check(null, bytes("aa 97 00")))
        assertNotNull(Hazards.check(null, bytes("aa 95 00")))
    }

    @Test
    fun `an empty payload is not a hazard`() {
        assertNull(Hazards.check(Channels.SONY, ByteArray(0)))
    }

    /**
     * ⚠ A frame too short to carry the dangerous parameter must not be refused on the
     * command byte alone — that would make `38` unusable for the reads it also serves.
     */
    @Test
    fun `a truncated frame is judged on what it actually contains`() {
        assertNull(Hazards.check(Channels.SONY, bytes("38"), table2 = true))
        assertNull(Hazards.check(Channels.SONY, bytes("38 01"), table2 = true))
    }
}
