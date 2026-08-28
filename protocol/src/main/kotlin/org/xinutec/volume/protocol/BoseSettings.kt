package org.xinutec.volume.protocol

/**
 * The Bose frame: `<block> <fn> <operator> <len> <payload…>`.
 *
 * Hand-rolled in [Drivers] for the ANC paths, which were driven against the real
 * headphones and are left exactly as measured. Everything decoded since goes
 * through here instead, because the length byte is the field that has been got
 * wrong — a name read one byte short, and a level read at offset 4 that lives at 5.
 */
object BoseFrame {
    const val GET: Byte = 0x01

    /**
     * ⚠ **Bose's own name for `02` is SET_GET, and `00` is the plain SET.** This
     * constant was called `SET` until 2026-08-23, when `BmapPacket$OPERATOR` was read
     * out of Bose Connect: `00` SET · `01` GET · `02` SET_GET · `03` STATUS · `04`
     * ERROR · `05` START · `06` RESULT · `07` PROCESSING.
     *
     * The **byte never changed** — `02` is what every Bose write in this repo has been
     * driven with, on hardware, and it works. Only the label was wrong, and it was
     * wrong in the direction that invites a mistake: a reader who wanted "just set it,
     * without the reply" would find this named `SET` and have no reason to look for
     * `00`. Which also explains the reply shape the drivers already work around —
     * SET_GET *returns the resulting state*, so a Bose write answering with a status
     * payload is the operator doing what it says, not the device being awkward.
     *
     * ⚠ **`00` has never been sent by this repo**, so nothing here knows what the
     * QC45 does with it, and finding out is not free — see the `04 07` note in
     * `docs/bose-settings.md` for what lives in that neighbourhood.
     */
    const val SET_GET: Byte = 0x02
    const val STATUS: Byte = 0x03
    const val ERROR: Byte = 0x04

    /**
     * ⚠ **Operator `05` is Start, and it is NOT needed for every write.** The ANC
     * mode table (`1f 03`) takes it; EQ, multipoint and the Action button all took a
     * plain [SET_GET] and the device's echoed state changed, so "Bose edits are
     * transactional" is true of one function, not of the protocol.
     */
    const val START: Byte = 0x05

    /**
     * The other two thirds of a transaction, named 2026-08-26 when one was first used.
     *
     * A [START] draws [PROCESSING], then one Status frame per item, then [RESULT] —
     * so `06` is the end marker rather than data, and a decoder that treated every
     * frame as a setting would find a zero-length one at the end.
     */
    const val RESULT: Byte = 0x06

    const val PROCESSING: Byte = 0x07

    fun encode(block: Byte, fn: Byte, operator: Byte, payload: ByteArray = ByteArray(0)) =
        byteArrayOf(block, fn, operator, payload.size.toByte()) + payload

    /**
     * Split a reply window into the frames it actually holds.
     *
     * ⚠ **A Bose read can return several frames in one buffer**, and until 2026-08-26
     * nothing here knew that. Capturing Bose Connect showed it writing eight BMAP
     * packets in a single SPP write and the device answering the same way — one read
     * carried `01 03`, `01 04`, `01 06` and `01 09` glued together. [payload] already
     * refuses to decode past its own length byte, so a single-frame decoder was safe;
     * it just could not see anything after the first frame.
     *
     * ⚠ **Length-driven, and it stops rather than guessing.** A frame is
     * `4 + payload[3]` bytes; if the buffer is too short for the frame it announces,
     * the walk ends and returns what it has. Skipping ahead to the next plausible
     * header would invent frames out of payload bytes — the JBL's [Bes.frame] has the
     * same rule for the same reason.
     */
    fun frames(buffer: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var at = 0
        while (at + 4 <= buffer.size) {
            val end = at + 4 + (buffer[at + 3].toInt() and 0xff)
            if (end > buffer.size) break
            out += buffer.copyOfRange(at, end)
            at = end
        }
        return out
    }

    /**
     * Does [buffer] already hold the frame that ENDS the exchange [sent] started?
     *
     * ⚠ **The point is not speed, it is that a timeout is the wrong instrument.** The
     * transport otherwise waits 400 ms of quiet after the last byte, on a device that
     * answers in about one millisecond — so a card that makes seven exchanges spends
     * most of its time waiting to be sure nothing more is coming. The protocol already
     * says when it is finished; asking it is both faster and more correct than guessing
     * from silence.
     *
     * ⚠ **Stopping at the first frame that matches block and function would TRUNCATE.**
     * A `05` START answers `07` PROCESSING, then one Status frame per item, then `06`
     * RESULT — `01 01` GET_ALL returns eight frames that way. The terminator depends on
     * the operator that was sent:
     *
     * ```
     * sent 01 GET      ends at 03 STATUS    (or 04 ERROR)
     * sent 02 SET_GET  ends at 03 STATUS    (or 04 ERROR)
     * sent 05 START    ends at 06 RESULT    (or 04 ERROR)
     * ```
     *
     * ⚠ **Returns false for anything else**, which means the caller falls back to its
     * timeout rather than to a guess. Several functions on this device answer nothing at
     * all (`01 07`, `01 08`), and a rule that claimed those were complete would turn a
     * silence worth noticing into an empty buffer that looks like a decode failure.
     */
    fun terminates(sent: ByteArray, buffer: ByteArray): Boolean {
        if (sent.size < 3) return false
        val block = sent[0]
        val fn = sent[1]
        val ends =
            when (sent[2]) {
                // ⚠ **A GET can be answered with RESULT.** `04 08` PAIRING_MODE does
                // exactly that — `04 08 01 00` draws `04 08 06 02 00 03`. That was
                // written down the morning this rule was made and not carried into it,
                // and the capture showed the cost precisely: every other exchange fell
                // to ~13 ms while `04 08` alone stayed at 418 ms, still timing out.
                // RESULT ends an exchange whatever started it.
                GET, SET_GET -> setOf(STATUS, RESULT, ERROR)

                START -> setOf(RESULT, ERROR)

                else -> return false
            }
        return frames(buffer).any { it[0] == block && it[1] == fn && it[2] in ends }
    }

    /**
     * The payload of [frame] if it is the expected block/fn/operator, else null.
     *
     * ⚠ **Trusts the length byte over the array's size**, and returns null when they
     * disagree. A reply window can hold two frames, and decoding to the end of the
     * buffer silently reads the next frame's header as this one's data.
     */
    fun payload(frame: ByteArray, block: Byte, fn: Byte, operator: Byte = STATUS): ByteArray? {
        if (frame.size < 4) return null
        if (frame[0] != block || frame[1] != fn || frame[2] != operator) return null
        val len = frame[3].toInt() and 0xff
        if (frame.size < 4 + len) return null
        return frame.copyOfRange(4, 4 + len)
    }
}

/**
 * The three Bose tone controls, in dB.
 *
 * ⚠ **There is no preset id on the wire.** Bose Music's four preset buttons (Bass
 * Boost, Bass Reducer, Treble Boost, Treble Reducer) are the app writing three band
 * values, so the presets are the app's, not the device's — the opposite of Sony,
 * where the preset is opaque and the levels follow it. Anything named here would be
 * a name this repo invented; [BoseEq.BASS_BOOST] and [BoseEq.TREBLE_BOOST] are
 * exceptions only because the capture shows what the vendor app sent for them.
 */
data class BoseBands(
    val bass: Int,
    val mid: Int,
    val treble: Int,
)

/**
 * Bose QC45 equaliser — block `01`, function `07`, decoded from the 2026-08-16
 * capture (`docs/captures.md`).
 *
 * Independently cross-checked: the 2026-08-15 read sweep recorded `01 07` answering
 * `f60a0000/0001/0002` with the EQ flat, which is this layout at rest and was
 * written down before anyone knew what it meant.
 */
object BoseEq {
    const val BLOCK: Byte = 0x01
    const val FN: Byte = 0x07

    const val BASS = 0
    const val MID = 1
    const val TREBLE = 2

    /**
     * ✅ **Proven on hardware 2026-08-16**, twice over and no longer an inference.
     *
     * The `f6 0a` that leads every band's group is the device declaring its own
     * bounds — a flat QC45 answers `01 07 03 0c  f6 0a 00 00  f6 0a 00 01
     * f6 0a 00 02`, which is `<min> <max> <level> <band>` three times. And both ends
     * were then driven: bass −10 and bass +10 were each accepted and read back before
     * being restored to flat. Until that evening only −0…+8 had ever been exercised,
     * because the capture pressed preset buttons and dragged no slider.
     */
    val RANGE = -10..10

    fun get() = BoseFrame.encode(BLOCK, FN, BoseFrame.GET)

