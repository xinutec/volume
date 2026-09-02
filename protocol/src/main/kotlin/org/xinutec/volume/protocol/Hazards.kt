package org.xinutec.volume.protocol

/**
 * Why a payload was refused, in the owner's terms.
 *
 * ⚠ [what] names the command, [why] names the consequence. Both, because "refused
 * `aa 95`" tells nobody anything and "this erases the pairing" without the bytes cannot
 * be checked against the docs.
 */
data class Refusal(
    val what: String,
    val why: String,
)

/**
 * The payloads this repo will not send unless it is told to, twice.
 *
 * ⚠ **This is a boundary check, not a driver check, and that is the point.** The probe
 * exists to send arbitrary bytes — hand-typed, at a terminal, at speed. Every safety
 * argument in this repo so far has lived in whichever caller happened to be careful:
 * `probe.sh` validates its hex, `JblGestures.offerable` hides the volume actions from
 * the UI. Neither helps someone who calls `am start-foreground-service` directly, which
 * is what a session actually does when it is chasing something.
 *
 * ⚠ **A deny-list is a floor, never a ceiling.** It knows the four destructive commands
 * that have been *found*; the BES table has hundreds and most are unread. Refusing to
 * sweep remains the rule, and this does not license one.
 *
 * ⚠ **Nothing here is silent.** A refusal returns a [Refusal] to be shown and the send
 * does not happen; it is never downgraded to a no-op or a warning that scrolls past.
 * Overriding is deliberate and per-call — see the probe's `force` extra.
 */
object Hazards {
    /**
     * `aa 95` — BES factory reset.
     *
     * ⚠ **This is why the BES protocol is never swept.** A sweep walks command bytes,
     * and `95` is in the middle of the range; the JBL would come back unpaired, with its
     * gestures, EQ and Personi-Fi profile gone. The profile has no getter, so that one is
     * not recoverable by reading it back first.
     */
    private const val BES_FACTORY_RESET: Byte = 0x95.toByte()

    /**
     * Bose `04 07` — CLEAR_DEVICE_LIST.
     *
     * ⚠ Block `04` is where the pairing list lives, and a Bose frame is
     * `<block> <fn> <operator> <len>`. A writer that took block and function as
     * parameters would be one typo from this, which is why none exists.
     */
    private const val BOSE_DEVICE_BLOCK: Byte = 0x04
    private const val BOSE_CLEAR_LIST: Byte = 0x07

    /**
     * Bose `04 03` — REMOVE_DEVICE, added 2026-08-26.
     *
     * ⚠ **`04 07` was guarded and this was not**, for the whole time both were known.
     * It unpairs **one** device rather than all of them, which sounds milder and is
     * not: the argument is a BD_ADDR, and the address most easily to hand is the
     * phone's own — `04 04` LIST_DEVICES hands it back, and `04 05` INFO already takes
     * it as a parameter. So the natural way to write a "forget this device" button is
     * also the way to unpair the device the app is talking over.
     *
     * ⚠ Found while asking whether the vendor app's **Connections** screen could be
     * built here. It could — and the first thing that screen wants is a remove button.
     * Guarding it before anything is built is the point; a refusal added after a
     * writer exists is a refusal added after the mistake is reachable.
     */
    private const val BOSE_REMOVE_DEVICE: Byte = 0x03

    /**
     * BMAP operators run `00` SET to `07` PROCESSING (`docs/protocols.md`). Anything
     * above that is not an operator, so the frame is not BMAP and its byte 3 is not a
     * length — see [boseLength].
     */
    private const val BOSE_MAX_OPERATOR: Int = 0x07

    /**
     * Sony table-2 `38` PERI_SET_PARAM, whose `ConnectivityActionType` is
     * `00 DISCONNECT · 01 CONNECT · 02 UNPAIR`.
     *
     * ⚠ **`02` is the hazard, and it is a PARAMETER rather than a command** — so the
     * command byte alone cannot tell you whether a frame is safe. This is exactly the
     * shape that makes sweeping the `30`–`3d` block unacceptable: the destructive value
     * is reached by varying an argument, not by walking the table.
     */
    private const val SONY_PERI_SET: Byte = 0x38
    private const val SONY_UNPAIR: Byte = 0x02

    /**
     * `aa a2` — the EQ curve, and the ONE BES frame whose length byte is not its payload
     * length: it undercounts by one. Exempt from [besLength] for that reason.
     */
    private const val BES_EQ_CURVE: Byte = 0xa2.toByte()

    /** Sony `a8` — the PLAYBACK_CONTROLLER setter, which reaches the volume. */
    private const val SONY_PLAYBACK_SET: Byte = 0xa8.toByte()

