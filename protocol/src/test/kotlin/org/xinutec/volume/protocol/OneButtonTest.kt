package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OneButtonTest {
    private val bose = "E4:58:BC:3E:9D:AA"
    private val sony = "80:99:E7:F9:D0:61"

    /**
     * ⚠ **The one that would be invisible.** With two pairs connected, acting on the
     * first in the list changes the ANC of the headphones NOT in your ears, and the
     * tile reports success either way — there is no feedback that says it went to the
     * wrong device.
     */
    @Test
    fun `the pair the audio is going to wins`() {
        assertEquals(sony, OneButton.target(listOf(bose, sony), active = sony))
        assertEquals(bose, OneButton.target(listOf(bose, sony), active = bose))
    }

    @Test
    fun `with one connected pair it needs no help`() {
        assertEquals(bose, OneButton.target(listOf(bose), active = null))
    }

    /** Ambiguous and unrouted: refuse rather than guess. */
    @Test
    fun `two connected and no audio route has no answer`() {
        assertNull(OneButton.target(listOf(bose, sony), active = null))
    }

    @Test
    fun `nothing connected has no answer`() {
        assertNull(OneButton.target(emptyList(), active = null))
        assertNull(OneButton.target(emptyList(), active = sony))
    }

    /** An active device that is not drivable does not drag the answer with it. */
    @Test
    fun `an active device outside the list is ignored`() {
        assertEquals(bose, OneButton.target(listOf(bose), active = "AA:BB:CC:DD:EE:FF"))
        assertNull(OneButton.target(listOf(bose, sony), active = "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `the cycle follows the order the driver declares, and wraps`() {
        val modes = listOf(AncMode.OFF, AncMode.ANC, AncMode.AMBIENT)
        assertEquals(AncMode.ANC, OneButton.next(modes, AncMode.OFF))
        assertEquals(AncMode.AMBIENT, OneButton.next(modes, AncMode.ANC))
        assertEquals(AncMode.OFF, OneButton.next(modes, AncMode.AMBIENT))
    }

    /**
     * ⚠ A tap must never resolve to the mode already held: a no-op write is
     * indistinguishable from a broken one, which cost a wrong conclusion on the QC45.
     */
    @Test
    fun `the next mode is never the current one`() {
        for (modes in listOf(
            listOf(AncMode.ANC, AncMode.AMBIENT),
            listOf(AncMode.OFF, AncMode.ANC, AncMode.AMBIENT),
            listOf(AncMode.OFF, AncMode.ANC, AncMode.AMBIENT, AncMode.TALK_THRU),
        )) {
            for (current in modes) {
                assert(OneButton.next(modes, current) != current) {
                    "$modes at $current cycled to itself"
                }
            }
        }
    }

    /** ⚠ A device with one mode cannot cycle — it would return itself. */
    @Test
    fun `a single-mode device is the one case that cannot change`() {
        assertEquals(AncMode.ANC, OneButton.next(listOf(AncMode.ANC), AncMode.ANC))
    }

    /** The JLab reports no mode, ever. The cycle has no anchor; start it. */
    @Test
    fun `an unreadable device starts at the first mode`() {
        val modes = listOf(AncMode.ANC, AncMode.AMBIENT)
        assertEquals(AncMode.ANC, OneButton.next(modes, current = null))
    }

    /** A mode the device does not offer is not an anchor either. */
    @Test
    fun `a current mode outside the list starts the cycle`() {
        assertEquals(
            AncMode.ANC,
            OneButton.next(listOf(AncMode.ANC, AncMode.AMBIENT), AncMode.TALK_THRU),
        )
    }

    @Test
    fun `no modes is a programming error, not a silent default`() {
        assertThrows(IllegalArgumentException::class.java) {
            OneButton.next(emptyList(), AncMode.ANC)
        }
    }
}