    /** `01 07 02 02 <level> <band>` — one band per frame. ⚠ Level first, band second. */
    fun set(band: Int, level: Int): ByteArray {
        require(band in BASS..TREBLE) { "no band $band" }
        require(level in RANGE) { "$level dB is outside $RANGE" }
        val payload = byteArrayOf(level.toByte(), band.toByte())
        return BoseFrame.encode(BLOCK, FN, BoseFrame.SET_GET, payload)
    }

    /**
     * Every frame needed to reach [bands], in the order the vendor app sends them.
     *
     * Treble, then mid, then bass. The order is almost certainly not load-bearing —
     * each write draws its own full status — but it is what was captured, and the
     * cost of matching it is nil.
     */
    fun setAll(bands: BoseBands): List<ByteArray> =
        listOf(set(TREBLE, bands.treble), set(MID, bands.mid), set(BASS, bands.bass))

    /** What Bose Music sends for its "Bass Boost" button. */
    val BASS_BOOST = BoseBands(bass = 8, mid = 0, treble = 0)

    /** And for "Treble Boost" — ⚠ +6, not the +8 its opposite number uses. */
    val TREBLE_BOOST = BoseBands(bass = 0, mid = 0, treble = 6)

    /** Flat. The app calls this "Reset"; it is three zeroes, not a distinct command. */
    val FLAT = BoseBands(bass = 0, mid = 0, treble = 0)

    /**
     * Decode `01 07 03 0c` + three groups of `<min> <max> <level> <band>`.
     *
     * ⚠ **Indexed by the band byte, not by position.** The three groups arrived
     * bass-first in every frame captured, but a Set names its band explicitly, so the
     * device clearly does not think of them as an ordered list — and reading them
     * positionally is the same off-by-one that made a Sony band table start at 257 Hz.
     */
    fun state(frame: ByteArray): BoseBands? {
        val payload = BoseFrame.payload(frame, BLOCK, FN) ?: return null
        if (payload.size % 4 != 0) return null
        val levels = mutableMapOf<Int, Int>()
        for (i in payload.indices step 4) {
            levels[payload[i + 3].toInt() and 0xff] = payload[i + 2].toInt()
        }
        val bass = levels[BASS] ?: return null
        val mid = levels[MID] ?: return null
        val treble = levels[TREBLE] ?: return null
        return BoseBands(bass, mid, treble)
    }
}

/**
 * `01 0e` SETTINGS_CNC_PERSISTENCE — Bose Music's `SettingsCncPersistence`.
 *
 * ```
 * → 01 0e 01 00        ← 01 0e 03 01 01
 * → 01 0e 02 01 00     ← 01 0e 03 01 00
 * → 01 0e 02 01 01     ← 01 0e 03 01 01
 * ```
 *
 * ⚠ **A plain boolean, unlike its neighbour [BoseMultipoint]** — the Status echoes the
 * byte that was written rather than a flags word, so a straight comparison is right here
 * and wrong one function along. Driven both ways and restored on a QC45, 2026-08-28.
 *
 * The name is the vendor's: `SettingsCncPersistenceResponse` parses payload `[0] == 1`
 * into an `isEnabled: Z`, and its SetGet packet takes a boolean into a one-byte payload.
 *
 * ⚠⚠ **What it DOES is untested.** "Persistence" is a name, not a measurement: whether
 * the noise setting survives a power cycle was never checked, because checking it means
 * switching the headphones off and on, which needs hands on the device. The byte moves
 * and reads back; the behaviour behind it is not attested here.
 */
object BoseCncPersistence {
    const val BLOCK: Byte = 0x01
    const val FN: Byte = 0x0e

    fun get() = BoseFrame.encode(BLOCK, FN, BoseFrame.GET)

    fun set(on: Boolean) =
        BoseFrame.encode(BLOCK, FN, BoseFrame.SET_GET, byteArrayOf(if (on) 0x01 else 0x00))

    fun state(frame: ByteArray): Boolean? {
        val payload = BoseFrame.payload(frame, BLOCK, FN) ?: return null
        return payload.firstOrNull()?.let { it.toInt() == 1 }
    }
}

/**
 * Multipoint — block `01`, function `0a`. The one setting here that is symmetric,
 * unlike the Sony's, whose two taps used two different subsystems.
 *
 * ✅ **Driven on hardware 2026-08-16**, on and off, each confirmed by read-back and
 * restored. ⚠ Worth stating next to [SonyMultipoint], which the XM4 refuses outright:
 * the same *setting* behaves completely differently on the two vendors, so neither
 * one's result may be carried across to the other.
 *
 * ⚠ The 2026-08-15 sweep did not record `01 0a` among block `01`'s readable
 * functions, though it plainly answers a Get. Either the sweep's function range
 * stopped short of it or the device answered differently then; re-sweep before
 * treating that list as complete.
 */

object BoseMultipoint {
    const val BLOCK: Byte = 0x01
    const val FN: Byte = 0x0a

    fun get() = BoseFrame.encode(BLOCK, FN, BoseFrame.GET)

    fun set(on: Boolean) =
        BoseFrame.encode(BLOCK, FN, BoseFrame.SET_GET, byteArrayOf(if (on) 0x01 else 0x00))

    /**
     * Whether it is on, from `01 0a 03 01 <flags>`.
     *
     * ⚠ **The status byte is not the byte that was written.** Off reads `06` and on
     * reads `07`, so enabled is bit 0 and bits 1–2 are something else that was set
     * throughout — capability, or a second connection's presence. Comparing the
     * status to the value sent would say every write failed.
     */
    fun state(frame: ByteArray): Boolean? {
        val payload = BoseFrame.payload(frame, BLOCK, FN) ?: return null
        val flags = payload.firstOrNull() ?: return null
        return (flags.toInt() and 0x01) != 0
    }
}

/**
 * The Action button's shortcut — block `01`, function `09`.
 *
 * ✅ **Driven on hardware 2026-08-16**: Spotify and back to Hear Battery Level, each
 * confirmed by read-back and restored.
 *
 * ⚠ **Only two of the app's options were exercised**, so this enum is two entries
 * and everything else decodes to null rather than to a guess. The QC45's own menu
 * offers more.
 */
object BoseButton {
    const val BLOCK: Byte = 0x01
    const val FN: Byte = 0x09

    /**
     * ⚠ Unexplained, and carried verbatim because it was in both directions of every
     * frame seen. Not knowing what `80 09` selects is survivable; inventing a meaning
     * for it and building on that is not.
     */
    private val SELECTOR = byteArrayOf(0x80.toByte(), 0x09)

    /** Named from Bose Music's own labels — see the action log in `docs/captures.md`. */
    enum class Action(
        val code: Byte,
    ) {
        HEAR_BATTERY_LEVEL(0x03),
        SPOTIFY(0x10),
    }

    fun get() = BoseFrame.encode(BLOCK, FN, BoseFrame.GET)

    fun set(action: Action) = BoseFrame.encode(BLOCK, FN, BoseFrame.SET_GET, SELECTOR + action.code)

    /**
     * `01 09 03 0b 80 09 <action>` + eight trailing bytes.
     *
     * ⚠ The trailer was `00 01 40 08 00 00 00 80` for **both** actions, and the
     * 2026-08-15 sweep recorded the same bytes — so it does not track the setting.
     * It is not a mask of the available actions either: read in either byte order it
     * has four bits set, and under neither do both `03` and `10` fall on one. Left
     * undecoded rather than given a meaning that fails its own arithmetic.
     */
    fun state(frame: ByteArray): Action? {
        val payload = BoseFrame.payload(frame, BLOCK, FN) ?: return null
        if (payload.size < 3) return null
        if (payload[0] != SELECTOR[0] || payload[1] != SELECTOR[1]) return null
        return Action.entries.firstOrNull { it.code == payload[2] }
    }
}

/**
 * The QC35's whole SETTINGS block, as `01 01` GET_ALL returns it.
 *
 * ⚠ **Read with operator `05` START, not `01` Get.** A Get answers `04 01 05`, which
 * `docs/bose-read-surface.md` used to call "not gettable, i.e. a Set". It is neither:
 * `05` opens a transaction and the device streams `07` Processing, one Status frame per
 * setting, then `06` Result. Found by capturing Bose Connect's own connect.
 *
 * ⚠ **The reply is SEVEN BMAP frames in one read**, which is why [Bose.frames] exists.
 * Every other Bose decoder here takes a fixed offset off the front of one frame, and
 * that is right only while a request draws exactly one reply.
 */
