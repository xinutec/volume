package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Auto Play & Pause, balance and PSAP — all three driven or read on 2026-08-17. */
class JblMoreTest {
    // ---- auto play & pause -------------------------------------------------

    /**
     * 23:38, driven off and back on, each confirmed by reading field `38`.
     *
     * ⚠ The setter is `35` and the status is `38`, which do NOT mirror — the habit that
     * works on `31`/`32`/`33` fails here, and arguing from it is what retracted a
     * correct finding once already.
     */
    @Test
    fun `auto play is set on 35 and read on 38`() {
        assertEquals("aa350100", hex(JblAutoPlay.set(on = false)))
        assertEquals("aa350101", hex(JblAutoPlay.set(on = true)))
        assertEquals("aa210138", hex(JblAutoPlay.get()))
        assertEquals(true, JblAutoPlay.state(bytes("aa22023801")))
        assertEquals(false, JblAutoPlay.state(bytes("aa22023800")))
    }

    /** ⚠ The ack `aa 00 02 35 <on>` is not the answer, and must not decode as one. */
    @Test
    fun `the ack is not the state`() {
        assertNull(JblAutoPlay.state(bytes("aa00023501")))
        // Another status field is not this one.
        assertNull(JblAutoPlay.state(bytes("aa22023301")))
    }

    // ---- left / right balance ----------------------------------------------

    /** 23:39 — switched on at centre and off again, both read back. */
    @Test
    fun `balance is read and written as key-value pairs`() {
        assertEquals(Balance(on = false, level = 100), JblBalance.state(bytes("aaa8050201000264")))
        assertEquals(Balance(on = true, level = 100), JblBalance.state(bytes("aaa8050201010264")))
        assertEquals("aaa8050001010264", hex(JblBalance.set(Balance(on = true, level = 100))))
        assertEquals("aaa80101", hex(JblBalance.get()))
    }

    /**
     * ⚠ **Index 4 is a KEY, not the switch**, and reading it as the switch reports a
     * feature that is off as on. That misreading is exactly what kept PSAP off the
     * screen for a day — the same frame shape, the same mistake.
     */
    @Test
    fun `the key bytes are checked, so a positional read cannot pass`() {
        assertEquals(false, JblBalance.state(bytes("aaa8050201000264"))!!.on)
        // Keys in the wrong places: not this command's frame.
        assertNull(JblBalance.state(bytes("aaa8050203000264")))
        assertNull(JblBalance.state(bytes("aaa8050201000364")))
        // A set is not a status, and another command is not this one.
        assertNull(JblBalance.state(bytes("aaa8050001000264")))
        assertNull(JblBalance.state(bytes("aaa0050201000264")))
    }

    // ---- personal sound amplification --------------------------------------

    /**
     * ✅ The frame that was called a contradiction, decoding to what the app said.
     *
     * ⚠ `PSAPCmd` has two branches; the one this shape selects reads the switch from
     * index **5**, not index 4. Index 4 is the key `01`. Reading index 4 gives `01` and
     * "on" for a row the vendor app draws as Disabled — which is the whole reason this
     * was blocked, and it was our misreading rather than a device fault.
     */
    @Test
    fun `psap reads off, which is what the vendor app shows`() {
        assertEquals(false, JblPsap.state(bytes("aaa00702010002640300")))
        assertEquals(true, JblPsap.state(bytes("aaa00702010102640300")))
        assertEquals("aaa00101", hex(JblPsap.get()))
    }

    /** ⚠ There is no writer, and that is the design — [JblPsap] says why. */
    @Test
    fun `another command is not read as psap`() {
        assertNull(JblPsap.state(bytes("aaa80702010002640300")))
        assertNull(JblPsap.state(bytes("aaa00700010002640300")))
        assertNull(JblPsap.state(bytes("aaa007020100")))
    }

    private fun bytes(s: String) = Hex.parse(s)

    private fun hex(b: ByteArray) = Hex.format(b).replace(" ", "")
}
