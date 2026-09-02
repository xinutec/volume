package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.xinutec.volume.protocol.Channels.Protocol

/**
 * ⚠ **Half of these assert that something is ALLOWED, deliberately.** A guard that
 * refuses everything passes every "it refuses X" test ever written, and this repo has
 * already been caught by a suite whose passing cases were all negatives — a grammar
 * matching nothing satisfied all of them. The reads below are the ones this app sends
 * constantly; if any of them starts being refused, the guard has become a wall.
 */
class HazardsTest {
    private fun bytes(hex: String) =
        hex
            .replace(" ", "")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    /**
     * ⚠ **A malformed length byte is a DIFFERENT failure from a hazard**, and it is the
     * one a hand-typed frame actually hits. `aa <cmd> <len>` declares its own payload
     * length, so a frame whose bytes disagree with it was mistyped or hand-built wrong —
     * and the device parses by that byte, so it reads a different frame than was meant.
     */
    @Test
    fun `a BES frame whose length byte disagrees with its payload is refused`() {
        // says 1 payload byte, carries 2
        val r = Hazards.check(null, bytes("aa a1 01 01 02"), SonyTable.TABLE_1)
        assertNotNull("a wrong length byte must not reach the wire", r)
        assertEquals(true, r!!.why.contains("length"))
        // says 4, carries 1
        assertNotNull(Hazards.check(null, bytes("aa 9b 04 01"), SonyTable.TABLE_1))
    }

    /**
     * ⚠ **The frames this app actually sends must keep passing.** These are read
     * straight from the objects that build them rather than typed here, because a
     * fixture typed by hand is exactly the thing the guard is meant to catch — and
     * three of this session's bugs were mistyped hex.
     */
    @Test
    fun `every getter this repo builds satisfies the BES length invariant`() {
        val getters =
            listOf(
                "JblAutoOff" to JblAutoOff.get(),
                "JblEq" to JblEq.get(),
                "JblSafeSound" to JblSafeSound.get(),
                "JblSpatial" to JblSpatial.get(),
                "JblVoiceAware" to JblVoiceAware.get(),
                "JblSmartTalk" to JblSmartTalk.get(),
                "JblLowVolumeEq" to JblLowVolumeEq.get(),
                "JblBattery" to JblBattery.get(),
                "JblPsap" to JblPsap.get(),
                "JblGestures" to JblGestures.get(),
            )
        for ((name, frame) in getters) {
            assertNull(
                "$name must not be refused",
                Hazards.check(null, frame.bytes, SonyTable.TABLE_1),
            )
        }
    }

    /**
     * ⚠ **`aa a2` is a KNOWN exception and must stay allowed.** Its length byte
     * undercounts its content by one — documented in `docs/protocols.md`, and the reason
     * `Bes.frame` cannot skip past one. Enforcing the invariant on it would refuse the
     * EQ curve, which this app reads on every card open.
     */
    @Test
    fun `the aa a2 curve is exempt from the length invariant`() {
        assertNull(Hazards.check(null, bytes("aa a2 02 01 01"), SonyTable.TABLE_1))
        // the reply shape, one byte longer than its length byte claims
        assertNull(Hazards.check(null, bytes("aa a2 03 02 01 01 00"), SonyTable.TABLE_1))
    }

    @Test
    fun `a frame that is not BES at all is left alone`() {
        // Bose BMAP over SPP has no aa header and its own framing; the invariant
        // must not be applied to it, nor to arbitrary GATT probing.
        assertNull(Hazards.check(Channels.SPP, bytes("01 06 01 00"), SonyTable.TABLE_1))
        assertNull(Hazards.check(null, bytes("01 02 03"), SonyTable.TABLE_1))
    }

    @Test
    fun `the BES factory reset is refused`() {
        val r = Hazards.check(null, bytes("aa 95 00"), SonyTable.TABLE_1)
        assertNotNull("aa 95 must never go out by accident", r)
        assertEquals(true, r!!.why.contains("wipes"))
    }

    @Test
    fun `Bose CLEAR_DEVICE_LIST is refused`() {
        assertNotNull(Hazards.check(Channels.SPP, bytes("04 07 02 00"), SonyTable.TABLE_1))
        // ⚠ Whatever the operator. `04 01 05` turned out to mean "this is a Start
        // transaction, ask again with 05" rather than "this is a Set" — which is an
        // invitation to try `05` on a function that answered it, and 04 07 did.
        assertNotNull(Hazards.check(Channels.SPP, bytes("04 07 05 00"), SonyTable.TABLE_1))
        assertNotNull(Hazards.check(Channels.SPP, bytes("04 07 01 00"), SonyTable.TABLE_1))
    }

