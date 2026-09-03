package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixtures are the real SDP records, read off the Pixel 9 on 2026-08-15. */
class RegistryTest {
    private val std =
        setOf(
            "0000110b-0000-1000-8000-00805f9b34fb",
            "0000110e-0000-1000-8000-00805f9b34fb",
            "0000111e-0000-1000-8000-00805f9b34fb",
        )
    private val shared =
        setOf(
            "00000000-deca-fade-deca-deafdecacaff",
            "81c2e72a-0591-443e-a1ff-05f988593351",
            "931c7e8a-540f-4686-b798-e8df0a2ad9f7",
            "f8d1fbe4-7966-4334-8024-ff96c9330e15",
        )
    private val spp = "00001101-0000-1000-8000-00805f9b34fb"

    private val qc45 = std + shared + setOf(spp, Channels.BOSE_MUSIC)
    private val qc35 = std + shared + setOf(spp)
    private val jbl = std + shared + setOf(spp, Channels.FAST_PAIR)
    private val jlab = std + setOf(spp, Channels.FAST_PAIR, Channels.BES_OTA)
    private val sony = std + shared + setOf(Channels.SONY)

    @Test
    fun `each device gets its own driver and route`() {
        assertSame(
            Drivers.BoseQc45,
            Registry.fromAdvertisement("Bose QC Headphones", qc45)!!.driver,
        )
        assertTrue(Registry.fromAdvertisement("WH-1000XM4", sony)!!.driver is Drivers.SonyXm4)
        assertSame(Drivers.JblBes, Registry.fromAdvertisement("JBL TOUR ONE M2", jbl)!!.driver)
        assertSame(
            Drivers.JLabQcy,
            Registry.fromAdvertisement("JLab JBuds Sport ANC 4", jlab)!!.driver,
        )
    }

    /** ⚠ The JBL is the only one routed over GATT, and carries no address. */
    @Test
    fun `the jbl routes to gatt and everyone else to rfcomm`() {
        val j = Registry.fromAdvertisement("JBL TOUR ONE M2", jbl)!!
        assertTrue(j.route is Route.Gatt)
        assertEquals(Channels.BES_GATT_SERVICE, (j.route as Route.Gatt).service)

        for (h in listOf(
            Registry.fromAdvertisement("Bose QC Headphones", qc45)!!,
            Registry.fromAdvertisement("WH-1000XM4", sony)!!,
            Registry.fromAdvertisement("JLab JBuds Sport ANC 4", jlab)!!,
        )) {
            assertTrue("${h.model} should be RFCOMM", h.route is Route.Rfcomm)
        }
    }

    /**
     * ⚠ **A speaker must not inherit a headphone's driver.** The Revolve is BMAP on the
     * QC35's own channel, so the only thing separating them is the model in the name —
     * and `identifyBose` cannot help, because it tells the two headphones apart by asking
     * `01 06`, which a Revolve answers "unsupported" exactly as a QC45 does.
     */
    @Test
    fun `the revolve is a speaker with its own driver and no anc`() {
        val r = Registry.fromAdvertisement("Bose Revolve SoundLink", qc35)
        assertNotNull(r)
        assertSame(Drivers.BoseRevolve, r!!.driver)
        assertEquals("Bose SoundLink Revolve", r.model)
        // ⚠ Empty modes AND `reads=false`: no chips to offer, and a null mode from it is
        // "there is nothing to read" rather than "the read failed".
        assertTrue(r.driver.modes.isEmpty())
        assertTrue(r.driver.offeredModes().isEmpty())
        assertFalse(r.driver.reads)
    }

    /**
     * ⚠⚠ **The same rule as the Bose test below, for the vendor where breaking it is
     * worst.** Every JLab used to resolve to the JBuds Sport ANC 4 and its driver; the
     * phone has seen three `JLab JBuds Air Sport` — not bonded on 2026-09-03, so nothing
     * was ever mis-driven, but the branch would have done it the day one was paired.
     *
     * ⛔ The JLab id space is a Realtek SDK's and holds a factory reset, so replaying
     * these frames at an unverified model is an unknown WRITE, not a wrong read.
     *
     * ⚠ An unrecognised JLab is not driven at all: there is no JLab counterpart to
     * [Registry.identifyBose] to fall back on, and undriven is the failure worth having.
     */
    @Test
    fun `an unrecognised jlab model gets no driver rather than this one's`() {
        assertSame(
            Drivers.JLabQcy,
            Registry.fromAdvertisement("JLab JBuds Sport ANC 4", jlab)!!.driver,
        )
        assertNull(Registry.fromAdvertisement("JLab JBuds Air Sport", jlab))
        assertNull(Registry.fromAdvertisement("JLab Epic Air ANC", jlab))
    }

    /**
     * ⚠ The QC45 and QC35 share a vendor, a channel and a protocol, and still need
     * different tables: `01 06` is ANC on one and unsupported on the other. A
     * registry keyed on vendor alone would drive one of them with the other's
     * commands.
     */
    @Test
    fun `the two bose models do not share a driver`() {
        val a = Registry.fromAdvertisement("Bose QC Headphones", qc45)!!.driver
        val b = Registry.fromAdvertisement("Bose QC35 II", qc35)!!.driver
        assertTrue(a !== b)
    }

    /** The session opener every Bose read now goes behind — `Registry.wakeBose`. */
    private val wake = "00 01 01 00" to "00 01 03 05 31 2e 30 2e 34"

    /**
     * The real device on this desk: a QC35 renamed by its owner, whose record holds
     * nothing but standard and shared UUIDs. Guessing would be wrong for anyone who
     * renamed a QC45 instead, so the answer is "ask it", not "assume".
     */
    @Test
    fun `a renamed bose is not guessed at from its advertisement`() {
        assertNull(Registry.fromAdvertisement("LE-Pippijn Headphon", qc35))
    }

    /**
     * ⚠ **The block-`00` read in front is not decoration** — without it a QC35 answers
     * `01 06` with nothing and is reported unidentified. See `Registry.wakeBose`.
     */
    @Test
    fun `a renamed bose is identified by a read`() {
        // The QC35 answers 01 06 with a Status frame carrying its ANC value.
        val q35 = Replay(wake, "01 06 01 00" to "01 06 03 02 00 0b")
        assertSame(Drivers.BoseQc35, Registry.identifyBose(q35))
        q35.assertDrained()

        // The QC45 refuses it: operator 04, "function not supported".
        val q45 = Replay(wake, "01 06 01 00" to "01 06 04 01 04")
        assertSame(Drivers.BoseQc45, Registry.identifyBose(q45))
        q45.assertDrained()
    }

    /** ⚠ Reads only, never a write — this runs against headphones someone is wearing. */
    @Test
    fun `identification uses Gets and touches nothing`() {
        val t = Replay(wake, "01 06 01 00" to "01 06 03 02 00 0b")
        Registry.identifyBose(t)
        // Operator 01 is Get; anything else could change a setting.
        assertEquals(listOf("00 01 01 00", "01 06 01 00"), t.sent)
    }

    @Test
    fun `an answer that settles nothing is reported as such`() {
        assertNull(Registry.identifyBose(Replay(wake, "01 06 01 00" to "")))
        assertNull(Registry.identifyBose(Replay(wake, "01 06 01 00" to "01 06 07 00")))
    }

    @Test
    fun `a device with no control channel gets no driver`() {
        assertNull(Registry.fromAdvertisement("ACTON II", std))
    }
}
