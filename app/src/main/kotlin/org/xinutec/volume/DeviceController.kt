package org.xinutec.volume

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import org.xinutec.volume.protocol.AdvancedAncRow
import org.xinutec.volume.protocol.AncDriver
import org.xinutec.volume.protocol.AncMode
import org.xinutec.volume.protocol.AuracastRow
import org.xinutec.volume.protocol.AutoOff
import org.xinutec.volume.protocol.AutoOffRow
import org.xinutec.volume.protocol.AutoPlayRow
import org.xinutec.volume.protocol.Balance
import org.xinutec.volume.protocol.BalanceRow
import org.xinutec.volume.protocol.BatteryRow
import org.xinutec.volume.protocol.BoseAddress
import org.xinutec.volume.protocol.BoseAll
import org.xinutec.volume.protocol.BoseBands
import org.xinutec.volume.protocol.BoseBattery
import org.xinutec.volume.protocol.BoseButton
import org.xinutec.volume.protocol.BoseButtonRow
import org.xinutec.volume.protocol.BosePromptName
import org.xinutec.volume.protocol.BoseSettingsDriver
import org.xinutec.volume.protocol.BoseStandby
import org.xinutec.volume.protocol.BoseVoicePromptLanguage
import org.xinutec.volume.protocol.BoseVoicePrompts
import org.xinutec.volume.protocol.Bud
import org.xinutec.volume.protocol.BudBatteryRow
import org.xinutec.volume.protocol.ButtonWrite
import org.xinutec.volume.protocol.ChatDetail
import org.xinutec.volume.protocol.ChatDetailRow
import org.xinutec.volume.protocol.CncModes
import org.xinutec.volume.protocol.CncPersistenceRow
import org.xinutec.volume.protocol.CncRow
import org.xinutec.volume.protocol.CodecRow
import org.xinutec.volume.protocol.Confirmation
import org.xinutec.volume.protocol.ConnectionsRow
import org.xinutec.volume.protocol.CurveEqRow
import org.xinutec.volume.protocol.DeviceState
import org.xinutec.volume.protocol.Drivers
import org.xinutec.volume.protocol.DseeRow
import org.xinutec.volume.protocol.Emptiness
import org.xinutec.volume.protocol.EqCurve
import org.xinutec.volume.protocol.EqDriver
import org.xinutec.volume.protocol.EqSetting
import org.xinutec.volume.protocol.FindBudsRow
import org.xinutec.volume.protocol.FocusOnVoiceRow
import org.xinutec.volume.protocol.Forget
import org.xinutec.volume.protocol.Gesture
import org.xinutec.volume.protocol.GestureAction
import org.xinutec.volume.protocol.GesturesRow
import org.xinutec.volume.protocol.IdleTimerRow
import org.xinutec.volume.protocol.JLabCurve
import org.xinutec.volume.protocol.JLabEqRow
import org.xinutec.volume.protocol.JLabSafeHearing
import org.xinutec.volume.protocol.JLabTouchRow
import org.xinutec.volume.protocol.JblEqPreset
import org.xinutec.volume.protocol.JblFeature
import org.xinutec.volume.protocol.JblSharedSettings
import org.xinutec.volume.protocol.LeAudioRow
import org.xinutec.volume.protocol.LoudnessRow
import org.xinutec.volume.protocol.LowVolumeEqRow
import org.xinutec.volume.protocol.MultipointDriver
import org.xinutec.volume.protocol.MultipointRow
import org.xinutec.volume.protocol.NameRow
import org.xinutec.volume.protocol.NoMode
import org.xinutec.volume.protocol.Note
import org.xinutec.volume.protocol.NoteKind
import org.xinutec.volume.protocol.PauseOnRemovalRow
import org.xinutec.volume.protocol.PowerOffRow
import org.xinutec.volume.protocol.PresetEqRow
import org.xinutec.volume.protocol.PsapRow
import org.xinutec.volume.protocol.RefusalReason
import org.xinutec.volume.protocol.Registry
import org.xinutec.volume.protocol.SafeHearingRow
import org.xinutec.volume.protocol.Screen
import org.xinutec.volume.protocol.SelfVoiceRow
import org.xinutec.volume.protocol.Setting
import org.xinutec.volume.protocol.Settings
import org.xinutec.volume.protocol.SidetoneLevel
import org.xinutec.volume.protocol.SmartAv
import org.xinutec.volume.protocol.SmartAvDriver
import org.xinutec.volume.protocol.SmartAvRow
import org.xinutec.volume.protocol.SmartTalk
import org.xinutec.volume.protocol.SmartTalkRow
import org.xinutec.volume.protocol.SonyButton
import org.xinutec.volume.protocol.SonyButtonRow
import org.xinutec.volume.protocol.SonyDsee
import org.xinutec.volume.protocol.SonyEqPresets
import org.xinutec.volume.protocol.SonyPauseOnRemoval
import org.xinutec.volume.protocol.SonySpeakToChat
import org.xinutec.volume.protocol.SonySwitch
import org.xinutec.volume.protocol.SonyTouchPanel
import org.xinutec.volume.protocol.SoundQuality
import org.xinutec.volume.protocol.SoundQualityRow
import org.xinutec.volume.protocol.Spatial
import org.xinutec.volume.protocol.SpatialDriver
import org.xinutec.volume.protocol.SpatialMode
import org.xinutec.volume.protocol.SpatialRow
import org.xinutec.volume.protocol.SpeakToChatRow
import org.xinutec.volume.protocol.StandbyRow
import org.xinutec.volume.protocol.TimedOff
import org.xinutec.volume.protocol.ToneRow
import org.xinutec.volume.protocol.TouchPanelRow
import org.xinutec.volume.protocol.VoiceAware
import org.xinutec.volume.protocol.VoiceAwareRow
import org.xinutec.volume.protocol.VoiceGuidanceRow
import org.xinutec.volume.protocol.VoicePromptsRow
import org.xinutec.volume.protocol.VolumeLimitRow
import org.xinutec.volume.protocol.Writability
import org.xinutec.volume.protocol.noMode
import org.xinutec.volume.protocol.note
import org.xinutec.volume.protocol.resulting
import org.xinutec.volume.protocol.set
import org.xinutec.volume.protocol.setEq
import org.xinutec.volume.protocol.setMultipoint
import org.xinutec.volume.protocol.settingNote

/**
 * Tag for the one thing about this app that cannot be established off-device:
 * whether the screen follows the radio, and **which** broadcast makes it do so.
 *
 * Kept rather than deleted after the question was answered. The ACL and profile
 * events race, the winner depends on the pair and on how the link came up, and a
 * reasoned answer about which one arrived first is exactly the kind that was wrong
 * before. `adb logcat -s VolumeLive` prints the chain; `scripts/watch-list.sh`
 * reads it from the outside.
 */
internal const val LIVE = "VolumeLive"

/**
 * The screen's hands: everything that blocks, off the main thread.
 *
 * **Connecting is slow and unevenly so.** An RFCOMM open is about a second; the
 * JBL needs an LE scan first and can take twenty-five, because its address rotates
 * and it advertises in bursts. So nothing here is done eagerly on load — a device
 * is opened when its owner asks for it, and the wait is shown rather than hidden.
 *
 * One background thread, not a pool: two connects at once contend for the same
 * radio, and the failures that produces look like protocol faults.
 */
