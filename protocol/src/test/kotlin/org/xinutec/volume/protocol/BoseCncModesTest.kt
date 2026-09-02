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
            frame.bytes.joinToString(" ") { "%02x".format(it) },
        )
    }

    /** The scale has eleven points and the ends are the two built-in modes. */
    @Test
    fun `a level outside the scale is clamped rather than sent`() {
        val commute = BoseCncModes.modes(transaction).single { it.name == "Commute" }
        assertEquals(10, BoseCncModes.setLevel(commute, 99).bytes[39].toInt() and 0xff)
        assertEquals(0, BoseCncModes.setLevel(commute, -4).bytes[39].toInt() and 0xff)
    }

    @Test
    fun `selecting is a Start whose payload is slot then 01`() {
        assertEquals(
            "1f 03 05 02 02 01",
            BoseCncModes.select(2).bytes.joinToString(" ") { "%02x".format(it) },
        )
    }

    /** A buffer with no `1f 03` in it has no active slot, rather than slot zero. */
    @Test
    fun `no active frame reads as unknown`() {
        assertNull(BoseCncModes.activeSlot(Hex.parse("1f 08 03 02 04 0f")))
    }

    /**
     * ⚠ **These two are the frames Bose Music sent**, lifted from the 2026-08-28 snoop of
     * a real delete and a real create, not from what this file implements. Both were then
     * replayed from this repo's own socket against the same headphones, and the table read
     * back byte-for-byte identical to the baseline taken before any of it.
     */
    private val capturedDelete =
        Hex.parse(
            """
        1f 06 02 27 02 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
        00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 05 00 00 00
        """,
        )

    private val capturedCreate =
        Hex.parse(
            """
        1f 06 02 27 02 00 0a 48 6f 6d 65 00 00 00 00 00 00 00 00 00 00 00
        00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
        """,
        )

    private val full = BoseCncModes.Slots(capacity = 4, occupied = 0x0f)

    @Test
    fun `1f 08 carries the capacity and the occupancy bitmask`() {
        val slots = BoseCncModes.slotsOf(transaction)
        assertEquals(BoseCncModes.Slots(4, 0x0f), slots)
        assertTrue(slots!!.holds(2))
    }

    @Test
    fun `deleting writes the blanked record Bose Music writes`() {
        val frames = BoseCncModes.delete(slot = 2, slots = full)
        assertEquals(capturedDelete.toList(), frames[0].bytes.toList())
    }

    @Test
    fun `deleting clears only its own occupancy bit`() {
        val frames = BoseCncModes.delete(slot = 2, slots = full)
        assertEquals(Hex.parse("1f 08 02 02 04 0b").toList(), frames[1].bytes.toList())
    }

    @Test
    fun `creating writes the record Bose Music writes`() {
        val frames =
            BoseCncModes.create(
                2,
                nameId = 0x0a,
                name = "Home",
                level = 0,
                slots = full.with(2, false),
            )
        assertEquals(capturedCreate.toList(), frames[0].bytes.toList())
    }

    @Test
    fun `creating sets its occupancy bit, because the record alone does not`() {
        val frames =
            BoseCncModes.create(
                2,
                nameId = 0x0a,
                name = "Home",
                level = 0,
                slots = full.with(2, false),
            )
        assertEquals(Hex.parse("1f 08 02 02 04 0f").toList(), frames[1].bytes.toList())
    }

    @Test
    fun `an empty name is refused, because that is a delete`() {
        val e =
            runCatching {
                BoseCncModes.create(2, nameId = 0x0a, name = "", level = 0, slots = full)
            }
        assertTrue(e.isFailure)
    }

    /**
     * ⚠ **The four the WIRE gave, before the decompile was read.** These are the check on
     * the other 33 names: they were measured off a QC45's own slot records on 2026-08-26
     * and 2026-08-28, and if the decompiled table disagreed with any of them the table
     * would be the thing that is wrong.
     */
    @Test
    fun `the decompiled name table agrees with every byte measured on the wire`() {
        assertEquals("Quiet", BosePromptName.of(0x01)?.label)
        assertEquals("Aware", BosePromptName.of(0x02)?.label)
        assertEquals("Commute", BosePromptName.of(0x07)?.label)
        assertEquals("Home", BosePromptName.of(0x0a)?.label)
    }

    @Test
    fun `the name table is not alphabetical, which is what misread it once`() {
        // Commute 07 and Home 0a are three apart with Outdoor and Workout between them,
        // not Focus — the vendor PICKER is sorted, the wire table is not.
        assertEquals("Outdoor", BosePromptName.of(0x08)?.label)
        assertEquals("Workout", BosePromptName.of(0x09)?.label)
        assertEquals(0x0d, BosePromptName.FOCUS.id)
    }

    @Test
    fun `an id the table does not have is null rather than a guess`() {
        assertNull(BosePromptName.of(0xff))
    }

    @Test
    fun `a free slot comes from the bitmask, not from the record list`() {
        // The delete leaves a full-length record behind, so `modes` still describes four
        // slots; only 1f 08 knows slot 2 is empty.
        val cnc =
            CncModes(
                modes = BoseCncModes.modes(transaction),
                active = 3,
                slots = BoseCncModes.Slots(capacity = 4, occupied = 0x0b),
            )
        assertEquals(2, cnc.free)
    }

    @Test
    fun `a full table offers no free slot`() {
        val cnc =
            CncModes(
                modes = BoseCncModes.modes(transaction),
                active = 3,
                slots = BoseCncModes.Slots(capacity = 4, occupied = 0x0f),
            )
        assertNull(cnc.free)
    }

    /**
     * Commute as the device returned it with wind block ON — `[46]`=01, and `[42]`=00
     * where it had been 07 a second earlier. Read off the wire 2026-08-28 after writing
     * `[38]`=01, and put back in the same sitting.
     */
    private val windBlockOn =
        Hex.parse(
            """
        1f 06 03 2f 03 00 07 01 01 01 43 6f 6d 6d 75 74 65 00 00 00 00 00
        00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
        00 09 00 00 00 00 01
        """,
        )

    @Test
    fun `wind block is read from byte 46`() {
        val m = BoseCncModes.modes(windBlockOn).single()
        assertTrue(m.windBlock)
    }

    @Test
    fun `turning wind block on took the level with it`() {
        // ⚠ Not an assertion about what SHOULD happen — this is what the device did when
        // 07 was written into the level beside a wind-block enable.
        assertEquals(0, BoseCncModes.modes(windBlockOn).single().level)
    }

    @Test
    fun `wind block is mutable on the owner's modes and not on the built-ins`() {
        val modes = BoseCncModes.modes(transaction).associateBy { it.name }
        assertTrue(modes.getValue("Commute").windBlockMutable)
        assertTrue(modes.getValue("Home").windBlockMutable)
        assertEquals(false, modes.getValue("Quiet").windBlockMutable)
        assertEquals(false, modes.getValue("Aware").windBlockMutable)
    }

    @Test
    fun `the wind block write is the frame that was driven`() {
        val commute = BoseCncModes.modes(transaction).single { it.name == "Commute" }
        val expected =
            Hex.parse(
                """
                1f 06 02 27 03 00 07 43 6f 6d 6d 75 74 65 00 00 00 00 00 00 00 00
                00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 07 00 00 01
                """,
            )
        assertEquals(expected.toList(), BoseCncModes.setWindBlock(commute, true).bytes.toList())
    }

    @Test
    fun `setting a level keeps wind block where it was`() {
        val on = BoseCncModes.modes(windBlockOn).single()
        // ⚠ The regression this guards: setLevel used to build a record from scratch, so
        // moving the slider would silently turn wind block off.
        assertEquals(
            1,
            BoseCncModes
                .setLevel(on, 4)
                .bytes
                .last()
                .toInt(),
        )
    }
}
