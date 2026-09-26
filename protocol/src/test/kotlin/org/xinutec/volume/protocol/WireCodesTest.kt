package org.xinutec.volume.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The byte each value sends, pinned, so reordering an enum cannot change the wire. */
class WireCodesTest {
    @Test
    fun `self voice levels`() {
        // Confirmed on the device: Medium read 02, Low read 03.
        val sent =
            SidetoneLevel.entries.associateWith {
                BoseWrites
                    .sidetone(
                        0x01,
                        it,
                    ).bytes
                    .last()
            }
        assertEquals(
            mapOf(
                SidetoneLevel.OFF to 0x00.toByte(),
                SidetoneLevel.HIGH to 0x01.toByte(),
                SidetoneLevel.MEDIUM to 0x02.toByte(),
                SidetoneLevel.LOW to 0x03.toByte(),
            ),
            sent,
        )
        for (l in SidetoneLevel.entries) {
            assertEquals(l, BoseSidetone.level(byteArrayOf(0x01, sent.getValue(l))))
        }
    }

    @Test
    fun `voice prompt languages`() {
        // 00 UK and 01 US are one bit apart; this unit reads 01, "English (U.S.)".
        val on = BoseWrites.voicePrompts(0x00, on = false, BoseVoicePromptLanguage.US_ENGLISH)
        assertEquals(0x01.toByte(), on.bytes.last())
        val codes =
            BoseVoicePromptLanguage.entries.map {
                BoseWrites
                    .voicePrompts(0x00, false, it)
                    .bytes
                    .last()
                    .toInt()
            }
        assertEquals((0 until BoseVoicePromptLanguage.entries.size).toList(), codes)
        for (l in BoseVoicePromptLanguage.entries) {
            val code = BoseWrites.voicePrompts(0x00, false, l).bytes.last()
            assertEquals(l, BoseVoicePromptLanguage.of(byteArrayOf(code)))
        }
        assertEquals(BoseVoicePromptLanguage.DUTCH, BoseVoicePromptLanguage.of(byteArrayOf(14)))
    }

    @Test
    fun `speak-to-chat detail`() {
        assertArrayEquals(
            Hex.parse("fc 05 00 02 01 03"),
            SonyChatDetail.set(ChatDetail(ChatSensitivity.LOW, true, ModeOutTime.NONE)),
        )
        assertArrayEquals(
            Hex.parse("fc 05 00 01 00 01"),
            SonyChatDetail.set(ChatDetail(ChatSensitivity.HIGH, false, ModeOutTime.MID)),
        )
    }
}