data class BoseAll(
    val voicePrompts: Boolean? = null,
    /** ⚠ Shares [voicePrompts]' byte — see [BoseVoicePromptLanguage]. */
    val promptLanguage: BoseVoicePromptLanguage? = null,
    /**
     * Which languages this unit will speak, from the four bytes after the flags.
     *
     * ⚠ **Its list, not our enum, and not the same list on two units.** Both models here
     * offer a subset of the twenty-two the SDK names, and they are different subsets — so
     * a number written down anywhere but a test against a capture is a number about one
     * device. The pairs that look like they travel together do not: UK English is absent
     * while US English is present, and European Spanish is absent while Mexican Spanish
     * is present. Offering the enum instead would put languages on a picker that the
     * headphones would refuse.
     */
    val supportedLanguages: List<BoseVoicePromptLanguage> = emptyList(),
    val standby: BoseStandby? = null,
    val sidetone: SidetoneLevel? = null,
    /**
     * The three tone bands — ⚠ **present on the QC45, absent on the QC35**, which does
     * not list `01 07` in its own enumeration at all.
     */
    val tone: BoseBands? = null,
    /** What the action button does — `01 09`. */
    val button: BoseButton.Action? = null,
    /** Two devices at once — `01 0a`. */
    val multipoint: Boolean? = null,
    /**
     * Whether the noise setting persists — `01 0e`.
     *
     * ⚠ The NAME is the vendor's; the behaviour behind it is untested — see
     * [BoseCncPersistence].
     */
    val cncPersistence: Boolean? = null,
    /**
     * The name the device holds — ⚠ **not the one Android has bonded.**
     *
     * It arrives in the same reply as everything else, so reading it costs nothing. What
     * it costs to *omit* was visible the first time a QC45 was renamed from the app: the
     * write was confirmed against an independent read, and both places the screen shows a
     * name went on showing the old one, because both were showing the bonded record. A
     * rename that works and a rename that does nothing looked identical.
     */
    val name: String? = null,
)

/**
 * How long the QC35 waits before powering itself off — `01 04` STANDBY_TIMER.
 *
 * ⚠ **[minutes] is UNSIGNED, and the top option overflows a signed byte.** "3 hours" is
 * `b4` = 180, which read as a Kotlin `Byte` is −76. Driven across the vendor app's whole
 * picker on 2026-08-26: Never `00` · 5 min `05` · 20 `14` · 40 `28` · 1 h `3c` · 3 h `b4`.
 *
 * ⚠ **Zero means NEVER, not "off in zero minutes".** Bose Connect offers it as its own
 * row, so it is a real setting rather than a degenerate value.
 *
 * ⚠ **`01 04` carries a second, different message.** When the payload is two bytes or
 * more with `payload[1] & 1` set, Bose Connect's own parser reads it as an auto-power-down
 * *boolean* instead. Nothing here has seen that shape; a decoder that took `payload[0]`
 * unconditionally would report it as a one-minute timer.
 */
data class BoseStandby(
    val minutes: Int,
) {
    /** The device's own word for `00`. */
    val never: Boolean get() = minutes == 0
}

/**
 * How much of your own voice you hear on a call — `01 0b` SIDETONE, which Bose Connect
 * calls **Self Voice**.
 *
 * ⚠ Named from `SidetoneMode` in `com.bose.monet`, and confirmed on the device: the app
 * showed Medium while the wire read `02`, then Low while it read `03`.
 */
enum class SidetoneLevel {
    OFF,
    HIGH,
    MEDIUM,
    LOW,
}

/**
 * `01 01` SETTINGS/GET_ALL — the whole block in one exchange.
 *
 * ⚠ **Operator START, and the reply is several frames.** See [BoseAll] for why a Get
 * is refused here, and [BoseFrame.frames] for why the buffer has to be split.
 *
 * ⚠ **What comes back is the device's own enumeration of what it has**, which is
 * stronger evidence than a sweep: on the QC35 it lists exactly `01 02`, `01 03`,
 * `01 04`, `01 06`, `01 09` and `01 0b`. `01 07` BASS_CONTROL and `01 08` ALERTS are
 * absent from it, and those are the two functions that answer nothing at all when
 * asked directly — so the silence is a missing function, not a missed reply.
 */
object BoseAllSettings {
    const val BLOCK: Byte = 0x01
    const val FN: Byte = 0x01

    fun get() = BoseFrame.encode(BLOCK, FN, BoseFrame.START)

    /**
     * Pick the settings out of a GET_ALL reply.
     *
     * Returns null only when nothing parsed at all; a device that answers with fewer
     * settings than another yields a [BoseAll] with nulls, because **absent and
     * unreadable are different** and the card draws them differently.
     */
    fun state(buffer: ByteArray): BoseAll? {
        val frames = BoseFrame.frames(buffer)
        if (frames.isEmpty()) return null
        var out = BoseAll()
        for (f in frames) {
            BoseFrame.payload(f, BLOCK, BoseVoicePrompts.FN)?.let {
                out =
                    out.copy(
                        voicePrompts = BoseVoicePrompts.enabled(it),
                        promptLanguage = BoseVoicePromptLanguage.of(it),
                        supportedLanguages = BoseVoicePrompts.supported(it),
                    )
            }
            BoseFrame.payload(f, BLOCK, BoseStandbyTimer.FN)?.let {
                out = out.copy(standby = BoseStandbyTimer.state(it))
            }
            BoseFrame.payload(f, BLOCK, BoseSidetone.FN)?.let {
                out = out.copy(sidetone = BoseSidetone.level(it))
            }
            BoseFrame.payload(f, BLOCK, BoseName.FN)?.let {
                out = out.copy(name = BoseName.of(it))
            }
            // ⚠ **The FRAME, not the payload** — each of these decoders checks the
            // block and function itself, which is what lets them be handed every frame
            // in turn. Passing a payload would find nothing and report three settings
            // the device plainly has as absent.
            BoseEq.state(f)?.let { out = out.copy(tone = it) }
            BoseButton.state(f)?.let { out = out.copy(button = it) }
            BoseMultipoint.state(f)?.let { out = out.copy(multipoint = it) }
            BoseCncPersistence.state(f)?.let { out = out.copy(cncPersistence = it) }
        }
        return out
    }
}

/** `01 04` STANDBY_TIMER — see [BoseStandby] for the unit and its traps. */
object BoseStandbyTimer {
    const val FN: Byte = 0x04

    /**
     * The six the vendor app offers, in minutes, `0` being its "Never".
     *
     * ⚠ **What is OFFERED, not what is legal** — the same caution as the JBL's
     * `JBL_IDLE_MINUTES`. Every one of these was selected in Bose Connect and read back
     * from the wire on 2026-08-26, so the mapping is measured; the field is a whole
     * byte and its edges are unprobed, so a value from outside this list is shown as it
     * stands rather than snapped to a neighbour.
     */
    val OFFERED = listOf(0, 5, 20, 40, 60, 180)

    fun get() = BoseFrame.encode(BoseAllSettings.BLOCK, FN, BoseFrame.GET)

    fun set(minutes: Int) =
        BoseFrame.encode(
            BoseAllSettings.BLOCK,
            FN,
            BoseFrame.SET_GET,
            byteArrayOf(minutes.toByte()),
        )

    /**
     * ⚠ **Unsigned**, because "3 hours" is `b4` = 180 and a Kotlin `Byte` makes that −76.
     *
     * ⚠ **Refuses the auto-power-down shape rather than misreading it.** Bose Connect's
     * parser treats a payload of two-or-more bytes whose `[1]` has bit 0 set as a
     * *boolean*, not a duration; taking `[0]` regardless would report it as a one-minute
     * timer. Nothing here has seen that shape, which is exactly why it must not be
     * guessed at.
     */
    fun state(payload: ByteArray): BoseStandby? {
        if (payload.isEmpty()) return null
        if (payload.size >= 2 && (payload[1].toInt() and 1) == 1) return null
        return BoseStandby(payload[0].toInt() and 0xff)
    }
}

/** `01 0b` SIDETONE — Bose Connect calls it Self Voice. */
object BoseSidetone {
    const val FN: Byte = 0x0b

    fun get() = BoseFrame.encode(BoseAllSettings.BLOCK, FN, BoseFrame.GET)

    /**
     * ⚠ **The level is payload `[1]`, not `[0]`.** `[0]` is a persist flag —
     * `SidetoneEvent`'s first constructor argument goes to a static `persist`, and its
     * second is the one turned into a `SidetoneMode`. Confirmed on the device: Medium
     * read `01 02 0f` and Low read `01 03 0f`, so `[0]` held still while `[1]` moved.
     *
     * ⚠ **The trailing `0f` is NOT decoded as a supported-modes mask**, tempting as four
     * bits for four modes is. Bose Connect hands `payload[1…]` to its
     * `SupportedSidetoneModes`, which would swallow the level byte too — so either that
     * offset is wrong for a three-byte payload or the field means something else. It is
     * constant across two levels; that is all that is established.
     */
    fun level(payload: ByteArray): SidetoneLevel? =
        when (payload.getOrNull(1)?.toInt()?.and(0xff)) {
            0x00 -> SidetoneLevel.OFF
            0x01 -> SidetoneLevel.HIGH
            0x02 -> SidetoneLevel.MEDIUM
            0x03 -> SidetoneLevel.LOW
            else -> null
        }
}

