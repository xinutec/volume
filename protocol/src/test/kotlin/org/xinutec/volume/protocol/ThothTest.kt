package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The thoth contract, against what the server actually said.
 *
 * Every JSON body below was read off the live service on 2026-09-04 with `curl`, and
 * the refusal text is the one it returned to an over-ceiling request. ⚠ **The
 * substitution is confined to device identifiers** — the two speakers' Bluetooth
 * addresses, the microphone's serial, and the two opaque UUIDs — because this
 * repository is public and those name hardware that is not its subject. Field names,
 * key order, number formatting and the refusal wording are verbatim, which is what
 * these tests are for.
 */
class ThothTest {
    private val pairJson =
        """{"active":true,"balance":0,"ceiling":0.65,""" +
            """"left":"aa-bb-cc-dd-ee-f1:output","right":"aa-bb-cc-dd-ee-f2:output",""" +
            """"stereo":false,"volume":0.5199999809265137}"""

    private val devicesJson =
        """{"devices":[""" +
            """{"aggregate":false,"name":"LG TV SSCR2",""" +
            """"uid":"00000000-0000-0000-0000-000000000001"},""" +
            """{"aggregate":false,"name":"Khonsu R","uid":"aa-bb-cc-dd-ee-f2:output"},""" +
            """{"aggregate":false,"name":"Khonsu L","uid":"aa-bb-cc-dd-ee-f1:output"},""" +
            """{"aggregate":false,"name":"Mac mini Speakers","uid":"BuiltInSpeakerDevice"},""" +
            """{"aggregate":true,"name":"Thoth Pair",""" +
            """"uid":"org.xinutec.thoth.agg.00000000-0000-0000-0000-000000000002"}]}"""

    private val micUid = "AppleUSBAudioEngine:Generic:USB Condenser Microphone:000000000001:1"

    private val inputJson =
        """{"current":"$micUid",""" +
            """"devices":[{"name":"USB Condenser Microphone","uid":"$micUid"}],""" +
            """"pinned":"$micUid"}"""

    private val picadesJson =
        """{"cabinets":[""" +
            """{"ceiling":255,"host":"picade0","raw":0,"status":"online","volume":0},""" +
            """{"ceiling":255,"host":"picade1","raw":76,""" +
            """"status":"online","volume":0.29803921568627451},""" +
            """{"ceiling":255,"host":"picade2","raw":171,""" +
            """"status":"online","volume":0.6705882352941176},""" +
            """{"host":"picade3","status":"offline"},""" +
            """{"host":"picade4","status":"offline"}]}"""

    /** The `ceiling` field, for the tests that take it away again. */
    private val ceilingField = """"ceiling":0.65,"""

    /** Verbatim from a `POST /api/picades` the server refused on 2026-09-04. */
    private val refusalText =
        "volume 0.9 exceeds the ceiling of 0.65 — thoth will not set a level that loud (see #787)"

    // ---- parsing -----------------------------------------------------------

    @Test
    fun `the live pair state parses`() {
        val p = ThothWire.pair(pairJson)
        assertEquals("aa-bb-cc-dd-ee-f1:output", p.left)
        assertEquals("aa-bb-cc-dd-ee-f2:output", p.right)
        assertFalse(p.stereo)
        assertTrue(p.active)
        assertEquals(0.0, p.balance, 1e-9)
        assertEquals(0.52, p.volume, 1e-6)
        assertEquals(0.65, p.ceiling!!, 1e-9)
        assertEquals(52, p.volumePercent)
        assertEquals(0, p.balancePercent)
    }

    @Test
    fun `a server that publishes no ceiling parses, with none`() {
        val p = ThothWire.pair(pairJson.replace(ceilingField, ""))
        assertNull(p.ceiling)
    }

    @Test
    fun `the live device list parses, and the group is not a candidate`() {
        val all = ThothWire.devices(devicesJson)
        assertEquals(5, all.size)
        assertEquals(1, all.count { it.aggregate })
        assertEquals(
            listOf("LG TV SSCR2", "Khonsu R", "Khonsu L", "Mac mini Speakers"),
            all.speakers().map { it.name },
        )
    }

