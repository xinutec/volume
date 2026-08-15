package org.xinutec.volume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures are the real SDP records, read off the Pixel 9 on 2026-08-15, and
 * the expectations are what each device actually answered when driven. They are
 * here rather than in a comment because the traps in this data are the kind a
 * later edit re-introduces by looking reasonable.
 */
class ChannelsTest {
    private val std =
        setOf(
            "0000110b-0000-1000-8000-00805f9b34fb",
            "0000110e-0000-1000-8000-00805f9b34fb",
            "0000111e-0000-1000-8000-00805f9b34fb",
        )

    private val sonyXm4 =
        std +
            setOf(
                "00000000-deca-fade-deca-deafdecacaff",
                "00001108-0000-1000-8000-00805f9b34fb",
                "81c2e72a-0591-443e-a1ff-05f988593351",
                "8901dfa8-5c7e-4d8f-9f0c-c2b70683f5f0",
                "931c7e8a-540f-4686-b798-e8df0a2ad9f7",
                "96cc203e-5068-46ad-b32d-e316f5e069ba",
                "b9b213ce-eeab-49e4-8fd9-aa478ed1b26b",
                "f8d1fbe4-7966-4334-8024-ff96c9330e15",
            )

    private val boseQc45 =
        std +
            setOf(
                "00000000-deca-fade-deca-deafdecacaff",
                "00001101-0000-1000-8000-00805f9b34fb",
                "00001108-0000-1000-8000-00805f9b34fb",
                "9b26d8c0-a8ed-440b-95b0-c4714a518bcc",
            )

    private val jblTourOneM2 =
        std +
            setOf(
                "00001101-0000-1000-8000-00805f9b34fb",
                "81c2e72a-0591-443e-a1ff-05f988593351",
                "931c7e8a-540f-4686-b798-e8df0a2ad9f7",
                "df21fe2c-2515-4fdb-8886-f12c4d67927c",
                "f8d1fbe4-7966-4334-8024-ff96c9330e15",
            )

    private val jlabJbuds =
        std +
            setOf(
                "00001101-0000-1000-8000-00805f9b34fb",
                "66666666-6666-6666-6666-666666666666",
                "99999999-9999-9999-9999-999999999999",
                "df21fe2c-2515-4fdb-8886-f12c4d67927c",
            )

    /** The QC35. Renamed by its owner, so nothing in it says "Bose". */
    private val qc35 =
        std +
            setOf(
                "00000000-deca-fade-deca-deafdecacaff",
                "00001101-0000-1000-8000-00805f9b34fb",
                "00001108-0000-1000-8000-00805f9b34fb",
                "81c2e72a-0591-443e-a1ff-05f988593351",
                "931c7e8a-540f-4686-b798-e8df0a2ad9f7",
                "f8d1fbe4-7966-4334-8024-ff96c9330e15",
            )

    @Test
    fun `sony is found by its own uuid, which is also its channel`() {
        val d = Channels.detect("WH-1000XM4", sonyXm4)
        assertEquals(Channels.Vendor.SONY, d.vendor)
        assertEquals(Channels.SONY, d.channel)
        assertEquals(Channels.Protocol.SONY_FRAMED, d.protocol)
    }

    /**
     * The trap, direction one. `9b26d8c0` identifies a QC45 and is NOT what it
     * speaks on — four packets sent there drew silence, while the identical packet
     * on SPP returned the firmware version.
     */
    @Test
    fun `qc45 is identified by bose-music but its channel is SPP`() {
        val d = Channels.detect("Bose QC Headphones", boseQc45)
        assertEquals(Channels.Vendor.BOSE, d.vendor)
        assertEquals(Channels.SPP, d.channel)
        assertNotEquals(Channels.BOSE_MUSIC, d.channel)
        assertEquals(Channels.Protocol.BOSE, d.protocol)
    }

    /**
     * The trap, direction two. `df21fe2c` is on both the JBL and the JLab because
     * both are Fast Pair certified: it identifies no vendor, and — the part that took
     * a second pass to see — it is not a vendor channel at all.
     */
    @Test
    fun `jbl and jlab share one channel and one protocol`() {
        val jbl = Channels.detect("JBL TOUR ONE M2", jblTourOneM2)
        val jlab = Channels.detect("JLab JBuds Sport ANC 4", jlabJbuds)
        assertEquals(Channels.Vendor.JBL, jbl.vendor)
        assertEquals(Channels.Vendor.JLAB, jlab.vendor)
        assertEquals(Channels.FAST_PAIR, jbl.channel)
        assertEquals(Channels.FAST_PAIR, jlab.channel)
        assertEquals(Channels.Protocol.FAST_PAIR, jbl.protocol)
        assertEquals(Channels.Protocol.FAST_PAIR, jlab.protocol)
    }

