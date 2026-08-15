package org.xinutec.volume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sighting-merge rules, which are where the bug was.
 *
 * A BLE device is seen several times per scan, and the name arrives in a different
 * packet from the service data. Keeping the first sighting made the JBL — which
 * *was* advertising, under a name — print as "(no name)", and a whole round went
 * into "so it must use some other transport".
 */
class ScanTest {
    private val advertisement =
        Scan.Seen("47:98:AB:CE:70:4E", null, -60, emptyList(), mapOf("fddf" to "85 20 00"))
    private val scanResponse =
        Scan.Seen("47:98:AB:CE:70:4E", "JBL TOUR ONE M2", -53, emptyList(), emptyMap())

    @Test
    fun `a name in the scan response survives the merge`() {
        assertEquals("JBL TOUR ONE M2", (advertisement + scanResponse).name)
        // And in the other order: which packet arrives first is not ours to choose.
        assertEquals("JBL TOUR ONE M2", (scanResponse + advertisement).name)
    }

    @Test
    fun `service data from either sighting survives the merge`() {
        assertEquals("85 20 00", (scanResponse + advertisement).serviceData["fddf"])
        assertEquals("85 20 00", (advertisement + scanResponse).serviceData["fddf"])
    }

    /** Weakest signal wins nothing: the merged rssi is the best evidence of range. */
    @Test
    fun `the merged rssi is the strongest seen`() {
        assertEquals(-53, (advertisement + scanResponse).rssi)
    }

    @Test
    fun `a device is matchable by name, by service data and by address`() {
        val merged = advertisement + scanResponse
        assertTrue(merged.haystack().contains("jbl tour"))
        assertTrue(merged.haystack().contains("85 20 00"))
        assertTrue(merged.haystack().contains("47:98"))
        assertFalse(merged.haystack().contains("bose"))
    }

    /**
     * The identifier is the merged whole, so a match on a field carried by only one
     * of the two packets still finds the device. Before the merge, matching on the
     * name found nothing while the device was plainly there.
     */
    @Test
    fun `neither sighting alone is matchable on everything`() {
        assertFalse(advertisement.haystack().contains("jbl tour"))
        assertFalse(scanResponse.haystack().contains("85 20 00"))
    }
}
