package org.xinutec.volume.protocol

/**
 * The JLab JBuds Sport ANC 4's own protocol, on plain RFCOMM SPP.
 *
 * Everything here was captured from `com.jlab.app` on 2026-09-01 and is written up in
 * `docs/protocols.md`, "The JLab's command map, decoded". ⛔ **Never sweep this
 * protocol.** The app is a QCY rebrand of a Realtek SDK whose id space holds a factory
 * reset, and nothing measured says which id that is — so only ids the vendor app was
 * seen to send appear here, and nothing is extrapolated into the gaps.
 *
 * ```
 * request  c0 ff 00 <cmd> 00 00 01 00 <sum>          sum = Σ preceding, mod 256
 * reply    00 ff 01 <cmd> <b1> 00 <payload…> <tail>  ⚠ no rule found for <tail>
 * write    c0 ff 00 <cmd+2> …                        each writer is its reader + 2
 * ```
 *
 * ⚠⚠ **A REPLY CANNOT BE DELIMITED, so nothing here tries.** The byte after the command
 * is not a length — `31` carries the same nine-byte body under `b1 = 01` and under
 * `b1 = 03`, so one of the two would have to be wrong; `4d` declares `1b` (27) and
 * carries 36; `71` declares `1e` (30) and carries 40. Nor does the checksum close: seven
 * reply commands come out 2 less than the request rule's sum and five close at no offset
 * over any prefix. **Every decoder below reads a fixed offset from a payload that
 * arrives whole**, which each of these does — a weaker guarantee than a parse, stated
 * rather than dressed up.
 *
 * ⚠ **The REQUEST rule is real and is needed**: the vendor app packs up to three
 * requests into one payload, and walking forward until Σ matches splits every such
 * packet in four captures with nothing left over.
 */
object JLabFrame {
    /**
     * Append the trailing sum-mod-256.
     *
     * ⚠ The device accepted a frame with it omitted, so it does not appear to be
     * verified — but a frame matching the vendor app's byte for byte is the one with
     * evidence behind it.
     */
    fun checksummed(body: ByteArray): OutFrame =
        OutFrame(body + body.fold(0) { acc, b -> acc + (b.toInt() and 0xff) }.toByte())

    /** The read every one of the app's getters uses, with only [cmd] varying. */
    fun read(cmd: Byte): OutFrame =
        checksummed(
            byteArrayOf(0xc0.toByte(), 0xff.toByte(), 0x00, cmd, 0x00, 0x00, 0x01, 0x00),
        )

    /**
     * The answer to [cmd] **wherever it sits in [r]**, or null if it is not there.
     *
     * ⚠ **Two commands answer with their OWN id** — `76` and `7a` — where every other
     * read answers `cmd + 1`. That is measured and is left as measured rather than
     * tidied into the pattern, so callers name the reply id they expect.
     *
     * ⚠⚠ **It SCANS rather than checking offset 0, and that is not tidiness.** Measured
     * 2026-09-01 against the real earbuds: the first read after an idle link took 420 ms
     * against a reply window of about 400, so its answer landed in the *next* read's
     * window and every read after it ran one behind — `44` returned nothing and `76` was
     * handed `45`. On the card that read as "could not read it" plus a silently absent
     * Spatial Audio row. This is the same "one behind" failure [Transport.receive]
     * documents for the XM4, on a second device.
     *
     * ⚠ Shape is not proof: a payload byte could in principle spell `00 ff 01 <cmd>`.
     * Requiring the header AND the command id makes that unlikely rather than
     * impossible, which is the same bargain `scripts/btsnoop.py` strikes.
     */
    fun replyTo(r: ByteArray, cmd: Byte, atLeast: Int): ByteArray? {
        for (i in 0..r.size - 4) {
            if (r[i] == 0x00.toByte() &&
                r[i + 1] == 0xff.toByte() &&
                r[i + 2] == 0x01.toByte() &&
                r[i + 3] == cmd &&
                r.size - i >= atLeast
            ) {
                return r.copyOfRange(i, r.size)
            }
        }
        return null
    }
}