    /**
     * The ONE door raw bytes pass to become an [OutFrame].
     *
     * ⚠ [Transport] refuses bare bytes at compile time, so a hand-typed payload — the
     * probe tool's stock in trade — must come through here, and this runs [check] on the
     * way. What used to be a call somebody remembered to make is now the only path that
     * exists. Refusing is the default; [force] is the probe's per-call override, and an
     * admission it forced still carries the refusal so the caller can print what was
     * overridden rather than sending in silence.
     */
    fun admit(
        uuid: String?,
        payload: ByteArray,
        table: SonyTable,
        protocol: Channels.Protocol? = null,
        force: Boolean = false,
    ): Admission {
        val r = check(uuid, payload, table, protocol)
        return when {
            r == null -> Admission.Admitted(OutFrame(payload), overrode = null)
            force -> Admission.Admitted(OutFrame(payload), overrode = r)
            else -> Admission.Refused(r)
        }
    }

    /**
     * Inspect a payload bound for [uuid], or for GATT when that is null.
     *
     * [table] names which of the Sony's two command tables applies, because `38` means
     * different things on each and a payload byte cannot say which was meant.
     * ⚠ **No default**: `table2: Boolean = false` let a forgotten argument silently
     * select the table where the peripheral-unpair refusal does not fire — fail-open
     * by omission. The caller says, every time.
     *
     * ⚠ The fall-through is BES, where the factory reset lives — GATT and anything
     * unrecognised land there. Guessing WIDE is the safe direction: the cost of a false
     * refusal is one `force`, the cost of a miss is a wiped device.
     */
    fun check(
        uuid: String?,
        payload: ByteArray,
        table: SonyTable,
        protocol: Channels.Protocol? = null,
    ): Refusal? =
        when {
            payload.isEmpty() -> null
            uuid.equals(Channels.SONY, ignoreCase = true) -> sony(payload, table)
            uuid.equals(Channels.SPP, ignoreCase = true) -> bose(payload, protocol)
            else -> bes(payload)
        }

    private fun sony(payload: ByteArray, table: SonyTable): Refusal? {
        val table2 = table == SonyTable.TABLE_2
        if (table2 && payload[0] == SONY_PERI_SET && payload.getOrNull(2) == SONY_UNPAIR) {
            return Refusal(
                "Sony PERI_SET_PARAM with ConnectivityActionType 02",
                "this UNPAIRS the headphones; they would have to be re-paired by hand",
            )
        }
        if (!table2 && payload[0] == SONY_PLAYBACK_SET) {
            return Refusal(
                "Sony PLAYBACK_SET_PARAM (a8)",
                "this block reaches the VOLUME — never send it to headphones being worn",
            )
        }
        return null
    }

    private fun bose(payload: ByteArray, protocol: Channels.Protocol?): Refusal? {
        if (payload[0] != BOSE_DEVICE_BLOCK) return boseLength(payload, protocol)
        return when (payload.getOrNull(1)) {
            BOSE_CLEAR_LIST -> {
                Refusal(
                    "Bose 04 07 CLEAR_DEVICE_LIST",
                    "this erases the pairing list, including this phone",
                )
            }

            BOSE_REMOVE_DEVICE -> {
                Refusal(
                    "Bose 04 03 REMOVE_DEVICE",
                    "this unpairs the device it names, and the address most easily to " +
                        "hand is this phone's own",
                )
            }

            else -> {
                boseLength(payload, protocol)
            }
        }
    }

    /**
     * `<block> <fn> <operator> <len>` — refuse a BMAP frame whose length byte disagrees
     * with what it carries. The twin of [besLength], and the mistake it exists for is a
     * value typed into the length byte: `01 04 02 14` is operator `02` declaring a
     * 20-byte payload and carrying none. It looks like a plausible four-byte frame, so
     * nothing downstream can catch it — the device re-frames it and answers `04 01 01`
     * bad-argument, which reads as a fact about the protocol rather than a typo.
     *
     * ⚠⚠ **A UUID DOES NOT DETERMINE THE PROTOCOL HERE, so this needs [protocol].** The
     * JLab JBuds is routed over the same SPP channel as the QC45 and QC35, and its
     * ordinary ANC read `c0 ff 00 44 00 00 01 00 04` has `00` in byte 2 — a valid BMAP
     * operator — and `0x44` in byte 3. Read as BMAP it declares 68 payload bytes and
     * carries 5, so a uuid-keyed version of this rule REFUSES A WORKING DEVICE'S MAIN
     * READ. The shell guard in `probe.sh` does exactly that; it was harmless only
     * because it was wired to one subcommand out of four.
     *
     * ⚠ So this fires on positive evidence and nothing else — **wide for a hazard,
     * narrow for a syntax rule**. An unbonded or unidentified device keeps the old
     * behaviour, which is a deliberate hole: refusing frames on a device nobody could
     * identify would break the probing this tool exists for.
     *
     * ⚠ **A byte that is not a valid operator means this is not a BMAP frame at all**,
     * so byte 3 is not a length and must not be read as one.
     */
    private fun boseLength(payload: ByteArray, protocol: Channels.Protocol?): Refusal? {
        if (protocol != Channels.Protocol.BOSE) return null
        val operator = payload.getOrNull(2)?.toInt()?.and(0xff) ?: return null
        if (operator > BOSE_MAX_OPERATOR) return null
        val declared =
            payload.getOrNull(3)?.toInt()?.and(0xff)
                ?: return Refusal(
                    "Bose frame with no length byte (${payload.size} bytes)",
                    "a <block> <fn> <operator> <len> header is FOUR bytes, so the device " +
                        "re-frames this into a command nobody typed — check the hex",
                )
        val actual = payload.size - 4
        if (declared == actual) return null
        return Refusal(
            "Bose frame with a wrong length byte (says $declared, carries $actual)",
            "the device parses by the length byte, so this sends a DIFFERENT frame " +
                "than the one meant — check the hex before forcing it",
        )
    }