class DeviceController(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val onScreen: (Screen) -> Unit,
) : SettingActions {
    // ⚠ NOT this object's sessions. The tile and the widget drive the same
    // headphones from the same process, and a second control channel to a device
    // that already has one simply fails — so the process owns them, not the screen.
    private val work = Sessions.work
    private var screen = Screen(emptyList(), Emptiness.LOOKING)

    /**
     * The tile changed a mode; re-read that one card.
     *
     * Re-reads rather than trusting what the tile reported, because the tile's word
     * for it went through a [org.xinutec.volume.protocol.Confirmation] that may have
     * been `Unverifiable` — and copying that across would launder an unconfirmed
     * write into a selected chip. Cheap: the session is already open.
     */
    private val watcher: (String) -> Unit = { address ->
        work.execute { Sessions.existing(address)?.let { describe(address, it) } }
    }

    init {
        Sessions.watch(watcher)
    }

    /**
     * The headphones that are actually here, opened as soon as they are listed.
     *
     * ⚠ **Connected, not merely bonded.** Nothing here can be driven over a link
     * that does not exist, and an earlier version listed every paired device —
     * including a speaker in another room, with a Connect button that could only
     * fail slowly.
     *
     * Opening is automatic because a device that is connected and supported has
     * nothing to decide: its owner opened the app to change a mode, and making them
     * tap Connect first was one slow device (the JBL, whose LE scan can take 25 s)
     * setting the policy for the other four, which take about a second.
     */
    fun refresh() =
        work.execute {
            val adapter = adapter ?: return@execute emit(Screen(emptyList(), Emptiness.NO_ADAPTER))
            // ⚠ Before touching `bondedDevices`, which returns an EMPTY SET rather
            // than failing while the radio is off — the one case where the honest
            // answer and the most misleading one are the same value.
            if (!adapter.isEnabled) {
                return@execute emit(Screen(emptyList(), Emptiness.BLUETOOTH_OFF))
            }
            val bonded =
                try {
                    adapter.bondedDevices.orEmpty()
                } catch (expected: SecurityException) {
                    emit(Screen(emptyList(), Emptiness.NOT_PERMITTED))
                    return@execute
                }
            val here = Connected.addresses(context, adapter)
            val listed =
                bonded
                    .filter { it.address in here && drivable(it) }
                    .sortedBy { it.name ?: it.address }
            // Narrowest true statement about why the list came out empty.
            val whenEmpty =
                when {
                    bonded.isEmpty() -> Emptiness.NONE_BONDED
                    here.isEmpty() -> Emptiness.NONE_CONNECTED
                    else -> Emptiness.NONE_DRIVABLE
                }
            Log.i(
                LIVE,
                "refresh: bonded=${bonded.size} connected=${here.size} listed=${listed.size}",
            )
            // ⚠ Reconcile, do not rebuild: this runs again every time anything
            // connects or disconnects, and a rebuild would blink every card back to
            // "connecting" and re-read what it already knew.
            emit(
                screen.reconciled(
                    listed.map { it.address to (it.name ?: "(unnamed)") },
                    whenEmpty,
                    // ⚠ Recomputed every refresh rather than cached: the active output
                    // moves when anything connects, and a card offering a volume that
                    // belongs to another device is worse than offering none.
                    Active.address(context),
                ),
            )
            // Anything that went away keeps no socket open.
            Sessions.held().filterNot { a -> listed.any { it.address == a } }.forEach(::drop)
            // Sequentially, on this one thread: two connects at once contend for the
            // radio, and what that produces looks like a protocol fault.
            listed.forEach { holding(it.address) { openIfNeeded(it.address) } }
        }

    /**
     * A device came or went. Re-reads the whole list rather than trusting the
     * broadcast's extra: the ACL events arrive for devices this app does not care
     * about, and a profile can connect a moment after the ACL does, so asking again
     * is both simpler and more accurate than patching one entry.
     */
    fun onLinkChanged(address: String, dropSession: Boolean = true) {
        if (dropSession) drop(address)
        refresh()
    }

    /**
     * Whether to list a device at all.
     *
     * Deliberately generous: a bonded device with SPP might be a renamed Bose, and
     * only asking it settles that. Better to offer a Connect that reports "it
     * answered neither way" than to hide the pair its owner is holding.
     */
    private fun drivable(d: BluetoothDevice): Boolean {
        val uuids =
            d.uuids
                ?.map { it.uuid.toString() }
                ?.toSet()
                .orEmpty()
        return Registry.drivable(d.name.orEmpty(), uuids, d.bluetoothClass?.deviceClass ?: 0)
    }

    fun connect(address: String) = work.execute { holding(address) { openIfNeeded(address) } }

    /** Bracket the lease; the bookkeeping and its traps live in [Sessions]. */
    private fun <T> holding(address: String, body: () -> T): T = Sessions.holding(address, body)

    fun set(address: String, mode: AncMode) =
        work.execute {
            holding(address) { drive(address, mode) }
        }

    private fun drive(address: String, mode: AncMode) {
        val s = openIfNeeded(address) ?: return
        val anc = s.can<AncDriver>() ?: return
        val was = (card(address)?.state as? DeviceState.Ready)?.mode
        update(address, DeviceState.Busy("setting ${mode.name.lowercase()}…"))
        val result = runCatching { anc.set(s.transport, mode) }
        result.onFailure {
            if (it is IllegalArgumentException) {
                update(address, DeviceState.Ready(s.headphones.model, s.offered, was, refused(it)))
                return
            }
            drop(address)
            update(address, DeviceState.Unavailable("lost the connection: ${it.message}"))
        }
        val c = result.getOrNull() ?: return
        update(
            address,
            DeviceState.Ready(
                s.headphones.model,
                s.offered,
                c.resulting(mode),
                c.note(mode, ::label),
            ),
        )
    }

    /**
     * Ask a device for everything it has beyond ANC.
     *
     * ⚠ **On demand, not on connect.** The XM4 answers six separate round trips and
     * takes about three seconds; doing this while listing devices would hold every
     * card behind the slowest one, for settings most openings of the app do not want.
     *
     * **Reads only.** Nothing here writes, so it is safe to run against a pair
     * somebody is wearing — which is also why it is the thing the screen does first.
     */
    override fun loadSettings(address: String) =
        work.execute {
            holding(address) {
                val s = openIfNeeded(address) ?: return@holding
                // ⚠ **Kept, not re-read.** `openIfNeeded` has just described this card,
                // and describing it again after the read below asks the device for its
                // mode and its name a second time — on the QC45 both are functions the
                // settings read in between already returns. Restoring the state from a
                // moment ago costs nothing; asking again cost two round trips and could
                // not have learned anything the mode table did not. #1191.
                val ready = screen.cards.firstOrNull { it.address == address }?.state
                update(address, DeviceState.Busy("reading settings…"))
                val settings = runCatching { readSettings(s) }.getOrNull()
                if (settings == null) {
                    drop(address)
                    update(address, DeviceState.Unavailable("lost the connection while reading"))
                    return@holding
                }
                // Put the card back to Ready before attaching, or `withSettings`
                // finds a Busy card and drops the read on the floor. ⚠ Falls back to
                // asking when there was no Ready state to keep — an unread card, which
                // is the one case where the values are not already known.
                if (ready is DeviceState.Ready) update(address, ready) else describe(address, s)
                emit(screen.withSettings(address, settings))
            }
        }

    /**
     * Every row this device answers for, read in one pass.
     *
     * ⚠ **The XM4's multipoint refusal is measured, not guessed.** It acks `d8 d2 01 01`
     * and then ignores it, and Sony's own app fails identically — [RefusalReason.DEVICE].
     * The QC45 accepts the same write from this code.
     *
     * Each branch reads into locals in a fixed order before building rows, so the
     * exchanges on the wire are in the order the device has always been asked.
     */
    private fun readSettings(s: Session): Settings {
        val t = s.transport
        val rows: List<Setting?> =
            when (val d = s.headphones.driver) {
                is Drivers.SonyXm4 -> {
                    // ONE read for the value and whether it may move — see
                    // [Drivers.SonyXm4.readFocus].
                    val focus = d.readFocus(t)
                    val eq = d.readEq(t)
                    val bands = d.bands(t)
                    val multipoint = d.readMultipoint(t)
                    val autoOff = d.readAutoOff(t)
                    val quality = d.readSoundQuality(t)
                    val button = d.readButton(t)
                    val buttonOptions = d.buttonPresets(t)
                    val battery = d.readBattery(t)
                    val dsee = d.readSwitch(t, SonyDsee)
                    val pause = d.readSwitch(t, SonyPauseOnRemoval)
                    val chat = d.readSwitch(t, SonySpeakToChat)
                    val touch = d.readSwitch(t, SonyTouchPanel)
                    val guidance = d.readVoiceGuidance(t)
                    val codec = d.readCodec(t)
                    val chatDetail = d.readChatDetail(t)
                    val presets = d.readEqPresets(t).ifEmpty { SonyEqPresets.SEEN }
                    val w = Writability.Writable
                    listOf(
                        eq?.let { PresetEqRow(it, bands, presets, SonyEqPresets.NAMES) },
                        multipoint?.let {
                            MultipointRow(it, Writability.Refused(RefusalReason.DEVICE))
                        },
                        autoOff?.let(::AutoOffRow),
                        quality?.let(::SoundQualityRow),
                        // ⚠ The button is writable: it sat refused for eight days because
                        // this app never subscribed to the alert the write waits on. #965.
                        button?.let { SonyButtonRow(it, buttonOptions) },
                        battery?.let { BatteryRow(it) },
                        dsee?.let { DseeRow(it, w) },
                        pause?.let { PauseOnRemovalRow(it, w) },
                        chat?.let { SpeakToChatRow(it, w) },
                        touch?.let { TouchPanelRow(it, w) },
                        guidance?.let { VoiceGuidanceRow(it, w) },
                        codec?.let(::CodecRow),
                        PowerOffRow,
                        chatDetail?.let(::ChatDetailRow),
                        focus.on?.let {
                            FocusOnVoiceRow(it, if (focus.settable) w else Writability.NotNow)
                        },
                    )
                }

                // The SoundLink Revolve — a speaker, so no ANC row and no chips.
                //
                // ⚠ **Every read here was measured on this unit**, not inherited from the
                // QC35: `01 04` standby, `01 02` name, `02 02` battery, `02 05` charger and
                // `05 05` volume all answered. `readAll` is deliberately NOT used —
                // `01 01` was never driven on this unit.
                Drivers.BoseRevolve -> {
                    val battery = BoseBattery.state(t.exchange(BoseBattery.get()))
                    // `01 03` read DIRECTLY: the Revolve answers it byte-identically to
                    // the QC35, so the same decoder applies.
                    val prompts = BoseVoicePrompts.read(t)
                    val standby = Drivers.BoseRevolve.readStandby(t)
                    val name = Drivers.BoseRevolve.name(t)
                    // ⚠ The charger bit rides on [Battery.charging], which the QC35 leaves
                    // null because it does not answer `02 05` at all.
                    val charging = battery?.let { Drivers.BoseRevolve.readCharging(t) }
                    val volume = Drivers.BoseRevolve.readVolume(t)
                    listOf(
                        standby?.let(::StandbyRow),
                        prompts?.let { p ->
                            BoseVoicePrompts.enabled(p)?.let {
                                VoicePromptsRow(
                                    it,
                                    Writability.Writable,
                                    BoseVoicePromptLanguage.of(p),
                                    BoseVoicePrompts.supported(p),
                                )
                            }
                        },
                        NameRow(name),
                        battery?.let { BatteryRow(it.copy(charging = charging)) },
                        volume?.let(::LoudnessRow),
                    )
                }

                // ⚠ Not `d.` — matching an `object` does not smart-cast, so this names
                // it again rather than going through the `Driver` it is typed as.
                Drivers.BoseQc35 -> {
                    // ⚠ ONE exchange, not one per setting — `01 01` GET_ALL is also the
                    // device's own enumeration of what it has.
                    val all = Drivers.BoseQc35.readAll(t)
                    val devices = Drivers.BoseQc35.readDevices(t)
                    val pairing = Drivers.BoseQc35.readPairing(t)
                    // A second exchange: battery is block 02, and GET_ALL covers only
                    // the block it is asked about.
                    val battery = BoseBattery.state(t.exchange(BoseBattery.get()))
                    listOf(
                        all?.standby?.let(::StandbyRow),
                        all?.sidetone?.let(::SelfVoiceRow),
                        all?.let(::bosePrompts),
                        NameRow(all?.name),
                        if (devices.isNotEmpty() || pairing != null) {
                            ConnectionsRow(devices, pairing)
                        } else {
                            null
                        },
                        battery?.let { BatteryRow(it) },
                    )
                }

                Drivers.BoseQc45 -> {
                    // **ONE exchange for the whole of block 01** — the reply already
                    // carries the tone, the button and multipoint. The three payloads were
                    // compared byte-for-byte against their individual reads first: `01 05`
                    // and `01 06` mean different things on these two models.
                    val all = Drivers.BoseQc45.readAll(t)
                    // The device's own named modes, which AncMode cannot express — see
                    // CncModes. Read every time: the button on the headphones moves it.
                    val cnc = Drivers.BoseQc45.readModes(t)
                    listOf(
                        all?.tone?.let(::ToneRow),
                        all?.multipoint?.let { MultipointRow(it, Writability.Writable) },
                        all?.cncPersistence?.let(::CncPersistenceRow),
                        all?.button?.let(::BoseButtonRow),
                        cnc?.let(::CncRow),
                        all?.standby?.let(::StandbyRow),
                        all?.sidetone?.let(::SelfVoiceRow),
                        all?.let(::bosePrompts),
                        NameRow(all?.name),
                    )
                }

                Drivers.JblBes -> {
                    // ⚠ ONE exchange carries both the charge and whether the cups agreed;
                    // two calls would attach an agreement to a percentage from another frame.
                    val charge = Drivers.JblBes.readCharge(t)
                    val curve = Drivers.JblBes.readCurve(t)
                    val timer = Drivers.JblBes.readAutoOff(t)
                    val limit = Drivers.JblBes.readVolumeLimit(t)
                    val spatial = Drivers.JblBes.readSpatial(t)
                    val voiceAware = Drivers.JblBes.readVoiceAware(t)
                    val smartTalk = Drivers.JblBes.readSmartTalk(t)
                    val lowVolumeEq = Drivers.JblBes.readLowVolumeEq(t)
                    val smartAv = Drivers.JblBes.readSmartAv(t)
                    val gestures = Drivers.JblBes.readGestures(t)
                    val autoPlay = Drivers.JblBes.readAutoPlay(t)
                    val balance = Drivers.JblBes.readBalance(t)
                    val psap = Drivers.JblBes.readPsap(t)
                    val advancedAnc = Drivers.JblBes.readAdvancedAnc(t)
                    val prompts = Drivers.JblBes.readVoicePrompts(t)
                    val leAudio = Drivers.JblBes.readFeature(t, JblFeature.Key.LE_AUDIO)
                    val auracast = Drivers.JblBes.readFeature(t, JblFeature.Key.AURACAST)
                    listOf(
                        curve?.let(::CurveEqRow),
                        timer?.let(::IdleTimerRow),
                        limit?.let(::VolumeLimitRow),
                        spatial?.let { SpatialRow(it, SpatialMode.entries) },
                        voiceAware?.let(::VoiceAwareRow),
                        smartTalk?.let(::SmartTalkRow),
                        lowVolumeEq?.let(::LowVolumeEqRow),
                        smartAv?.let { SmartAvRow(it, SmartAv.entries) },
                        gestures?.let(::GesturesRow),
                        charge?.let { BatteryRow(it.battery, it.cupsDiffer) },
                        autoPlay?.let(::AutoPlayRow),
                        balance?.let(::BalanceRow),
                        psap?.let(::PsapRow),
                        advancedAnc?.let(::AdvancedAncRow),
                        prompts?.let { VoicePromptsRow(it, Writability.NoWriter) },
                        leAudio?.let(::LeAudioRow),
                        auracast?.let(::AuracastRow),
                        PowerOffRow,
                    )
                }

                Drivers.JblLivePro2 -> {
                    // ⚠ **Exactly the reads this device answers, and no others.** All
                    // sixteen of the M2's were tried; each silent one costs ~1.6 s of
                    // timeout. Decoders are [Drivers.JblBes]'s — the frames ARE the M2's,
                    // byte for byte, and only ANC differs.
                    //
                    // **Four reads that ANSWER are dropped**: a reply that decodes is not
                    // evidence the field means here what it means on an over-ear, so a row
                    // needs `jbl.stc.com` to offer it for THIS model. No power-off row:
                    // `aa 97` has never been sent here.
                    //
                    // Which reads were driven, against which instrument, is in
                    // `docs/protocols.md`.
                    val charge = Drivers.JblLivePro2.readCharge(t)
                    val timer = Drivers.JblLivePro2.readAutoOff(t)
                    val autoPlay = Drivers.JblLivePro2.readAutoPlay(t)
                    val balance = Drivers.JblLivePro2.readBalance(t)
                    val prompts = Drivers.JblLivePro2.readVoicePrompts(t)
                    val gestures = Drivers.JblLivePro2.readGestures(t)
                    val advancedAnc = Drivers.JblLivePro2.readAdvancedAnc(t)
                    val voiceAware = Drivers.JblLivePro2.readVoiceAware(t)
                    // ⚠ Names from [JblEqPreset], NOT [JBL_EQ_PRESETS] — a different field
                    // with the same small integers.
                    val eq = Drivers.JblLivePro2.readEq(t)
                    // ⚠ Read every time the section opens: it is the guard on the locating
                    // tone, and a bud that went into an ear since would still get a button.
                    val inEar = Drivers.JblLivePro2.readInEar(t)
                    // ✅ Its own payload table — see [Drivers.JblLivePro2.SMART_AV].
                    val smartAv = Drivers.JblLivePro2.readSmartAv(t)
                    listOf(
                        timer?.let(::IdleTimerRow),
                        autoPlay?.let(::AutoPlayRow),
                        balance?.let(::BalanceRow),
                        prompts?.let { VoicePromptsRow(it, Writability.NoWriter) },
                        gestures?.let(::GesturesRow),
                        advancedAnc?.let(::AdvancedAncRow),
                        voiceAware?.let(::VoiceAwareRow),
                        charge?.let { BatteryRow(it.battery, it.cupsDiffer) },
                        eq?.let {
                            PresetEqRow(
                                it,
                                bands = emptyList(),
                                presets = JblEqPreset.NAMES.keys.sorted(),
                                names = JblEqPreset.NAMES,
                            )
                        },
                        inEar?.let(::FindBudsRow),
                        smartAv?.let {
                            SmartAvRow(it, ArrayList(Drivers.JblLivePro2.SMART_AV.keys))
                        },
                    )
                }

                is Drivers.JLabQcy -> {
                    // ⚠ **Two reads for one row.** The JLab keeps the switch and the mode in
                    // separate commands, so the row needs both: a guessed mode would put a
                    // choice on screen that nothing read.
                    val on = Drivers.JLabQcy.readSpatial(t)
                    val mode = Drivers.JLabQcy.readSpatialMode(t)
                    val battery = Drivers.JLabQcy.readBattery(t)
                    val eq = Drivers.JLabQcy.readEq(t)
                    val presets = Drivers.JLabQcy.readEqPresets(t)
                    val touch = Drivers.JLabQcy.readTouch(t)
                    val safeHearing = Drivers.JLabQcy.readSafeHearing(t)
                    listOf(
                        battery?.let(::BudBatteryRow),
                        if (on != null && mode != null) {
                            SpatialRow(
                                Spatial(on, mode),
                                listOf(SpatialMode.MUSIC, SpatialMode.MOVIE),
                            )
                        } else {
                            null
                        },
                        eq?.let { JLabEqRow(it, presets) },
                        touch?.let(::JLabTouchRow),
                        safeHearing?.let(::SafeHearingRow),
                    )
                }

                else -> {
                    return Settings.NONE
                }
            }
        return Settings(rows.filterNotNull(), attempted = true)
    }

    /** Bose's voice prompts from GET_ALL. ✅ Bose is the only driver with this writer. */
    private fun bosePrompts(all: BoseAll): VoicePromptsRow? =
        all.voicePrompts?.let {
            VoicePromptsRow(
                it,
                Writability.Writable,
                all.promptLanguage,
                all.supportedLanguages,
            )
        }

    /**
     * This session's driver as [C], or null when the device does not have it. Every
     * setter reaches its driver this way, so a write meant for another model is refused
     * rather than sent down this device's link.
     */
    private inline fun <reified C> Session.can(): C? = headphones.driver as? C

    /** The noise-cancelling chips to draw; none for a device without it. */
    private val Session.offered: List<AncMode>
        get() = can<AncDriver>()?.offeredModes().orEmpty()

    /** The block-`01` settings every Bose shares, or null if it is not a Bose. */
    private val Session.bose: BoseSettingsDriver?
        get() = can<BoseSettingsDriver>()

    /** The XM4's own settings have one implementor, so the model is the capability. */
    private val Session.sony: Drivers.SonyXm4?
        get() = can<Drivers.SonyXm4>()

    /** Every settings write goes through here: drive it, then re-read the truth. */
    private fun <T> applied(
        address: String,
        what: String,
        describe: (T) -> String,
        body: (Session) -> Confirmation<T>?,
    ) = driven(address, what, { it.settingNote(describe) }, body)

    /**
     * [applied] for a write whose outcome is not a [Confirmation].
     *
     * ⚠ **Extracted rather than copied.** Everything below is load-bearing and was learned
     * the hard way — the mode kept across [DeviceState.Busy], the re-read that does not
     * trust the write's own answer, the log line that prints both. A second copy would
     * drift from it, and the JBL gesture write is exactly the caller that needs all three:
     * a refused write there can leave the device in a state the write's answer does not
     * name. See [org.xinutec.volume.protocol.GestureWrite].
     */
    private fun <O : Any> driven(
        address: String,
        what: String,
        note: (O) -> Note?,
        body: (Session) -> O?,
    ) = work.execute {
        holding(address) {
            val s = openIfNeeded(address) ?: return@holding
            // ⚠ **Taken before [DeviceState.Busy] overwrites it**, and kept rather
            // than re-read below. Changing the equaliser is not a question about noise
            // cancelling: re-asking spent a round trip on something nothing had
            // invalidated, and on the JBL — where a reply can be a keepalive that
            // arrived first — it came back empty and the mode silently went blank.
            // Measured: switching auto power off on the JBL cleared its
            // selected ANC chip, with the headphones still plainly cancelling noise.
            val mode = (card(address)?.state as? DeviceState.Ready)?.mode
            update(address, DeviceState.Busy("$what…"))
            val c = runCatching { body(s) }
            c.onFailure {
                if (it is IllegalArgumentException) {
                    update(
                        address,
                        DeviceState.Ready(s.headphones.model, s.offered, mode, refused(it)),
                    )
                    return@holding
                }
                drop(address)
                update(address, DeviceState.Unavailable("lost the connection: ${it.message}"))
                return@holding
            }
            val outcome = c.getOrNull()
            if (outcome == null) {
                val absent =
                    Note("this pair does not have that — nothing was sent", NoteKind.PROBLEM)
                update(
                    address,
                    DeviceState.Ready(
                        s.headphones.model,
                        s.offered,
                        mode,
                        absent,
                    ),
                )
                return@holding
            }
            // ⚠ Re-read rather than assume. `Confirmed` already means a read agreed,
            // but the other two do not, and the row must show what the device says.
            val settings = runCatching { readSettings(s) }.getOrNull() ?: return@holding
            // The write's own answer and the refresh's answer, side by side. Three
            // hypotheses about #1107 were formed by reasoning about frames and none
            // survived contact; this prints the disagreement instead of predicting it.
            // **`eq` is in here because a bare `Confirmed` is not evidence about
            // WHICH value landed.** A slider dragged too small rounds back to where it
            // started, writes the value already held, and confirms — indistinguishable
            // in the log from a drag that moved a band. Measured, and it
            // cost a re-run to notice the screen and the log did not disagree because
            // neither of them named a number.
            // **The JLab needs its SLOTS beside its curve, for the same reason.** `49`
            // answers a preset index and ten bytes, and a measurement caught those two
            // disagreeing — the index moved to a preset whose stored curve is flat while
            // the bytes stayed cut. Neither the card nor this log could show which slot
            // the bytes belonged to, so `71` is printed alongside them; the question is
            // not answerable from the curve alone. See [JLabEq].
            Log.i(
                LIVE,
                "$what: wrote=$outcome refresh: eq=${settings.get<PresetEqRow>()?.eq?.levels} " +
                    "jlab=${settings.get<JLabEqRow>()} " +
                    "dsee=${settings.get<DseeRow>()?.on} " +
                    "pause=${settings.get<PauseOnRemovalRow>()?.on} " +
                    "chat=${settings.get<SpeakToChatRow>()?.on} " +
                    "voice=${settings.get<FocusOnVoiceRow>()?.on}",
            )
            update(
                address,
                DeviceState.Ready(
                    s.headphones.model,
                    s.offered,
                    mode,
                    note(outcome),
                ),
            )
            emit(screen.withSettings(address, settings))
        }
    }

    private fun card(address: String) = screen.cards.firstOrNull { it.address == address }

    /** A builder refused the value before any frame existed, so the link is fine. */
    private fun refused(e: IllegalArgumentException) =
        Note("not sent: ${e.message}", NoteKind.PROBLEM)

    override fun setEqPreset(address: String, preset: Int) =
        applied<EqSetting>(address, "setting the equaliser", { "preset ${it.preset}" }) {
            val d = it.can<EqDriver>() ?: return@applied null
            d.setEq(it.transport, preset)
        }

    /**
     * ⚠ Reports the LEVELS, not the preset — the preset is deliberately unchanged by
     * this write, so naming it in the outcome would describe the wrong thing.
     */
    override fun setEqLevels(address: String, levels: List<Int>) =
        applied<EqSetting>(
            address,
            "setting the equaliser bands",
            { it.levels.joinToString(", ") },
        ) {
            val d = it.sony ?: return@applied null
            d.setEqLevels(it.transport, levels)
        }

    override fun setTone(address: String, bands: BoseBands) =
        applied<BoseBands>(address, "setting the tone controls", { "$it" }) {
            val d = it.can<Drivers.BoseQc45>() ?: return@applied null
            d.setTone(it.transport, bands)
        }

    override fun setMultipoint(address: String, on: Boolean) =
        applied<Boolean>(address, "setting multipoint", { if (it) "on" else "off" }) {
            val d = it.can<MultipointDriver>() ?: return@applied null
            d.setMultipoint(it.transport, on)
        }

    /**
     * The Status echoes the byte written, unlike multipoint's flags word, so this
     * compares directly — see [org.xinutec.volume.protocol.BoseCncPersistence].
     */
    override fun setCncPersistence(address: String, on: Boolean) =
        applied<Boolean>(address, "setting noise persistence", { if (it) "on" else "off" }) { s ->
            val d = s.bose ?: return@applied null
            d.setCncPersistence(s.transport, on)
        }

    override fun setAutoOff(address: String, mode: AutoOff) =
        applied<AutoOff>(address, "setting power off", { it.name }) {
            val d = it.sony ?: return@applied null
            d.setAutoOff(it.transport, mode)
        }

    /**
     * ⚠ **`was` comes from the CARD, which is what the owner was looking at when they
     * tapped.** Re-reading the map first would spend a round trip and still be a guess
     * about the moment between the two frames — and if the two disagreed, the value to
     * put back is the one on screen, not one the device volunteered in between.
     */
    override fun setGesture(address: String, g: Gesture, want: GestureAction) =
        driven(address, "setting ${g.label}", { it.note(GestureAction::label) }) {
            val was =
                card(address)
                    ?.settings
                    ?.get<GesturesRow>()
                    ?.bindings
                    ?.get(g) ?: GestureAction.NONE
            it.can<JblSharedSettings>()?.writeGesture(it.transport, g, want, was)
        }

    override fun setTimedOff(address: String, v: TimedOff) =
        // ⚠ The label names the MINUTES too: a duration chip tapped while the switch is
        // off changes only that byte, and "off → off" would read as a no-op in the log.
        applied<TimedOff>(
            address,
            "setting power off",
            { "${if (it.on) "on" else "off"}, ${it.minutes} min" },
        ) {
            val d = it.can<JblSharedSettings>() ?: return@applied null
            d.setAutoOff(it.transport, v)
        }

    override fun setCncMode(address: String, slot: Int) =
        applied<CncModes>(
            address,
            "selecting an ANC mode",
            { it.current?.name ?: "slot ${it.active}" },
        ) {
            val d = it.can<Drivers.BoseQc45>() ?: return@applied null
            d.selectMode(it.transport, slot)
        }

    override fun setWindBlock(address: String, slot: Int, on: Boolean) =
        applied<CncModes>(
            address,
            "setting wind block",
            { m ->
                m.modes.firstOrNull { it.slot == slot }?.let {
                    "${it.name}: wind block ${if (it.windBlock) "on" else "off"}, level ${it.level}"
                } ?: "unknown"
            },
        ) {
            val d = it.can<Drivers.BoseQc45>() ?: return@applied null
            d.setWindBlock(it.transport, slot, on)
        }

    override fun createCncMode(address: String, slot: Int, name: BosePromptName, level: Int) =
        applied<CncModes>(
            address,
            "creating an ANC mode",
            { it.modes.firstOrNull { m -> m.slot == slot }?.name ?: "nothing" },
        ) {
            val d = it.can<Drivers.BoseQc45>() ?: return@applied null
            d.createMode(it.transport, slot, name, level)
        }

    override fun deleteCncMode(address: String, slot: Int) =
        applied<CncModes>(
            address,
            "deleting an ANC mode",
            { "${it.modes.count { m -> m.editable }} of your own left" },
        ) {
            val d = it.can<Drivers.BoseQc45>() ?: return@applied null
            d.deleteMode(it.transport, slot)
        }

    /**
     * ⚠ **Sent on release, not per slider step.** Bose Music writes once per position —
     * eight frames for one drag — and there is no reason to put that on the channel.
     */
    override fun setCncLevel(address: String, slot: Int, level: Int) =
        applied<CncModes>(
            address,
            "setting an ANC level",
            { m -> m.current?.let { "${it.name} at ${it.level}" } ?: "unknown" },
        ) {
            val d = it.can<Drivers.BoseQc45>() ?: return@applied null
            d.setModeLevel(it.transport, slot, level)
        }

    override fun setStandby(address: String, minutes: Int) =
        applied<BoseStandby>(
            address,
            "setting standby timer",
            // ⚠ Named the way the card names it, so "never" does not appear in the log
            // as "0 min" — which would read as powering off at once.
            { if (it.minutes == 0) "never" else "${it.minutes} min" },
        ) {
            val d = it.bose ?: return@applied null
            d.setStandby(it.transport, minutes)
        }

    override fun setName(address: String, name: String) =
        applied<String>(address, "renaming", { it }) {
            // ⚠ The device's answer, not the request: if it trimmed or refused the
            // name, what it now reports IS the name, and the card must not disagree.
            val d = it.bose ?: return@applied null
            d.setName(it.transport, name)
        }

    override fun forgetDevice(address: String, device: BoseAddress) =
        driven<Forget>(address, "forgetting a device", { outcome ->
            when (outcome) {
                is Forget.Connected -> {
                    Note(
                        "${outcome.name ?: "that device"} is connected — forgetting it " +
                            "would disconnect it, and this app refuses that",
                        NoteKind.PROBLEM,
                    )
                }

                Forget.StillThere -> {
                    Note("the headphones still list it", NoteKind.PROBLEM)
                }

                Forget.Unverifiable -> {
                    Note("the list did not come back; nothing confirmed", NoteKind.CAUTION)
                }

                Forget.Forgot -> {
                    null
                }
            }
        }) { it.can<Drivers.BoseQc35>()?.forget(it.transport, device) }

    override fun startPairing(address: String) =
        applied<Boolean>(
            address,
            "opening for a new device",
            { if (it) "ready" else "not ready" },
        ) {
            val d = it.can<Drivers.BoseQc35>() ?: return@applied null
            d.startPairing(it.transport)
        }

    override fun setVoicePrompts(address: String, on: Boolean) =
        applied<Boolean>(address, "setting voice prompts", { if (it) "on" else "off" }) {
            val d = it.bose ?: return@applied null
            d.setVoicePrompts(it.transport, on)
        }

    override fun setPromptLanguage(address: String, language: BoseVoicePromptLanguage) =
        applied<BoseVoicePromptLanguage>(
            address,
            "setting prompt language",
            { it.name.lowercase().replace('_', ' ') },
        ) {
            val d = it.bose ?: return@applied null
            d.setPromptLanguage(it.transport, language)
        }

    override fun setSelfVoice(address: String, level: SidetoneLevel) =
        applied<SidetoneLevel>(address, "setting self voice", { it.name.lowercase() }) {
            val d = it.bose ?: return@applied null
            d.setSelfVoice(it.transport, level)
        }

    override fun setSpatial(address: String, v: Spatial) =
        applied<Spatial>(
            address,
            "setting spatial sound",
            { "${if (it.on) "on" else "off"}, ${it.mode.name.lowercase()}" },
        ) {
            val d = it.can<SpatialDriver>() ?: return@applied null
            d.setSpatial(it.transport, v)
        }

    /**
     * ⚠ **Raising this raises how loud the headphones can get.** Writable at the user's
     * explicit request. It re-reads rather than trusting the reply: `69`
     * answers `01` for every level, so it is an ack and says nothing about what the device
     * did — and reporting a hearing control as set when it was not is the worst version of
     * that mistake.
     */
    override fun setSafeHearing(address: String, level: JLabSafeHearing.Level) =
        applied<JLabSafeHearing.Level>(
            address,
            "setting safe hearing",
            { it.name.lowercase() },
        ) {
            val d = it.can<Drivers.JLabQcy>() ?: return@applied null
            d.setSafeHearing(it.transport, level)
        }

    /**
     * ⚠ **Can RAISE band levels** — the JLab's stored presets are flat while its live
     * Custom curve is cut in two places. Writable at the user's explicit request.
     */
    override fun setJlabEq(address: String, curve: JLabCurve) =
        applied<JLabCurve>(
            address,
            "setting the equaliser",
            { "preset ${it.preset}" },
        ) {
            val d = it.can<Drivers.JLabQcy>() ?: return@applied null
            d.setEq(it.transport, curve)
        }

    override fun setVoiceAware(address: String, v: VoiceAware) =
        applied<VoiceAware>(
            address,
            "setting voiceaware",
            { "${if (it.on) "on" else "off"}, ${it.level.name.lowercase()}" },
        ) {
            val d = it.can<JblSharedSettings>() ?: return@applied null
            d.setVoiceAware(it.transport, v)
        }

    override fun setSmartTalk(address: String, v: SmartTalk) =
        applied<SmartTalk>(
            address,
            "setting smart talk",
            { "${if (it.on) "on" else "off"}, ${it.timeout.seconds} s" },
        ) {
            val d = it.can<Drivers.JblBes>() ?: return@applied null
            d.setSmartTalk(it.transport, v)
        }

    override fun setLowVolumeEq(address: String, on: Boolean) =
        applied<Boolean>(
            address,
            "setting low volume dynamic eq",
            { if (it) "on" else "off" },
        ) {
            val d = it.can<Drivers.JblBes>() ?: return@applied null
            d.setLowVolumeEq(it.transport, on)
        }

    /**
     * The XM4's on/off settings, all through [SonyXm4.setSwitch], which writes and
     * then **reads back** — the reply is never the evidence on this device.
     *
     * [setTouchPanel] is one of these too and sits further down, past
     * [setVoiceGuidance], out of sight of this block. Count the callers of [sonySwitch],
     * never this sentence.
     *
     * Only the Sony has them; any other device is told so and sent nothing.
     */
    override fun setDsee(address: String, on: Boolean) = sonySwitch(address, "dsee", SonyDsee, on)

    override fun setPauseOnRemoval(address: String, on: Boolean) =
        sonySwitch(address, "pause on removal", SonyPauseOnRemoval, on)

    override fun setSpeakToChat(address: String, on: Boolean) =
        sonySwitch(address, "speak-to-chat", SonySpeakToChat, on)

    /**
     * ⚠ **No [applied] and no read-back**, and that is not an omission: the link drops as
     * the device acts, so re-reading would ask a question of something that has gone. The
     * card follows the radio, which is where the answer actually shows up.
     */
    override fun powerOff(address: String) =
        work.execute {
            holding(address) {
                val s = openIfNeeded(address) ?: return@holding
                update(address, DeviceState.Busy("switching off…"))
                // ⚠ A `when`, not a cast. Two vendors answer this now and a third
                // will not: casting would crash the worker on whichever device is
                // added next, at the one moment the owner is trying to end a session.
                runCatching {
                    when (val d = s.headphones.driver) {
                        is Drivers.SonyXm4 -> d.powerOff(s.transport)
                        Drivers.JblBes -> Drivers.JblBes.powerOff(s.transport)
                        else -> Log.i(LIVE, "$address cannot be switched off from here")
                    }
                }
                Log.i(LIVE, "power off sent to $address")
                drop(address)
            }
        }

    override fun setVoiceGuidance(address: String, on: Boolean) =
        applied<Boolean>(address, "setting voice guidance", { if (it) "on" else "off" }) {
            val d = it.sony ?: return@applied null
            d.setVoiceGuidance(it.transport, on)
        }

    override fun setTouchPanel(address: String, on: Boolean) =
        sonySwitch(address, "the touch panel", SonyTouchPanel, on)

    /**
     * Ask the XM4 to change its CUSTOM key.
     *
     * ⚠ **This may end by putting a question on the card rather than finishing.** The
     * device will not commit until its alert is answered, and answering yes drops the
     * audio link — so the answer is the owner's, not ours. [answerButton] resumes it.
     */
    override fun setSonyButton(address: String, action: SonyButton.Action) =
        work.execute {
            holding(address) {
                val s = openIfNeeded(address) ?: return@holding
                val d = s.headphones.driver as? Drivers.SonyXm4 ?: return@holding
                when (d.beginButtonWrite(s.transport, action)) {
                    ButtonWrite.Asks -> {
                        emit(
                            screen.asking(
                                address,
                                "Changing the button disconnects and reconnects the " +
                                    "headphones. Change it to ${pretty(action.name)}?",
                            ),
                        )
                    }

                    ButtonWrite.Unchanged -> {
                        // No alert means nothing changed — including the ordinary
                        // case of choosing the value already set. Re-read either way.
                        refresh(address, s)
                    }
                }
            }
        }

    /**
     * Pass the owner's answer to the device.
     *
     * ⚠ **A yes takes the link down with it.** The session is dropped and reopened before
     * the read-back, because the socket this was sent on is already dead — that is the
     * success path, not an error. See [Drivers.SonyXm4.answerButtonAlert].
     */
    override fun answerButton(address: String, yes: Boolean) =
        work.execute {
            emit(screen.asking(address, null))
            holding(address) {
                val s = openIfNeeded(address) ?: return@holding
                val d = s.headphones.driver as? Drivers.SonyXm4 ?: return@holding
                d.answerButtonAlert(s.transport, yes)
                if (!yes) {
                    refresh(address, s)
                    return@holding
                }
                // ⚠ **A yes has already taken the link down.** Reopening is a race against
                // the device's own reconnect, so this retries rather than waiting a fixed
                // time and hoping — measured, where a single attempt after 6 s
                // sometimes lost and left the card showing the PRE-CHANGE value. That is
                // the worst outcome available: the change had committed and the screen
                // said it had not.
                update(address, DeviceState.Busy("reconnecting…"))
                drop(address)
                // ⚠ **The loop turns on the READ, not on the socket.** Measured #1137:
                // the XM4's link is back within a second, so `openIfNeeded` succeeds on
                // the first try — and then every read on it returns nothing, because the
                // control channel is not serving yet. Retrying the open therefore exits
                // immediately with a session that cannot answer, no settings are emitted,
                // and the card silently keeps the pre-change value.
                //
                // A socket that opens is not a device that will answer. That is the
                // shape of precondition this repo has been caught by before: it passes
                // for the wrong reason and takes the question away.
                repeat(RECONNECT_TRIES) {
                    Thread.sleep(RECONNECT_STEP_MS)
                    val again = openIfNeeded(address)
                    if (again != null && refresh(address, again)) return@holding
                    // That session answers nothing; throw it away rather than reuse it.
                    drop(address)
                }
                // Say so rather than leave the old value on screen. The write almost
                // certainly landed — that is what took the link down — and this app has
                // no way to check until the pair is back.
                update(
                    address,
                    DeviceState.Unavailable(
                        "changed it, but the headphones have not come back yet",
                    ),
                )
            }
        }

    /** Re-read everything and put it on the card; false if the device answered nothing. */
    private fun refresh(address: String, s: Session): Boolean {
        describe(address, s)
        val read = runCatching { readSettings(s) }.getOrNull()
        // ⚠ **Kept from #1137, because it is what found the cause and what would find
        // the next one.** Two explanations were written down first — `withSettings`
        // dropping the update, or a broadcast refresh winning the race — and this line
        // refuted both in one run: the read simply returned nothing on a link that had
        // just come back. Same shape as #1107 and #1117, where reasoning produced
        // confident wrong answers and one printed value settled it.
        val before = card(address)?.state
        read?.let { emit(screen.withSettings(address, it)) }
        Log.i(
            LIVE,
            "refresh $address: read button=${read?.get<SonyButtonRow>()?.action} " +
                "state=$before → card now ${card(address)?.settings?.get<SonyButtonRow>()?.action}",
        )
        return read != null
    }

    /**
     * ⚠ **Takes the whole [ChatDetail]**, because the frame carries all three fields.
     * A per-field setter here would have to invent the other two.
     */
    override fun setChatDetail(address: String, detail: ChatDetail) =
        applied<ChatDetail>(address, "setting speak-to-chat detail", { it.sensitivity.name }) {
            val d = it.sony ?: return@applied null
            d.setChatDetail(it.transport, detail)
        }

    private fun sonySwitch(address: String, what: String, switch: SonySwitch, on: Boolean) =
        applied<Boolean>(address, "setting $what", { if (it) "on" else "off" }) {
            val d = it.sony ?: return@applied null
            d.setSwitch(it.transport, switch, on)
        }

    /**
     * ⚠ **Ambient mode only.** [Drivers.SonyXm4.setFocusOnVoice] does the checking — it
     * refuses in ANC rather than sending a frame the device accepts and ignores. The UI
     * also hides the switch there, so this is the second of two guards, deliberately:
     * the screen's copy of the mode can be stale by the time a tap arrives.
     */
    override fun setFocusOnVoice(address: String, on: Boolean) =
        applied<Boolean>(address, "setting focus on voice", { if (it) "on" else "off" }) {
            val d = it.sony ?: return@applied null
            d.setFocusOnVoice(it.transport, on)
        }

    /**
     * ⚠ The in-ear guard is in the UI, not here: a driver that silently refused would
     * leave a button on screen that does nothing, which is worse than no button.
     */
    override fun findBud(address: String, bud: Bud, on: Boolean) {
        val what = if (on) "sounding the ${bud.label} bud" else "stopping the ${bud.label} bud"
        driven<Unit>(address, what, { null }) {
            it.can<Drivers.JblLivePro2>()?.findBud(it.transport, bud, on)
        }
    }

    /**
     * ⚠ **Dispatched on the driver, because the PAYLOADS differ by model.** The modes
     * are shared; the bytes each one carries are not, and `JblSmartAv.set` writes those
     * bytes — so sending the M2's AUDIO to a LIVE PRO 2 would write the wrong thing
     * rather than fail visibly.
     */
    override fun setSmartAv(address: String, v: SmartAv) =
        applied<SmartAv>(address, "setting smart audio & video", { it.name.lowercase() }) {
            val d = it.can<SmartAvDriver>() ?: return@applied null
            d.setSmartAv(it.transport, v)
        }

    override fun setAutoPlay(address: String, on: Boolean) =
        applied<Boolean>(address, "setting auto play and pause", { if (it) "on" else "off" }) {
            val d = it.can<JblSharedSettings>() ?: return@applied null
            d.setAutoPlay(it.transport, on)
        }

    override fun setBalance(address: String, v: Balance) =
        applied<Balance>(address, "setting the balance", { if (it.on) "on" else "off" }) {
            val d = it.can<JblSharedSettings>() ?: return@applied null
            d.setBalance(it.transport, v)
        }

    override fun setCurve(address: String, curve: EqCurve) =
        applied<EqCurve>(address, "setting the equaliser", { "table ${it.table.id}" }) {
            val d = it.can<Drivers.JblBes>() ?: return@applied null
            d.setCurve(it.transport, curve)
        }

    override fun setSoundQuality(address: String, mode: SoundQuality) =
        applied<SoundQuality>(address, "setting sound quality", { it.name }) {
            val d = it.sony ?: return@applied null
            d.setSoundQuality(it.transport, mode)
        }

    override fun setButton(address: String, action: BoseButton.Action) =
        applied<BoseButton.Action>(address, "setting the button", { it.name }) {
            val d = it.can<Drivers.BoseQc45>() ?: return@applied null
            d.setButton(it.transport, action)
        }

    private fun openIfNeeded(address: String): Session? {
        Sessions.existing(address)?.let { return describe(address, it) }
        val device =
            try {
                adapter?.bondedDevices?.firstOrNull { it.address == address }
            } catch (expected: SecurityException) {
                null
            } ?: run {
                update(address, DeviceState.Unavailable("no longer bonded"))
                return null
            }
        val uuids =
            device.uuids
                ?.map { it.uuid.toString() }
                ?.toSet()
                .orEmpty()
        // ⚠ **A device known to have no control channel is not probed again.** The
        // probe WRITES two Bose frames, and `refresh()` runs it for everything listed
        // on every connect broadcast — so without this the app sends unsolicited
        // vendor bytes at a device it has already established says nothing, forever.
        (screen.cards.firstOrNull { it.address == address }?.state as? DeviceState.NoControl)
            ?.let { return null }
        update(address, DeviceState.Busy("connecting…"))
        var why = "would not connect"
        var durable = false
        val session =
            Control.connect(
                context,
                adapter!!,
                device,
                device.name.orEmpty(),
                uuids,
                resolveLe = { model, advertises ->
                    update(address, DeviceState.Busy("looking for $model over LE…"))
                    Scan.find(adapter, advertises, 25_000)?.device
                },
                onNote = { why = it },
                onNoControl = { durable = true },
            )
        if (session == null) {
            update(
                address,
                if (durable) DeviceState.NoControl(why) else DeviceState.Unavailable(why),
            )
            return null
        }
        Sessions.remember(address, session)
        return describe(address, session)
    }

    /**
     * Fill in a card from an open session: model, modes and the mode it reports.
     *
     * ⚠ **Called on the reused path too, and that is the whole point.** Sessions are
     * owned by the process now, so the Quick Settings tile can have opened this
     * channel before the screen ever asked. Returning early with "we already have a
     * session" left the card on [DeviceState.Idle] — reading *"Not connected"*, with
     * a Connect button, for a device the tile was driving at that moment. Measured.
     */
    private fun describe(address: String, session: Session): Session {
        update(address, DeviceState.Busy("reading…"))
        val mode =
            session.can<AncDriver>()?.let { d ->
                runCatching { d.read(session.transport) }.getOrNull()
            }
        // The name the device holds beats the bonded record, which for this phone's
        // QC35 is the LE advertisement's truncation of what its owner actually set.
        runCatching { session.headphones.driver.name(session.transport) }
            .getOrNull()
            ?.let { rename(address, it) }
        // A read that did not answer is PROBLEM, not CAUTION: retrying can fix it.
        val note =
            when (
                noMode(session.headphones.driver, mode)
            ) {
                null -> {
                    null
                }

                // ⚠ **No note at all, and that is the point.** A speaker has no ANC, so
                // there is no absence to explain — saying anything here would invent a
                // missing feature. The card simply draws no mode row.
                NoMode.NO_MODES -> {
                    null
                }

                NoMode.UNANSWERED -> {
                    Note(
                        "could not read it — the link may have gone; reconnect to retry",
                        NoteKind.PROBLEM,
                    )
                }
            }
        update(
            address,
            DeviceState.Ready(
                session.headphones.model,
                session.offered,
                mode,
                note,
            ),
        )
        return session
    }

    private fun drop(address: String) = Sessions.drop(address)

    /**
     * Let go of everything, now — the app is no longer on screen.
     *
     * **The screen's contents are kept**, so coming back shows the cards
     * immediately rather than blinking through "connecting"; only the radio links go.
     *
     * **In split screen this never fires.** Both halves stay resumed, so `onStop`
     * is not called and the lease sweep in [Sessions] is the only thing that lets go.
     * That is exactly the arrangement on this phone, which is why the tile could not
     * open a channel the app was holding — and why sessions are owned per-process
     * rather than per-screen.
     */
    fun release() = Sessions.releaseAll()

    /**
     * The activity is going; the process and its channels are not.
     *
     * ⚠ Unwatch, or this controller outlives its screen: [Sessions] is a process-level
     * object, so a retained callback would keep a destroyed activity's closure alive
     * and push updates at a screen nobody can see.
     */
    fun closeAll() {
        Sessions.unwatch(watcher)
        Sessions.releaseAll()
    }

    /**
     * Re-stamp which card owns the media volume, from the audio framework's own event.
     *
     * ⚠ **Hanging this off state changes was wrong, and two fixes made the same day
     * collided to prove it.** The first re-stamped on every [update]; the second
     * stopped re-probing a [DeviceState.NoControl] device, which removed its state
     * changes entirely. So when the other pair disconnected, the card that HAD become
     * the output never learned it, and showed no volume row until the app restarted.
     *
     * "Which device owns the media volume" is an audio-framework question, and
     * `AudioDeviceCallback` is its event — it fires when outputs appear and disappear,
     * which is the fact itself rather than a proxy for it arriving late.
     */
    fun outputsChanged() =
        work.execute { emit(screen.copy(activeAddress = Active.address(context))) }

    private fun update(address: String, state: DeviceState) = emit(screen.with(address, state))

    private fun rename(address: String, name: String) = emit(screen.renamed(address, name))

    private fun emit(next: Screen) {
        screen = next
        onScreen(next)
    }

    /** Same shaping as the card's chips, so the question names what the owner tapped. */
    private fun pretty(name: String) =
        name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    private companion object {
        /**
         * How long to wait between attempts to reopen after a commit dropped the link.
         *
         * ⚠ **The XM4 reconnects on its own, and not on a schedule.** A single wait of
         * 6 s was tried first and lost the race often enough to show a stale value on
         * the card, so this retries instead. Reopening too early gets a refused socket,
         * which reads exactly like the write having failed.
         */
        const val RECONNECT_STEP_MS = 3_000L

        /** Bounded — `3 s × 8` is 24 s, after which the card says so rather than lying. */
        const val RECONNECT_TRIES = 8

        /**
         * How long a control channel is kept after the last thing that needed it.
         *
         * ⚠ **A backstop, not the mechanism.** Releasing on background ([release],
         * from `onStop`) is what actually stops this app squatting on the radio; this
         * only catches a screen left open and forgotten, holding links for an hour.
         *
         * **Deliberately long.** Letting go quickly only matters while the vendor
         * apps need the channel, and they are to be uninstalled once this app replaces
         * them. With nothing to yield to, an eager release only buys a reconnect on the
         * next tap — a second on RFCOMM, up to 25 on the JBL, whose rotating address
         * must be found by an LE scan first. Two minutes is long enough that no
         * interaction pays that, short enough that a forgotten screen does not hold
         * five links all day.
         */
        const val IDLE_MS = 120_000L
    }
}
