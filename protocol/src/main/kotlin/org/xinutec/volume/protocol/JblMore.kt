package org.xinutec.volume.protocol

/**
 * JBL Auto Play & Pause — set `aa 35`, reported as status field `38`.
 *
 * ```
 * → aa 35 01 <on>      ← aa 00 02 35 <on>      the ack, not the answer
 * → aa 21 01 38        ← aa 22 02 38 <on>
 * ```
 *
 * ⚠ **Setter and status field do NOT mirror here**, and this row is why that is written
 * down as a habit rather than a rule. `31`, `32` and `33` all set on the field they
 * report; this one sets on `35` and reports on `38`. The claim "38 is Auto Play & Pause"
 * was published on one agreeing byte, retracted on the mirror argument, and re-argued
 * from the SDK — none of which is a measurement. **Driven both ways 2026-08-17 23:38**:
 * off and back on, each confirmed by reading `38`, which is what settles it.
 */
object JblAutoPlay {
    const val FIELD: Byte = 0x38
    const val SET: Byte = 0x35

    fun get(): OutFrame = OutFrame(byteArrayOf(Bes.HEADER, Bes.STATUS_GET, 0x01, FIELD))

    fun set(on: Boolean): OutFrame =
        OutFrame(byteArrayOf(Bes.HEADER, SET, 0x01, if (on) 0x01 else 0x00))

    fun state(reply: ByteArray): Boolean? {
        val p = Bes.status(reply, FIELD) ?: return null
        if (p.isEmpty()) return null
        return p[0] != 0x00.toByte()
    }
}

/**
 * Left / right sound balance: a switch, and where the balance sits.
 *
 * ⚠ **[level] is carried, not offered.** `64` = 100 is what this unit reports and what
 * the app draws as "0" on an L—R slider, so 100 is its centre. The range is NOT
 * established — nothing here has moved it — so a write sends back whatever was read and
 * the UI offers only the switch. The same discipline as [TimedOff.minutes].
 */
data class Balance(
    val on: Boolean,
    val level: Int,
)

/**
 * JBL left/right balance — `aa a8`, driven 2026-08-17 23:39.
 *
 * ```
 * → aa a8 01 01                    ← aa a8 05 02 01 <on> 02 <level>
 * → aa a8 05 00 01 <on> 02 <level> ← aa a8 05 02 01 <on> 02 <level>
 * ```
 *
 * ⚠ **The payload is key/value pairs, not positional** — `01` is the switch's key and
 * `02` the level's, so the byte after the operator is a KEY and reading it as the value
 * says "on" for a feature that is off. `LeftRightSoundBalanceCmd` agrees: it takes the
 * switch from index 5 and the level from index 7, never from index 4.
 *
 * ✅ Switched on at centre and off again, each read back — inaudible at level 100, which
 * is why that was the safe way to prove the writer.
 */
object JblBalance {
    const val CMD: Byte = 0xa8.toByte()

    private const val LEN: Byte = 0x05
    private const val SET: Byte = 0x00
    private const val STATUS: Byte = 0x02
    private const val ON_KEY: Byte = 0x01
    private const val LEVEL_KEY: Byte = 0x02
    private const val ON_AT = 5
    private const val LEVEL_AT = 7

    fun get(): OutFrame = OutFrame(byteArrayOf(Bes.HEADER, CMD, 0x01, 0x01))

    fun set(v: Balance): OutFrame =
        OutFrame(
            byteArrayOf(
                Bes.HEADER,
                CMD,
                LEN,
                SET,
                ON_KEY,
                if (v.on) 0x01 else 0x00,
                LEVEL_KEY,
                v.level.toByte(),
            ),
        )

    fun state(reply: ByteArray): Balance? {
        if (reply.size <= LEVEL_AT) return null
        if (reply[0] != Bes.HEADER || reply[1] != CMD) return null
        if (reply[2] != LEN || reply[3] != STATUS) return null
        if (reply[4] != ON_KEY || reply[6] != LEVEL_KEY) return null
        return Balance(on = reply[ON_AT] != 0x00.toByte(), level = reply[LEVEL_AT].toInt() and 0xff)
    }
}

/**
 * Personal Sound Amplification — **read, never written, and for the hearing reason.**
 *
 * ```
 * → aa a0 01 01     ← aa a0 07 02 01 <on> 02 <level> 03 <index>
 * ```
 *
 * ⚠ **PSAP amplifies the world into your ears.** It is not the Max Volume Limiter, but
 * it is the other control on this device whose whole job is to make things louder, so it
 * gets the same treatment: shown, never set. No writer exists here and none should
 * without Pippijn asking for one.
 *
 * ✅ **This row's published contradiction is resolved, and it was a misreading, not a
 * device fault.** The note said `PSAPCmd` takes `setOn` from index 4 — `01` in the
 * captured reply — while the app's own row said *Disabled*. `PSAPCmd` has TWO branches:
 * the other one reads `setOn` from index **5**, level from 7 and index from 9, and that
 * is the one this frame's shape selects. Index 4 is the KEY `01`, and index 5 is `00`.
 * The device and the app agreed all along.
 */
