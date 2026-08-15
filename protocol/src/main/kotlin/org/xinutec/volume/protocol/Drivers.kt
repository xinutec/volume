package org.xinutec.volume.protocol

/**
 * The five ANC drivers, one per wire format.
 *
 * Every byte below was driven against the real headphones on 2026-08-15 and is
 * documented with its evidence in `docs/protocols.md`. **Re-measure before
 * trusting it**; firmware moves things.
 */
object Drivers {
    /**
     * Bose QC45. Its ANC lives in block `1f`, which the first sweep never reached
     * because blocks `0a`–`0d` answer "block not supported" and read like the end
     * of the map.
     *
     * Slots are the device's own mode table: 0 Quiet, 1 Aware, 2 Home, 3 unnamed.
     * Only the two ends are mapped here — Home is a user-defined level, not a
     * fourth semantic mode.
     */
    object BoseQc45 : AncDriver {
        override val modes = setOf(AncMode.ANC, AncMode.AMBIENT)

        private const val QUIET: Byte = 0x00
        private const val AWARE: Byte = 0x01

        override fun read(t: Transport): AncMode? {
            // 01 05 reads the ACTIVE level, and is read-only: three writes there
            // were refused 04 01 05. The reply is a Status frame whose payload is
            // `0b <level> 03`, so the level is the SIXTH byte, not the fifth —
            // 0b is a constant and reads convincingly like data.
            val r = t.exchange(byteArrayOf(0x01, 0x05, 0x01, 0x00))
            val level = r.getOrNull(5) ?: return null
            return if (level == 0x00.toByte()) AncMode.ANC else AncMode.AMBIENT
        }

        override fun write(t: Transport, mode: AncMode) {
            val slot = if (mode == AncMode.ANC) QUIET else AWARE
            // ⚠ Operator 05 is Start, and the payload order is <slot> 01, not
            // 01 <slot>. The one captured example had 01 in both bytes, which hid
            // the order until a slot other than 1 was tried.
            t.exchange(byteArrayOf(0x1f, 0x03, 0x05, 0x02, slot, 0x01))
        }
    }

    /**
     * Bose QC35. Same framing as the QC45, different table — `01 06` is ANC here
     * and is a function the QC45 reports unsupported. Three states, not a scale.
     */
    object BoseQc35 : AncDriver {
        override val modes = setOf(AncMode.OFF, AncMode.ANC, AncMode.AMBIENT)

        private fun value(mode: AncMode): Byte =
            when (mode) {
                AncMode.ANC -> 0x00
                AncMode.AMBIENT -> 0x01
                AncMode.OFF -> 0x03
                else -> error("QC35 has no $mode")
            }

        override fun read(t: Transport): AncMode? {
            val r = t.exchange(byteArrayOf(0x01, 0x06, 0x01, 0x00))
            return when (r.getOrNull(4)) {
                0x00.toByte() -> AncMode.ANC
                0x01.toByte() -> AncMode.AMBIENT
                0x03.toByte() -> AncMode.OFF
                else -> null
            }
        }

        override fun write(t: Transport, mode: AncMode) {
            t.exchange(byteArrayOf(0x01, 0x06, 0x02, 0x01, value(mode)))
        }
    }

    /**
     * JBL Tour One M2, over **GATT** — the one device here whose control is not
     * RFCOMM at all.
     *
     * The body is TLV pairs. `01` is ANC, `02` ambient, `03` presumably TalkThru:
     * the first two were read against the vendor app's own screen, the third is
     * named from its UI and has **not** been exercised, which is why it is absent
     * from [modes].
     */
    object JblBes : AncDriver {
        override val modes = setOf(AncMode.ANC, AncMode.AMBIENT)

        override fun read(t: Transport): AncMode? {
            val r = t.exchange(byteArrayOf(0xaa.toByte(), 0x91.toByte(), 0x01, 0x11))
            // aa 91 07 12 <01 anc> <02 amb> <03 talkthru>
            if (r.size < 9 || r[0] != 0xaa.toByte()) return null
            return when {
                r[5] == 0x01.toByte() -> AncMode.ANC
                r[7] == 0x01.toByte() -> AncMode.AMBIENT
                else -> AncMode.OFF
            }
        }

