package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Jieli's RCSP envelope, against what `packSendBasePacket` assembles.
 *
 * ⚠ **No device has answered any of this.** Every expectation here is read off the SDK
 * in the JLab APK, so these tests pin THE SDK's frame, and a device that disagrees
 * refutes the assumption that this unit speaks RCSP rather than refuting the test.
 */
class RcspTest {
    private fun bytes(s: String) = Hex.parse(s)

    private fun hex(b: ByteArray) = Hex.format(b).replace(" ", "")

    /**
     * A read of `GET_TARGET_INFO` is nine bytes.
     *
     * `paramLen + 8`, and the param is the sequence number alone. `c0` is command
     * (`80`) with a response wanted (`40`).
     */
    @Test
    fun `a read frame is the SDK's shape`() {
        assertEquals(
            "fedcbac0030001" + "07" + "ef",
            hex(Rcsp.command(RcspCommand.GET_TARGET_INFO, sn = 7)),
        )
    }

    /** ⚠ The length counts the sequence number, which lives in the param block. */
    @Test
    fun `the length counts the sequence number and the params`() {
        val f = Rcsp.command(RcspCommand.GET_SYS_INFO, sn = 1, params = byteArrayOf(1, 2, 3))
        assertEquals(4, (f[5].toInt() shl 8) or f[6].toInt())
        assertEquals(4 + Rcsp.OVERHEAD, f.size)
    }

    /** Two bytes, high first — `CHexConver.int2byte2` writes the length big-endian. */
    @Test
    fun `the length is big-endian`() {
        val f = Rcsp.command(RcspCommand.GET_SYS_INFO, sn = 0, params = ByteArray(300))
        assertEquals(0x01, f[5].toInt() and 0xff)
        assertEquals(0x2d, f[6].toInt() and 0xff)
    }

    /** Without a response wanted the `40` bit is clear and the rest is unchanged. */
    @Test
    fun `a frame that wants no answer clears one bit`() {
        val f = Rcsp.command(RcspCommand.GET_SYS_INFO, sn = 0, wantResponse = false)
        assertEquals(0x80, f[3].toInt() and 0xff)
    }

    /** ⚠ The head is `fe dc ba`, which is what the SIGNED smali constants decode to. */
    @Test
    fun `a frame that is not RCSP has no opcode`() {
        assertEquals(RcspCommand.GET_SYS_INFO, Rcsp.opcode(Rcsp.command(0x07, 0)))
        assertNull(Rcsp.opcode(bytes("aa21013300")))
        assertNull(Rcsp.opcode(bytes("fedc")))
    }

    /**
     * ⚠ **A TRUNCATED destructive frame is still refused.**
     *
     * The first version required a whole valid frame to recognise one, so seven bytes
     * of `fe dc ba … 22` fell through to the Bose arm and was described as "Bose block
     * fe". It would not have driven the device, but a guard that a malformed frame
     * walks past is not a guard.
     */
    @Test
    fun `a truncated destructive frame is still refused`() {
        val short = Rcsp.command(RcspCommand.FORMAT_DEVICE, 0).copyOf(7)
        assertEquals(RcspCommand.FORMAT_DEVICE, Rcsp.opcode(short))
        assertNotNull(Hazards.check(Channels.SPP, short, SonyTable.TABLE_1, null))
    }

    // ---- the deny-list, which exists before anything has been sent ----------

    /**
     * ⛔ The destructive opcodes are refused, and by name.
     *
     * ⚠ The check is keyed on the payload's own head bytes rather than on a detected
     * protocol, because the device this guards reads as `UNKNOWN / NONE` — an arm that
     * waited for detection would never run.
     */
    @Test
    fun `the destructive RCSP opcodes are refused`() {
        for (op in listOf(0x22, 0xe7, 0x1a, 0x1f, 0x23, 0x06)) {
            val r = Hazards.check(Channels.SPP, Rcsp.command(op, 0), SonyTable.TABLE_1, null)
            assertNotNull("opcode %02x was admitted".format(op), r)
        }
        for (op in 0xe1..0xe8) {
            val r = Hazards.check(Channels.SPP, Rcsp.command(op, 0), SonyTable.TABLE_1, null)
            assertNotNull("OTA opcode %02x was admitted".format(op), r)
        }
    }

    /** …and the reads are not, or the deny-list would be a ban. */
    @Test
    fun `the RCSP reads are admitted`() {
        for (op in listOf(0x02, 0x03, 0x07, 0xd9)) {
            val r = Hazards.check(Channels.SPP, Rcsp.command(op, 0), SonyTable.TABLE_1, null)
            assertNull("opcode %02x was refused".format(op), r)
        }
    }

    /** The refusal names the command and the consequence, as every other one does. */
    @Test
    fun `a refusal says what it refused and why`() {
        val r = Hazards.check(Channels.SPP, Rcsp.command(0x22, 0), SonyTable.TABLE_1, null)!!
        assertEquals("RCSP 22", r.what)
        assertEquals(true, r.why.contains("FORMAT_DEVICE"))
    }
}
