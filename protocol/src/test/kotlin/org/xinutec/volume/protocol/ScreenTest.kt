package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTest {
    private val screen =
        Screen(
            listOf(
                DeviceCard("Bose QC Headphones", "E4:58:BC:3E:9D:AA", DeviceState.Idle),
                DeviceCard("JLab JBuds Sport ANC 4", "EC:9A:0C:E0:D2:96", DeviceState.Idle),
            ),
        )

    @Test
    fun `updating one card leaves the others and the order alone`() {
        val next = screen.with("EC:9A:0C:E0:D2:96", DeviceState.Busy("connecting"))
        assertEquals(
            listOf("E4:58:BC:3E:9D:AA", "EC:9A:0C:E0:D2:96"),
            next.cards.map { it.address },
        )
        assertEquals(DeviceState.Idle, next.cards[0].state)
        assertTrue(next.cards[1].state is DeviceState.Busy)
    }

    /** The device's own name replaces the bonded one without disturbing anything. */
    @Test
    fun `a rename touches one card and keeps its state`() {
        val busy = screen.with("E4:58:BC:3E:9D:AA", DeviceState.Busy("reading"))
        val named = busy.renamed("E4:58:BC:3E:9D:AA", "Pippijn Bose QC35")
        assertEquals("Pippijn Bose QC35", named.cards[0].name)
        assertTrue(named.cards[0].state is DeviceState.Busy)
        assertEquals("JLab JBuds Sport ANC 4", named.cards[1].name)
    }

    @Test
    fun `an address that is not on screen changes nothing`() {
        assertEquals(screen, screen.with("00:00:00:00:00:00", DeviceState.Idle))
        assertEquals(screen, screen.renamed("00:00:00:00:00:00", "nobody"))
    }

    /**
     * ⚠ The case the type exists for. A device with no read command reports a null
     * mode forever, and a UI that treats null as "still loading" spins for ever.
     */
    @Test
    fun `a device with no read command is ready, not pending`() {
        val ready = DeviceState.Ready("JLab", listOf(AncMode.ANC, AncMode.AMBIENT), mode = null)
        assertNull(ready.mode)
        assertEquals(2, ready.modes.size)
    }

    @Test
    fun `modes are offered only once we know what the device is`() {
        assertTrue(DeviceCard("x", "y", DeviceState.Idle).offer.isEmpty())
        assertTrue(DeviceCard("x", "y", DeviceState.Busy("scanning")).offer.isEmpty())
        assertTrue(DeviceCard("x", "y", DeviceState.Unavailable("off")).offer.isEmpty())
        assertEquals(
            listOf(AncMode.ANC),
            DeviceCard("x", "y", DeviceState.Ready("m", listOf(AncMode.ANC), AncMode.ANC)).offer,
        )
    }

    private val label: (AncMode) -> String = {
        if (it ==
            AncMode.ANC
        ) {
            "Noise cancelling"
        } else {
            "$it"
        }
    }

    /**
     * ⚠ **The one that matters.** An unconfirmable write must not read like a
     * confirmed one, or the JLab's "success" — which it returns for modes that do
     * not exist — gets laundered into a tick on screen.
     */
    @Test
    fun `an unverifiable result never reads like a confirmed one`() {
        val confirmed = Confirmation.Confirmed.note(AncMode.ANC, label)
        val unverifiable = Confirmation.Unverifiable.note(AncMode.ANC, label)
        assertNotEquals(confirmed, unverifiable)
        assertTrue(unverifiable!!.text.contains("cannot confirm"))
        assertEquals(NoteKind.CAUTION, unverifiable.kind)
        assertNull(Confirmation.Unverifiable.resulting(AncMode.ANC))
    }

    /**
     * ⚠ A confirmed write says nothing: the selected control already carries it,
     * and a line repeating it is noise that teaches the eye to skip the line that
     * matters. This was wrong on the first render — it printed "ANC" in the colour
     * reserved for something being off.
     */
    @Test
    fun `a confirmed write adds no note at all`() {
        assertNull(Confirmation.Confirmed.note(AncMode.ANC, label))
    }

    @Test
    fun `a contradicted write shows what the device actually says, and as a problem`() {
        val c = Confirmation.Contradicted(AncMode.AMBIENT).note(AncMode.ANC, label)!!
        assertTrue(c.text.contains("AMBIENT"))
        assertEquals(NoteKind.PROBLEM, c.kind)
        assertEquals(
            AncMode.AMBIENT,
            Confirmation.Contradicted(AncMode.AMBIENT).resulting(AncMode.ANC),
        )
    }

    /** The vendors' words come from the caller, so :protocol holds no UI copy. */
    @Test
    fun `notes use the label the caller supplies`() {
        val c = Confirmation.Contradicted(AncMode.OFF).note(AncMode.ANC, label)!!
        assertTrue(c.text.contains("Noise cancelling"))
    }

    @Test
    fun `a confirmed write settles on what was asked for`() {
        assertEquals(AncMode.ANC, Confirmation.Confirmed.resulting(AncMode.ANC))
    }

    /** A failure keeps its reason: "error" is not a thing anyone can act on. */
    @Test
    fun `unavailability carries why`() {
        val s = DeviceState.Unavailable("not advertising right now")
        assertTrue(s.why.isNotBlank())
    }
}