/**
 * How much charge each earbud reports — `30` asks, `31` answers.
 *
 * ```
 * → c0 ff 00 30 00 00 01 00 f0
 * ← 00 ff 01 31 <b1> 00 <a> <b> 04 00 02 00 00 <sum>
 * ```
 *
 * ✅ **The two level bytes are measured, not inferred from an SDK.** Across one capture
 * they went `5a 5a` → `5a 50` → `50 50`, i.e. 90/90 → 90/80 → 80/80, which is a pair of
 * cells discharging at different rates and pins both the offsets and the plain-percent
 * scale.
 *
 * ✅ **WHICH BUD IS WHICH IS SETTLED — 2026-09-01, and by measurement.** It could not be
 * done when the decode was found, because both cells then read `50`. Once they had
 * drifted to 70/60 the vendor app was opened beside a read of this frame and its two
 * battery icons measured off the render: the `L` icon's green fill is **34 px** and the
 * `R` icon's is **30 px**, while this frame read byte 6 = 70 and byte 7 = 60. Same
 * direction, so byte 6 is [left]. ⚠ That fixes the ORDER only — the icon fill is not
 * linear in the percentage (34/30 px for 70/60) and nothing here calibrates it.
 *
 * ⚠ This is the question [JblBattery]'s master/slave note still cannot answer, and the
 * difference is worth keeping in view: that device's two bytes have been equal in every
 * frame ever captured, so no render can separate them. These drifted apart on their own.
 *
 * ⚠ **Byte 8 is not decoded and is not the case.** It read `04` in every frame, through
 * three different level pairs, so nothing here has varied it. `docs/protocols.md` records
 * a *different* channel — Fast Pair `03 03` — carrying three values including a case; do
 * not read that decode onto this frame.
 *
 * ⚠ **Charging is unknown on this device**, so [Battery.charging] is null rather than
 * false: no captured frame changes with the buds on the cable.
 */
object JLabBattery {
    const val ASK: Byte = 0x30
    const val REPLY: Byte = 0x31

    fun get(): OutFrame = JLabFrame.read(ASK)

    fun state(r: ByteArray): BudBattery? {
        val f = JLabFrame.replyTo(r, REPLY, atLeast = 8) ?: return null
        return BudBattery(
            left = Battery(percent = f[6].toInt() and 0xff, charging = null),
            right = Battery(percent = f[7].toInt() and 0xff, charging = null),
        )
    }
}

/**
 * Two cells, because this device genuinely has two.
 *
 * ⚠ **Not [Battery] with one number.** `docs/protocols.md` already warns that battery
 * frame *length* carries topology — an over-ear returns one value, these earbuds return
 * two that differ — so folding them into a single percentage discards half the reading.
 */
data class BudBattery(
    val left: Battery,
    val right: Battery,
)

/**
 * Spatial Audio's switch — `76` reads it, `74` writes it.
 *
 * ```
 * → c0 ff 00 76 00 00 01 00 36     ← 00 ff 01 76 01 00 <on> 00 00 <sum>
 * → c0 ff 00 74 01 00 <on> 01 00 <sum>
 * ```
 *
 * ✅ **`76` was named by ablation, not by pattern.** It answers `01` while the feature
 * is on, which is only *consistent* with being its state — the trap `docs/sony-settings.md`
 * records for the codec row. So: switch it off, force-stop the vendor app, relaunch it
 * cold, and diff the whole enumeration against one taken with it on. Exactly one frame
 * moved; the other eleven came back byte for byte. The app also drew the switch off on
 * that cold start, so it reads the state from the device rather than remembering it.
 *
 * ⚠ **`76` answers with its own id, not `77`.** See [JLabFrame.replyTo].
 *
 * ⚠ **The switch and the mode are SEPARATE frames here**, unlike the JBL's `aa 9d`
 * which carries both and therefore cannot be written apart. Setting both on a JLab is
 * two writes, and either can land without the other.
 */
