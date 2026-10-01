package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which command table each Sony payload is sent on.
 *
 * ⚠ `48` is `VPT_SET_PARAM` on table 1 and `VOICE_GUIDANCE_SET_PARAM` on table 2, so a
 * payload framed on the wrong table is a different command, not a malformed one.
 */
class SonyTableTest {
    @Test
    fun `voice guidance is on table 2, and its 48 is framed as 0e`() {
        val p = SonyVoiceGuidance.set(true)
        assertEquals(SonyTable.TABLE_2, p.table)
        assertEquals(SonyFrame.TYPE_DATA_MDR_NO2, p.type)
        assertEquals(SonyTable.TABLE_2, SonyVoiceGuidance.get().table)
    }

    @Test
    fun `every other setting is on table 1`() {
        for (p in listOf(
            SonyEq.set(0xa1),
            SonyAutoOff.get(),
            SonyDsee.set(true),
            SonyChatDetail.get(),
        )) {
            assertEquals("$p", SonyTable.TABLE_1, p.table)
            assertEquals(SonyFrame.TYPE_DATA_MDR, p.type)
        }
    }
}
