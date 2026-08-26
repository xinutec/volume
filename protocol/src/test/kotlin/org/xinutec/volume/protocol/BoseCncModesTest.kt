package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The QC45's mode table, against bytes the device actually sent.
 *
 * ⚠ **Every buffer here is a real reply**, copied from the 2026-08-26 sitting — the
 * `1f 01 05 00` transaction and the Status that came back from an edit. A hand-written
 * fixture would agree with whatever this file happened to implement.
 */
class BoseCncModesTest {
    /**
     * The whole `1f 01` transaction as one buffer, exactly as read: Processing, four
     * slot records, Result, and the neighbouring frames the device volunteers with them.
     */
    private val transaction =
        Hex.parse(
            """
        1f 01 07 00 1f 02 03 06 02 02 00 00 00 09 1f 03 03 01 03 1f 05 03
        01 01 1f 06 07 00 1f 06 03 2f 00 00 01 00 00 01 51 75 69 65 74 00
        00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
        00 00 00 00 00 00 00 00 00 00 00 00 00 1f 06 03 2f 01 00 02 00 00
        01 41 77 61 72 65 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
        00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 0a 00 00 00 00 1f 06
        03 2f 02 00 0a 01 01 01 48 6f 6d 65 00 00 00 00 00 00 00 00 00 00
        00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 09
        00 00 00 00 00 1f 06 03 2f 03 00 07 01 01 01 43 6f 6d 6d 75 74 65
        00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
        00 00 00 00 00 00 09 07 00 00 00 00 1f 06 06 00 1f 08 03 02 04 0f
        1f 01 06 00
        """,
        )

    @Test
    fun `names come from the device, not from here`() {
        val modes = BoseCncModes.modes(transaction)
        assertEquals(listOf("Quiet", "Aware", "Home", "Commute"), modes.map { it.name })
        assertEquals(listOf(0, 1, 2, 3), modes.map { it.slot })
    }

    /**
     * ⚠ **The regression this file exists for.** `[2]` reads `01 02 0a 07` across the
     * four slots and the level reads `00 0a 00 07`; they agree only on Commute, where
     * `07` lands in both bytes by luck. Selecting each mode and reading `01 05` gave
     * `00 0a 00 07`, so `[42]` is the level and `[2]` is not.
     */
    @Test
    fun `the level is byte 42, not the byte that agrees with it on one mode`() {
        val modes = BoseCncModes.modes(transaction)
        assertEquals(listOf(0, 10, 0, 7), modes.map { it.level })
        assertEquals(listOf(1, 2, 10, 7), modes.map { it.nameId })
    }

    /** Quiet and Aware are the device's; the other two are the owner's. */
    @Test
    fun `only the user slots are editable`() {
        assertEquals(
            listOf(false, false, true, true),
            BoseCncModes.modes(transaction).map { it.editable },
        )
    }

    @Test
    fun `the active slot is read from 1f 03`() {
        assertEquals(3, BoseCncModes.activeSlot(transaction))
    }

    /**
     * Before Commute existed, slot 3 answered a full-length record with an empty name.
     * ⚠ That is an empty SLOT, and reporting it as a mode called "" would put a blank
     * row on the card.
     */
    @Test
    fun `an empty name is an empty slot, not a nameless mode`() {
        val empty =
            Hex.parse(
                """
            1f 06 03 2f 03 00 00 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00
            00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
            00 09 00 00 00 00 00
            """,
            )
        assertTrue(BoseCncModes.modes(empty).isEmpty())
    }

    /**
     * ⚠ The write puts the level at `[35]` where the read has it at `[42]`. This is the
     * exact frame that was sent to the headphones and obeyed.
     */
    @Test
    fun `setLevel builds the frame the device obeyed`() {
        val commute = BoseCncModes.modes(transaction).single { it.name == "Commute" }
        val frame = BoseCncModes.setLevel(commute, 3)
        assertEquals(
            "1f 06 02 27 03 00 07 43 6f 6d 6d 75 74 65 " +
                "00 00 00 00 00 00 00 00 00 00 00 00 00 00 " +
                "00 00 00 00 00 00 00 00 00 00 00 03 00 00 " +
                "00",
            frame.joinToString(" ") { "%02x".format(it) },
        )
    }

    /** The scale has eleven points and the ends are the two built-in modes. */
    @Test
    fun `a level outside the scale is clamped rather than sent`() {
        val commute = BoseCncModes.modes(transaction).single { it.name == "Commute" }
        assertEquals(10, BoseCncModes.setLevel(commute, 99)[39].toInt() and 0xff)
        assertEquals(0, BoseCncModes.setLevel(commute, -4)[39].toInt() and 0xff)
    }

    @Test
    fun `selecting is a Start whose payload is slot then 01`() {
        assertEquals(
            "1f 03 05 02 02 01",
            BoseCncModes.select(2).joinToString(" ") { "%02x".format(it) },
        )
    }

    /** A buffer with no `1f 03` in it has no active slot, rather than slot zero. */
    @Test
    fun `no active frame reads as unknown`() {
        assertNull(BoseCncModes.activeSlot(Hex.parse("1f 08 03 02 04 0f")))
    }
}