object JLabSpatial {
    const val ASK: Byte = 0x76
    const val SET: Byte = 0x74

    fun get(): OutFrame = JLabFrame.read(ASK)

    fun state(r: ByteArray): Boolean? {
        val f = JLabFrame.replyTo(r, ASK, atLeast = 7) ?: return null
        return when (f[6]) {
            0x00.toByte() -> false
            0x01.toByte() -> true
            else -> null
        }
    }

    fun set(on: Boolean): OutFrame =
        JLabFrame.checksummed(
            byteArrayOf(
                0xc0.toByte(),
                0xff.toByte(),
                0x00,
                SET,
                0x01,
                0x00,
                if (on) 0x01 else 0x00,
                0x01,
                0x00,
            ),
        )
}

/**
 * What Spatial Audio renders for — `50` reads, `52` writes.
 *
 * ```
 * → c0 ff 00 50 00 00 01 00 10     ← 00 ff 01 51 02 00 <mode> 00 00 00 <sum>
 * → c0 ff 00 52 01 00 <mode> 01 00 <sum>
 * ```
 *
 * `00` Music · `01` Movie, both driven from the app's own tiles and read back.
 *
 * ⚠⚠ **These are NOT [SpatialMode]'s wire values.** The JBL numbers the same idea
 * `01` Music · `02` Movie · `03` Game; this device starts at zero and has no Game. A
 * table shared between the two would be wrong on every value, so the mapping is spelled
 * out here and [SpatialMode] is used only as the vocabulary.
 *
 * ⚠ **GAME is unreachable on this device** — its app offers two tiles. [of] returns null
 * for anything else rather than inventing a third.
 */
object JLabSpatialMode {
    const val ASK: Byte = 0x50
    const val REPLY: Byte = 0x51
    const val SET: Byte = 0x52

    fun get(): OutFrame = JLabFrame.read(ASK)

    fun of(wire: Byte): SpatialMode? =
        when (wire) {
            0x00.toByte() -> SpatialMode.MUSIC
            0x01.toByte() -> SpatialMode.MOVIE
            else -> null
        }

    fun state(r: ByteArray): SpatialMode? {
        val f = JLabFrame.replyTo(r, REPLY, atLeast = 7) ?: return null
        return of(f[6])
    }

    fun set(mode: SpatialMode): OutFrame? {
        val m: Byte =
            when (mode) {
                SpatialMode.MUSIC -> 0x00

                SpatialMode.MOVIE -> 0x01

                // ⚠ Refused rather than coerced: sending Music for Game would report
                // success for a mode the device was never put into.
                SpatialMode.GAME -> return null
            }
        return JLabFrame.checksummed(
            byteArrayOf(
                0xc0.toByte(),
                0xff.toByte(),
                0x00,
                SET,
                0x01,
                0x00,
                m,
                0x01,
                0x00,
            ),
        )
    }
}