    /** The JLab's other advertised UUIDs open and never answer, so never prefer them. */
    @Test
    fun `jlab is not routed to its silent uuids`() {
        val d = Channels.detect("JLab JBuds Sport ANC 4", jlabJbuds)
        assertNotEquals(Channels.BES_OTA, d.channel)
        assertNotEquals(Channels.JLAB_UNIDENTIFIED, d.channel)
    }

    /** A device with only those has nothing to connect to, and must not claim otherwise. */
    @Test
    fun `silent uuids alone yield no channel`() {
        val d = Channels.detect("JLab thing", std + Channels.BES_OTA)
        assertEquals(Channels.Vendor.JLAB, d.vendor)
        assertNull(d.channel)
        assertEquals(Channels.Protocol.NONE, d.protocol)
    }

    /**
     * An unrecognised name on the shared channel still gets a working connection:
     * the channel and protocol are known even when the vendor is not.
     */
    @Test
    fun `an unnamed fast pair device still gets its channel`() {
        val d = Channels.detect("something else", jblTourOneM2)
        assertEquals(Channels.Vendor.UNKNOWN, d.vendor)
        assertEquals(Channels.FAST_PAIR, d.channel)
        assertEquals(Channels.Protocol.FAST_PAIR, d.protocol)
    }

    /**
     * The renamed QC35 cannot be identified from its record — every UUID it has is
     * standard or shared. It is reported as unidentified rather than guessed at,
     * and still offered SPP so a caller can probe it.
     */
    @Test
    fun `a renamed qc35 is not guessed at, but keeps a probeable channel`() {
        val d = Channels.detect("LE-Pippijn Headphon", qc35)
        assertEquals(Channels.Vendor.UNKNOWN, d.vendor)
        assertEquals(Channels.SPP, d.channel)
    }

    /** A factory-named one is nameable, and Bose is the only known SPP protocol. */
    @Test
    fun `a factory-named qc35 is routed to the bose protocol`() {
        val d = Channels.detect("Bose QC35 II", qc35)
        assertEquals(Channels.Vendor.BOSE, d.vendor)
        assertEquals(Channels.SPP, d.channel)
        assertEquals(Channels.Protocol.BOSE, d.protocol)
    }

    /**
     * `00000000-deca-fade-…` is annotated "Bose proprietary" in task #783 and is on
     * the Sony too. No member of SHARED may ever name a vendor by itself.
     */
    @Test
    fun `shared uuids never identify a vendor`() {
        for (shared in Channels.SHARED) {
            val d = Channels.detect("", std + shared)
            assertEquals("$shared must not identify a vendor", Channels.Vendor.UNKNOWN, d.vendor)
        }
    }

    @Test
    fun `every shared uuid really is on more than one of the measured devices`() {
        val devices = listOf(sonyXm4, boseQc45, jblTourOneM2, jlabJbuds, qc35)
        for (shared in Channels.SHARED) {
            val n = devices.count { shared in it }
            assertTrue("$shared appears on $n measured devices, expected >1", n > 1)
        }
    }

    /** The converse: a UUID we DO identify on must be unique in the fixtures. */
    @Test
    fun `identifying uuids are unique across the measured devices`() {
        val devices = listOf(sonyXm4, boseQc45, jblTourOneM2, jlabJbuds, qc35)
        for (marker in listOf(Channels.SONY, Channels.BOSE_MUSIC)) {
            assertEquals(
                "$marker should be on exactly one device",
                1,
                devices.count { marker in it },
            )
        }
    }

    @Test
    fun `a device with no control channel is reported as such`() {
        val d = Channels.detect("ACTON II", std)
        assertEquals(Channels.Vendor.UNKNOWN, d.vendor)
        assertNull(d.channel)
        assertEquals(Channels.Protocol.NONE, d.protocol)
    }

    @Test
    fun `annotation flags the two uuids that mislead`() {
        assertTrue("speaks on SPP" in Channels.annotate(Channels.BOSE_MUSIC))
        assertTrue("not an id" in Channels.annotate(Channels.FAST_PAIR))
        // The one that misled longest: it answers, so it reads as control until the
        // annotation says otherwise.
        assertTrue("not control" in Channels.annotate(Channels.FAST_PAIR))
        assertEquals("  ← A2DP sink", Channels.annotate("0000110b-0000-1000-8000-00805f9b34fb"))
    }
}