/** `01 03` VOICE_PROMPTS — the switch only; the language is read elsewhere. */
object BoseVoicePrompts {
    const val FN: Byte = 0x03

    /** How many times [set] will wait and re-ask before reporting what it still sees. */
    private const val SETTLE_READS = 2

    fun get() = BoseFrame.encode(BoseAllSettings.BLOCK, FN, BoseFrame.GET)

    /**
     * The payload as the device holds it right now — **the only safe basis for a write.**
     *
     * ⚠ **Walks the frames** rather than decoding the buffer as one. A `01 03` Get is
     * normally answered alone, but a preceding write can leave its own Status ahead of
     * this one, and reading the buffer as a single frame would then return the wrong
     * one. See [BoseSettingsDriver.writeVoicePrompts], which is exactly that sequence.
     */
    fun read(t: Transport): ByteArray? =
        BoseFrame.frames(t.exchange(get())).firstNotNullOfOrNull {
            BoseFrame.payload(it, BoseAllSettings.BLOCK, FN)
        }

    /**
     * Change the switch, the language, or both, and answer with the payload afterwards.
     *
     * One primitive for two settings, because they are **one byte**: a caller that
     * changed only its own half would still have to read the other, and two copies of
     * that read-modify-write is two places for the carried field to be dropped.
     * Null for either argument means "leave it as it is".
     *
     * ⚠⚠ **On a QC45, a write that CHANGES something draws no reply at all.**
     * Discriminated over one socket, 7/7 across two sittings: writing the byte the device
     * already holds answers a Status in about a millisecond, and writing a different one
     * answers nothing while applying the change. `01 04` and `01 0b` changed state and
     * answered normally in the same sitting, so on that model it is this function's alone.
     * [BoseFrame.terminates] ends a SET_GET on Status, Result or Error — so a real change
     * has no terminator and would sit out the transport's whole window on every switch
     * press, and the reply it eventually failed to get would be worth nothing anyway.
     *
     * ⚠ **The QC35 does the OPPOSITE, and it is the same function.** Measured 2026-08-28,
     * same seven-step discrimination: every write answers a Status at once, carrying the
     * new byte, and a Get immediately after agrees. No silence and no settle delay. So
     * "`01 03` is asynchronous" is a fact about one model, not about the protocol — which
     * is the mistake this file has made before with `01 05` and `01 06`.
     *
     * The path below is written for the harder of the two and is correct on both: the QC35
     * answers the write, [Transport.receive] carries that answer off, and the Get that
     * follows reads the same value. It costs that model one settle window it does not
     * need — measured, accepted, and cheaper than a per-model branch here.
     *
     * So the write is **sent, not exchanged**, and the truth comes from the Get after it.
     *
     * ⚠⚠ **And that Get has to WAIT, or it reads the state from before the write.** The
     * first build of this sent the write and asked immediately; the device answered with
     * the byte it had held a millisecond earlier, so a toggle that worked was reported to
     * the owner as *"this pair refused that"* — twice, once in each direction, while the
     * card's own later refresh drew the new value beside that message. The change is
     * applied asynchronously: a Get about 430 ms after the write has shown the new byte
     * every time it has been asked, and one sent at once has never shown it.
     *
     * [Transport.receive] is what buys that time — bounded by the transport rather than
     * by a number invented here, and it also carries off anything the device volunteered
     * instead of leaving it in the socket for the next exchange to adopt.
     *
     * ⚠ **Two attempts, not a loop until it changes.** A device that genuinely refuses
     * the write looks exactly like one that is still applying it, so an unbounded wait
     * would hang on the one case that most needs reporting.
     *
     * ⚠ **A no-op is not written at all**, which is what keeps the send-and-do-not-wait
     * safe: the one case that does answer is the one that never happens here, so nothing
     * is left in the socket for the next exchange to mistake for its own answer. If a
     * firmware ever does reply to a real change, its Status lands in a Get's window and
     * [read] walks past it to the right frame — wasteful, not wrong.
     */
    fun set(
        t: Transport,
        on: Boolean? = null,
        language: BoseVoicePromptLanguage? = null,
    ): ByteArray? {
        val current = read(t) ?: return null
        val byte = current.getOrNull(0) ?: return null
        val frame =
            BoseWrites.voicePrompts(
                byte,
                on ?: enabled(current) ?: return null,
                language ?: BoseVoicePromptLanguage.of(current) ?: return null,
            )
        // The frame is a one-byte payload, so its last byte IS the byte being asked for.
        if (frame.last() == byte) return current
        t.send(frame)
        var after = current
        repeat(SETTLE_READS) {
            t.receive()
            after = read(t) ?: return null
            if (after.getOrNull(0) != byte) return after
        }
        // Still the old byte after both attempts: the caller must report that as it is,
        // because "refused" and "slower than we waited" are not distinguishable here.
        return after
    }

    /**
     * ⚠ **Bit 5 of byte 0**, from `SettingsBmapPacketParser`: it shifts right by five
     * and masks one, and hands that to `VoicePromptEvent`'s first argument, which is
     * what `getVoicePromptsEnabled()` returns. Bit 7 is a second flag the SDK exposes
     * only as `c()`, and the low five bits are the language.
     *
     * Confirmed against Bose Connect on 2026-08-26: the screen showed prompts on and
     * "English (U.S.)" while this byte read `a1` — bit 5 set, low bits `01`.
     */
    fun enabled(payload: ByteArray): Boolean? =
        payload.getOrNull(0)?.let { (it.toInt() shr 5) and 1 == 1 }

    /**
     * The languages this unit will speak — bytes 1–4, big-endian, bit *n* being the
     * language whose wire value is *n*.
     *
     * ⚠ **LSB-first within the number**, from `BitSetUtil.b`, which shifts right one bit
     * at a time. Reading it the other way round yields a plausible list of the wrong
     * languages.
     */
    fun supported(payload: ByteArray): List<BoseVoicePromptLanguage> {
        if (payload.size < 5) return emptyList()
        var mask = 0
        for (i in 1..4) mask = (mask shl 8) or (payload[i].toInt() and 0xff)
        return BoseVoicePromptLanguage.entries.filter { (mask shr it.ordinal) and 1 == 1 }
    }
}

/**
 * The QC45's named ANC modes — block `1f`, and the whole of its "custom modes" feature.
 *
 * ⚠ **This block is NOT in Bose Connect's SDK**, and Bose Music's own
 * `SettingsCncPresets` (`01 0f`) answers `04 01 04` *function not supported* on this
 * device. So none of the below comes from a decompile: it was captured off the wire
 * while the vendor app edited a mode, then reconstructed and driven from this repo's
 * own socket — level set to 3, read back, restored. See `docs/bose-read-surface.md`,
 * "The mode-edit write, captured and replayed".
 *
 * ⚠ **The device has FOUR slots**, two built in (Quiet, Aware) and two the owner makes.
 * The names are the device's own — this repo does not supply them.
 */
object BoseCncModes {
    const val BLOCK: Byte = 0x1f

    /** `1f 01` — a Start whose transaction returns every slot. */
    const val LIST: Byte = 0x01

    /** `1f 03` — read the active slot, or Start a selection. */
    const val ACTIVE: Byte = 0x03

    /** `1f 06` — one slot: Get by index, or SET_GET to edit it. */
    const val SLOT: Byte = 0x06

    /** `1f 08` — `<capacity> <bitmask of occupied slots>`. */
    const val SLOTS: Byte = 0x08

    // ⚠⚠ **`04 01 04` here means NOT ON THIS DEVICE, not "no such function".** `1f 09`
    // and `1f 0a` were read as not-supported and written up as "the block ends at 08";
    // a capture of Bose Music the same evening shows it sending `1f 09 05 01 <slot>` as
    // its DELETE, three times, taking the error, and falling back — and probing `1f 0b`
    // too. So the block extends past `08` in the protocol and this QC45 declines part of
    // it. An error code bounds the DEVICE, never the protocol.
    //
    // The rest is read-only so far and none of it is named here, because naming a byte
    // is a decode and these are readings:
    //
    //   1f 00  "1.0.0"                the block's own version, not the protocol's
    //   1f 02  02 02 00 00 00 09      constant across a selection change
    //   1f 04  <slot>                 a SECOND report of the active slot — it moved
    //                                 03 -> 02 -> 03 with [ACTIVE], so it is not a
    //                                 previous-slot register. What it is for is unknown.
    //   1f 05  01                     constant across a selection change
    //   1f 07  00 01 02 03            the four slot INDICES — see the correction below
    //
    // ⚠ **`1f 07` is NOT an occupancy list.** It was written up here as "the shape a
    // delete must change"; deleting a mode later the same day left it at `00 01 02 03`
    // while [SLOTS] went `04 0f` -> `04 0b`. It is a static index list and the occupancy
    // lives in [SLOTS]'s bitmask alone. Measured 2026-08-28 by deleting slot 2 and
    // reading both — see `docs/bose-read-surface.md`.