    @Test
    fun `the live input state parses`() {
        val i = ThothWire.input(inputJson)
        assertEquals(1, i.devices.size)
        assertEquals("USB Condenser Microphone", i.devices[0].name)
        assertEquals(micUid, i.current)
        assertEquals(micUid, i.pinned)
    }

    @Test
    fun `the live cabinet list parses, statuses and all`() {
        val c = ThothWire.cabinets(picadesJson)
        assertEquals(5, c.size)
        assertEquals(
            listOf("picade0", "picade1", "picade2", "picade3", "picade4"),
            c.map { it.host },
        )
        assertEquals(3, c.count { it.status == CabinetStatus.ONLINE })
        assertEquals(2, c.count { it.status == CabinetStatus.OFFLINE })
        assertEquals(30, c[1].percent)
        assertEquals(67, c[2].percent)
        assertEquals(255, c[1].steps)
        assertEquals(76, c[1].raw)
    }

    /**
     * ⚠ The `has`-vs-`optInt` trap, from the live data: picade0 is online at raw 0.
     * Read with `optInt` that is indistinguishable from a cabinet that reported no
     * level at all, and the reading would have been thrown away.
     */
    @Test
    fun `a cabinet silent at zero is a reading, not a missing one`() {
        val c = ThothWire.cabinets(picadesJson)[0]
        assertEquals(0, c.raw)
        assertEquals(0.0, c.volume!!, 1e-9)
        assertEquals(0, c.percent)
        assertTrue(c.reachable)
        assertNull(c.note)
    }

    @Test
    fun `an offline cabinet has no level and says why`() {
        val c = ThothWire.cabinets(picadesJson)[3]
        assertNull(c.volume)
        assertNull(c.raw)
        assertNull(c.steps)
        assertFalse(c.reachable)
        assertEquals("off", c.note)
    }

    @Test
    fun `a cabinet with no control yet is settable and sits at unity`() {
        val c = one("""{"host":"picade0","status":"no-control"}""")
        assertTrue(c.reachable)
        assertEquals(100, c.percent)
        assertEquals("silent since boot — set a level to fix one", c.note)
    }

    @Test
    fun `an unverified cabinet is surfaced, not folded into off`() {
        val c = one("""{"host":"picade0","status":"unverified"}""")
        assertFalse(c.reachable)
        assertEquals("host key not known", c.note)
    }

    /** A status this app has never seen must not read as "off". */
    @Test
    fun `an unknown status says so`() {
        val c = one("""{"host":"picade0","status":"rebooting"}""")
        assertEquals(CabinetStatus.UNKNOWN, c.status)
        assertFalse(c.reachable)
        assertEquals("unrecognised status", c.note)
    }

    private fun one(json: String) = ThothWire.cabinet(json)

    // ---- the volume bound --------------------------------------------------

    @Test
    fun `the published ceiling is what bounds the control`() {
        val v = ThothWire.pair(pairJson).volumeControl()
        assertEquals(65, v.maxPercent)
        assertFalse(v.over)
        assertEquals("ceiling 65% — thoth refuses louder", v.why)
    }

    /**
     * ⚠ The bound is 80, not 65. Clamping to the ceiling would make volume-UP turn
     * the speakers DOWN by fifteen points — and putting a level back where it
     * already was is not raising it.
     */
    @Test
    fun `a level already above the ceiling can only come down`() {
        val loud = ThothWire.pair(pairJson.replace("0.5199999809265137", "0.8"))
        val v = loud.volumeControl()
        assertEquals(80, loud.volumePercent)
        assertEquals(80, v.maxPercent)
        assertTrue(v.over)
        assertEquals("already above the 65% ceiling — this can only come down", v.why)
    }

    /**
     * ⚠ The fallback is not "no bound" and not a copy of 0.65: it is the level the
     * pair is at, so an unknown server can be turned down and never up.
     */
    @Test
    fun `no published ceiling bounds the control at where it already is`() {
        val p = ThothWire.pair(pairJson.replace(ceilingField, ""))
        val v = p.volumeControl()
        assertEquals(52, v.maxPercent)
        assertFalse(v.over)
        assertEquals("this thoth publishes no ceiling, so the level can only come down", v.why)
    }

