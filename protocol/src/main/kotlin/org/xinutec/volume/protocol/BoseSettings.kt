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

    fun encode(block: Byte, fn: Byte, operator: Byte, payload: ByteArray = ByteArray(0)) =
        byteArrayOf(block, fn, operator, payload.size.toByte()) + payload

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
