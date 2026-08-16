package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    /**
     * ⚠ Headphones come and go while the app is open, and the list must follow
     * without losing what it already knows. A rebuild would drop the mode, the
     * device-reported name and the note, then reopen every session to learn them
     * again — visibly, as five cards blinking back to "connecting".
     */
    @Test
    fun `reconciling keeps what is known about devices that are still here`() {
        val live =
            screen
                .with(
                    "E4:58:BC:3E:9D:AA",
                    DeviceState.Ready("Bose QC45", listOf(AncMode.ANC), AncMode.ANC),
                ).renamed("E4:58:BC:3E:9D:AA", "Pippijn Bose QC45")

        val next =
            live.reconciled(
                listOf(
                    "E4:58:BC:3E:9D:AA" to "Bose QC Headphones",
                    "EC:9A:0C:E0:D2:96" to "JLab JBuds Sport ANC 4",
                ),
                Emptiness.NONE_CONNECTED,
            )

        // Untouched: still Ready, still under the name it reported for itself.
        assertEquals("Pippijn Bose QC45", next.cards[0].name)
        assertTrue(next.cards[0].state is DeviceState.Ready)
        assertTrue(next.cards[1].state is DeviceState.Idle)
    }

    @Test
    fun `a device that has gone is dropped, and a new one arrives idle`() {
        val next =
            screen.reconciled(
                listOf("80:99:E7:F9:D0:61" to "WH-1000XM4"),
                Emptiness.NONE_CONNECTED,
            )
        assertEquals(listOf("80:99:E7:F9:D0:61"), next.cards.map { it.address })
        assertTrue(next.cards[0].state is DeviceState.Idle)
        // A populated screen must NOT carry a reason it is empty.
        assertNull(next.emptiness)
    }

    @Test
    fun `reconciling to nothing empties the list, and says why`() {
        val next = screen.reconciled(emptyList(), Emptiness.BLUETOOTH_OFF)
        assertTrue(next.cards.isEmpty())
        assertEquals(Emptiness.BLUETOOTH_OFF, next.emptiness)
    }

    /**
     * ⚠ **The defect this type replaces.** Every empty list rendered one sentence,
     * "No headphones bonded to this phone", and it was false in four of the five
     * cases — measured on 2026-08-16 with thirteen devices bonded. The radio being
     * off is the one that bites, because `bondedDevices` returns an empty set then
     * rather than failing, so the honest answer and the misleading one are the same
     * value and only the caller can tell them apart.
     */
    @Test
    fun `an empty screen must say why, and the reasons are distinct`() {
        val off = Screen(emptyList(), Emptiness.BLUETOOTH_OFF)
        val quiet = Screen(emptyList(), Emptiness.NONE_CONNECTED)
        assertNotEquals(off, quiet)
        assertThrows(IllegalArgumentException::class.java) { Screen(emptyList()) }
    }

    @Test
    fun `a populated screen may not claim to be empty`() {
        assertThrows(IllegalArgumentException::class.java) {
            Screen(listOf(DeviceCard("x", "y", DeviceState.Idle)), Emptiness.NONE_BONDED)
        }
    }

    /** Updating cards leaves the (absent) reason alone — the count cannot change. */
    @Test
    fun `with and renamed preserve the invariant`() {
        val next = screen.with("E4:58:BC:3E:9D:AA", DeviceState.Busy("x"))
        assertNull(next.emptiness)
        assertNull(screen.renamed("E4:58:BC:3E:9D:AA", "n").emptiness)
    }

    /** The caller's order wins, so the list does not reshuffle as devices arrive. */
    @Test
    fun `reconciling draws them in the order given`() {
        val next =
            screen.reconciled(
                listOf(
                    "EC:9A:0C:E0:D2:96" to "JLab JBuds Sport ANC 4",
                    "E4:58:BC:3E:9D:AA" to "Bose QC Headphones",
                ),
                Emptiness.NONE_CONNECTED,
            )
        assertEquals(
            listOf("EC:9A:0C:E0:D2:96", "E4:58:BC:3E:9D:AA"),
            next.cards.map { it.address },
        )
    }

    /** A failure keeps its reason: "error" is not a thing anyone can act on. */
    @Test
    fun `unavailability carries why`() {
        val s = DeviceState.Unavailable("not advertising right now")
        assertTrue(s.why.isNotBlank())
    }

    // ---- settings ----------------------------------------------------------

    private val ready = DeviceState.Ready("Sony WH-1000XM4", listOf(AncMode.ANC), AncMode.ANC)

    @Test
    fun `settings attach to a ready card`() {
        val next =
            screen
                .with("E4:58:BC:3E:9D:AA", ready)
                .withSettings("E4:58:BC:3E:9D:AA", Settings(multipoint = false))
        assertEquals(
            false,
            next.cards
                .first()
                .settings
                ?.multipoint,
        )
    }

    /**
     * ⚠ **The regression this type was reshaped for.** Every write goes
     * `Ready → Busy → Ready`, and while `settings` lived on [DeviceState.Ready] the
     * `Busy` in the middle — which has no such field — destroyed them. On screen the
     * open settings section fell back to a "reading…" spinner that could never
     * resolve, because the read is only triggered by opening the section. Reproduced
     * on the XM4 by expanding settings and then tapping an ANC chip.
     */
    @Test
    fun `settings survive the busy state that every write passes through`() {
        val next =
            screen
                .with("E4:58:BC:3E:9D:AA", ready)
                .withSettings("E4:58:BC:3E:9D:AA", Settings(multipoint = false))
                .with("E4:58:BC:3E:9D:AA", DeviceState.Busy("setting ambient…"))
                .with("E4:58:BC:3E:9D:AA", ready)
        assertEquals(
            false,
            next.cards
                .first()
                .settings
                ?.multipoint,
        )
    }

    /** And a plain refresh, which rebuilds `Ready` from scratch, keeps them too. */
    @Test
    fun `settings survive a reconcile that keeps the card`() {
        val next =
            screen
                .with("E4:58:BC:3E:9D:AA", ready)
                .withSettings("E4:58:BC:3E:9D:AA", Settings(autoOff = AutoOff.NEVER))
                .reconciled(
                    listOf("E4:58:BC:3E:9D:AA" to "Bose QC Headphones"),
                    Emptiness.NONE_CONNECTED,
                )
        assertEquals(
            AutoOff.NEVER,
            next.cards
                .single()
                .settings
                ?.autoOff,
        )
    }

    /**
     * ⚠ The read takes seconds and the device can go in that time. Landing settings
     * on a card that is no longer Ready would resurrect a dead one, fully furnished
     * with controls, over a socket that is gone.
     */
    @Test
    fun `settings do not land on a card that went away mid-read`() {
        val next =
            screen
                .with("E4:58:BC:3E:9D:AA", DeviceState.Unavailable("switched off"))
                .withSettings("E4:58:BC:3E:9D:AA", Settings(multipoint = false))
        assertTrue(next.cards.first().state is DeviceState.Unavailable)
        assertNull(next.cards.first().settings)
    }

    /** Nothing read yet and nothing to draw are the same thing for a renderer. */
    @Test
    fun `empty settings have nothing to show`() {
        assertFalse(Settings().any)
        assertTrue(Settings(multipoint = true).any)
        assertTrue(Settings(tone = BoseBands(0, 0, 0)).any)
    }

    /**
     * ⚠ **Reported and changeable are different questions**, and this is the whole
     * reason [Settings.refuses] exists. The XM4 answers `d6 d2` and then ignores
     * `d8 d2 01 01`; the QC45 accepts both. A screen that inferred "we can set it"
     * from "it told us" would offer a switch that springs back.
     */
    @Test
    fun `a setting can be reported and still not be writable`() {
        val xm4 =
            Settings(
                multipoint = false,
                button = "Ambient Sound Control",
                refuses = setOf(SettingKind.MULTIPOINT, SettingKind.BUTTON),
            )
        assertEquals(false, xm4.multipoint)
        assertFalse(xm4.writable(SettingKind.MULTIPOINT))
        assertFalse(xm4.writable(SettingKind.BUTTON))
        assertTrue(xm4.writable(SettingKind.EQ))

        val qc45 = Settings(multipoint = false, tone = BoseBands(0, 0, 0))
        assertTrue(qc45.writable(SettingKind.MULTIPOINT))
    }

    /** A confirmed settings write says nothing: the row already shows the new value. */
    @Test
    fun `a confirmed setting write is silent`() {
        assertNull(Confirmation.Confirmed.settingNote<Boolean> { "$it" })
    }

    /** ⚠ A refusal must read as a refusal, not as an unexplained value change. */
    @Test
    fun `a refused setting write names what the device still reports`() {
        val note = Confirmation.Contradicted(false).settingNote { if (it) "on" else "off" }
        assertEquals(NoteKind.PROBLEM, note?.kind)
        assertTrue(note!!.text.contains("refused"))
        assertTrue(note.text.contains("off"))
    }

    /** ⚠ And "sent, unverifiable" must never render as success. */
    @Test
    fun `an unconfirmable setting write is a caution, not silence`() {
        val note = Confirmation.Unverifiable.settingNote<Boolean> { "$it" }
        assertEquals(NoteKind.CAUTION, note?.kind)
    }
}