    @Test
    fun `the cabinets are bounded by the same ceiling as the speakers`() {
        val screen = live()
        // picade1 sits under the ceiling, so the ceiling is what bounds it.
        assertEquals(65, screen.boundFor(screen.cabinets[1]).maxPercent)
        assertFalse(screen.boundFor(screen.cabinets[1]).over)
        // picade2 is already at 67, above it — that cabinet can only come down.
        assertEquals(67, screen.boundFor(screen.cabinets[2]).maxPercent)
        assertTrue(screen.boundFor(screen.cabinets[2]).over)
    }

    /**
     * ⚠ The ordinary bound is NOT repeated per cabinet. It is one number for the whole
     * server, stated once under the pair's volume; saying it again on every row would
     * bury the two rows whose bound is genuinely different.
     */
    @Test
    fun `only an unusual bound puts a sentence under a cabinet`() {
        val screen = live()
        // picade1 is under the ceiling: the ceiling is already stated elsewhere.
        assertNull(screen.noteFor(screen.cabinets[1]))
        // picade2 is over it, and a control that will not go up has to say why.
        assertEquals(
            "already above the 65% ceiling — this can only come down",
            screen.noteFor(screen.cabinets[2]),
        )
        // A status is a fact about the cabinet and outranks the bound.
        assertEquals("off", screen.noteFor(screen.cabinets[3]))
    }

    @Test
    fun `with no ceiling published every cabinet says why it cannot go up`() {
        val screen = live().copy(pair = ThothWire.pair(pairJson.replace(ceilingField, "")))
        assertEquals(
            "this thoth publishes no ceiling, so the level can only come down",
            screen.noteFor(screen.cabinets[1]),
        )
    }

    @Test
    fun `a cabinet read without a pair to bound it can only come down`() {
        val c = ThothWire.cabinets(picadesJson)
        val screen = ThothScreen("h", ThothReach.LIVE, null, emptyList(), null, c)
        assertEquals(67, screen.boundFor(c[2]).maxPercent)
    }

    // ---- what a pick has to do ---------------------------------------------

    @Test
    fun `picking an input while pinned moves the pin`() {
        assertEquals(InputPick.REPIN, ThothWire.input(inputJson).pickIs())
    }

    @Test
    fun `picking an input with no pin standing just switches it`() {
        // Escaped rather than raw: a raw literal ending in four quotes gives the
        // LAST three to the terminator, so `""""pinned":""""` is `"pinned":"` — an
        // unterminated key that fails as a JSON error rather than as a wrong answer.
        val unpinned =
            ThothWire.input(
                inputJson.replace("\"pinned\":\"$micUid\"", "\"pinned\":\"\""),
            )
        assertEquals(InputPick.SET, unpinned.pickIs())
    }

    // ---- request bodies ----------------------------------------------------

    @Test
    fun `a patch sends only the fields it sets`() {
        assertEquals("""{"volume":0.4}""", ThothWire.body(PairPatch(volume = 0.4)))
        assertEquals("""{"stereo":true}""", ThothWire.body(PairPatch(stereo = true)))
        assertEquals("{}", ThothWire.body(PairPatch()))
        assertTrue(PairPatch().empty)
        assertFalse(PairPatch(left = "x").empty)
    }

    @Test
    fun `merging a drag keeps the last value of each field`() {
        val merged =
            PairPatch(
                volume = 0.3,
            ).and(PairPatch(balance = 0.1)).and(PairPatch(volume = 0.4))
        assertEquals(0.4, merged.volume!!, 1e-9)
        assertEquals(0.1, merged.balance!!, 1e-9)
        assertNull(merged.stereo)
    }

    @Test
    fun `an input body carries the uid, and an empty one clears the pin`() {
        assertEquals("""{"uid":"x"}""", ThothWire.uid("x"))
        assertEquals("""{"uid":""}""", ThothWire.uid(""))
    }

    // ---- the client --------------------------------------------------------

    @Test
    fun `a refusal reaches the caller in the server's own words`() {
        val client = ThothClient(Fake(ThothReply(400, refusalText)))
        val e = runCatching { client.setPair(PairPatch(volume = 0.9)) }.exceptionOrNull()
        assertTrue(e is ThothRefused)
        assertEquals(400, (e as ThothRefused).status)
        assertEquals(refusalText, e.reason)
    }