/**
 * The equaliser — `48` reads the live curve, `70` reads all four presets.
 *
 * ```
 * → c0 ff 00 48 00 00 01 00 08    ← 00 ff 01 49 0b 00 <preset> <10 levels> 06 00 <sum>
 * → c0 ff 00 70 00 00 01 00 30    ← 00 ff 01 71 1e 00 <4 × 10 levels> 00 00 <sum>
 * ```
 *
 * ✅ **The preset index is corroborated by the app's own screen**, not assumed: `49`
 * answered `03` while the app had **Custom** — the fourth of EQ1/EQ2/EQ3/Custom — ticked,
 * and preset 3's ten bytes inside `71` are byte-identical to `49`'s curve.
 *
 * ⚠⚠ **WRITABLE AT PIPPIJN'S EXPLICIT REQUEST — 2026-09-01.** It shipped read-only first,
 * because selecting any of EQ1/EQ2/EQ3 — all flat `78` — RAISES the two bands the live
 * Custom curve cuts to `5a`. That is still true and the card says so; what changed is that
 * he asked for the control.
 *
 * ```
 * → c0 ff 00 4a 0b 00 <preset> <10 levels> 01 00 <sum>
 * ← 00 ff 01 4b 0b 00 …    ⚠ does NOT echo what was written
 * ```
 *
 * ✅ **The frame was CAPTURED, not composed.** The SDK named it `P(B,[B)` — preset plus a
 * byte array — and a capture then confirmed the layout exactly, taken in the safe
 * direction by dragging one band DOWN in the vendor app rather than by tapping a preset:
 * `4a 0b 00 03 78 78 5a 78 78 78 5a 78 78 50 01 00`, with 16k moved from `78` to `50`.
 * ⚠ `0b` is 11 — preset plus ten levels — and it agrees with the read's own length prefix.
 *
 * ⚠ **`4b` answered preset `01` and a flat curve after a write of preset `03`**, so it
 * reports neither the request nor the state. [Drivers.JLabQcy.writeEq] re-reads `48`.
 *
 * ✅ **A SECOND PRESET INDEX IS NOW MEASURED — 2026-09-02.** `eq 1` was selected on our
 * own card and `49` re-read preset `0`, so the preset byte is observed at two values and
 * writing an index is no longer an extension of a single capture. The original selection
 * was restored in the same step and the device ended byte-identical to where it started.
 *
 * ⚠⚠ **BUT THE TEN LEVEL BYTES DID NOT LAND, and that is the finding.** The card sends a
 * slot's OWN stored curve, so `eq 1` carried slot 0's flat `78`s — and `49` answered
 * preset `0` still holding the CUT curve, `78 78 5a 78 78 78 5a 78 78 78`.
 * [DeviceController] reported `Contradicted`, naming the disagreement instead of
 * reporting a write it could not see.
 *
 * ⚠ **Two readings survive and nothing here picks one**: the device ignores the levels on
 * a preset write, or `49` reports the Custom slot whatever index is selected. They differ
 * in whether the two cut bands were audibly RAISED for the seconds the selection stood.
 * The corroboration above cannot separate them — it was taken with Custom selected, where
 * `49` and slot 3 of `71` coincide by construction.
 *
 * ⚠ **Restoring by tapping `custom` CONFIRMS for free and proves nothing.** It sends slot
 * 3 its own contents, so the re-read agrees whether or not the levels were applied. The
 * restore is sound; the `Confirmed` beside it is not evidence.
 *
 * ⚠ **The levels are RAW DEVICE UNITS and this deliberately does not convert them.** No
 * capture establishes what `78` and `5a` mean in dB, nor what the endpoints are. Calling
 * them gains would put a number on a card that nothing measured supports — the same
 * invention [SonyEq.RANGE] is careful to attribute to the vendor's own axis.
 */
object JLabEq {
    const val ASK: Byte = 0x48
    const val REPLY: Byte = 0x49
    const val ASK_PRESETS: Byte = 0x70
    const val REPLY_PRESETS: Byte = 0x71