    private fun bes(payload: ByteArray): Refusal? {
        if (payload[0] != Bes.HEADER) return null
        if (payload.getOrNull(1) == BES_FACTORY_RESET) {
            return Refusal(
                "BES aa 95 factory reset",
                "this wipes gestures, EQ and the Personi-Fi hearing profile — and the " +
                    "profile has no getter, so it cannot be read back first",
            )
        }
        return besGesture(payload) ?: besLength(payload)
    }

    /**
     * `aa <cmd> <len> <payload…>` — refuse a frame whose length byte disagrees with what
     * it carries.
     *
     * ⚠ **This is not a hazard, it is a MISTYPING, and that is why it belongs here
     * anyway**: it is caught at the same place, before the socket opens, so every caller
     * gets it. A wrong length byte does not fail loudly — the device parses by that byte,
     * so it reads a *different* frame than the one intended and answers plausibly. Three
     * frames were mistyped by hand in one session on 2026-08-28; a length check would
     * have caught the ones that changed the payload size.
     *
     * ⚠ **Only frames that actually look like BES are checked.** The caller's fall-through
     * is BES for anything unrecognised, and applying a validity rule that widely would
     * refuse ordinary GATT probing — which is the probe's whole job. Guessing wide is
     * right for a hazard and wrong for a syntax rule.
     *
     * ⚠ **`aa a2` is exempt**: its length byte undercounts its content by one. That is
     * documented in `docs/protocols.md` and is the reason [Bes.frame] cannot skip past
     * one. Enforcing the invariant on it would refuse the EQ curve this app reads on
     * every card open.
     */
    private fun besLength(payload: ByteArray): Refusal? {
        if (payload.size < 3) return null
        if (payload[1] == BES_EQ_CURVE) return null
        val declared = payload[2].toInt() and 0xff
        val actual = payload.size - 3
        if (declared == actual) return null
        return Refusal(
            "BES frame with a wrong length byte " +
                "(says $declared, carries $actual)",
            "the device parses by the length byte, so this sends a DIFFERENT frame " +
                "than the one meant — check the hex before forcing it",
        )
    }

    /**
     * A gesture write that binds a button to a volume change.
     *
     * ⚠ **Derived from [GestureAction.volume], never hand-listed.** Three actions change
     * the volume and the third — `0x56` VOLUME_CONTROL — sits far outside the tidy
     * `01`/`02` pair and was invisible until the vendor app's own array was read. A list
     * written out here would say two, and be wrong in exactly the way that matters.
     */
    private fun besGesture(payload: ByteArray): Refusal? {
        if (payload.getOrNull(1) != JblGestures.CMD) return null
        // ⚠ `aa 77 03 <SET> <gesture> <action>` — the action is the SIXTH byte. Reading
        // index 4 gets the GESTURE, which silently matched nothing and let all three
        // volume bindings through; the test that named every action caught it.
        val action = payload.getOrNull(5) ?: return null
        val named = GestureAction.entries.firstOrNull { it.wire == action } ?: return null
        return if (named.volume) {
            Refusal(
                "JBL gesture bound to ${named.label}",
                "a button that changes the volume is not offered by this app",
            )
        } else {
            null
        }
    }
}

/** What [Hazards.admit] decided. A refusal carries its reason; a forced pass carries it too. */
sealed interface Admission {
    data class Admitted(
        val frame: OutFrame,
        /** The refusal [Hazards.admit] was told to override, for printing — or null. */
        val overrode: Refusal?,
    ) : Admission

    data class Refused(
        val refusal: Refusal,
    ) : Admission
}