    @Test
    fun `an empty error body still names the status`() {
        val client = ThothClient(Fake(ThothReply(500, "  ")))
        val e = runCatching { client.pair() }.exceptionOrNull()
        assertEquals("HTTP 500", (e as ThothRefused).reason)
    }

    /** Unreachable is not refused, and the client must not turn one into the other. */
    @Test
    fun `a dead host surfaces as the transport's own failure`() {
        val client =
            ThothClient(
                object : ThothTransport {
                    override fun send(method: String, path: String, body: String?) =
                        throw IOException("no route")
                },
            )
        assertTrue(runCatching { client.pair() }.exceptionOrNull() is IOException)
    }

    @Test
    fun `the client asks the paths the server serves`() {
        val fake = Fake(ThothReply(200, pairJson))
        ThothClient(fake).recalibrate()
        assertEquals("POST", fake.method)
        assertEquals("/api/pair/recalibrate", fake.path)
        assertEquals("{}", fake.body)
    }

    @Test
    fun `a cabinet write names the host and the level`() {
        val fake =
            Fake(
                ThothReply(
                    200,
                    """{"host":"picade1","status":"online","volume":0.3,"raw":76,"ceiling":255}""",
                ),
            )
        val c = ThothClient(fake).setCabinet("picade1", 0.3)
        assertEquals("/api/picades", fake.path)
        assertTrue(fake.body!!.contains(""""host":"picade1""""))
        assertEquals("picade1", c.host)
        assertEquals(30, c.percent)
    }

    private class Fake(
        private val reply: ThothReply,
    ) : ThothTransport {
        var method: String? = null
        var path: String? = null
        var body: String? = null

        override fun send(method: String, path: String, body: String?): ThothReply {
            this.method = method
            this.path = path
            this.body = body
            return reply
        }
    }

    // ---- the screen --------------------------------------------------------

    private fun live() =
        ThothScreen(
            host = THOTH_DEFAULT_HOST,
            reach = ThothReach.LIVE,
            pair = ThothWire.pair(pairJson),
            outputs = ThothWire.devices(devicesJson).speakers(),
            input = ThothWire.input(inputJson),
            cabinets = ThothWire.cabinets(picadesJson),
        )

    @Test
    fun `a live screen has controls to draw`() {
        assertNull(live().trouble)
        assertFalse(live().blank)
    }

    @Test
    fun `the sentences name where it looked`() {
        assertEquals("Looking for the Mac at h…", ThothScreen.looking("h").trouble)
        assertEquals("Not reachable at h — off this network?", ThothScreen.away("h").trouble)
    }

    /** Reached, but the pair read failed on its own — not a card with nothing in it. */
    @Test
    fun `live with no pair is still trouble`() {
        val s = live().copy(pair = null)
        assertEquals(
            "Reached ${THOTH_DEFAULT_HOST}, but it did not describe its speakers",
            s.trouble,
        )
        assertTrue(s.blank)
    }

    // ---- odds and ends -----------------------------------------------------

    @Test
    fun `balance is the two controls summed and clamped`() {
        assertEquals(0.0, balanceOf(0, 0), 1e-9)
        assertEquals(0.12, balanceOf(10, 2), 1e-9)
        assertEquals(-0.12, balanceOf(-10, -2), 1e-9)
        assertEquals(1.0, balanceOf(100, 20), 1e-9)
        assertEquals(-1.0, balanceOf(-100, -20), 1e-9)
    }

    @Test
    fun `a typed host reaches the same place however it is typed`() {
        val want = "http://192.168.1.81:8089"
        assertEquals(want, thothOrigin("192.168.1.81:8089"))
        assertEquals(want, thothOrigin("http://192.168.1.81:8089"))
        assertEquals(want, thothOrigin("http://192.168.1.81:8089/"))
        assertEquals(want, thothOrigin("  192.168.1.81:8089  "))
        assertEquals(want, thothOrigin("192.168.1.81"))
        assertEquals(want, thothOrigin(""))
        assertEquals("http://mac.local:8089", thothOrigin("mac.local"))
    }
}
