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
    val standby: BoseStandby? = null,
    val sidetone: SidetoneLevel? = null,
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
                out = out.copy(voicePrompts = BoseVoicePrompts.enabled(it))
            }
            BoseFrame.payload(f, BLOCK, BoseStandbyTimer.FN)?.let {
                out = out.copy(standby = BoseStandbyTimer.state(it))
            }
            BoseFrame.payload(f, BLOCK, BoseSidetone.FN)?.let {
                out = out.copy(sidetone = BoseSidetone.level(it))
            }
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

    fun get() = BoseFrame.encode(BoseAllSettings.BLOCK, FN, BoseFrame.GET)

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
}
