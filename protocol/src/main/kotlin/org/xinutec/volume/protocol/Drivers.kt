package org.xinutec.volume.protocol

/**
 * What asking the XM4 to change its [CUSTOM] key produced.
 *
 * ⚠ **`Asks` is not a failure and not a success** — it is the device putting a question
 * to the owner, about disconnecting their own audio. Modelled as a distinct outcome so a
 * caller cannot quietly answer it: see #965.
 */
sealed interface ButtonWrite {
    /** The device wants an answer before it will commit. */
    data object Asks : ButtonWrite

    /** Nothing happened — including the ordinary case of writing the value already set. */
    data object Unchanged : ButtonWrite
}

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
     *
     * ⚠ **All three mode bytes were wrong until 2026-08-26, and in a way that
     * inverted the two that matter.** The table read `00` ANC · `01` AMBIENT ·
     * `03` OFF; the device means `00` **Off** · `01` **High** · `03` **Low**. So
     * this app's "Off" chip turned cancelling *down* rather than off, and its
     * "Noise cancelling" chip turned it *off* — the one a person reaches for in a
     * noisy place did the opposite, which is a state somebody might answer by
     * turning the volume up.
     *
     * ⚠ **The wrong table was self-consistent, so nothing on this side could catch
     * it.** [read] and [write] shared it, so a write read back as the mode it had
     * asked for, the card drew that mode, and `DriversTest` asserted the same three
     * pairs. Every check agreed with every other and all of them were wrong
     * together. What broke it was **the vendor app**: Bose Connect names these
     * High/Low/Off on screen, and driving each one there while reading `01 06` from
     * this side gave three labelled bytes that no amount of internal consistency
     * could have produced.
     *
     * ⚠ **The QC35 has no pass-through at all** — see [AncMode.ANC_LOW]. The
     * earlier table's `AMBIENT` was a mode this device does not have, which is the
     * detail that should have looked wrong on paper before any of it was driven.
     */
    object BoseQc35 : AncDriver {
        override val modes = setOf(AncMode.OFF, AncMode.ANC, AncMode.ANC_LOW)

        private fun value(mode: AncMode): Byte =
            when (mode) {
                AncMode.OFF -> 0x00
                AncMode.ANC -> 0x01
                AncMode.ANC_LOW -> 0x03
                else -> error("QC35 has no $mode")
            }

        override fun read(t: Transport): AncMode? {
            val r = t.exchange(byteArrayOf(0x01, 0x06, 0x01, 0x00))
            return when (r.getOrNull(4)) {
                0x00.toByte() -> AncMode.OFF
                0x01.toByte() -> AncMode.ANC
                0x03.toByte() -> AncMode.ANC_LOW
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
     *
     * ⚠ **[modes] omitted OFF until 2026-08-23, so the one thing a JBL owner most
     * obviously wants could not be done here at all.** Nothing else was missing:
     * [read] already decodes the all-zero frame as OFF and [write] already builds
     * it, because "exactly one slot is set" makes OFF fall out of the same
     * arithmetic. The gap was three words in a set, and it survived because the
     * inventory that would have caught it (#974) counts the vendor app's *rows* —
     * and its Ambient Sound Control master switch lives *inside* the ANC row rather
     * than beside it. A parity analysis built from someone else's UI cannot see a
     * gap of that shape. #1041.
     */
    object JblBes : AncDriver {
        override val modes =
            setOf(AncMode.OFF, AncMode.ANC, AncMode.AMBIENT, AncMode.TALK_THRU)

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

        /**
         * Ask, then hand the decoder the frame it is looking for.
         *
         * ⚠ **This exists because the buffer can begin with someone ELSE's frame.** See
         * [Bes.frame]: an unsolicited battery notification lands in 1 reply in 8, and when
         * it arrives first every decoder correctly returns null and a settings row silently
         * disappears. #1154. The decoders were never wrong; they were being handed the
         * wrong offset.
         *
         * ⚠ [decode] is applied to the whole buffer FIRST, so a reply that already starts
         * where it should behaves exactly as it did before this was added.
         */
        private fun <T> ask(t: Transport, request: ByteArray, decode: (ByteArray) -> T?): T? {
            val buffer = t.exchange(request)
            return decode(buffer) ?: Bes.frame(buffer) { decode(it) != null }?.let(decode)
        }

        fun readAutoOff(t: Transport): TimedOff? = ask(t, JblAutoOff.get(), JblAutoOff::state)

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

        fun readCurve(t: Transport): EqCurve? = ask(t, JblEq.get(), JblEq::curve)

        /** ⚠ Read only, deliberately — [JblSafeSound] says why there is no writer. */
        fun readVolumeLimit(t: Transport): Boolean? =
            ask(t, JblSafeSound.get(), JblSafeSound::state)

        fun readSpatial(t: Transport): Spatial? = ask(t, JblSpatial.get(), JblSpatial::state)

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
            ask(t, JblVoiceAware.get(), JblVoiceAware::state)

        /** Level and switch in one frame, and the reply is the read-back — as [writeSpatial]. */
        fun writeVoiceAware(t: Transport, v: VoiceAware): VoiceAware? =
            JblVoiceAware.state(t.exchange(JblVoiceAware.set(v)))

        fun readSmartTalk(t: Transport): SmartTalk? =
            ask(t, JblSmartTalk.get(), JblSmartTalk::state)

        /** Switch and hold in one frame, and the reply is the read-back — as [writeSpatial]. */
        fun writeSmartTalk(t: Transport, v: SmartTalk): SmartTalk? =
            JblSmartTalk.state(t.exchange(JblSmartTalk.set(v)))

        fun readLowVolumeEq(t: Transport): Boolean? =
            ask(t, JblLowVolumeEq.get(), JblLowVolumeEq::state)

        fun writeLowVolumeEq(t: Transport, on: Boolean): Boolean? =
            JblLowVolumeEq.state(t.exchange(JblLowVolumeEq.set(on)))

        fun readSmartAv(t: Transport): SmartAv? = ask(t, JblSmartAv.get(), JblSmartAv::state)

        fun readGestures(t: Transport): Map<Gesture, GestureAction>? =
            ask(t, JblGestures.get(), JblGestures::state)

        fun readBattery(t: Transport): Battery? = ask(t, JblBattery.get(), JblBattery::state)

        fun readAutoPlay(t: Transport): Boolean? = ask(t, JblAutoPlay.get(), JblAutoPlay::state)

        /**
         * ⚠ **The reply to the set is an ACK, not the state** — `aa 00 02 35 <on>` — so
         * this re-reads, exactly as [writeAutoOff] does and unlike [writeSpatial].
         */
        fun writeAutoPlay(t: Transport, on: Boolean): Boolean? {
            t.exchange(JblAutoPlay.set(on))
            return readAutoPlay(t)
        }

        fun readBalance(t: Transport): Balance? = ask(t, JblBalance.get(), JblBalance::state)

        /** The level goes back as it was read — [Balance] says why it is not offered. */
        fun writeBalance(t: Transport, v: Balance): Balance? =
            JblBalance.state(t.exchange(JblBalance.set(v)))

        /** ⚠ Read only, deliberately — see [JblPsap]. */
        fun readPsap(t: Transport): Boolean? = ask(t, JblPsap.get(), JblPsap::state)

        /**
         * Bind [g] to [want], and put [was] back if the device refuses.
         *
         * ⚠ **Two writes on the refusal path, deliberately.** A refused action comes back
         * as `<gesture> 00`, which IS the binding being cleared — so the only way to leave
         * the headphones as they were found is to write the old value again. There is no
         * "undo" frame and no error to catch.
         *
         * ⚠ **[was] is the caller's, not re-read here.** Re-reading first would cost a
         * round trip and still be a guess about the instant between the two frames; the
         * card already holds what the last read said, and that is what the owner is
         * looking at when they tap.
         *
         * ⚠ **The restore is believed from its own status frame**, never assumed. It goes
         * down the same path that just refused a write.
         */
        fun writeGesture(
            t: Transport,
            g: Gesture,
            want: GestureAction,
            was: GestureAction,
        ): GestureWrite {
            val got =
                JblGestures.changed(t.exchange(JblGestures.set(g, want)), g)
                    ?: return GestureWrite.Unanswered
            if (got == want) return GestureWrite.Took(got)
            // ⚠ Nothing to restore into an empty slot — and writing NONE over NONE would
            // spend a frame to reach the state it is already in.
            if (was == GestureAction.NONE) {
                return GestureWrite.RefusedAndRestored(want, GestureAction.NONE)
            }
            val back =
                JblGestures.changed(t.exchange(JblGestures.set(g, was)), g)
                    ?: return GestureWrite.RefusedAndLost(want, was)
            return if (back == was) {
                GestureWrite.RefusedAndRestored(want, back)
            } else {
                GestureWrite.RefusedAndLost(want, was)
            }
        }

        /** Voice Prompts' switch. ⚠ Read only — [JblVoicePrompts] says why. */
        fun readVoicePrompts(t: Transport): Boolean? =
            ask(t, JblVoicePrompts.get(), JblVoicePrompts::state)

        /** Customize ANC. ⚠ Read only — [JblAdvancedAnc] says why there is no writer. */
        fun readAdvancedAnc(t: Transport): AdvancedAnc? =
            ask(t, JblAdvancedAnc.get(), JblAdvancedAnc::state)

        /**
         * One key out of the `aa b1` feature bag — [JblFeature.LE_AUDIO] and
         * [JblFeature.AURACAST] are the two that are named.
         *
         * ⚠ **One key per exchange, because a get answers about the FIRST key only** —
         * measured 2026-08-17: asking `01` and `02` together returned `01` alone. The
         * vendor SDK's list form buys nothing on this firmware.
         */
        fun readFeature(t: Transport, key: Byte): Boolean? =
            ask(t, JblFeature.get(key)) { JblFeature.state(it, key) }

        /**
         * Switch the pair off. ⚠ **Ends the session**; see [JblPowerOff].
         *
         * Returns nothing, for the reason [SonyXm4.powerOff] returns nothing: the link
         * drops as the device acts, so there is no one left to answer a read-back.
         */
        fun powerOff(t: Transport) {
            t.exchange(JblPowerOff.off())
        }

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

            /** `AsmId.NORMAL` — Focus on Voice off. */
            const val NORMAL: Byte = 0x00

            /** `AsmId.VOICE` — Focus on Voice on. */
            const val VOICE: Byte = 0x01

            /**
             * How many extra reads to spend looking for a displaced answer.
             *
             * ⚠ Two, because two is what the measurement showed: one volunteered frame
             * ahead of the reply. A larger number would turn a device that has stopped
             * answering into a long stall, and this runs on the settings path where the
             * user is waiting.
             */
            const val EXTRA_READS = 2

            /** `ALERT_NTFY_PARAM` — the device asking, not answering. */
            const val ALERT: Byte = 0x99.toByte()
        }

        override fun read(t: Transport): AncMode? {
            val body = exchangeFramed(t, byteArrayOf(0x66, TYPE)) ?: return null
            // 67 02 <NcAsmEffect> 02 <NcDualSingleValue> 01 <AsmId> <ambient 0-20>
            //
            // ✅ Every byte is named now. The three that this file called "held 02 01 00
            // in all three states and are not identified" are NcAsmSettingType.
            // DUAL_SINGLE_OFF, AsmSettingType.LEVEL_ADJUSTMENT and AsmId — the last of
            // which is Focus on Voice and moves. See [setFocusOnVoice].
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
            exchangeFramed(t, byteArrayOf(0x68, TYPE, on, 0x02, nc, 0x01, NORMAL, ambient))
        }

        /**
         * **Focus on Voice** — `AsmId`, byte 6 of the frame [write] already sends.
         *
         * ✅ **Driven on the XM4 2026-08-23 18:05, worn**, and confirmed by independent
         * reads: `67 02 01 02 00 01 00 14` then `67 02 01 02 00 01 01 14`.
         *
         * ⚠ **It only takes in ambient mode.** Sending `AsmId.NORMAL` while the device is
         * in ANC is accepted and ignored — the restore after the test did exactly that and
         * left the setting on, which a read-back caught and a trusted write would not have.
         * Getting back required going through ambient. Hence the mode check below: this
         * refuses rather than sending a frame that would be silently dropped.
         */
        fun readFocusOnVoice(t: Transport): Boolean? = focus(current(t) ?: return null)

        /**
         * Voice guidance — the spoken prompts. ⚠ **Table 2**; see [SonyVoiceGuidance].
         */
        fun readVoiceGuidance(t: Transport): Boolean? =
            exchangeFramed2(t, SonyVoiceGuidance.get(), SonyVoiceGuidance.RET)
                ?.let(SonyVoiceGuidance::state)

        /**
         * ⚠ Its notify echoes the value, so this one is confirmable from its own reply —
         * but [setVoiceGuidance] re-reads anyway, for the reason [setMultipoint] does.
         *
         * ⚠ **Turning it ON can make the headphones speak.** That is the device doing what
         * the setting is for, not a side effect to be suppressed; it is noted because it
         * is the one write here that is audible to whoever is wearing them.
         */
        fun writeVoiceGuidance(t: Transport, on: Boolean): Boolean? =
            exchangeFramed2(t, SonyVoiceGuidance.set(on), SonyVoiceGuidance.NOTIFY)
                ?.let(SonyVoiceGuidance::state)

        /** Write it, then establish from a real read what the device holds. */
        fun setVoiceGuidance(t: Transport, on: Boolean): Confirmation<Boolean> {
            writeVoiceGuidance(t, on)
            val after = readVoiceGuidance(t) ?: return Confirmation.Unverifiable
            return if (after == on) Confirmation.Confirmed else Confirmation.Contradicted(after)
        }

        /**
         * Focus on Voice **and** whether it can be set, from ONE read.
         *
         * ⚠ Both facts come out of the same `67 02 …` frame, so asking twice is a
         * wasted round trip — and on this device a round trip is not free: every extra
         * exchange is another chance for a volunteered notification to put the session
         * one behind (#1107). The screen needs both, so the driver returns both.
         */
        fun readFocus(t: Transport): Focus {
            val body = current(t) ?: return Focus(null, settable = false)
            // Ambient means NcAsmEffect on with NcDualSingleValue off — the same two
            // bytes [read] uses, and the same condition [setFocusOnVoice] enforces.
            val ambient = body[2] != 0x00.toByte() && body[4] == 0x00.toByte()
            return Focus(focus(body), settable = ambient)
        }

        fun setFocusOnVoice(t: Transport, on: Boolean): Confirmation<Boolean> {
            val before = current(t) ?: return Confirmation.Unverifiable
            // ⚠ byte 4 is NcDualSingleValue; anything but OFF means noise cancelling is
            // engaged and this setting is not the one in play.
            if (before[4] != 0x00.toByte() || before[2] == 0x00.toByte()) {
                return Confirmation.Contradicted(focus(before) ?: return Confirmation.Unverifiable)
            }
            val want = before.copyOf()
            want[0] = 0x68
            want[6] = if (on) VOICE else NORMAL
            exchangeFramed(t, want)
            val after = current(t)?.let(::focus)
            settle(t)
            after ?: return Confirmation.Unverifiable
            return if (after == on) Confirmation.Confirmed else Confirmation.Contradicted(after)
        }

        /** The whole `67 02 …` frame, or null if the device did not answer with one. */
        private fun current(t: Transport): ByteArray? {
            val body = exchangeFramed(t, byteArrayOf(0x66, TYPE)) ?: return null
            return if (body.size >= 8 && body[0] == 0x67.toByte()) body else null
        }

        private fun focus(body: ByteArray): Boolean? =
            when (body[6]) {
                NORMAL -> false
                VOICE -> true
                else -> null
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
         * Move the band levels of the selected preset, and say whether they moved.
         *
         * ⚠ **A levels write draws no state back — only an ack**, unlike [writeEq],
         * whose notify carries the result. So the read is not an optional second
         * opinion here, it is the only evidence there is, and Sony's own app does
         * exactly the same thing: `58 01 ff …` then `56 01`, after every drag.
         *
         * ⚠ Compares **levels, not preset**. The preset byte sent is `ff`, and the
         * device goes on reporting the real slot — so a preset comparison would
         * contradict every correct write.
         *
         * ✅ Driven against the XM4 on 2026-08-24, down and back up.
         */
        fun setEqLevels(t: Transport, levels: List<Int>): Confirmation<EqSetting> {
            exchangeFramed(t, SonyEq.setLevels(levels))
            val after = readEq(t) ?: return Confirmation.Unverifiable
            return if (after.levels == levels) {
                Confirmation.Confirmed
            } else {
                Confirmation.Contradicted(after)
            }
        }

        /**
         * ⚠ The band table is **not** re-read per preset because it changes — it
         * was byte-identical in all eleven captured replies. Sony Headphones Connect
         * asks after every change; this asks when someone wants the axis.
         */
        override fun bands(t: Transport): List<Int> =
            exchangeFramed(t, SonyEq.getBands())?.let(SonyEq::bands) ?: emptyList()

        /**
         * Switch the pair off. ⚠ **Ends the session**; see [SonyPowerOff].
         *
         * Returns nothing because there is nothing to return: the link drops as the
         * device acts. A caller that wants to know it worked should watch the radio,
         * which is what the screen already does.
         */
        fun powerOff(t: Transport) {
            exchangeFramed(t, SonyPowerOff.off())
        }

        /** The codec the link settled on. ⚠ Read only — see [SonyCodec]. */
        fun readCodec(t: Transport): String? =
            exchangeFramed(t, SonyCodec.get(), SonyCodec.RET)?.let(SonyCodec::state)

        fun readAutoOff(t: Transport): AutoOff? =
            exchangeFramed(t, SonyAutoOff.get())?.let(SonyAutoOff::state)

        /**
         * ⚠ Its notify echoes the value set, so this one really is confirmable.
         * ✅ Driven on hardware 2026-08-16, both directions, and restored.
         */
        fun writeAutoOff(t: Transport, mode: AutoOff): AutoOff? =
            exchangeFramed(t, SonyAutoOff.set(mode))?.let(SonyAutoOff::state)

        /**
         * Speak-to-Chat's three detail settings, which travel as one frame.
         *
         * ⚠ **[expect] names both `fb` and `fd`** because a write's own notify is a
         * legitimate answer to a read that raced it. Leaving it out would take the last
         * DATA frame in the window whatever it said — the defect that made a working
         * DSEE write report as unconfirmable.
         */
        fun readChatDetail(t: Transport): ChatDetail? =
            exchangeFramed(t, SonyChatDetail.get(), SonyChatDetail.RET, SonyChatDetail.NOTIFY)
                ?.let(SonyChatDetail::state)

        /** ✅ Driven on hardware 2026-08-24, all three fields, and restored. */
        fun writeChatDetail(t: Transport, detail: ChatDetail): ChatDetail? =
            exchangeFramed(
                t,
                SonyChatDetail.set(detail),
                SonyChatDetail.RET,
                SonyChatDetail.NOTIFY,
            )?.let(SonyChatDetail::state)

        /** ✅ Driven on hardware 2026-08-16, both directions, and restored. */
        fun readSoundQuality(t: Transport): SoundQuality? =
            exchangeFramed(t, SonySoundQuality.get())?.let(SonySoundQuality::state)

        fun writeSoundQuality(t: Transport, mode: SoundQuality): SoundQuality? =
            exchangeFramed(t, SonySoundQuality.set(mode))?.let(SonySoundQuality::state)

        fun readButton(t: Transport): SonyButton.Action? =
            exchangeFramed(t, SonyButton.get())?.let(SonyButton::state)

        /** What this pair will let its key be set to, from `f0 06`. */
        fun buttonPresets(t: Transport): List<SonyButton.Action> =
            exchangeFramed(t, SonyButton.capabilities(), SonyButton.RET_CAPABILITY)
                ?.let(SonyButton::presets)
                .orEmpty()

        /**
         * Ask to change the key, and report **what the device said back**.
         *
         * ✅ The write commits only once the device's alert is answered, and the device
         * raises no alert unless [SonyButton.subscribeAlerts] was sent first — that was
         * #965, open eight days. So this subscribes, writes, and hands the question back
         * rather than deciding it: see [ButtonWrite.Asks].
         *
         * ⚠ **The alert fires only on a REAL change.** Writing the value already set
         * draws nothing at all, which is [ButtonWrite.Unchanged] and not a failure.
         */
        fun beginButtonWrite(t: Transport, action: SonyButton.Action): ButtonWrite {
            val ask =
                SonyFrame.encode(
                    SonyFrame.TYPE_DATA_MDR,
                    nextSeq(),
                    SonyButton.subscribeAlerts(),
                )
            t.send(ask)
            val reply =
                exchangeFramed(t, SonyButton.set(action), ALERT, SonyButton.NOTIFY)
                    ?: return ButtonWrite.Unchanged
            return if (SonyButton.asksAboutKeyAssign(reply)) {
                ButtonWrite.Asks
            } else {
                ButtonWrite.Unchanged
            }
        }

        /**
         * Answer the device's question.
         *
         * ⚠ **A yes KILLS THE LINK, and that is the success path.** The XM4 commits and
         * reconnects at once, so this write throws or reports a broken pipe while its
         * bytes land. The exception is swallowed for exactly that reason — measured
         * 2026-08-24, both answers. **The caller must reopen and re-read to learn the
         * outcome; nothing here can tell it.**
         *
         * ⚠ A no is orderly: no disconnect, an `f9` echo, and the value unchanged.
         */
        fun answerButtonAlert(t: Transport, yes: Boolean) {
            runCatching {
                val frame =
                    SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, nextSeq(), SonyButton.answer(yes))
                t.send(frame)
            }
        }

        /**
         * The three on/off settings whose **reads** were confirmed on 2026-08-23
         * against both the XM4 and Sound Connect's own screens — see [SonySwitch].
         *
         * ⚠ **The writes had not been driven when this was written.** Each returns the
         * device's echo so [setSwitch] can compare it against a real read, exactly as
         * the multipoint path does, because a Sony reply is not a result.
         */
        fun readSwitch(t: Transport, switch: SonySwitch): Boolean? =
            exchangeFramed(t, switch.get(), *switch.answers)?.let(switch::state)

        fun writeSwitch(t: Transport, switch: SonySwitch, on: Boolean): Boolean? =
            exchangeFramed(t, switch.set(on), *switch.answers)?.let(switch::state)

        /**
         * Write it, read it back, and say which happened.
         *
         * ⚠ **The read is not skipped when the echo already agrees.** [SonyButton] is
         * the reason: that write is acked, echoes nothing, and does not take — a driver
         * that reported success from a reply would call it confirmed. So the echo is
         * only ever a fallback for "nothing came back", never the evidence.
         */
        fun setSwitch(t: Transport, switch: SonySwitch, on: Boolean): Confirmation<Boolean> {
            writeSwitch(t, switch, on)
            val after = readSwitch(t, switch)
            settle(t)
            after ?: return Confirmation.Unverifiable
            return if (after == on) Confirmation.Confirmed else Confirmation.Contradicted(after)
        }

        /**
         * Consume and acknowledge whatever the write left in flight.
         *
         * ⚠ **A write on this device emits more than its own answer.** Changing DSEE
         * draws an `e9` NTFY_PARAM *and* a `17` COMMON_NTFY_UPSCALING_EFFECT, and the
         * second commonly arrives after the confirming read has finished. It then sits
         * in the socket waiting for the next request — and the next request, on the
         * settings path, is a nine-read refresh whose FIRST exchange collects it.
         *
         * ⚠ **This did NOT fix the symptom it was written for, and is kept on its own
         * merits.** The symptom: one tap leaves the XM4 on, the switch drawn on, and the
         * row's own label reading "off". It was reproduced again *with* this in place,
         * in a clean run with nothing else touching the channel — so a leftover frame on
         * the socket is **not** the explanation, or not the whole one. See #1107.
         *
         * What it is still worth: acknowledging a DATA frame is required whether or not
         * anybody wanted its contents, and leaving one unacked is the defect that put
         * sessions permanently one behind. Retiring them politely is correct in itself.
         *
         * ⚠ **This belongs to the driver and not to [Transport].** A transport that
         * dropped pending bytes would discard Sony DATA frames without acknowledging
         * them, which is the very thing that put sessions permanently one behind. Only
         * something that can parse a frame can retire one politely.
         *
         * Bounded, and silent about what it finds: these frames are real state, but
         * nothing here is asking a question, so there is nobody to hand an answer to.
         */
        private fun settle(t: Transport) {
            repeat(EXTRA_READS) {
                val more = t.receive()
                if (more.isEmpty()) return
                SonyFrame
                    .decodeAll(more)
                    .filter { f -> f.type == SonyFrame.TYPE_DATA_MDR }
                    .forEach { f -> ackFor(f)?.let(t::send) }
            }
        }

        /** ✅ Confirmed against Sound Connect's own card on 2026-08-23 — see [SonyBattery]. */
        fun readBattery(t: Transport): Battery? =
            exchangeFramed(t, SonyBattery.get(), SonyBattery.RET, SonyBattery.NOTIFY)
                ?.let(SonyBattery::state)

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

        /**
         * Send one framed payload, ack the device's answer, return its payload.
         *
         * ⚠ **[expect] is which command bytes would actually answer this request**, and
         * without it the last DATA frame in the window is taken whatever it says. That
         * is not a hypothetical: driving `e8 02 00 01` on 2026-08-23 made the XM4 emit
         * `17` COMMON_NTFY_UPSCALING_EFFECT *as well as* its `e9` NTFY_PARAM, and the
         * `17` landed in the next read's window. The read returned it, [SonySwitch.state]
         * rightly refused to decode it, and the write — **which had worked** — was
         * reported as unverifiable.
         *
         * ⚠ **This narrows the wrong answer to no answer; it does not resynchronise.**
         * When the extra notification arrives *before* the real reply, every subsequent
         * exchange in that session is one window behind — measured on Speak-to-Chat,
         * where six consecutive exchanges each returned the previous one's answer. The
         * cause is stop-and-wait acking, see below; this only limits the damage.
         *
         * ⚠ Callers that pass nothing keep the old behaviour. The ANC, EQ and multipoint
         * paths were driven and confirmed against hardware with it, and changing what
         * they select is not free just because it looks safer.
         */
        private fun exchangeFramed(
            t: Transport,
            payload: ByteArray,
            vararg expect: Byte,
        ): ByteArray? = exchangeOn(t, SonyFrame.TYPE_DATA_MDR, payload, expect.toList())

        /**
         * The same exchange, on **table 2**.
         *
         * ⚠ **The type byte is the only thing that says which command table a payload
         * belongs to, and the ranges overlap.** `48` is `VPT_SET_PARAM` on table 1 and
         * `VOICE_GUIDANCE_SET_PARAM` on table 2, with nothing in the payload to tell
         * them apart. So this is a separate entry point rather than a flag on the one
         * above: a caller has to say which table it means.
         */
        private fun exchangeFramed2(
            t: Transport,
            payload: ByteArray,
            vararg expect: Byte,
        ): ByteArray? = exchangeOn(t, SonyFrame.TYPE_DATA_MDR_NO2, payload, expect.toList())

        private fun exchangeOn(
            t: Transport,
            type: Byte,
            payload: ByteArray,
            expect: List<Byte>,
        ): ByteArray? {
            // ⚠ **Every DATA frame is acked, and acked WHILE THE WINDOW IS OPEN.** The
            // XM4 is stop-and-wait, so acking after the window returns is what made a
            // single volunteered notification displace every later reply — see
            // [Transport.exchange]. This is the fix for #1107; [EXTRA_READS] below is
            // now the fallback rather than the cure.
            val frame = SonyFrame.encode(type, nextSeq(), payload)
            var got = t.exchange(frame, ::acks)
            var rounds = 0
            while (true) {
                val frames = SonyFrame.decodeAll(got).filter { it.type == type }
                val answer =
                    if (expect.isEmpty()) {
                        frames.lastOrNull()
                    } else {
                        frames.lastOrNull { it.payload.firstOrNull() in expect }
                    }
                if (answer != null) return answer.payload
                // ⚠ Bounded, and small. Nothing here waits for a device to become
                // agreeable — this covers "the answer was behind one volunteered
                // frame", which is what was measured. A caller that gets null still
                // reports honestly rather than retrying forever.
                if (expect.isEmpty() || rounds++ >= EXTRA_READS) return null
                got = t.receive()
                if (got.isEmpty()) return null
            }
        }

        /**
         * One ack per DATA frame in [got], in order.
         *
         * ⚠ **Order and completeness matter, not just the count.** A transport sends
         * only the tail it has not sent yet, so dropping a frame here would shift every
         * later ack onto the wrong sequence number.
         */
        private fun acks(got: ByteArray): List<ByteArray> =
            SonyFrame.decodeAll(got).mapNotNull(::ackFor)

        /**
         * The ack a received DATA frame expects: type 01, sequence inverted.
         *
         * ⚠ **Both tables, deliberately.** The device is stop-and-wait and it withholds
         * its next frame until the current one is acknowledged — so acking only `0c`
         * would leave any table-2 notification unacked and wedge the session from that
         * point on. What table a frame belongs to is the caller's question, not the
         * transport's.
         */
        private fun ackFor(frame: SonyFrame.Frame): ByteArray? =
            if (frame.type != SonyFrame.TYPE_DATA_MDR &&
                frame.type != SonyFrame.TYPE_DATA_MDR_NO2
            ) {
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