    /**
     * ⚠ **REMOVE_DEVICE unpairs ONE device, which is not the milder case it sounds
     * like.** Its argument is a BD_ADDR, and the address most easily to hand is this
     * phone's own — `04 04` LIST_DEVICES returns it and `04 05` INFO already takes it
     * as a parameter. So the obvious way to build a "forget this device" button is also
     * the way to unpair the link the app is talking over.
     */
    @Test
    fun `Bose REMOVE_DEVICE is refused, and its harmless neighbours are not`() {
        assertNotNull(
            Hazards.check(Channels.SPP, bytes("04 03 02 06 aa bb cc dd ee ff"), SonyTable.TABLE_1),
        )
        // The reads either side of it stay usable — a guard that refused the whole
        // block would take the paired list with it.
        assertNull(Hazards.check(Channels.SPP, bytes("04 04 01 00"), SonyTable.TABLE_1))
        assertNull(
            Hazards.check(Channels.SPP, bytes("04 05 01 06 aa bb cc dd ee ff"), SonyTable.TABLE_1),
        )
        // ⚠ And nothing outside block 04 is touched by the block check.
        assertNull(Hazards.check(Channels.SPP, bytes("01 03 02 01 21"), SonyTable.TABLE_1))
    }

    /** ⚠ The hazard is the PARAMETER, not the command — `38` alone is not it. */
    @Test
    fun `Sony unpair is refused, and the same command with another action is not`() {
        assertNotNull(Hazards.check(Channels.SONY, bytes("38 01 02"), SonyTable.TABLE_2))
        assertNull(Hazards.check(Channels.SONY, bytes("38 01 01"), SonyTable.TABLE_2))
        assertNull(Hazards.check(Channels.SONY, bytes("38 01 00"), SonyTable.TABLE_2))
    }

    /**
     * ⚠ **`38` is PERI_SET_PARAM only on table 2.** On table 1 the same byte is
     * something else entirely, so refusing it there would be a guess dressed as safety.
     */
    @Test
    fun `the Sony unpair check does not fire on table one`() {
        assertNull(Hazards.check(Channels.SONY, bytes("38 01 02"), SonyTable.TABLE_1))
    }

    /**
     * ⚠ All three volume actions, and the third is the point: `0x56` VOLUME_CONTROL sits
     * outside the `01`/`02` pair and a hand-written list said two.
     */
    @Test
    fun `every gesture action that changes the volume is refused`() {
        val volume = GestureAction.entries.filter { it.volume }
        assertEquals(3, volume.size)
        for (a in volume) {
            val frame = JblGestures.set(Gesture.LEFT_TAP, a)
            assertNotNull(
                "${a.label} binds a volume change",
                Hazards.check(null, frame.bytes, SonyTable.TABLE_1),
            )
        }
    }

    @Test
    fun `a gesture action that does not touch the volume is allowed`() {
        for (a in GestureAction.entries.filterNot { it.volume }) {
            val frame = JblGestures.set(Gesture.LEFT_TAP, a)
            assertNull(
                "${a.label} is ordinary",
                Hazards.check(null, frame.bytes, SonyTable.TABLE_1),
            )
        }
    }

    /** The reads this app makes on every settings load must stay allowed. */
    @Test
    fun `ordinary traffic is not refused`() {
        assertNull(Hazards.check(Channels.SONY, SonyEq.get(), SonyTable.TABLE_1))
        assertNull(
            Hazards.check(
                Channels.SONY,
                SonyEq.setLevels(listOf(0, 0, 0, 0, 0, 0)),
                SonyTable.TABLE_1,
            ),
        )
        assertNull(Hazards.check(Channels.SONY, SonyBattery.get(), SonyTable.TABLE_1))
        assertNull(Hazards.check(Channels.SONY, SonyVoiceGuidance.set(true), SonyTable.TABLE_2))
        assertNull(Hazards.check(Channels.SPP, BoseEq.get().bytes, SonyTable.TABLE_1))
        assertNull(Hazards.check(null, JblGestures.get().bytes, SonyTable.TABLE_1))
        assertNull(Hazards.check(null, JblPowerOff.off().bytes, SonyTable.TABLE_1))
    }

    /**
     * ⚠ **The two bytes that must never be confused**, and they are two apart. Switching
     * the JBL off costs a walk to the headphones; `aa 95` costs the gestures, the
     * equaliser and a hearing profile with no getter. This asserts the guard separates
     * them rather than refusing the neighbourhood.
     */
    @Test
    fun `power off is allowed and the factory reset beside it is not`() {
        assertNull(Hazards.check(null, bytes("aa 97 00"), SonyTable.TABLE_1))
        assertNotNull(Hazards.check(null, bytes("aa 95 00"), SonyTable.TABLE_1))
    }

    @Test
    fun `an empty payload is not a hazard`() {
        assertNull(Hazards.check(Channels.SONY, ByteArray(0), SonyTable.TABLE_1))
    }