    /** ⚠ Read off the vendor app's own axis labels, so it names the app's bands. */
    val HZ = listOf(32, 64, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

    const val BANDS = 10
    const val PRESETS = 4

    fun get(): OutFrame = JLabFrame.read(ASK)

    fun presets(): OutFrame = JLabFrame.read(ASK_PRESETS)

    fun state(r: ByteArray): JLabCurve? {
        val f = JLabFrame.replyTo(r, REPLY, atLeast = 7 + BANDS) ?: return null
        return JLabCurve(
            preset = f[6].toInt() and 0xff,
            levels = (0 until BANDS).map { f[7 + it].toInt() and 0xff },
        )
    }

    const val SET: Byte = 0x4a

    /**
     * ⚠ **This can RAISE band levels**, which on this device is the whole hazard: the
     * stored presets are flat and the live Custom curve is cut in two places. Refuses a
     * curve that is not exactly [BANDS] long rather than padding one — a short array
     * would write whatever followed it in memory into somebody's treble.
     */
    fun set(preset: Int, levels: List<Int>): OutFrame? {
        if (levels.size != BANDS) return null
        if (levels.any { it !in 0..0xff }) return null
        return JLabFrame.checksummed(
            byteArrayOf(0xc0.toByte(), 0xff.toByte(), 0x00, SET, 0x0b, 0x00, preset.toByte()) +
                levels.map { it.toByte() }.toByteArray() +
                byteArrayOf(0x01, 0x00),
        )
    }

    /** All four stored curves, in the order the device lists them. */
    fun allPresets(r: ByteArray): List<List<Int>>? {
        val f = JLabFrame.replyTo(r, REPLY_PRESETS, atLeast = 6 + PRESETS * BANDS) ?: return null
        return (0 until PRESETS).map { p ->
            (0 until BANDS).map { b -> f[6 + p * BANDS + b].toInt() and 0xff }
        }
    }
}

/**
 * One stored curve, and which slot the device keeps it in.
 *
 * ⚠ **Not [EqCurve], whose `bands` carry a dB gain.** Nothing establishes a unit for
 * these, so they stay integers — see [JLabEq].
 */
data class JLabCurve(
    val preset: Int,
    val levels: List<Int>,
)

/**
 * Safe Hearing — the exposure ceiling. `66` reads it.
 *
 * ```
 * → c0 ff 00 66 00 00 01 00 26     ← 00 ff 01 67 01 00 <level> 00 00 <sum>
 * ```
 *
 * ✅ **Measured at two values on 2026-09-01**, which is why this is a decode and not the
 * reader+2 rule guessing: `67` read `00` in two cold enumerations taken while the app's
 * slider sat on `Default`, then `02` in a third taken with it on `85 dB Limit`. One
 * variable, one byte.
 *
 * ⚠⚠ **A HIGHER VALUE IS A LOWER CEILING, and reading it the other way round is the
 * mistake this control punishes.** `02` is the most protective setting and `00` is the
 * least. Anything that sorts or compares these numbers as "loudness" has them backwards.
 *
 * ⚠⚠ **THE WRITER EXISTS BECAUSE PIPPIJN ASKED FOR IT — 2026-09-01, explicitly.** It was
 * shipped read-only first, with a test asserting the absence, precisely so that adding one
 * had to be a decision rather than a refactor. That decision was taken; this comment is
 * the record of it, and nothing here should be read as the default for a control of this
 * kind. The JBL's Max Volume Limiter and PSAP remain read-only.
 *
 * ```
 * → c0 ff 00 68 01 00 <level> 01 00 <sum>     ← 00 ff 01 69 01 00 01 00 00 <sum>
 * ```
 *
 * ⚠ **`69` answers `01` whichever level was written**, so it is a bare acknowledgement
 * carrying no state. A caller that believes it reports success for a write the device
 * declined — [Drivers.JLabQcy.writeSafeHearing] re-reads instead.
 *
 * ⚠ The three values were identified **downward from `Default` and back**, so the ceiling
 * was never above where it started at any point in the identification.
 */
object JLabSafeHearing {
    const val ASK: Byte = 0x66
    const val REPLY: Byte = 0x67
    const val SET: Byte = 0x68

    /** ⚠ Ordered as the device numbers them, which is most-permissive first. */
    enum class Level(
        val wire: Byte,
    ) {
        DEFAULT(0x00),
        DB_95(0x01),
        DB_85(0x02),
        ;

        companion object {
            fun of(wire: Byte): Level? = entries.firstOrNull { it.wire == wire }
        }
    }

    fun get(): OutFrame = JLabFrame.read(ASK)

    fun state(r: ByteArray): Level? {
        val f = JLabFrame.replyTo(r, REPLY, atLeast = 7) ?: return null
        return Level.of(f[6])
    }

