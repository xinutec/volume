package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTest {
    /**
     * A device with no control channel offers the media volume — but only when the
     * audio is going to it.
     *
     * ⚠ **Both halves, because either alone is a bug.** Without the active check the
     * slider moves a level belonging to whatever IS playing, which the owner cannot see
     * from this card. Without the state check a driveable pair would get a second,
     * competing volume beside its own.
     */
    @Test
    fun `only an undriveable active device owns the media volume`() {
        val here = DeviceCard("NewPie 32", "aa", DeviceState.NoControl("it said nothing"))
        // ⚠ **A failed ATTEMPT is not a device without a channel.** It may well have
        // its own volume commands, so offering the phone's is the wrong control on a
        // guess — and the retry it gets instead is the one that can actually help.
        val flaky = DeviceCard("QC45", "dd", DeviceState.Unavailable("would not connect"))
        val driveable = DeviceCard("XM4", "bb", DeviceState.Ready("XM4", emptyList(), null))
        val idle = DeviceCard("QC45", "cc", DeviceState.Idle)
        val cards = listOf(here, flaky, driveable, idle)

        assertTrue(Screen(cards, activeAddress = "aa").ownsMediaVolume(here))

        assertFalse(Screen(cards).ownsMediaVolume(here))
        assertFalse(Screen(cards, activeAddress = "dd").ownsMediaVolume(flaky))
        assertFalse(Screen(cards, activeAddress = "bb").ownsMediaVolume(driveable))
        assertFalse(Screen(cards, activeAddress = "cc").ownsMediaVolume(idle))
        // The audio is going to another card, so this one's slider would move a level
        // belonging to something the owner cannot see from here.
        assertFalse(Screen(cards, activeAddress = "cc").ownsMediaVolume(here))
    }

    /** Reconciling carries the address across, and a later one replaces it. */
    @Test
    fun `reconciling takes the active address`() {
        val s =
            Screen(emptyList(), emptiness = Emptiness.LOOKING)
                .reconciled(listOf("aa" to "One", "bb" to "Two"), Emptiness.NONE_CONNECTED, "bb")
        assertEquals("bb", s.activeAddress)

        val moved =
            s.reconciled(
                listOf("aa" to "One", "bb" to "Two"),
                Emptiness.NONE_CONNECTED,
                "aa",
            )
        assertEquals("aa", moved.activeAddress)
    }

    private val screen =
        Screen(
            listOf(
                DeviceCard("Bose QC Headphones", "E4:58:BC:3E:9D:AA", DeviceState.Idle),
                DeviceCard("JLab JBuds Sport ANC 4", "EC:9A:0C:E0:D2:96", DeviceState.Idle),
            ),
        )

    @Test
    fun `updating one card leaves the others and the order alone`() {
        val next = screen.with("EC:9A:0C:E0:D2:96", DeviceState.Busy("connecting"))
        assertEquals(
            listOf("E4:58:BC:3E:9D:AA", "EC:9A:0C:E0:D2:96"),
            next.cards.map { it.address },
        )
        assertEquals(DeviceState.Idle, next.cards[0].state)
        assertTrue(next.cards[1].state is DeviceState.Busy)
    }

    /** The device's own name replaces the bonded one without disturbing anything. */
    @Test
    fun `a rename touches one card and keeps its state`() {
        val busy = screen.with("E4:58:BC:3E:9D:AA", DeviceState.Busy("reading"))
        val named = busy.renamed("E4:58:BC:3E:9D:AA", "Example Bose QC35")
        assertEquals("Example Bose QC35", named.cards[0].name)
        assertTrue(named.cards[0].state is DeviceState.Busy)
        assertEquals("JLab JBuds Sport ANC 4", named.cards[1].name)
    }

    @Test
    fun `an address that is not on screen changes nothing`() {
        assertEquals(screen, screen.with("00:00:00:00:00:00", DeviceState.Idle))
        assertEquals(screen, screen.renamed("00:00:00:00:00:00", "nobody"))
    }

    /**
     * ⚠ The case the type exists for. A device with no read command reports a null
     * mode forever, and a UI that treats null as "still loading" spins for ever.
     */
    @Test
    fun `a device with no read command is ready, not pending`() {
        val ready = DeviceState.Ready("JLab", listOf(AncMode.ANC, AncMode.AMBIENT), mode = null)
        assertNull(ready.mode)
        assertEquals(2, ready.modes.size)
    }

    @Test
    fun `modes are offered only once we know what the device is`() {
        assertTrue(DeviceCard("x", "y", DeviceState.Idle).offer.isEmpty())
        assertTrue(DeviceCard("x", "y", DeviceState.Busy("scanning")).offer.isEmpty())
        assertTrue(DeviceCard("x", "y", DeviceState.Unavailable("off")).offer.isEmpty())
        assertEquals(
            listOf(AncMode.ANC),
            DeviceCard("x", "y", DeviceState.Ready("m", listOf(AncMode.ANC), AncMode.ANC)).offer,
        )
    }

    private val label: (AncMode) -> String = {
        if (it ==
            AncMode.ANC
        ) {
            "Noise cancelling"
        } else {
            "$it"
        }
    }

    /**
     * ⚠ **The one that matters.** An unconfirmable write must not read like a
     * confirmed one, or the JLab's "success" — which it returns for modes that do
     * not exist — gets laundered into a tick on screen.
     */
    @Test
    fun `an unverifiable result never reads like a confirmed one`() {
        val confirmed = Confirmation.Confirmed.note(AncMode.ANC, label)
        val unverifiable = Confirmation.Unverifiable.note(AncMode.ANC, label)
        assertNotEquals(confirmed, unverifiable)
        assertTrue(unverifiable!!.text.contains("cannot confirm"))
        assertEquals(NoteKind.CAUTION, unverifiable.kind)
        assertNull(Confirmation.Unverifiable.resulting(AncMode.ANC))
    }

    /**
     * ⚠ A confirmed write says nothing: the selected control already carries it,
     * and a line repeating it is noise that teaches the eye to skip the line that
     * matters. This was wrong on the first render — it printed "ANC" in the colour
     * reserved for something being off.
     */
    @Test
    fun `a confirmed write adds no note at all`() {
        assertNull(Confirmation.Confirmed.note(AncMode.ANC, label))
    }

    @Test
    fun `a contradicted write shows what the device actually says, and as a problem`() {
        val c = Confirmation.Contradicted(AncMode.AMBIENT).note(AncMode.ANC, label)!!
        assertTrue(c.text.contains("AMBIENT"))
        assertEquals(NoteKind.PROBLEM, c.kind)
        assertEquals(
            AncMode.AMBIENT,
            Confirmation.Contradicted(AncMode.AMBIENT).resulting(AncMode.ANC),
        )
    }

    /** The vendors' words come from the caller, so :protocol holds no UI copy. */
    @Test
    fun `notes use the label the caller supplies`() {
        val c = Confirmation.Contradicted(AncMode.OFF).note(AncMode.ANC, label)!!
        assertTrue(c.text.contains("Noise cancelling"))
    }

    @Test
    fun `a confirmed write settles on what was asked for`() {
        assertEquals(AncMode.ANC, Confirmation.Confirmed.resulting(AncMode.ANC))
    }

    /**
     * ⚠ Headphones come and go while the app is open, and the list must follow
     * without losing what it already knows. A rebuild would drop the mode, the
     * device-reported name and the note, then reopen every session to learn them
     * again — visibly, as five cards blinking back to "connecting".
     */
    @Test
    fun `reconciling keeps what is known about devices that are still here`() {
        val live =
            screen
                .with(
                    "E4:58:BC:3E:9D:AA",
                    DeviceState.Ready("Bose QC45", listOf(AncMode.ANC), AncMode.ANC),
                ).renamed("E4:58:BC:3E:9D:AA", "Example Bose QC45")

        val next =
            live.reconciled(
                listOf(
                    "E4:58:BC:3E:9D:AA" to "Bose QC Headphones",
                    "EC:9A:0C:E0:D2:96" to "JLab JBuds Sport ANC 4",
                ),
                Emptiness.NONE_CONNECTED,
            )

        // Untouched: still Ready, still under the name it reported for itself.
        assertEquals("Example Bose QC45", next.cards[0].name)
        assertTrue(next.cards[0].state is DeviceState.Ready)
        assertTrue(next.cards[1].state is DeviceState.Idle)
    }

    @Test
    fun `a device that has gone is dropped, and a new one arrives idle`() {
        val next =
            screen.reconciled(
                listOf("80:99:E7:F9:D0:61" to "WH-1000XM4"),
                Emptiness.NONE_CONNECTED,
            )
        assertEquals(listOf("80:99:E7:F9:D0:61"), next.cards.map { it.address })
        assertTrue(next.cards[0].state is DeviceState.Idle)
        // A populated screen must NOT carry a reason it is empty.
        assertNull(next.emptiness)
    }

    @Test
    fun `reconciling to nothing empties the list, and says why`() {
        val next = screen.reconciled(emptyList(), Emptiness.BLUETOOTH_OFF)
        assertTrue(next.cards.isEmpty())
        assertEquals(Emptiness.BLUETOOTH_OFF, next.emptiness)
    }

    /**
     * ⚠ **The defect this type replaces.** Every empty list rendered one sentence,
     * "No headphones bonded to this phone", and it was false in four of the five
     * cases — measured with thirteen devices bonded. The radio being
     * off is the one that bites, because `bondedDevices` returns an empty set then
     * rather than failing, so the honest answer and the misleading one are the same
     * value and only the caller can tell them apart.
     */
    @Test
    fun `an empty screen must say why, and the reasons are distinct`() {
        val off = Screen(emptyList(), Emptiness.BLUETOOTH_OFF)
        val quiet = Screen(emptyList(), Emptiness.NONE_CONNECTED)
        assertNotEquals(off, quiet)
        assertThrows(IllegalArgumentException::class.java) { Screen(emptyList()) }
    }

    @Test
    fun `a populated screen may not claim to be empty`() {
        assertThrows(IllegalArgumentException::class.java) {
            Screen(listOf(DeviceCard("x", "y", DeviceState.Idle)), Emptiness.NONE_BONDED)
        }
    }

    /** Updating cards leaves the (absent) reason alone — the count cannot change. */
    @Test
    fun `with and renamed preserve the invariant`() {
        val next = screen.with("E4:58:BC:3E:9D:AA", DeviceState.Busy("x"))
        assertNull(next.emptiness)
        assertNull(screen.renamed("E4:58:BC:3E:9D:AA", "n").emptiness)
    }

    /** The caller's order wins, so the list does not reshuffle as devices arrive. */
    @Test
    fun `reconciling draws them in the order given`() {
        val next =
            screen.reconciled(
                listOf(
                    "EC:9A:0C:E0:D2:96" to "JLab JBuds Sport ANC 4",
                    "E4:58:BC:3E:9D:AA" to "Bose QC Headphones",
                ),
                Emptiness.NONE_CONNECTED,
            )
        assertEquals(
            listOf("EC:9A:0C:E0:D2:96", "E4:58:BC:3E:9D:AA"),
            next.cards.map { it.address },
        )
    }

    /** A failure keeps its reason: "error" is not a thing anyone can act on. */
    @Test
    fun `unavailability carries why`() {
        val s = DeviceState.Unavailable("not advertising right now")
        assertTrue(s.why.isNotBlank())
    }

    // ---- settings ----------------------------------------------------------

    private val ready = DeviceState.Ready("Sony WH-1000XM4", listOf(AncMode.ANC), AncMode.ANC)

    private val multipointOff =
        Settings(listOf(MultipointRow(false, Writability.Refused(RefusalReason.DEVICE))), true)

    @Test
    fun `settings attach to a ready card`() {
        val next =
            screen
                .with("E4:58:BC:3E:9D:AA", ready)
                .withSettings("E4:58:BC:3E:9D:AA", multipointOff)
        assertEquals(
            false,
            next.cards
                .first()
                .settings
                ?.get<MultipointRow>()
                ?.on,
        )
    }

    /**
     * ⚠ **The regression this type was reshaped for.** Every write goes
     * `Ready → Busy → Ready`, and while `settings` lived on [DeviceState.Ready] the
     * `Busy` in the middle — which has no such field — destroyed them. On screen the
     * open settings section fell back to a "reading…" spinner that could never
     * resolve, because the read is only triggered by opening the section. Reproduced
     * on the XM4 by expanding settings and then tapping an ANC chip.
     */
    @Test
    fun `settings survive the busy state that every write passes through`() {
        val next =
            screen
                .with("E4:58:BC:3E:9D:AA", ready)
                .withSettings("E4:58:BC:3E:9D:AA", multipointOff)
                .with("E4:58:BC:3E:9D:AA", DeviceState.Busy("setting ambient…"))
                .with("E4:58:BC:3E:9D:AA", ready)
        assertEquals(
            false,
            next.cards
                .first()
                .settings
                ?.get<MultipointRow>()
                ?.on,
        )
    }

    /** And a plain refresh, which rebuilds `Ready` from scratch, keeps them too. */
    @Test
    fun `settings survive a reconcile that keeps the card`() {
        val next =
            screen
                .with("E4:58:BC:3E:9D:AA", ready)
                .withSettings(
                    "E4:58:BC:3E:9D:AA",
                    Settings(listOf(AutoOffRow(AutoOff.NEVER)), true),
                ).reconciled(
                    listOf("E4:58:BC:3E:9D:AA" to "Bose QC Headphones"),
                    Emptiness.NONE_CONNECTED,
                )
        assertEquals(
            AutoOff.NEVER,
            next.cards
                .single()
                .settings
                ?.get<AutoOffRow>()
                ?.mode,
        )
    }

    /**
     * ⚠ The read takes seconds and the device can go in that time. Landing settings
     * on a card that is no longer Ready would resurrect a dead one, fully furnished
     * with controls, over a socket that is gone.
     */
    @Test
    fun `settings do not land on a card that went away mid-read`() {
        val next =
            screen
                .with("E4:58:BC:3E:9D:AA", DeviceState.Unavailable("switched off"))
                .withSettings("E4:58:BC:3E:9D:AA", multipointOff)
        assertTrue(next.cards.first().state is DeviceState.Unavailable)
        assertNull(next.cards.first().settings)
    }

    /** Nothing read yet and nothing to draw are the same thing for a renderer. */
    @Test
    fun `empty settings have nothing to show`() {
        assertFalse(Settings.NONE.any)
        assertTrue(Settings(listOf(ToneRow(BoseBands(0, 0, 0))), true).any)
    }

    /**
     * ⚠ An action is not a reading: a card holding only a power-off button or a rename
     * has no settings section to put them in.
     */
    @Test
    fun `a card of actions alone has nothing to show`() {
        assertFalse(Settings(listOf(PowerOffRow, NameRow("Example")), true).any)
    }

    /** The card's order is the kinds', not the order the device's reads came back in. */
    @Test
    fun `rows come out in card order`() {
        val read =
            Settings(
                listOf(
                    BatteryRow(Battery(percent = 60, charging = false)),
                    PowerOffRow,
                    ToneRow(BoseBands(0, 0, 0)),
                ),
                true,
            )
        assertEquals(
            listOf(SettingKind.TONE, SettingKind.POWER_OFF, SettingKind.BATTERY),
            read.rows.map { it.kind },
        )
    }

    /** ⚠ Two rows of one kind would draw one twice and say nothing about which is true. */
    @Test(expected = IllegalArgumentException::class)
    fun `a row cannot appear twice`() {
        Settings(listOf(AutoPlayRow(true), AutoPlayRow(false)), true)
    }

    @Test
    fun `a link is open while Ready or Busy, and shut otherwise`() {
        assertTrue(DeviceState.Ready(model = "XM4", modes = emptyList(), mode = null).linkOpen)
        assertTrue(DeviceState.Busy("reading settings…").linkOpen)
        // ⚠ **Every state, not only the open ones.** #973 was an omitted arm, and a test
        // that lists what should be true cannot notice the next thing left out.
        assertFalse(DeviceState.Idle.linkOpen)
        assertFalse(DeviceState.Unavailable("switched off").linkOpen)
    }

    /**
     * ⚠ **Every kind of row is something to show, unless it is an action** — and this
     * fails when a kind is added without a sample here. `spatial` was once added to the
     * old union without reaching `any`, and nothing noticed.
     */
    @Test
    fun `every kind of row is something to show unless it is an action`() {
        val one =
            listOf(
                PresetEqRow(
                    EqSetting(preset = 0, levels = emptyList()),
                    emptyList(),
                    listOf(0),
                    emptyMap(),
                ),
                ToneRow(BoseBands(0, 0, 0)),
                CurveEqRow(EqCurve(table = JblCurveTable(0), bands = emptyList())),
                IdleTimerRow(TimedOff(on = true, minutes = 30)),
                SpatialRow(Spatial(true, SpatialMode.MUSIC), SpatialMode.entries),
                VoiceAwareRow(VoiceAware(true, VoiceLevel.MID)),
                SmartTalkRow(SmartTalk(true, TalkTimeout.SEC_5)),
                LowVolumeEqRow(true),
                SmartAvRow(SmartAv.AUDIO, SmartAv.entries),
                // ⚠ Shows on its own: a bud that reports itself worn is offered no button.
                FindBudsRow(InEar(left = false, right = false)),
                AutoPlayRow(true),
                BalanceRow(Balance(on = false, level = 100)),
                PsapRow(false),
                VoicePromptsRow(true, Writability.NoWriter),
                NameRow("Example"),
                ConnectionsRow(
                    listOf(BoseDevice(address = BoseAddress("aa bb cc dd ee ff"))),
                    pairing = null,
                ),
                CncRow(
                    CncModes(
                        modes =
                            listOf(
                                BoseCncModes.Mode(
                                    2,
                                    nameId = 10,
                                    name = "Home",
                                    level = 4,
                                    editable = true,
                                ),
                            ),
                        active = 2,
                    ),
                ),
                StandbyRow(BoseStandby(60)),
                SelfVoiceRow(SidetoneLevel.MEDIUM),
                AdvancedAncRow(AdvancedAnc(tuning = AncTuning.ADAPTIVE)),
                LeAudioRow(false),
                AuracastRow(true),
                CodecRow("LDAC"),
                PowerOffRow,
                BatteryRow(Battery(percent = 60, charging = false)),
                // ⚠ A volume alone IS worth a card: a Revolve that answers nothing else
                // still has a level worth seeing.
                LoudnessRow(BoseLoudness(steps = 100, level = 36)),
                BudBatteryRow(
                    BudBattery(
                        Battery(percent = 90, charging = null),
                        Battery(percent = 80, charging = null),
                    ),
                ),
                JLabEqRow(JLabCurve(preset = 3, levels = List(10) { 120 }), presets = null),
                // ⚠ DEFAULT, the value that means "no ceiling": a renderer treating it as
                // absent would pass with any other one.
                SafeHearingRow(JLabSafeHearing.Level.DEFAULT),
                JLabTouchRow(
                    mapOf(
                        (JLabTouch.Side.FIRST to JLabTouch.Tap.ONE_TAP) to
                            JLabTouch.Action.PLAY_PAUSE,
                    ),
                ),
                GesturesRow(mapOf(Gesture.LEFT_TAP to GestureAction.ANC_AMBIENT)),
                VolumeLimitRow(true),
                CncPersistenceRow(true),
                MultipointRow(true, Writability.Writable),
                DseeRow(true, Writability.Writable),
                PauseOnRemovalRow(true, Writability.Writable),
                SpeakToChatRow(false, Writability.Writable),
                ChatDetailRow(ChatDetail(ChatSensitivity.AUTO, false, ModeOutTime.MID)),
                TouchPanelRow(false, Writability.Writable),
                VoiceGuidanceRow(false, Writability.Writable),
                FocusOnVoiceRow(false, Writability.NotNow),
                AutoOffRow(AutoOff.NEVER),
                SoundQualityRow(SoundQuality.QUALITY),
                SonyButtonRow(SonyButton.Action.entries.first(), SonyButton.Action.entries),
            )
        val actions = setOf(SettingKind.POWER_OFF, SettingKind.NAME)
        for (row in one) {
            assertEquals("${row.kind} alone", row.kind !in actions, Settings(listOf(row), true).any)
        }
        assertEquals(
            "a kind of row was added — give it a sample here",
            SettingKind.entries.toSet(),
            one.map { it.kind }.toSet(),
        )
    }

    /**
     * ⚠ **Reported and changeable are different questions**, and this is the whole
     * reason [Writability] exists. The XM4 answers `d6 d2` and then ignores
     * `d8 d2 01 01`; the QC45 accepts both. A screen that inferred "we can set it"
     * from "it told us" would offer a switch that springs back.
     */
    @Test
    fun `a setting can be reported and still not be writable`() {
        val row = multipointOff.get<MultipointRow>()!!
        assertEquals(false, row.on)
        // ⚠ **The reason is part of the refusal.** "Not even its own app" is true of
        // multipoint and was once shown, falsely, under the button too (#965).
        assertEquals(Writability.Refused(RefusalReason.DEVICE), row.writability)
    }

    /** A confirmed settings write says nothing: the row already shows the new value. */
    @Test
    fun `a confirmed setting write is silent`() {
        assertNull(Confirmation.Confirmed.settingNote<Boolean> { "$it" })
    }

    /** ⚠ A refusal must read as a refusal, not as an unexplained value change. */
    @Test
    fun `a refused setting write names what the device still reports`() {
        val note = Confirmation.Contradicted(false).settingNote { if (it) "on" else "off" }
        assertEquals(NoteKind.PROBLEM, note?.kind)
        assertTrue(note!!.text.contains("refused"))
        assertTrue(note.text.contains("off"))
    }

    /** ⚠ And "sent, unverifiable" must never render as success. */
    @Test
    fun `an unconfirmable setting write is a caution, not silence`() {
        val note = Confirmation.Unverifiable.settingNote<Boolean> { "$it" }
        assertEquals(NoteKind.CAUTION, note?.kind)
    }

    /** A dead link must not be reported as a limitation of the headphones. */
    @Test
    fun `a read that did not answer is not a device without noise cancelling`() {
        assertEquals(NoMode.UNANSWERED, noMode(Drivers.BoseQc35, mode = null))
        assertEquals(NoMode.NO_MODES, noMode(Drivers.BoseRevolve, mode = null))
        assertNull(noMode(Drivers.BoseQc35, mode = AncMode.ANC))
    }

    /** Every declared driver is either an ANC driver with modes to offer, or not one at all. */
    @Test
    fun `every driver has noise cancelling with modes, or has none`() {
        val withAnc =
            listOf<Driver>(
                Drivers.BoseQc45,
                Drivers.BoseQc35,
                Drivers.JblBes,
                Drivers.JblLivePro2,
                Drivers.JLabQcy,
                Drivers.SonyXm4(),
            )
        for (d in withAnc) {
            val anc = d as? AncDriver
            assertNotNull("${d::class.java.simpleName} should have noise cancelling", anc)
            assertTrue("${d::class.java.simpleName} should offer modes", anc!!.modes.isNotEmpty())
        }
        val withoutAnc = listOf<Driver>(Drivers.BoseRevolve)
        for (d in withoutAnc) {
            assertFalse("${d::class.java.simpleName} has no noise cancelling", d is AncDriver)
        }

        val declared =
            Drivers::class.java.declaredClasses
                .filter { Driver::class.java.isAssignableFrom(it) }
                .map { it.simpleName }
                .toSet()
        assertEquals(
            "a driver was added — say whether it has noise cancelling",
            declared,
            (withAnc + withoutAnc).map { it::class.java.simpleName }.toSet(),
        )
    }

    /**
     * ⚠ Nobody asked, and asked-but-silent, are different sentences.
     *
     * The second was rendered as "nothing is decoded for this pair yet" on a device
     * with six decoded settings, because every read had failed at once.
     */
    @Test
    fun `settings that were asked for and came back empty are not settings nobody asked for`() {
        assertFalse(Settings.NONE.attempted)
        assertFalse(Settings.NONE.any)
        val silent = Settings(emptyList(), attempted = true)
        assertFalse(silent.any)
        assertTrue(silent.attempted)
    }
}