        override fun write(t: Transport, mode: AncMode) {
            val anc = if (mode == AncMode.ANC) 1 else 0
            val amb = if (mode == AncMode.AMBIENT) 1 else 0
            t.exchange(
                byteArrayOf(
                    0xaa.toByte(),
                    0x91.toByte(),
                    0x07,
                    0x10,
                    0x01,
                    anc.toByte(),
                    0x02,
                    amb.toByte(),
                    0x03,
                    0x00,
                ),
            )
        }
    }

    /**
     * Sony WH-1000XM4.
     *
     * ⚠ Reads answer only inside a session whose DATA frames are acknowledged;
     * [SonyFrame] does the framing and [ackFor] the acking. One-shot, `66 02`
     * returns a bare ACK, which reads like "no such command" and is not.
     *
     * Byte roles were measured by changing the mode in the vendor app three times
     * and re-reading. Bytes 1, 3 and 4 held `02 01 00` in every state and are NOT
     * identified — they are echoed back verbatim rather than reasoned about.
     */
    object SonyXm4 : AncDriver {
        override val modes = setOf(AncMode.OFF, AncMode.ANC, AncMode.AMBIENT)

        /** `NcAsmInquiredType.NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE`. */
        private const val TYPE: Byte = 0x02

        /** The maximum of the twenty ambient steps the capability answer reports. */
        private const val AMBIENT_MAX: Byte = 0x14

        override fun read(t: Transport): AncMode? {
            val body = exchangeFramed(t, byteArrayOf(0x66, TYPE)) ?: return null
            // 67 02 <on> 02 <nc> 01 00 <ambient>
            if (body.size < 8 || body[0] != 0x67.toByte()) return null
            return when {
                body[2] == 0x00.toByte() -> AncMode.OFF
                body[4] == 0x00.toByte() -> AncMode.AMBIENT
                else -> AncMode.ANC
            }
        }

        override fun write(t: Transport, mode: AncMode) {
            val on: Byte = if (mode == AncMode.OFF) 0x00 else 0x01
            val nc: Byte = if (mode == AncMode.ANC) 0x02 else 0x00
            val ambient: Byte = if (mode == AncMode.ANC) 0x00 else AMBIENT_MAX
            exchangeFramed(t, byteArrayOf(0x68, TYPE, on, 0x02, nc, 0x01, 0x00, ambient))
        }

        /** Send one framed payload, ack the device's answer, return its payload. */
        private fun exchangeFramed(t: Transport, payload: ByteArray): ByteArray? {
            val got = t.exchange(SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, 0x00, payload))
            val data =
                SonyFrame.decodeAll(got).lastOrNull { it.type == SonyFrame.TYPE_DATA_MDR }
                    ?: return null
            ackFor(data)?.let(t::send)
            return data.payload
        }

        /** The ack a received DATA frame expects: type 01, sequence inverted. */
        fun ackFor(frame: SonyFrame.Frame): ByteArray? =
            if (frame.type != SonyFrame.TYPE_DATA_MDR) {
                null
            } else {
                SonyFrame.encode(
                    SonyFrame.TYPE_ACK,
                    (frame.seq.toInt() xor 1).toByte(),
                    ByteArray(0),
                )
            }
    }

    /**
     * JLab JBuds Sport ANC 4, on plain SPP — the channel that looked dead because
     * it only ever emitted an unsolicited broadcast.
     *
     * ⚠ **No read command is known**, so [read] returns null and every write is
     * [Confirmation.Unverifiable]. Its `47` reply is not a success signal: a mode
     * that does not exist draws the identical one.
     */
    object JLabQcy : AncDriver {
        override val modes = setOf(AncMode.ANC, AncMode.AMBIENT)

        override fun read(t: Transport): AncMode? = null

        override fun write(t: Transport, mode: AncMode) {
            val m: Byte = if (mode == AncMode.ANC) 0x01 else 0x02
            t.exchange(
                checksummed(
                    byteArrayOf(
                        0xc0.toByte(),
                        0xff.toByte(),
                        0x00,
                        0x46,
                        0x03,
                        0x00,
                        m,
                        0x04,
                        0x04,
                        0x01,
                        0x00,
                    ),
                ),
            )
        }

        /**
         * Append the trailing sum-mod-256. The device accepted a frame with it
         * omitted, so it does not appear to be verified — but a frame that matches
         * the app's byte for byte is the one with evidence behind it.
         */
        fun checksummed(body: ByteArray): ByteArray =
            body + body.fold(0) { acc, b -> acc + (b.toInt() and 0xff) }.toByte()
    }
}