    /** How many bytes the name occupies in a record, NUL-padded, in both directions. */
    private const val NAME_LEN = 32

    /**
     * ⚠ **The level is at a DIFFERENT offset in each direction** — `[35]` in the write
     * and `[42]` in the reply. The two records are not the same struct and the reply is
     * not an echo, so one constant for both would be five bytes wrong coming back.
     */
    private const val LEVEL_WRITE = 35
    private const val LEVEL_READ = 42

    /**
     * Where the wind-block byte sits in each direction — `[38]` going out, `[46]` coming
     * back, the same asymmetry [LEVEL_WRITE] and [LEVEL_READ] have.
     *
     * ⚠ Read off Bose Music's parser: `AudioModesModeConfigResponse.createFromPacket`
     * indexes `0x2e` into `WindBlockEnabledTypes`, and the write builder's parameter order
     * puts it last in a 39-byte record. Then driven — `[38]` set to `01` came back at
     * `[46]` as `01`, and was put back.
     */
    private const val WIND_WRITE = 38
    private const val WIND_READ = 46

    /** `[41]`, a bitfield of mutability flags rather than any kind of level. */
    private const val FLAGS_READ = 41
    private const val WIND_MUTABLE_BIT = 3

    /** The quietest and most transparent ends of the eleven-point scale. */
    const val QUIETEST = 0
    const val MOST_AWARE = 10

    /**
     * One slot as the device describes it.
     *
     * ⚠ [nameId] is **undecoded** — `01` Quiet, `02` Aware, and whatever the owner's
     * modes were made with. It is constant across every edit of a given mode, so it
     * belongs to the mode's identity rather than its level; it looked like the level on
     * one mode where `07` happened to land in both bytes. It is carried through an edit
     * unchanged rather than interpreted.
     */
    data class Mode(
        val slot: Int,
        val nameId: Int,
        val name: String,
        val level: Int,
        val editable: Boolean,
        /**
         * `[46]` — Bose Music's `windBlockToggleEnabled`.
         *
         * ⚠⚠ **Turning this ON takes the level over.** Written `01` alongside level `07`
         * in one frame, the device read back level `00`; the same frame with `00` and
         * level `07` took the level fine, so a level beside a wind-block change is not
         * ignored in general — the zero came from wind block. The vendor's own screen
         * says it "automatically adjusts noise cancellation". ⚠ Whether the device FORCES
         * `0` or merely reports `0` while it manages the level is NOT separated.
         */
        val windBlock: Boolean = false,
        /** `[41]` bit 3 — `anrWindToggleMutable`. False on Quiet and Aware. */
        val windBlockMutable: Boolean = false,
    )

    fun list() = BoseFrame.encode(BLOCK, LIST, BoseFrame.START)

    fun active() = BoseFrame.encode(BLOCK, ACTIVE, BoseFrame.GET)

    fun slots() = BoseFrame.encode(BLOCK, SLOTS, BoseFrame.GET)

    /**
     * `1f 08`'s reply: how many slots the device has, and which are occupied.
     *
     * ⚠ The bitmask is the ONLY place occupancy lives. [Mode] does not carry it, because
     * a record's `[5]` is a view of this bit rather than a field of its own — see
     * [occupancy].
     */
    data class Slots(
        val capacity: Int,
        val occupied: Int,
    ) {
        fun holds(slot: Int) = occupied and (1 shl slot) != 0

        fun with(slot: Int, held: Boolean) =
            copy(occupied = if (held) occupied or (1 shl slot) else occupied and (1 shl slot).inv())
    }

    fun slotsOf(buffer: ByteArray): Slots? {
        val p =
            BoseFrame.frames(buffer).firstNotNullOfOrNull {
                BoseFrame.payload(it, BLOCK, SLOTS)
            } ?: return null
        if (p.size < 2) return null
        return Slots(p[0].toInt() and 0xff, p[1].toInt() and 0xff)
    }

    /** `1f 08` as a WRITE — the other half of both [create] and [delete]. */
    fun occupancy(slots: Slots): ByteArray =
        BoseFrame.encode(
            BLOCK,
            SLOTS,
            BoseFrame.SET_GET,
            byteArrayOf(slots.capacity.toByte(), slots.occupied.toByte()),
        )

    /**
     * The 39-byte record write. [setLevel] is the special case that keeps everything but
     * the level; this is the general one, and an empty [name] with [nameId] 0 is how a
     * slot is blanked.
     */
    private fun record(
        slot: Int,
        nameId: Int,
        name: String,
        level: Int,
        windBlock: Boolean = false,
    ): ByteArray {
        val padded = name.toByteArray(Charsets.UTF_8).copyOf(NAME_LEN)
        val tail =
            byteArrayOf(
                level.coerceIn(QUIETEST, MOST_AWARE).toByte(),
                0,
                0,
                if (windBlock) 1 else 0,
            )
        val body = byteArrayOf(slot.toByte(), 0x00, nameId.toByte()) + padded + tail
        check(body.size == LEVEL_WRITE + 4) { "record is ${body.size} bytes, not 39" }
        return BoseFrame.encode(BLOCK, SLOT, BoseFrame.SET_GET, body)
    }

    /**
     * Fill an empty slot. **Both frames, in order** — captured off Bose Music creating a
     * mode on 2026-08-28 and replayed from this repo's own socket the same evening.
     *
     * ⚠⚠ **The record write ALONE does not create anything.** Sent on its own it stores
     * the name and level and leaves `[5]` at `00`, so the mode is invisible to every
     * reader including the vendor app — measured, not reasoned about: this repo sent
     * exactly that frame and read the record back still unoccupied. The `1f 08` write is
     * what brings it into existence, and [delete] is the same two frames inverted.
     *
     * ⚠ [nameId] is an index into a vendor name table this repo has NOT decoded — Home is
     * `0a`, Commute `07`. Creating a mode with a nameId nothing has observed is a guess at
     * a byte the device may use for its icon or its ordering, so pass one that has been
     * seen on this device.
     */
    fun create(slot: Int, nameId: Int, name: String, level: Int, slots: Slots): List<ByteArray> {
        require(name.isNotEmpty()) { "an empty name is a delete, not a create" }
        return listOf(record(slot, nameId, name, level), occupancy(slots.with(slot, true)))
    }

    /**
     * Empty a slot. **Both frames, in order.**
     *
     * ⚠ The blanked record carries level `05`, which is what Bose Music writes and what
     * the device then reports at `[42]` for an unoccupied slot. It is a leftover, not a
     * level — read `[5]`, or [Slots.holds], before believing any of it.
     *
     * ⚠⚠ **`1f 09 05 01 <slot>` is the protocol's own delete and this device REFUSES it**
     * (`04 01 04`), which is why the operation is two writes rather than one. Bose Music
     * tries `1f 09` three times, takes the error, and falls back to exactly this pair.
     */
    fun delete(slot: Int, slots: Slots): List<ByteArray> =
        listOf(record(slot, 0, "", DELETED_LEVEL), occupancy(slots.with(slot, false)))

    /** What Bose Music writes into a slot it is emptying. */
    private const val DELETED_LEVEL = 5

    /**
     * ⚠ **Selecting takes operator `05` Start and the payload `<slot> 01`**, not
     * `01 <slot>`: the one captured example had `01` in both bytes and hid the order.
     */
    fun select(slot: Int) =
        BoseFrame.encode(BLOCK, ACTIVE, BoseFrame.START, byteArrayOf(slot.toByte(), 0x01))

    /**
     * Move one mode's level, leaving its name and [Mode.nameId] as they are.
     *
     * ⚠ **Send this on release, not on change.** Bose Music writes once per slider
     * position — eight frames for one adjustment — and there is no reason to copy that.
     */
    fun setLevel(mode: Mode, level: Int): ByteArray =
        record(mode.slot, mode.nameId, mode.name, level, mode.windBlock)

    /**
     * Turn wind block on or off, leaving the rest of the record alone.
     *
     * ⚠ **The level goes with it when this turns ON** — see [Mode.windBlock]. The caller
     * re-reads rather than assuming, which is what makes that visible on the card.
     */
    fun setWindBlock(mode: Mode, on: Boolean): ByteArray =
        record(mode.slot, mode.nameId, mode.name, mode.level, on)

