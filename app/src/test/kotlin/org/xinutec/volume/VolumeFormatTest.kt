package org.xinutec.volume

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two number formatters on the settings screen.
 *
 * ⚠ **Written because [hz] shipped rendering `2.5kk`**, and was caught by looking at
 * the screen rather than by anything here. The bug only touched the bands that are
 * not exact multiples of 1000, so a test naming just `1k` and `16k` would have passed
 * — which is the reason the case below is the XM4's five real band centres, taken
 * from `docs/sony-settings.md`, and not a tidy sample.
 */
class VolumeFormatTest {
    @Test
    fun `the XM4's own band table renders as its app labels it`() {
        assertEquals(
            listOf("400", "1k", "2.5k", "6.3k", "16k"),
            listOf(400, 1000, 2500, 6300, 16000).map(::hz),
        )
    }

    /** ⚠ The failing shape had a doubled unit, so assert the suffix appears once. */
    @Test
    fun `a band never gets two k`() {
        listOf(1000, 2500, 6300, 16000).forEach {
            assertEquals("$it", 1, hz(it).count { c -> c == 'k' })
        }
    }

    /** Below a kilohertz there is no unit at all — the row's label supplies it. */
    @Test
    fun `sub-kilohertz bands are plain`() {
        assertEquals("400", hz(400))
        assertEquals("999", hz(999))
    }

    /** dB is signed, and a boost has to read as one. Zero is bare, not "+0". */
    @Test
    fun `tone levels carry their sign`() {
        assertEquals("+8", signed(8))
        assertEquals("-10", signed(-10))
        assertEquals("0", signed(0))
    }

    /**
     * The JBL's gains are floats, and its own JAZZ curve lands on a half.
     *
     * ⚠ A whole number must not render `+4.0` beside a band labelled `4k`; the two
     * decimal points then look like the same kind of number and are not.
     */
    @Test
    fun `a gain shows a decimal only when it has one`() {
        assertEquals(
            listOf("+4", "+2", "+1", "+2.5", "-1.5", "-1.5", "0", "+1", "+2", "+4"),
            listOf(4f, 2f, 1f, 2.5f, -1.5f, -1.5f, 0f, 1f, 2f, 4f).map(::db),
        )
    }
}