    /**
     * ⚠ A frame too short to carry the dangerous parameter must not be refused on the
     * command byte alone — that would make `38` unusable for the reads it also serves.
     */
    @Test
    fun `a truncated frame is judged on what it actually contains`() {
        assertNull(Hazards.check(Channels.SONY, bytes("38"), SonyTable.TABLE_2))
        assertNull(Hazards.check(Channels.SONY, bytes("38 01"), SonyTable.TABLE_2))
    }

    /**
     * `<block> <fn> <operator> <len>` — the BMAP twin of the BES length rule, and the
     * mistake it exists for is `01 04 02 14`: a value typed into the LENGTH byte. It
     * looks like a plausible four-byte frame, so nothing downstream can catch it; the
     * device re-frames it and answers `04 01 01` bad-argument, which reads as a fact
     * about the protocol rather than a typo.
     */
    @Test
    fun `a Bose frame whose length byte disagrees with its payload is refused`() {
        val r =
            Hazards.check(
                Channels.SPP,
                bytes("01 04 02 14"),
                SonyTable.TABLE_1,
                protocol = Protocol.BOSE,
            )
        assertNotNull(r)
        assertEquals(
            "Bose frame with a wrong length byte (says 20, carries 0)",
            r!!.what,
        )
    }

    /**
     * The 2026-08-26 incident: `06 01 00` was sent seven times, meant as `06 01 01 00`.
     * Three bytes is not a BMAP frame at all — there is no length byte to disagree with
     * — and the device re-framed the STREAM into commands nobody typed.
     */
    @Test
    fun `a Bose frame with no length byte at all is refused`() {
        assertNotNull(
            Hazards.check(
                Channels.SPP,
                bytes("06 01 00"),
                SonyTable.TABLE_1,
                protocol = Protocol.BOSE,
            ),
        )
    }

    /**
     * ⚠⚠ **THE REGRESSION THIS RULE EXISTS TO NOT CAUSE.** The JLab JBuds is routed over
     * the SAME SPP uuid as the QC45 and QC35 ([Registry]), and its ordinary ANC read is
     * `c0 ff 00 44 …` — byte 2 is `00`, a valid BMAP operator, and byte 3 is `0x44`, so a
     * BMAP length rule reads it as declaring 68 payload bytes while carrying 5.
     *
     * The shell guard this replaced DID refuse it, which was harmless only because it was
     * wired to one subcommand. **A uuid does not determine a protocol here**, so the rule
     * fires on positive protocol evidence and nothing else: wide for a hazard, narrow for
     * a syntax rule.
     */
    @Test
    fun `the JLab's own read is not refused on the shared SPP uuid`() {
        val jlab = bytes("c0 ff 00 44 00 00 01 00 04")
        assertNull(Hazards.check(Channels.SPP, jlab, SonyTable.TABLE_1, protocol = Protocol.NONE))
        assertNull(
            "an unidentified device must not be guessed at",
            Hazards.check(Channels.SPP, jlab, SonyTable.TABLE_1),
        )
    }

    /**
     * ⚠ Without positive evidence the rule does NOT fire, so an unbonded or unidentified
     * device behaves exactly as before. That is a deliberate hole: refusing frames on a
     * device nobody could identify would break the probing this tool exists for.
     */
    @Test
    fun `an unidentified device keeps the old behaviour`() {
        assertNull(Hazards.check(Channels.SPP, bytes("01 04 02 14"), SonyTable.TABLE_1))
    }

    /**
     * The other half: the reads this app sends on every card open must survive the rule.
     */
    @Test
    fun `ordinary Bose frames are allowed under the length rule`() {
        assertNull(
            Hazards.check(
                Channels.SPP,
                bytes("01 06 01 00"),
                SonyTable.TABLE_1,
                protocol = Protocol.BOSE,
            ),
        )
        assertNull(
            Hazards.check(
                Channels.SPP,
                bytes("00 01 01 00"),
                SonyTable.TABLE_1,
                protocol = Protocol.BOSE,
            ),
        )
        assertNull(
            Hazards.check(
                Channels.SPP,
                bytes("01 03 02 01 21"),
                SonyTable.TABLE_1,
                protocol = Protocol.BOSE,
            ),
        )
        assertNull(
            Hazards.check(
                Channels.SPP,
                BoseEq.get().bytes,
                SonyTable.TABLE_1,
                protocol = Protocol.BOSE,
            ),
        )
        assertNull(
            Hazards.check(
                Channels.SPP,
                bytes("04 05 01 06 aa bb cc dd ee ff"),
                SonyTable.TABLE_1,
                protocol = Protocol.BOSE,
            ),
        )
    }

    /**
     * ⚠ A byte that is not a valid operator means this is not a BMAP frame being typed,
     * so the length byte is not a length and must not be read as one.
     */
    @Test
    fun `a payload whose third byte is no operator is left alone`() {
        assertNull(
            Hazards.check(
                Channels.SPP,
                bytes("01 06 09 14"),
                SonyTable.TABLE_1,
                protocol = Protocol.BOSE,
            ),
        )
    }
}