    /**
     * Every occupied slot in a `1f 01` transaction's reply, or in one slot's Status.
     *
     * ⚠ The reply arrives as ONE batched buffer holding a dozen frames, so it has to be
     * split before anything is read out of it — see [BoseFrame.frames].
     */
    fun modes(buffer: ByteArray): List<Mode> =
        BoseFrame.frames(buffer).mapNotNull { frame ->
            val p = BoseFrame.payload(frame, BLOCK, SLOT) ?: return@mapNotNull null
            if (p.size <= LEVEL_READ) return@mapNotNull null
            val name =
                String(p, 6, NAME_LEN.coerceAtMost(p.size - 6), Charsets.UTF_8)
                    .substringBefore('\u0000')
            // ⚠ An empty name is an EMPTY SLOT, not a nameless mode: before its fourth
            // mode existed this device answered a full-length record with no name in it.
            if (name.isEmpty()) return@mapNotNull null
            Mode(
                slot = p[0].toInt() and 0xff,
                nameId = p[2].toInt() and 0xff,
                name = name,
                level = p[LEVEL_READ].toInt() and 0xff,
                editable = p[3].toInt() != 0,
                windBlock = p.getOrNull(WIND_READ)?.toInt() == 1,
                windBlockMutable =
                    (p.getOrNull(FLAGS_READ)?.toInt() ?: 0) shr WIND_MUTABLE_BIT and 1 == 1,
            )
        }

    /** Which slot is selected, from `1f 03 03 01 <slot>`. */
    fun activeSlot(buffer: ByteArray): Int? {
        val frames = BoseFrame.frames(buffer)
        val payload = frames.firstNotNullOfOrNull { BoseFrame.payload(it, BLOCK, ACTIVE) }
        return payload?.firstOrNull()?.toInt()?.and(0xff)
    }
}

/**
 * `02 02` BATTERY_LEVEL — one byte, a percentage.
 *
 * ⚠ **Charging is NOT reported on the QC35**, and this returns null for it rather than
 * `false`. `02 05` CHARGER_DETECT answers `04 01 04` function-not-supported on that
 * device, so there is nowhere for a charging state to come from; the QC45's four-byte
 * `5a ff ff 00` may carry one, and nothing here has established that either.
 *
 * ⚠ **Measured against the vendor app**: Bose Connect showed 100 while this read `64`.
 * And against itself over time — `46` (70) on 2026-08-15, `64` after charging — which is
 * what proved this is the battery and `01 04` is not.
 */
object BoseBattery {
    const val BLOCK: Byte = 0x02
    const val FN: Byte = 0x02

    fun get() = BoseFrame.encode(BLOCK, FN, BoseFrame.GET)

    fun state(buffer: ByteArray): Battery? {
        val frame =
            BoseFrame.frames(buffer).firstOrNull { it[0] == BLOCK && it[1] == FN } ?: return null
        val payload = BoseFrame.payload(frame, BLOCK, FN) ?: return null
        val pct = payload.getOrNull(0)?.toInt()?.and(0xff) ?: return null
        if (pct > 100) return null
        return Battery(percent = pct, charging = null)
    }
}

/**
 * The voice-prompt language, from the low five bits of `01 03`'s first byte.
 *
 * ⚠ **The wire value is NOT the enum ordinal.** Bose Connect's `VoicePromptLanguage`
 * leads with two sentinels (`UNKNOWN`, `INVALID`) and subtracts them in
 * `adjustedOrdinal()`, so the byte matches the position only after that shift. The same
 * trap as Sony's `Command` and the JBL's gesture table.
 *
 * ⚠ **`00` is UK English and `01` is US English** — one bit apart, and this unit reads
 * `01`, which Bose Connect renders as "English (U.S.)". A decoder off by one here is
 * wrong in a way nobody would notice.
 */
enum class BoseVoicePromptLanguage {
    UK_ENGLISH,
    US_ENGLISH,
    FRENCH,
    ITALIAN,
    GERMAN,
    EUROPEAN_SPANISH,
    MEXICAN_SPANISH,
    BRAZILIAN_PORTUGUESE,
    MANDARIN_CHINESE,
    KOREAN,
    RUSSIAN,
    POLISH,
    HEBREW,
    TURKISH,
    DUTCH,
    JAPANESE,
    CANTONESE,
    ARABIC,
    SWEDISH,
    DANISH,
    NORWEGIAN,
    FINNISH,
    ;

    companion object {
        /** ⚠ Five bits, so a value outside the table is possible and returns null. */
        fun of(payload: ByteArray): BoseVoicePromptLanguage? =
            payload
                .getOrNull(0)
                ?.toInt()
                ?.and(0x1f)
                ?.let { entries.getOrNull(it) }
    }
}

/**
 * The writes for `01 03` and `01 0b`, **as Bose Connect sends them**.
 *
 * ⚠ **Both are read-modify-write, and that is not a style choice.** Each carries a field
 * this app is not changing — the voice-prompt byte holds the switch *and* the language,
 * and the sidetone payload leads with a persist flag. Writing a value assembled from
 * scratch would set the other field to whatever this code happened to assume. Captured
 * 2026-08-26; before that the shape here was a guess, and the guess was wrong: sidetone
 * takes a TWO-byte payload, not the one byte every other setting on this block takes.
 */
object BoseWrites {
    /**
     * `01 03 02 01 <current bits 7–6 | (on ? 0x20 : 0) | language>`.
     *
     * ⚠ **[current] is carried because one byte holds four fields**, two of them
     * undecoded. This function used to build the byte from [on] and [language] alone, on
     * the reasoning that the vendor app writes `21` where the device reads `a1` — so the
     * high bits were the device's to maintain. That is right about bit 7, which the
     * device restores by itself, and unestablished about bit 6.
     *
     * ⚠ **A QC45's bit 6 was set before any write to this function and has been clear
     * ever since one**, across a re-enable and a power cycle. **Whether carrying it would
     * have saved it cannot now be tested**: a deliberate `61` to that unit read back `a1`,
     * so the bit cannot be set from zero, and the only device that could answer the
     * question is one still holding it. Carrying it is the cheap side of an unknown, not
     * a demonstrated fix — the demonstrated part is only that dropping it is not free.
     *
     * ✅ **Writing the high bits back is accepted, and that IS measured**: `a1` written to
     * a QC45 holding `a1` read back `a1`, and `81` likewise, on 2026-08-28.
     *
     * ⚠ **The language rides in the same byte**, so "turn prompts on" without carrying
     * the current language across silently resets it to `00` — UK English, one bit from
     * the US English this unit uses.
     */
    fun voicePrompts(current: Byte, on: Boolean, language: BoseVoicePromptLanguage): ByteArray =
        BoseFrame.encode(
            BoseAllSettings.BLOCK,
            BoseVoicePrompts.FN,
            BoseFrame.SET_GET,
            byteArrayOf(
                (
                    (current.toInt() and 0xc0) or
                        (if (on) 0x20 else 0x00) or
                        language.ordinal
                ).toByte(),
            ),
        )

    /**
     * `01 0b 02 02 <persist> <level>`.
     *
     * ⚠ **Two payload bytes.** `01 0b 02 01 <level>` is what the shape of every
     * neighbouring setting suggests, and it is wrong — [persist] is byte 0 of the reply
     * and the vendor app sends it back verbatim. Read it; do not assume `01`.
     */
    fun sidetone(persist: Byte, level: SidetoneLevel): ByteArray =
        BoseFrame.encode(
            BoseAllSettings.BLOCK,
            BoseSidetone.FN,
            BoseFrame.SET_GET,
            byteArrayOf(persist, level.ordinal.toByte()),
        )
}

/**
 * `04 08` PAIRING_MODE — Bose Connect's **CONNECT NEW**.
 *
 * ```
 * → 04 08 05 01 01      operator 05 START, payload 01
 * ← 04 08 07 00         Processing
 * ← 04 08 06 02 01 01   Result
 * ```
 *
 * ⚠ **A START transaction, not a Set** — captured from the vendor app 2026-08-26. The
 * shape of every other setting on this device would have suggested
 * `04 08 02 01 01`, and by now that guess has been wrong twice on this protocol
 * (sidetone's payload length, and `01 01` GET_ALL refusing a plain Get).
 *
 * ⚠ **A Get answers with operator `06` RESULT rather than `03` STATUS**, payload
 * `<on> <?>`: `00 01` read while idle and `01 01` immediately after entering pairing
 * mode, so byte 0 is the mode. ⚠ Byte 1 has been seen as both `00` and `01` with no
 * account of why, so it is carried and not interpreted.
 *
 * ⚠ **Only ENTERING is implemented.** Leaving is presumably payload `00`, and
 * presumably is not a word this file gets to use about an untested frame — the mode
 * times out on its own, so nothing needs it yet.
 *
 * ⚠ **This writes to block `04`**, where `04 07` CLEAR_DEVICE_LIST and `04 03`
 * REMOVE_DEVICE live. It is safe because the block and function are *fixed here*, which
 * is exactly the property `docs/bose-settings.md` demands: a Bose writer taking block
 * and function as parameters must not exist.
 */