    /**
     * ⚠ **This can RAISE an exposure ceiling** — `DEFAULT` is the least protective of the
     * three. It is a plain setter because the device offers a plain setting, and the
     * judgement about when to call it belongs to the caller and to whoever is wearing
     * them, not to a clamp invented here that would silently refuse what the vendor app
     * does freely.
     */
    fun set(level: Level): OutFrame =
        JLabFrame.checksummed(
            byteArrayOf(
                0xc0.toByte(),
                0xff.toByte(),
                0x00,
                SET,
                0x01,
                0x00,
                level.wire,
                0x01,
                0x00,
            ),
        )
}

/**
 * What each tap does — `4c` reads the map.
 *
 * ```
 * → c0 ff 00 4c 00 00 01 00 0c
 * ← 00 ff 01 4d 1b 00 <12 × (side, gesture, action)> 05 00 <sum>
 * ```
 *
 * ✅ **Corroborated row for row against the app's own screen**, which is why this is a
 * decode rather than a shape: the twelve triples are exactly the two sides × six gestures
 * the Touch Controls screen draws, in its order, and the actions it names against them.
 *
 * ⚠⚠ **WHICH SIDE IS WHICH IS NOT ESTABLISHED.** Both sides carried identical maps in
 * every capture, so nothing distinguishes `01` from `02`. [Side] says so in its own name
 * rather than claiming left and right. **The test:** change one side in the vendor app and
 * see which byte moves — it needs a writer this does not have.
 *
 * ⚠ **Six actions of an unknown set.** Only the values the device was already using have
 * been seen; the gaps are not evidence that nothing lives there. [Action.of] returns null
 * rather than guessing.
 *
 * ⚠ **READ ONLY.** `4e` follows from reader+2 and has never been captured, and the vendor
 * app itself draws this screen with no editable control — every row is inert, so there was
 * nothing to capture even with the app driving.
 */
object JLabTouch {
    const val ASK: Byte = 0x4c
    const val REPLY: Byte = 0x4d

    /** Twelve triples: two sides, six gestures. */
    const val ENTRIES = 12
    const val TRIPLE = 3

    enum class Side(
        val wire: Byte,
    ) {
        /** ⚠ Named by wire value on purpose — see the class note. */
        FIRST(0x01),
        SECOND(0x02),
        ;

        companion object {
            fun of(wire: Byte): Side? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** ⚠ The app's own order, top to bottom, and the device numbers them the same way. */
    enum class Tap(
        val wire: Byte,
    ) {
        ONE_TAP(0x01),
        TWO_TAPS(0x02),
        THREE_TAPS(0x03),
        LONG_PRESS(0x04),
        SWIPE_DOWN(0x05),
        SWIPE_UP(0x06),
        ;

        companion object {
            fun of(wire: Byte): Tap? = entries.firstOrNull { it.wire == wire }
        }
    }

    enum class Action(
        val wire: Byte,
    ) {
        LAST_TRACK(0x01),
        NEXT_TRACK(0x02),
        PLAY_PAUSE(0x03),
        VOLUME_UP(0x07),
        VOLUME_DOWN(0x08),
        NOISE_CONTROL(0x0b),
        ;

        companion object {
            fun of(wire: Byte): Action? = entries.firstOrNull { it.wire == wire }
        }
    }

    fun get(): OutFrame = JLabFrame.read(ASK)

    /**
     * ⚠ An unrecognised side, gesture or action drops that entry rather than the map:
     * one unknown action byte should not blank a screen that decoded eleven others.
     */
    fun state(r: ByteArray): Map<Pair<Side, Tap>, Action>? {
        val f = JLabFrame.replyTo(r, REPLY, atLeast = 6 + ENTRIES * TRIPLE) ?: return null
        return buildMap {
            for (i in 0 until ENTRIES) {
                val at = 6 + i * TRIPLE
                val side = Side.of(f[at]) ?: continue
                val tap = Tap.of(f[at + 1]) ?: continue
                val action = Action.of(f[at + 2]) ?: continue
                put(side to tap, action)
            }
        }
    }
}