object JblPsap {
    const val CMD: Byte = 0xa0.toByte()

    private const val LEN: Byte = 0x07
    private const val STATUS: Byte = 0x02
    private const val ON_AT = 5
    private const val LEVEL_AT = 7

    fun get(): OutFrame = OutFrame(byteArrayOf(Bes.HEADER, CMD, 0x01, 0x01))

    fun state(reply: ByteArray): Boolean? {
        if (reply.size <= LEVEL_AT) return null
        if (reply[0] != Bes.HEADER || reply[1] != CMD) return null
        if (reply[2] != LEN || reply[3] != STATUS) return null
        if (reply[4] != 0x01.toByte()) return null
        return reply[ON_AT] != 0x00.toByte()
    }
}

/**
 * Switch the JBL off — `aa 97 00`, and that is the whole command.
 *
 * ```
 * → aa 97 00     ← aa 00 02 97 00     an ack, sent before the link drops
 * ```
 *
 * ⚠ **The way back is physical.** The vendor app's own dialog says "To power on again,
 * press a power button on a headphone", so this is the last thing any run can do and it
 * costs someone getting up. Driven once, 2026-08-16 23:29, by agreement.
 *
 * ⚠ **`aa 95` is two bytes away and is FACTORY RESET.** A slip in this constant would
 * wipe the gestures, the equaliser and the Personi-Fi profile, and the profile has no
 * getter to restore it from. [Hazards] refuses `aa 95` at the wire for exactly this,
 * which is what makes a `97` sitting next to it acceptable to write down at all.
 */
object JblPowerOff {
    const val CMD: Byte = 0x97.toByte()

    fun off(): OutFrame = OutFrame(byteArrayOf(Bes.HEADER, CMD, 0x00))
}

/**
 * Which way the JBL's noise cancelling decides how hard to work.
 *
 * ⚠ **Not a switch, and the SDK says so in its own constants** — `MANUAL_ADAPTIVE_ANC = 0`
 * and `TRUE_ADAPTIVE_ANC = 1` are declared fields on `AdvanceAncSettings`. Reading the byte
 * as a boolean would render "manual" as *off*, which is the one thing it is not: manual
 * means the level in [AdvancedAnc.manualLevel] is used instead of being chosen for you.
 */
