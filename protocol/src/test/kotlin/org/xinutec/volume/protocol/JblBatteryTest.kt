package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `aa 25`, read from the M2 on 2026-08-17 and volunteered by it all evening. */
class JblBatteryTest {
    /** The answer to `aa 25 01 01` at 23:10, and the frame it volunteers unasked. */
    private val sixty = "aa250d0100003c3cffffffffffffffff"

    @Test
    fun `the level is read, and the absent cells are not zeroes`() {
        assertEquals(Battery(percent = 60, charging = false), JblBattery.state(Hex.parse(sixty)))
        assertEquals("aa250101", Hex.format(JblBattery.get()).replace(" ", ""))
    }

    /**
     * ⚠ **`ff` is "no cell", not 255% and not 0%.**
     *
     * `BatteryInfoCmd` discards anything over 100, which is what the trailing bytes of
     * every real frame are — the box slot included, because an over-ear has no case. A
     * reader that masked to 7 bits without the range check would report the box at 127%;
     * one that treated the byte as a plain level would report 255%.
     */
    @Test
    fun `a slot with no cell reads as null, not as a number`() {
        assertNull(JblBattery.state(Hex.parse("aa250d010000ffffffffffffffffffff")))
        // 0x65 = 101, one past the limit.
        assertNull(JblBattery.state(Hex.parse("aa250d0100006565ffffffffffffffff")))
        // 0x64 = 100 is a real full charge and must survive.
        assertEquals(100, JblBattery.state(Hex.parse("aa250d0100006464ffffffffffffffff"))?.percent)
    }

    /** ⚠ The top bit is the cable, so a charging 60% must not read as 188%. */
    @Test
    fun `the charging bit is not part of the percentage`() {
        val charging = JblBattery.state(Hex.parse("aa250d010000bcbcffffffffffffffff"))
        assertEquals(60, charging?.percent)
        // ⚠ `== true` / `== false`, not the bare Boolean: [Battery.charging] became
        // nullable for the Bose, which reports a level and no charging state at all.
        // The JBL always knows — the bit is in the same byte as the percentage — so
        // these stay exact rather than being loosened to a null check.
        assertEquals(true, charging!!.charging)
        assertEquals(false, JblBattery.state(Hex.parse(sixty))!!.charging)
    }

    /**
     * ⚠ The two cup slots have been equal in every frame seen, which is exactly why
     * this repo cannot say which is which — the same shape as SafeSound's two `01`
     * bytes. [JblBattery.cupsDiffer] exists so the day they disagree is visible rather
     * than silently resolved in the master's favour.
     */
    @Test
    fun `the cups agreeing is a fact about the frame, not an assumption`() {
        assertEquals(false, JblBattery.cupsDiffer(Hex.parse(sixty)))
        assertEquals(true, JblBattery.cupsDiffer(Hex.parse("aa250d0100003c50ffffffffffffffff")))
        // Master is index 7, per parseBatteryInfo — 0x50 = 80, not the 0x3c beside it.
        assertEquals(80, JblBattery.state(Hex.parse("aa250d0100003c50ffffffffffffffff"))?.percent)
    }

    /**
     * ⚠ This frame arrives unsolicited and glued to other replies, so the command byte
     * is the only thing separating it from an answer to something else. It was once
     * read as the reply to a question about status field `3b`.
     */
    @Test
    fun `another command is not read as a battery frame`() {
        assertNull(JblBattery.state(Hex.parse("aa220d0100003c3cffffffffffffffff")))
        // Sub-command 02 is not the levels frame parseBatteryInfo reads.
        assertNull(JblBattery.state(Hex.parse("aa250d0200003c3cffffffffffffffff")))
        assertNull(JblBattery.state(Hex.parse("aa250d0100")))
    }
}
