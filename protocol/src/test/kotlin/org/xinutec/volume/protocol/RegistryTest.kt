package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
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
