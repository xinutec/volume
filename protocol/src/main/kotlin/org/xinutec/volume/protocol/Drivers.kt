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
    object BoseQc45 :
        AncDriver,
        MultipointDriver {
        override val modes = setOf(AncMode.ANC, AncMode.AMBIENT)

        override fun readMultipoint(t: Transport): Boolean? =
            BoseMultipoint.state(t.exchange(BoseMultipoint.get()))

        override fun writeMultipoint(t: Transport, on: Boolean) {
            t.exchange(BoseMultipoint.set(on))
        }

        /**
         * The tone controls. ⚠ **Not [EqDriver]**, on purpose: that interface is
         * built around a preset the device chooses a curve for, and the QC45 has no
         * preset on the wire at all. Making it fit would mean inventing an id to put
         * in [EqSetting.preset], which is the kind of tidy lie that later reads as a
         * measurement.
         */
        fun readEq(t: Transport): BoseBands? = BoseEq.state(t.exchange(BoseEq.get()))

        /**
         * ⚠ **Three frames, one per band**, because that is what the vendor app
         * sends — and each draws the full state back, so the last reply is the whole
         * answer. Sending fewer is untested: nothing says a band left alone keeps its
         * value across a partial write.
         */
        fun writeEq(t: Transport, bands: BoseBands): BoseBands? {
            val replies = BoseEq.setAll(bands).map { t.exchange(it) }
            return BoseEq.state(replies.last())
        }

        fun readButton(t: Transport): BoseButton.Action? =
            BoseButton.state(t.exchange(BoseButton.get()))

        fun writeButton(t: Transport, action: BoseButton.Action): BoseButton.Action? =
            BoseButton.state(t.exchange(BoseButton.set(action)))

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

        override fun name(t: Transport): String? = Bose.name(t)
    }

    /** Shared by both Bose models, whose framing is identical. */
    private object Bose {
        /**
         * `01 02` is the device name, as the owner set it.
         *
         * ⚠ The payload begins with a byte that is **not** part of the name — the
         * QC35 answers `01 02 03 12 00 "Pippijn Bose QC35"`. Its meaning is not
         * established; what matters is that including it yields a name with a
         * leading NUL, which `trim()` does not remove and which renders as nothing,
         * so the bug would have shown up as a name that silently lost a character.
         */
        fun name(t: Transport): String? {
            val r = t.exchange(byteArrayOf(0x01, 0x02, 0x01, 0x00))
            // <block><fn><operator><length><payload…>; only a Status frame carries one.
            if (r.size < 6 || r[2] != 0x03.toByte()) return null
            val len = ((r[3].toInt() and 0xff) - 1).coerceAtMost(r.size - 5)
            if (len <= 0) return null
            return String(r, 5, len, Charsets.UTF_8).trim { it <= ' ' }.ifBlank { null }
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

        override fun name(t: Transport): String? = Bose.name(t)
    }

    /**
     * JBL Tour One M2, over **GATT** — the one device here whose control is not
     * RFCOMM at all.
     *
     * The body is TLV pairs: `01` ANC, `02` ambient, `03` TalkThru.
     *
     * ✅ **All three driven, TalkThru on 2026-08-16** — and confirmed against the
     * vendor app's own selector, which is what makes it TalkThru rather than merely
     * "the third slot went to 1". Its screen showed TalkThru highlighted.
     */
    object JblBes : AncDriver {
        override val modes = setOf(AncMode.ANC, AncMode.AMBIENT, AncMode.TALK_THRU)

        /**
         * `aa 91 07 12 01 <anc> 02 <amb> 03 <talkthru>`.
         *
         * ⚠ **This used to report TalkThru as OFF**, because it only looked at the
         * first two slots and fell through to OFF — so a real mode the device was
         * actually in rendered as the one state the JBL cannot be put into from
         * here. Found the moment TalkThru was first driven. ⚠ The length guard was
         * `< 9` while the byte it now reads is index 9, which needs 10.
         */
        override fun read(t: Transport): AncMode? {
            val r = t.exchange(byteArrayOf(0xaa.toByte(), 0x91.toByte(), 0x01, 0x11))
            // ⚠ **The command byte is checked, not just the `aa`.** Every frame this
            // chip sends starts `aa`, including the `aa b1` GetSetFeature poll it
            // runs every four seconds, so `aa` alone admits any of them — and the TLV slots would
            // then be read out of a frame about the battery. Being strict turns a
            // confident wrong mode into an honest "cannot say".
            if (r.size < 10 || r[0] != 0xaa.toByte() || r[1] != 0x91.toByte()) return null
            return when {
                r[5] == 0x01.toByte() -> AncMode.ANC
                r[7] == 0x01.toByte() -> AncMode.AMBIENT
                r[9] == 0x01.toByte() -> AncMode.TALK_THRU
                else -> AncMode.OFF
            }
        }

        /**
         * `aa 11` asks; the reply `aa 12 <len> <name…>` starts with the name as
         * NUL-terminated ASCII, followed by battery and addresses.
         */
        override fun name(t: Transport): String? {
            val r = t.exchange(byteArrayOf(0xaa.toByte(), 0x11, 0x00))
            if (r.size < 4 || r[0] != 0xaa.toByte() || r[1] != 0x12.toByte()) return null
            val end = (3 until r.size).firstOrNull { r[it] == 0x00.toByte() } ?: return null
            return String(r, 3, end - 3, Charsets.UTF_8).trim().ifBlank { null }
        }

        override fun write(t: Transport, mode: AncMode) {
            val anc = if (mode == AncMode.ANC) 1 else 0
            val amb = if (mode == AncMode.AMBIENT) 1 else 0
            // ⚠ Exactly one slot is set; OFF is all three zero, which is how the
            // device reports the state and how its app writes it.
            val talk = if (mode == AncMode.TALK_THRU) 1 else 0
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
                    talk.toByte(),
                ),
            )
        }

        fun readAutoOff(t: Transport): TimedOff? = JblAutoOff.state(t.exchange(JblAutoOff.get()))

        /**
         * Send it, and say nothing about whether it took.
         *
         * ⚠ **Returns Unit on purpose.** The device answers `aa 00 02 33 00`, which is
         * an ack — it says the frame arrived, not that the setting moved, and this
         * repo has been wrong once already by reading one of those as an answer. The
         * caller re-reads; [readAutoOff] is the only thing that knows.
         */
        fun writeAutoOff(t: Transport, v: TimedOff) {
            t.exchange(JblAutoOff.set(v))
        }

        fun readCurve(t: Transport): EqCurve? = JblEq.curve(t.exchange(JblEq.get()))

        /** ⚠ Read only, deliberately — [JblSafeSound] says why there is no writer. */
        fun readVolumeLimit(t: Transport): Boolean? =
            JblSafeSound.state(t.exchange(JblSafeSound.get()))

        fun readSpatial(t: Transport): Spatial? = JblSpatial.state(t.exchange(JblSpatial.get()))

        /**
         * Write both the switch and the mode, and return what the device then reports.
         *
         * ⚠ **Unlike [writeAutoOff] this can return the new state**, because `aa 9d`
         * answers with the status frame rather than an ack — so the read-back is the
         * reply itself and costs no extra round trip. Still a read-back and not an
         * assumption: [JblSpatial.state] returns null if the device answered something
         * else, and a null here means *unknown*, never *it worked*.
         *
         * ⚠ The mode goes with every write because the device takes both in one frame;
         * there is no way to change the switch alone, which is also why the vendor
         * app's mode buttons switch the feature on.
         */
        fun writeSpatial(t: Transport, v: Spatial): Spatial? =
            JblSpatial.state(t.exchange(JblSpatial.set(v)))

        fun readVoiceAware(t: Transport): VoiceAware? =
            JblVoiceAware.state(t.exchange(JblVoiceAware.get()))

        /** Level and switch in one frame, and the reply is the read-back — as [writeSpatial]. */
        fun writeVoiceAware(t: Transport, v: VoiceAware): VoiceAware? =
            JblVoiceAware.state(t.exchange(JblVoiceAware.set(v)))

        fun readSmartTalk(t: Transport): SmartTalk? =
            JblSmartTalk.state(t.exchange(JblSmartTalk.get()))

        /** Switch and hold in one frame, and the reply is the read-back — as [writeSpatial]. */
        fun writeSmartTalk(t: Transport, v: SmartTalk): SmartTalk? =
            JblSmartTalk.state(t.exchange(JblSmartTalk.set(v)))

        fun readLowVolumeEq(t: Transport): Boolean? =
            JblLowVolumeEq.state(t.exchange(JblLowVolumeEq.get()))

        fun writeLowVolumeEq(t: Transport, on: Boolean): Boolean? =
            JblLowVolumeEq.state(t.exchange(JblLowVolumeEq.set(on)))

        fun readSmartAv(t: Transport): SmartAv? = JblSmartAv.state(t.exchange(JblSmartAv.get()))

        fun readGestures(t: Transport): Map<Gesture, GestureAction>? =
            JblGestures.state(t.exchange(JblGestures.get()))

        fun writeSmartAv(t: Transport, v: SmartAv): SmartAv? =
            JblSmartAv.state(t.exchange(JblSmartAv.set(v)))

        /**
         * Read the curve, write [table] and [gains] into that frame, and read back.
         *
         * ⚠ The read up front is not a wasted round trip: [JblEq.set] builds the write
         * from it precisely so the thirteen unexplained bytes go back unchanged.
         */
        fun writeCurve(t: Transport, table: Int, gains: List<Float>): EqCurve? {
            val read = t.exchange(JblEq.get())
            val frame = JblEq.set(read, table, gains) ?: return null
            t.exchange(frame)
            return readCurve(t)
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
    class SonyXm4 :
        AncDriver,
        EqDriver,
        MultipointDriver {
        /**
         * ⚠ **Sony frames carry an alternating sequence bit, and it is not
         * decoration.** Send two frames with the same one and the device treats the
         * second as a retransmission and ignores it — silently, so a read returns
         * nothing and the screen says "this device reports no mode", which is
         * exactly what another device legitimately says.
         *
         * A hard-coded `00` therefore worked for as long as each session asked
         * exactly one question, and broke the moment a session opener was added in
         * front of the read. Per instance, not per object: two headphones must not
         * share a counter, which is why [Registry] builds a fresh driver per device.
         */
        private var seq: Byte = 0

        private fun nextSeq(): Byte {
            val s = seq
            seq = (s.toInt() xor 1).toByte()
            return s
        }

        override val modes = setOf(AncMode.OFF, AncMode.ANC, AncMode.AMBIENT)

        /**
         * `CONNECT_GET_PROTOCOL_INFO`, which is what turns a socket into a session.
         *
         * The probe always sent this first and every read worked; the driver did
         * not, and read fine — until a Bluetooth cycle gave it a fresh link, after
         * which `66 02` answered with an ACK and nothing else. So it is not
         * ceremony: it is the difference between a session and a socket.
         */
        override fun prepare(t: Transport) {
            exchangeFramed(t, byteArrayOf(0x00, 0x00))
        }

        private companion object {
            /** `NcAsmInquiredType.NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE`. */
            const val TYPE: Byte = 0x02

            /** The maximum of the twenty ambient steps the capability reports. */
            const val AMBIENT_MAX: Byte = 0x14
        }

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

        /**
         * The equaliser, decoded 2026-08-16 (`docs/sony-settings.md`).
         *
         * ✅ **Driven against the XM4 on 2026-08-16**, read and write. The read
         * returned `preset=a2, levels=[3, 0, 0, 2, 4, 6]`, byte-identical to what the
         * vendor app's capture had shown the day it was decoded — the same answer by
         * two independent routes. Presets `a1` and `a2` were each written and
         * confirmed by read-back.
         *
         * Preset ids seen going past: `a0`, `a1`, `a2` — the Customs, one of which
         * the owner had set to CLEAR BASS +3 and which read back as `0d` = +3 — and
         * `16`, `17`. The XM4's menu holds more, and no frame enumerates them.
         * `a1` reads flat.
         */
        override fun readEq(t: Transport): EqSetting? =
            exchangeFramed(t, SonyEq.get())?.let(SonyEq::state)

        /**
         * ⚠ The reply window holds **two** frames: the ack, then a `NTFY_PARAM`
         * carrying the resulting state. [exchangeFramed] takes the last DATA frame,
         * so the ack does not shadow it — and the state comes back for [setEq] to
         * compare, which is the only thing that makes it evidence.
         */
        override fun writeEq(t: Transport, preset: Int): EqSetting? =
            exchangeFramed(t, SonyEq.set(preset))?.let(SonyEq::state)

        /**
         * ⚠ The band table is **not** re-read per preset because it changes — it
         * was byte-identical in all eleven captured replies. Sony Headphones Connect
         * asks after every change; this asks when someone wants the axis.
         */
        override fun bands(t: Transport): List<Int> =
            exchangeFramed(t, SonyEq.getBands())?.let(SonyEq::bands) ?: emptyList()

        fun readAutoOff(t: Transport): AutoOff? =
            exchangeFramed(t, SonyAutoOff.get())?.let(SonyAutoOff::state)

        /**
         * ⚠ Its notify echoes the value set, so this one really is confirmable.
         * ✅ Driven on hardware 2026-08-16, both directions, and restored.
         */
        fun writeAutoOff(t: Transport, mode: AutoOff): AutoOff? =
            exchangeFramed(t, SonyAutoOff.set(mode))?.let(SonyAutoOff::state)

        /** ✅ Driven on hardware 2026-08-16, both directions, and restored. */
        fun readSoundQuality(t: Transport): SoundQuality? =
            exchangeFramed(t, SonySoundQuality.get())?.let(SonySoundQuality::state)

        fun writeSoundQuality(t: Transport, mode: SoundQuality): SoundQuality? =
            exchangeFramed(t, SonySoundQuality.set(mode))?.let(SonySoundQuality::state)

        fun readButton(t: Transport): SonyButton.Action? =
            exchangeFramed(t, SonyButton.get())?.let(SonyButton::state)

        /**
         * ⚠ **Known not to take**, and kept because the frame is right and the failure
         * is the finding — see [SonyButton]. The device acks and ignores it, so this
         * returns null and [readButton] will still report the old value. That is the
         * honest outcome, not a bug to paper over with a retry.
         */
        fun writeButton(t: Transport, action: SonyButton.Action): SonyButton.Action? =
            exchangeFramed(t, SonyButton.set(action))?.let(SonyButton::state)

        override fun readMultipoint(t: Transport): Boolean? =
            exchangeFramed(t, SonyMultipoint.get())?.let(SonyMultipoint::state)

        /**
         * ⚠ Returns nothing on purpose. The reply to this write is a notification
         * about a *different* parameter, so handing it back would invite exactly the
         * comparison it cannot support — see [SonyMultipoint].
         */
        override fun writeMultipoint(t: Transport, on: Boolean) {
            exchangeFramed(t, SonyMultipoint.set(on))
        }

        /** Send one framed payload, ack the device's answer, return its payload. */
        private fun exchangeFramed(t: Transport, payload: ByteArray): ByteArray? {
            val got = t.exchange(SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, nextSeq(), payload))
            val data =
                SonyFrame.decodeAll(got).lastOrNull { it.type == SonyFrame.TYPE_DATA_MDR }
                    ?: return null
            ackFor(data)?.let(t::send)
            return data.payload
        }

        /** The ack a received DATA frame expects: type 01, sequence inverted. */
        private fun ackFor(frame: SonyFrame.Frame): ByteArray? =
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
     * ✅ **The read was found on 2026-08-16 and this device is now confirmable.**
     * Until then [read] returned null and every write was
     * [Confirmation.Unverifiable]. Its `47` reply is still not a success signal — a
     * mode that does not exist draws the identical one — so confirmation comes from
     * [read], never from the reply.
     *
     * ⚠ **It was found by disproving "the app tracks the mode locally"**, which is
     * what this file used to say. The test was to set a mode from *this* code, then
     * launch `com.jlab.app` cold and see what its UI drew: it showed the mode the
     * device was actually in, both ways round. So a read had to exist, and the
     * capture of that launch contained it.
     */
    object JLabQcy : AncDriver {
        override val modes = setOf(AncMode.OFF, AncMode.ANC, AncMode.AMBIENT)

        /**
         * `c0 ff 00 44 00 00 01 00 04` → `00 ff 01 45 03 00 <mode> <a> <b> 00 <sum> 00`.
         *
         * ⚠ **The mode is the SEVENTH byte**, and the two after it are not constant:
         * they read `04 04` in either ANC mode and `00 00` when ANC is off, so a
         * decoder keying on them would be reading something else's field.
         *
         * ⚠ The reply's checksum does not follow the requests' sum-mod-256 rule. It
         * came out **exactly 2 less** than that sum in all three states measured,
         * which is consistent enough to be a rule and is not one anybody here has
         * worked out — so it is left unchecked rather than guessed at.
         */
        override fun read(t: Transport): AncMode? {
            val r =
                t.exchange(
                    checksummed(
                        byteArrayOf(
                            0xc0.toByte(),
                            0xff.toByte(),
                            0x00,
                            0x44,
                            0x00,
                            0x00,
                            0x01,
                            0x00,
                        ),
                    ),
                )
            if (r.size < 7 || r[3] != 0x45.toByte()) return null
            return when (r[6]) {
                0x00.toByte() -> AncMode.OFF
                0x01.toByte() -> AncMode.ANC
                0x02.toByte() -> AncMode.AMBIENT
                else -> null
            }
        }

        /**
         * ⚠ **`00` is Off, and that is now measured rather than assumed.** It was
         * written down as "untested" for as long as there was no read to check it
         * with; with [read] in hand it was driven and read back, and the whole
         * payload came back `00 00 00`. ⚠ The trailing `04 04` is still sent for
         * Off, because that is what was driven — the device normalises it.
         */
        override fun write(t: Transport, mode: AncMode) {
            val m: Byte =
                when (mode) {
                    AncMode.OFF -> 0x00
                    AncMode.ANC -> 0x01
                    else -> 0x02
                }
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