object BosePairing {
    const val BLOCK: Byte = 0x04
    const val FN: Byte = 0x08

    fun get() = BoseFrame.encode(BLOCK, FN, BoseFrame.GET)

    /** Put the headphones in pairing mode so a new device can find them. */
    fun enter() = BoseFrame.encode(BLOCK, FN, BoseFrame.START, byteArrayOf(0x01))

    /** ⚠ The reply is a RESULT, so [BoseFrame.payload] is asked for that operator. */
    fun on(buffer: ByteArray): Boolean? {
        val frame =
            BoseFrame.frames(buffer).firstOrNull { it[0] == BLOCK && it[1] == FN } ?: return null
        val payload = BoseFrame.payload(frame, BLOCK, FN, BoseFrame.RESULT) ?: return null
        return payload.getOrNull(0)?.let { it.toInt() != 0 }
    }
}

/**
 * One device the headphones are paired with, as `04 04` LIST_DEVICES and `04 05` INFO
 * describe it together.
 *
 * ⚠ **[connected] comes from a BITMASK over the list, not from a count.** See
 * [BoseDevices]; reading that byte as a count is a mistake this repo made and corrected
 * within a day, and it survived because with a single paired device the two are the
 * same byte.
 */
data class BoseDevice(
    val address: String,
    val name: String? = null,
    val connected: Boolean = false,
)

/** `04 04` LIST_DEVICES + `04 05` INFO. */
object BoseDevices {
    const val BLOCK: Byte = 0x04
    const val LIST: Byte = 0x04
    const val INFO: Byte = 0x05

    fun list() = BoseFrame.encode(BLOCK, LIST, BoseFrame.GET)

    /** ⚠ **INFO is keyed by the ADDRESS, not by an index.** A bare Get and a one-byte
     *  index both answer `04 01 01` bad-argument; the six address bytes are the key, so
     *  the paired list is a set rather than an array. */
    fun info(address: ByteArray) = BoseFrame.encode(BLOCK, INFO, BoseFrame.GET, address)

    /**
     * The addresses, and which are connected.
     *
     * ⚠ **Byte 0 is a bitmask over the list's own positions.** Measured 2026-08-26:
     * one paired device `01`; two, both connected, `03`; then `01` again when one was
     * disconnected **while both stayed in the list**. A count would have said `02`.
     */
    fun state(buffer: ByteArray): List<BoseDevice> {
        val frame = BoseFrame.frames(buffer).firstOrNull { it[0] == BLOCK && it[1] == LIST }
        val payload = frame?.let { BoseFrame.payload(it, BLOCK, LIST) } ?: return emptyList()
        val mask = payload.getOrNull(0)?.toInt()?.and(0xff) ?: return emptyList()
        val out = mutableListOf<BoseDevice>()
        var at = 1
        var slot = 0
        while (at + 6 <= payload.size) {
            out +=
                BoseDevice(
                    address = Hex.format(payload, at, at + 6),
                    connected = (mask shr slot) and 1 == 1,
                )
            at += 6
            slot++
        }
        return out
    }

    /** The friendly name out of an `04 05` reply. ⚠ Three status bytes precede it. */
    fun name(buffer: ByteArray): String? {
        val frame = BoseFrame.frames(buffer).firstOrNull { it[0] == BLOCK && it[1] == INFO }
        val payload = frame?.let { BoseFrame.payload(it, BLOCK, INFO) } ?: return null
        if (payload.size <= 9) return null
        return String(
            payload,
            9,
            payload.size - 9,
            Charsets.UTF_8,
        ).trim { it <= ' ' }.ifBlank { null }
    }
}

/** What happened when a device was asked to be forgotten. */
sealed interface Forget {
    /** Gone from the list, confirmed by re-reading it. */
    data object Forgot : Forget

    /**
     * ⚠ **Refused because the device is CONNECTED.**
     *
     * Removing a connected device disconnects it as part of the same command — captured
     * 2026-08-26, where `04 03` drew unsolicited `04 02` DISCONNECT frames back. The
     * phone this app talks over is always connected, so refusing every connected entry
     * makes it impossible to cut our own channel, **without needing to know which
     * address is ours** — which is the part that cannot be established: Android hands
     * out a fake adapter address, and whether `04 09` names the SPP peer or merely the
     * active audio device has never been tested.
     */
    data class Connected(
        val name: String?,
    ) : Forget

    /** The list came back without it being asked, or not at all. */
    data object Unverifiable : Forget

    /** It answered, and the device is still listed. */
    data object StillThere : Forget
}

/**
 * `04 02` DISCONNECT — drop a device's link without forgetting it.
 *
 * ```
 * → 04 02 05 06 <6-byte address>
 * ← (nothing)
 * ```
 *
 * ⚠ **The silence IS the reply.** The link dies with the frame, so the socket carrying it
 * dies too and no Processing or Result can arrive. This is attested by its EFFECT — ACL
 * went 1 → 0 on the device it named — not by an answer, and no reply shape is known.
 *
 * ⚠⚠ **The phone does NOT come back on its own.** Measured 2026-08-28: twelve seconds at
 * ACL 0, then Android Settings → the device's gear → Connect brought it back and the BMAP
 * channel answered normally. So this is recoverable, but only by an explicit reconnect.
 *
 * ⚠ **Deliberately NOT offered on the card.** The QC45's `04 04` list holds exactly one
 * entry — the phone running this app — so the only link it can drop is the app's own, and
 * with no auto-reconnect a tap would strand the owner in Settings with no way back from
 * this screen. The frame belongs here; the button does not.
 *
 * ⚠ Block `04` again, so the same rule as [BosePairing]: block and function are FIXED
 * here. `04 03` REMOVE_DEVICE and `04 07` CLEAR_DEVICE_LIST are neighbours and a writer
 * that took either as a parameter must not exist.
 */
object BoseDisconnect {
    const val FN: Byte = 0x02

    /** ⚠ START, and the payload is the address to drop — the same shape as [BoseForget]. */
    fun frame(address: ByteArray) =
        BoseFrame.encode(BoseDevices.BLOCK, FN, BoseFrame.START, address)
}

/** `04 03` REMOVE_DEVICE — Bose Connect's "disconnect & forget". */
object BoseForget {
    /**
     * ⚠ **START, and the payload is the ADDRESS to forget.** Captured from the vendor
     * app; the Set-shaped guess would have been `04 03 02 06 <addr>`.
     */
    fun frame(address: ByteArray) =
        BoseFrame.encode(BoseDevices.BLOCK, 0x03, BoseFrame.START, address)
}

/**
 * `01 02` PRODUCT_NAME — Bose Connect's "Nickname It".
 *
 * ```
 * → 01 02 01 00                     ← 01 02 03 0a 00 "BoseTest1"
 * → 01 02 02 09 "BoseTest1"         ← 01 02 03 0a 00 "BoseTest1"
 * ```
 *
 * ⚠ **THE WRITE IS NOT THE READ'S SHAPE.** The reply carries a leading byte that is not
 * part of the name — `Drivers.Bose.name` skips it, and including it yields a name with a
 * leading NUL that `trim()` does not remove and that renders as nothing. **The write has
 * no such byte**: the payload is the UTF-8 name and nothing else. Captured from the
 * vendor app 2026-08-26 in both directions, renaming and renaming back.
 *
 * ⚠ `docs/captures.md` says "every vendor here mirrors its getter", and for the *frame*
 * that holds. For the *payload* it does not, here. A setter built by mirroring would have
 * prepended a NUL to the owner's device name — visible on every phone it pairs with, and
 * not obviously this app's fault.
 */
object BoseName {
    const val BLOCK: Byte = 0x01
    const val FN: Byte = 0x02

    fun get() = BoseFrame.encode(BLOCK, FN, BoseFrame.GET)

    /**
     * The name out of a `01 02` payload.
     *
     * ⚠ **Byte 0 is not part of the name**, as the frames above show — including it
     * yields a name with a leading NUL, which `trim()` does not remove and which renders
     * as nothing, so the bug appears as a name that silently lost its first character.
     */
    fun of(payload: ByteArray): String? {
        if (payload.size < 2) return null
        return String(payload, 1, payload.size - 1, Charsets.UTF_8)
            .trim { it <= ' ' }
            .ifBlank { null }
    }

