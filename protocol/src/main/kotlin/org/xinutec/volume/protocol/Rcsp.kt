package org.xinutec.volume.protocol

/**
 * Jieli's RCSP — the control protocol behind a `JL_SPP` service record.
 *
 * ```
 * fe dc ba <flags> <opcode> <len: 2 BE> <param…> ef
 *          flags: 80 = command, |40 = a response is wanted
 *          param[0] = the sequence number, on a command
 * ```
 *
 * ✅ **Read out of `com/jieli/bluetooth` in the JLab APK on 2026-09-12**, not guessed:
 * `ParseHelper.packSendBasePacket` assembles exactly this, `ParseHelper`'s four framing
 * constants are the head and tail, and `CHexConver.int2byte2` writes the length high
 * byte first. There is no checksum — the assembler sizes the whole frame at
 * `paramLen + 8`, which is the three head bytes, flags, opcode, two length bytes and
 * the tail, with nothing left over.
 *
 * ⚠ **The framing constants are SIGNED in smali**, the same trap as the Sony tables:
 * `PREFIX_FLAG_FIRST` reads `-0x2t` and is `0xfe`. A parser that took them unsigned
 * would find none of them.
 *
 * ⚠⚠ **No device here has answered this yet.** The JLab's APK carries Jieli's SDK
 * because it is a rebranded QCY app bundling six chip SDKs; the NewPie 32 advertises
 * `JL_SPP` and is silent until asked. Those are two separate facts and neither is a
 * reply. Until one arrives this is a description of the SDK, not of a device.
 */
object Rcsp {
    /** `fe dc ba`, from `ParseHelper.PREFIX_FLAG_FIRST`/`SECOND`/`THIRD`. */
    val HEAD = byteArrayOf(0xfe.toByte(), 0xdc.toByte(), 0xba.toByte())

    /** `ef`, from `ParseHelper.END_FLAG`. */
    const val TAIL: Byte = 0xef.toByte()

    /** Set in the flags byte when the frame is a command rather than a response. */
    const val COMMAND: Int = 0x80

    /** Set in the flags byte to ask the device to answer. */
    const val WANT_RESPONSE: Int = 0x40

    /** Head, flags, opcode, two length bytes and the tail — everything but the param. */
    const val OVERHEAD = 8

    private const val FLAGS = 3
    private const val OPCODE = 4
    private const val LENGTH = 5
    private const val PARAM = 7

    /**
     * A command frame carrying [params] after the sequence number.
     *
     * ⚠ **[sn] is part of the PARAM block, not the header** — `packSendBasePacket`
     * copies it to `param[0]` and the length counts it. Sizing a frame as though the
     * sequence number were a header field puts every later byte one place out.
     */
    fun command(
        opcode: Int,
        sn: Int,
        params: ByteArray = ByteArray(0),
        wantResponse: Boolean = true,
    ): ByteArray {
        val len = params.size + 1
        val out = ByteArray(len + OVERHEAD)
        HEAD.copyInto(out)
        out[FLAGS] = (COMMAND or if (wantResponse) WANT_RESPONSE else 0).toByte()
        out[OPCODE] = opcode.toByte()
        out[LENGTH] = ((len shr 8) and 0xff).toByte()
        out[LENGTH + 1] = (len and 0xff).toByte()
        out[PARAM] = sn.toByte()
        params.copyInto(out, PARAM + 1)
        out[out.size - 1] = TAIL
        return out
    }

    /** The opcode a frame carries, or null if it is not one of these frames. */
    fun opcode(frame: ByteArray): Int? {
        if (frame.size < OVERHEAD) return null
        if (!(frame[0] == HEAD[0] && frame[1] == HEAD[1] && frame[2] == HEAD[2])) return null
        return frame[OPCODE].toInt() and 0xff
    }
}

/**
 * The RCSP opcodes this repo names, from `com/jieli/bluetooth/constant/Command.smali`.
 *
 * ⚠ **Named, not offered.** Nothing here is wired to a driver; the reads are the
 * candidates for a first bounded exchange and the rest are here so [Hazards] can refuse
 * them by name rather than by number.
 */
object RcspCommand {
    // Reads — no state changes behind any of these.
    const val GET_TARGET_FEATURE_MAP = 0x02
    const val GET_TARGET_INFO = 0x03
    const val GET_SYS_INFO = 0x07
    const val GET_DEVICE_CONFIG_INFO = 0xd9

    // ⛔ Destructive. See [Hazards].
    const val DISCONNECT_CLASSIC_BLUETOOTH = 0x06
    const val EXTERNAL_FLASH_IO_CTRL = 0x1a
    const val FILE_BROWSE_DELETE = 0x1f
    const val FORMAT_DEVICE = 0x22
    const val DELETE_FILE_BY_NAME = 0x23
    const val OTA_FIRST = 0xe1
    const val OTA_LAST = 0xe8
    const val REBOOT_DEVICE = 0xe7
}