enum class AncTuning(
    val wire: Int,
) {
    MANUAL(0),
    ADAPTIVE(1),
    ;

    companion object {
        fun of(wire: Int): AncTuning? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The vendor app's "Customize ANC" screen, as key/value pairs off the wire.
 *
 * ⚠ **Every field is nullable because a frame carries only the keys the device chose to
 * send.** This unit sends four of the six; a decoder with non-null defaults would report
 * `autoCompensation = 0` for a key that was never mentioned, which is a claim the frame
 * does not make.
 *
 * ⚠ **The LEVELS are numbers, not scales.** Nothing here establishes what `7` is out of —
 * the SDK declares constants for [tuning] alone, and the app's slider bounds were not
 * found. So they are carried and shown as read, the same discipline as [Balance.level].
 */
data class AdvancedAnc(
    val tuning: AncTuning? = null,
    val manualLevel: Int? = null,
    val ambientLevel: Int? = null,
    val leakageCompensation: Int? = null,
    val autoCompensation: Int? = null,
    val earCanalCompensation: Int? = null,
)

/**
 * JBL Customize ANC — `aa 91` sub-command `21` asks, `22` answers, `20` sets.
 *
 * ```
 * → aa 91 01 21   ← aa 91 09 22 01 01 04 07 05 01 a1 07
 * ```
 *
 * ⚠ **A THIRD grammar on `aa 91`.** The mode commands are `aa 91 07 10/12` with fixed
 * slots `01 <anc> 02 <ambient> 03 <talkthru>`; this one is a variable-length pair list
 * whose keys are sparse and non-consecutive. Same command byte, different shape, selected
 * by the sub-command — so the mode readers in [Drivers] cannot be pointed at it.
 *
 * ⚠ **The pair count comes from the LENGTH byte, `(len - 1) / 2`**, which is
 * `AdvancedAncCmd.parse`'s own arithmetic rather than a guess from one frame: key at
 * `i * 2 + 4`, value at `i * 2 + 5`. `09` gives four pairs, and four is what arrived.
 *
 * ⚠ **`a1` is a key, not a command byte or a level.** It is the one key outside `01`–`08`,
 * it appears last, and a reader walking fixed offsets or assuming a contiguous key space
 * drops it — losing the ambient level while looking entirely healthy.
 *
 * ⚠ **No writer.** Sub-command `20` is named and has never been sent; the levels' meaning
 * is unestablished, so a setter here would be writing numbers nobody can check.
 */
object JblAdvancedAnc {
    const val CMD: Byte = 0x91.toByte()

    /** `21` asks. ⚠ `20` SETS and is deliberately not offered — see above. */
    const val GET_SUB: Byte = 0x21
    private const val STATUS_SUB: Byte = 0x22

    private const val ADAPTIVE: Byte = 0x01
    private const val MANUAL_LEVEL: Byte = 0x04
    private const val LEAKAGE: Byte = 0x05
    private const val EAR_CANAL: Byte = 0x06
    private const val AUTO_COMP: Byte = 0x08
    private const val AMBIENT_LEVEL: Byte = 0xa1.toByte()

    fun get(): OutFrame = OutFrame(byteArrayOf(Bes.HEADER, CMD, 0x01, GET_SUB))

    fun state(reply: ByteArray): AdvancedAnc? {
        if (reply.size < 4) return null
        if (reply[0] != Bes.HEADER || reply[1] != CMD || reply[3] != STATUS_SUB) return null
        val pairs = ((reply[2].toInt() and 0xff) - 1) / 2
        if (pairs <= 0) return null
        var out = AdvancedAnc()
        for (i in 0 until pairs) {
            val at = i * 2 + 4
            // ⚠ The length byte is the vendor's, not this buffer's: a frame that claims
            // more pairs than it carries must stop, not read past the end.
            if (at + 1 >= reply.size) return null
            val v = reply[at + 1].toInt() and 0xff
            out =
                when (reply[at]) {
                    ADAPTIVE -> out.copy(tuning = AncTuning.of(v))

                    MANUAL_LEVEL -> out.copy(manualLevel = v)

                    LEAKAGE -> out.copy(leakageCompensation = v)

                    EAR_CANAL -> out.copy(earCanalCompensation = v)

                    AUTO_COMP -> out.copy(autoCompensation = v)

                    AMBIENT_LEVEL -> out.copy(ambientLevel = v)

                    // ⚠ An unknown key is skipped, not fatal: the six named here are what
                    // one firmware's parser knows, and a seventh must not blank the row.
                    else -> out
                }
        }
        return out
    }
}

/**
 * JBL Voice Prompts — the SWITCH only. `aa 93` sub-command `04` asks, `05` answers.
 *
 * ```
 * → aa 93 01 04   ← aa 93 02 05 <on>
 * ```
 *
 * ✅ **Attributed by ablation, 2026-08-25 11:47.** Toggling the vendor app's own switch
 * moved this byte `01` → `00` and back, while `aa 93 01 01` — the frame this row was
 * filed under for a week — stayed `aa 93 05 02 01 00 00 08` throughout. Two sub-commands
 * on one command byte, and the one that looked like the answer was the one that never
 * moved.
 *
 * ⚠ **NO SETTER, and not from caution alone.** The mirror rule that guessed every other
 * writer here is already broken on this device — Auto Play & Pause sets on `35` and
 * reports on `38` — and `aa 93`'s neighbouring sub-commands reach the LANGUAGE, which
 * `BesOTATask` pushes as an `OTA_LANGUAGE_TYPE` file over the DFU path. Guessing
 * sub-commands in that space is the sweep this repo forbids, on the one command family
 * where a wrong guess starts a file transfer.
 *
 * ⚠ **The length byte bounds the read, and this frame proved why**: the restore's reply
 * arrived as `aa 93 02 05 01 aa 25 0d …` — an unsolicited battery notification glued on.
 * A reader that scanned to the end of the buffer would take `aa` as the payload.
 */
object JblVoicePrompts {
    const val CMD: Byte = 0x93.toByte()

    private const val GET_SUB: Byte = 0x04
    private const val STATUS_SUB: Byte = 0x05
    private const val LEN: Byte = 0x02

    fun get(): OutFrame = OutFrame(byteArrayOf(Bes.HEADER, CMD, 0x01, GET_SUB))

    fun state(reply: ByteArray): Boolean? {
        if (reply.size < 5) return null
        if (reply[0] != Bes.HEADER || reply[1] != CMD) return null
        if (reply[2] != LEN || reply[3] != STATUS_SUB) return null
        return reply[4] != 0x00.toByte()
    }
}
