package org.xinutec.volume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures are the real SDP records, read off the Pixel 9 on 2026-08-15. They
 * are here rather than in a comment because the traps in this data are the kind
 * that a later edit re-introduces by looking reasonable.
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

    /** Plain SPP and nothing else that identifies it. Suspected QC35. */
    private val unidentifiedBose =
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
    fun `sony is found by its own uuid`() {
        val d = Channels.detect("WH-1000XM4", sonyXm4)
        assertEquals(Channels.Vendor.SONY, d.vendor)
        assertEquals(Channels.SONY, d.uuid)
        assertEquals("unique uuid", d.basis)
    }

    @Test
    fun `qc45 is found by the bose music uuid, not by spp`() {
        val d = Channels.detect("Bose QC Headphones", boseQc45)
        assertEquals(Channels.Vendor.BOSE_MUSIC, d.vendor)
        assertEquals(Channels.BOSE_MUSIC, d.uuid)
    }

    @Test
    fun `jlab is found by its placeholder uuids`() {
        val d = Channels.detect("JLab JBuds Sport ANC 4", jlabJbuds)
        assertEquals(Channels.Vendor.JLAB, d.vendor)
        assertEquals("unique uuid", d.basis)
    }

    /**
     * The JBL advertises no unique UUID whatsoever, so it can only be named — and
     * the result has to admit that, or a caller will trust it as far as it trusts
     * the Sony.
     */
    @Test
    fun `jbl falls back to the name and says so`() {
        val d = Channels.detect("JBL TOUR ONE M2", jblTourOneM2)
        assertEquals(Channels.Vendor.JBL, d.vendor)
        assertEquals(Channels.SPP, d.uuid)
        assertTrue(d.basis, "ambiguous" in d.basis)
    }

    @Test
    fun `an spp-only device with an unhelpful name is not guessed at`() {
        val d = Channels.detect("LE-Pippijn Headphon", unidentifiedBose)
        assertEquals(Channels.Vendor.UNKNOWN, d.vendor)
        assertEquals(Channels.SPP, d.uuid)
    }

    /**
     * The regression this file exists for. `00000000-deca-fade-…` is annotated
     * "Bose proprietary" in task #783, and it is on the Sony too — so it must never
     * become a discriminator. Same for the three Sony/JBL UUIDs and the JBL/JLab one.
     */
    @Test
    fun `shared uuids never identify a vendor`() {
        for (shared in Channels.SHARED) {
            val d = Channels.detect("", std + shared)
            assertEquals(
                "$shared must not identify a vendor",
                Channels.Vendor.UNKNOWN,
                d.vendor,
            )
        }
    }

    @Test
    fun `every shared uuid really is on more than one of the measured devices`() {
        val devices = listOf(sonyXm4, boseQc45, jblTourOneM2, jlabJbuds, unidentifiedBose)
        for (shared in Channels.SHARED) {
            val n = devices.count { shared in it }
            assertTrue("$shared appears on $n measured devices, expected >1", n > 1)
        }
    }

    /** The converse: a UUID we DO discriminate on must be unique in the fixtures. */
    @Test
    fun `discriminating uuids are unique across the measured devices`() {
        val devices = listOf(sonyXm4, boseQc45, jblTourOneM2, jlabJbuds, unidentifiedBose)
        for (marker in listOf(
            Channels.SONY,
            Channels.BOSE_MUSIC,
            Channels.JLAB_A,
            Channels.JLAB_B,
        )) {
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
        assertEquals(null, d.uuid)
    }

    @Test
    fun `standard uuids are annotated by their profile`() {
        assertEquals("  ← A2DP sink", Channels.annotate("0000110b-0000-1000-8000-00805f9b34fb"))
        assertEquals("  ← SPP", Channels.annotate(Channels.SPP))
        assertNotEquals("  ← unknown", Channels.annotate(Channels.SONY))
    }
}