    /**
     * ⚠ **Returns null rather than truncating.** The device's own limit is unknown —
     * Bose Connect's field stops somewhere this repo has not established — so the only
     * bound enforced is the one the protocol imposes: a length byte. Silently cutting a
     * name to fit would rename the headphones to something the owner did not type.
     */
    fun set(name: String): ByteArray? {
        val bytes = name.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > 0xff) return null
        return BoseFrame.encode(BLOCK, FN, BoseFrame.SET_GET, bytes)
    }
}

/**
 * Block `01` as **both Bose models speak it** — everything in the settings block that is
 * not ANC: the name, the voice prompts, the standby timer, self voice.
 *
 * ⚠ **This exists because these writes lived on the QC35's driver and were being called
 * with a QC45's transport.** They worked — the drivers are stateless and the frames are
 * identical — so nothing ever failed and nothing could. What was wrong was quieter: the
 * code said "QC35" about a QC45, and the QC45's own read branch had never been given
 * these settings at all, so its card simply had no rows for them. Four settings the repo
 * could already speak were absent from one device's screen for that reason alone.
 *
 * ⚠ **Sharing a block number is NOT sharing a meaning**, and this interface is the
 * exception rather than the rule — `01 05` and `01 06` are different functions on these
 * two models. What justifies it here is that each function below appears in *both*
 * devices' own `01 01` enumeration, in the same shape, and was read from each.
 */
interface BoseSettingsDriver : AncDriver {
    /**
     * ⚠ **A fresh socket answers NOTHING until the session is woken** — see
     * [Registry.wakeBose] for the measurement. This is the other path to it: a device
     * identified from its advertisement never goes through `identifyBose`, so without
     * this override it would open, read, and be told the headphones had nothing to say.
     */
    override fun prepare(t: Transport) = Registry.wakeBose(t)

    /**
     * Every setting the device has, in ONE exchange.
     *
     * ⚠ **Separate Gets would be the wrong shape here**, and not just slower: the reply
     * enumerates what this unit actually has, so a missing setting is distinguishable
     * from an unreadable one. That is how `01 07` EQ and `01 08` ALERTS — which answer
     * nothing at all to a direct ask on the QC35 — were established as absent rather than
     * merely silent. The QC45 answers the same ask with ten settings, `01 07` among them.
     */
    fun readAll(t: Transport): BoseAll? = BoseAllSettings.state(t.exchange(BoseAllSettings.get()))

    /**
     * `01 0e` — whether the noise setting persists.
     *
     * ⚠ **Read back with a separate Get**, the same rule as [writeStandby]: the SET_GET
     * echoes the resulting state, and an echo is the device repeating what it was told.
     * Driven both ways and restored on a QC45, 2026-08-28.
     */
    fun writeCncPersistence(t: Transport, on: Boolean): Confirmation<Boolean> {
        t.exchange(BoseCncPersistence.set(on))
        val after =
            BoseCncPersistence.state(t.exchange(BoseCncPersistence.get()))
                ?: return Confirmation.Unverifiable
        return if (after == on) Confirmation.Confirmed else Confirmation.Contradicted(after)
    }

    /**
     * ⚠ **Read back with a separate Get, not from the SET_GET's own echo.** An echo is
     * the device repeating what it was told; only an independent read says the value
     * stuck. Driven and restored on a QC35 2026-08-26 and on a QC45 2026-08-28.
     */
    fun writeStandby(t: Transport, minutes: Int): BoseStandby? {
        t.exchange(BoseStandbyTimer.set(minutes))
        return BoseStandbyTimer.state(
            BoseFrame.payload(t.exchange(BoseStandbyTimer.get()), 0x01, BoseStandbyTimer.FN)
                ?: return null,
        )
    }

    /**
     * Rename the headphones.
     *
     * ⚠ **Confirmed by a separate read**, because SET_GET echoes the resulting state and
     * an echo is the device repeating what it was told. Returns the name the device
     * reports afterwards, which is what the card should show — if the device trimmed or
     * refused it, that is the truth and not what was typed.
     */
    fun writeName(t: Transport, name: String): String? {
        val frame = BoseName.set(name) ?: return null
        t.exchange(frame)
        return name(t)
    }

    /**
     * Turn the prompts on or off **without touching the language**, and vice versa.
     *
     * ⚠ Both halves live in one byte and both traps are [BoseVoicePrompts.set]'s: the
     * field this call is not changing has to be carried, and a write that changes
     * anything draws no reply.
     */
    fun writeVoicePrompts(t: Transport, on: Boolean): Boolean? =
        BoseVoicePrompts.set(t, on = on)?.let { BoseVoicePrompts.enabled(it) }

    /** The other half of the same byte; the switch is carried across unchanged. */
    fun writePromptLanguage(
        t: Transport,
        language: BoseVoicePromptLanguage,
    ): BoseVoicePromptLanguage? =
        BoseVoicePrompts.set(t, language = language)?.let { BoseVoicePromptLanguage.of(it) }

    /**
     * ⚠ **The persist byte is READ, not assumed.** It has only ever been seen as `01`,
     * which is exactly the kind of constant that turns out to mean something on the next
     * device.
     */
    fun writeSelfVoice(t: Transport, level: SidetoneLevel): SidetoneLevel? {
        val current =
            BoseFrame.payload(t.exchange(BoseSidetone.get()), 0x01, BoseSidetone.FN)
                ?: return null
        val persist = current.getOrNull(0) ?: return null
        t.exchange(BoseWrites.sidetone(persist, level))
        val after =
            BoseFrame.payload(t.exchange(BoseSidetone.get()), 0x01, BoseSidetone.FN)
                ?: return null
        return BoseSidetone.level(after)
    }
}

/**
 * The vendor's table of mode names, and the byte `[2]` of a slot record indexes it.
 *
 * ⚠ **This is a DECOMPILE, checked against the wire.** Read out of Bose Music's
 * `AudioModesPrompt` `<clinit>` by tracking registers — its constructor is
 * `(BBLjava/lang/String;)`, and the two bytes are exactly the record's `[1]` and `[2]`,
 * with `[1]` zero for all 37 entries. Four of these were measured independently on a
 * QC45 before the decompile was read: Quiet `01`, Aware `02`, Commute `07`, Home `0a`.
 * They all agree, which is what makes the other 33 worth having.
 *
 * ⚠⚠ **The table is NOT alphabetical, and reasoning as if it were is how this repo got
 * it wrong once.** Commute `07` and Home `0a` are three apart while the vendor's PICKER
 * — which is sorted — shows only Focus between them; that was written up as "at least
 * one entry the picker does not show". The picker is a product-specific SUBSET presented
 * in display order, and the wire order is this one.
 *
 * ⚠ [OFFERED] is the subset Bose Music offers for the QC45, read off its own picker.
 * The other entries are decoded but unattested ON THIS PRODUCT: nothing here has seen
 * what the headphones do with a name their vendor app never sends them.
 */
enum class BosePromptName(
    val id: Int,
    val label: String,
) {
    NONE(0x00, "None"),
    QUIET(0x01, "Quiet"),
    AWARE(0x02, "Aware"),
    TRANSPARENT(0x03, "Transparent"),
    TRANSPARENCY(0x04, "Transparency"),
    MASKING(0x05, "Masking"),
    COMFORT(0x06, "Comfort"),
    COMMUTE(0x07, "Commute"),
    OUTDOOR(0x08, "Outdoor"),
    WORKOUT(0x09, "Workout"),
    HOME(0x0a, "Home"),
    WORK(0x0b, "Work"),
    MUSIC(0x0c, "Music"),
    FOCUS(0x0d, "Focus"),
    RELAX(0x0e, "Relax"),
    FLIGHT(0x0f, "Flight"),
    AIRPORT(0x10, "Airport"),
    DRIVING(0x11, "Driving"),
    TRAINING(0x12, "Training"),
    GYM(0x13, "Gym"),
    RUN(0x14, "Run"),
    WALK(0x15, "Walk"),
    HIKE(0x16, "Hike"),
    TALK(0x17, "Talk"),
    CALL(0x18, "Call"),
    WHISPER(0x19, "Whisper"),
    HEARING(0x1a, "Hearing"),
    LEARN(0x1b, "Learn"),
    PODCAST(0x1c, "Podcast"),
    AUDIOBOOK(0x1d, "Audiobook"),
    CALM(0x1e, "Calm"),
    SLEEP(0x1f, "Sleep"),
    MEDITATE(0x20, "Meditate"),
    YOGA(0x21, "Yoga"),
    IMMERSION(0x22, "Immersion"),
    STEREO(0x23, "Stereo"),
    CINEMA(0x24, "Cinema"),
    ;

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id }

        /**
         * What the QC45's own Bose Music lets you pick, in ITS order (alphabetical),
         * read off the picker on 2026-08-28.
         */
        val OFFERED = listOf(COMMUTE, FOCUS, HOME, MUSIC, OUTDOOR, RELAX, RUN, WALK, WORK, WORKOUT)
    }
}
