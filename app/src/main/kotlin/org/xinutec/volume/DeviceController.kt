package org.xinutec.volume

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import org.xinutec.volume.protocol.AncMode
import org.xinutec.volume.protocol.AutoOff
import org.xinutec.volume.protocol.Balance
import org.xinutec.volume.protocol.BoseBands
import org.xinutec.volume.protocol.BoseBattery
import org.xinutec.volume.protocol.BoseButton
import org.xinutec.volume.protocol.BosePromptName
import org.xinutec.volume.protocol.BoseSettingsDriver
import org.xinutec.volume.protocol.BoseStandby
import org.xinutec.volume.protocol.BoseVoicePromptLanguage
import org.xinutec.volume.protocol.BoseVoicePrompts
import org.xinutec.volume.protocol.ButtonWrite
import org.xinutec.volume.protocol.ChatDetail
import org.xinutec.volume.protocol.CncModes
import org.xinutec.volume.protocol.Confirmation
import org.xinutec.volume.protocol.DeviceCard
import org.xinutec.volume.protocol.DeviceState
import org.xinutec.volume.protocol.Drivers
import org.xinutec.volume.protocol.Emptiness
import org.xinutec.volume.protocol.EqCurve
import org.xinutec.volume.protocol.EqDriver
import org.xinutec.volume.protocol.EqSetting
import org.xinutec.volume.protocol.Forget
import org.xinutec.volume.protocol.Gesture
import org.xinutec.volume.protocol.GestureAction
import org.xinutec.volume.protocol.JLabCurve
import org.xinutec.volume.protocol.JLabSafeHearing
import org.xinutec.volume.protocol.JblFeature
import org.xinutec.volume.protocol.MultipointDriver
import org.xinutec.volume.protocol.NoMode
import org.xinutec.volume.protocol.Note
import org.xinutec.volume.protocol.NoteKind
import org.xinutec.volume.protocol.RefusalReason
import org.xinutec.volume.protocol.Registry
import org.xinutec.volume.protocol.Screen
import org.xinutec.volume.protocol.SettingKind
import org.xinutec.volume.protocol.Settings
import org.xinutec.volume.protocol.SidetoneLevel
import org.xinutec.volume.protocol.SmartAv
import org.xinutec.volume.protocol.SmartTalk
import org.xinutec.volume.protocol.SonyButton
import org.xinutec.volume.protocol.SonyDsee
import org.xinutec.volume.protocol.SonyPauseOnRemoval
import org.xinutec.volume.protocol.SonySpeakToChat
import org.xinutec.volume.protocol.SonySwitch
import org.xinutec.volume.protocol.SonyTouchPanel
import org.xinutec.volume.protocol.SoundQuality
import org.xinutec.volume.protocol.Spatial
import org.xinutec.volume.protocol.SpatialMode
import org.xinutec.volume.protocol.TimedOff
import org.xinutec.volume.protocol.VoiceAware
import org.xinutec.volume.protocol.Wearable
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
 * ⚠ Kept rather than deleted after the question was answered. The ACL and profile
 * events race, the winner depends on the pair and on how the link came up, and a
 * reasoned answer about which one arrived first is exactly the kind that was wrong
 * before. `adb logcat -s VolumeLive` prints the chain; `scripts/watch-list.sh`
 * reads it from the outside.
 */
internal const val LIVE = "VolumeLive"

/**
 * The screen's hands: everything that blocks, off the main thread.
 *
 * ⚠ **Connecting is slow and unevenly so.** An RFCOMM open is about a second; the
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
     * ⚠ Re-reads rather than trusting what the tile reported, because the tile's word
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
                } catch (e: SecurityException) {
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
                screen.reconciled(listed.map { it.address to (it.name ?: "(unnamed)") }, whenEmpty),
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
        // ⚠⚠ **A POSITIVE IDENTIFICATION BEATS THE CLASS HEURISTIC, and the order was the
        // other way round until 2026-09-03.** [Wearable.couldBeHeadphones] exists to keep
        // UNIDENTIFIED SPP devices off the list — the speakers are always in the room and
        // a Connect button that can only fail slowly is worse than no row. But it answers
        // false for `LOUDSPEAKER`, and the SoundLink Revolve is a loudspeaker this app now
        // drives: BMAP on the QC35's own channel, with a driver and a card. Filtering a
        // device we can NAME on a guess about its class is the heuristic overruling the
        // fact.
        if (Registry.fromAdvertisement(d.name.orEmpty(), uuids) != null) return true
        // ⚠ Still first for everything else, and still for the same reason: SPP does not
        // distinguish headphones from a laptop or a speaker. `Crowley` and the ACTON II
        // have no driver here and must not list.
        if (!Wearable.couldBeHeadphones(d.bluetoothClass?.deviceClass ?: 0)) return false
        return org.xinutec.volume.protocol.Channels.SPP in uuids.map { it.lowercase() }
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
        update(address, DeviceState.Busy("setting ${mode.name.lowercase()}…"))
        val result = runCatching { s.headphones.driver.set(s.transport, mode) }
        result.onFailure {
            drop(address)
            update(address, DeviceState.Unavailable("lost the connection: ${it.message}"))
        }
        val c = result.getOrNull() ?: return
        update(
            address,
            DeviceState.Ready(
                s.headphones.model,
                s.headphones.driver.offeredModes(),
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
     * ⚠ **Reads only.** Nothing here writes, so it is safe to run against a pair
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
     * ⚠ **`refuses` is not a guess about the device — it is measured, on 2026-08-16.**
     * The XM4 acks `d8 d2 01 01` and `f8 06 01 31` and then ignores both. The QC45
     * accepts the same two settings from this code. So the map is per-driver and is
     * stated where a future session will see it next to the evidence, rather than
     * being rediscovered by a user watching a switch spring back.
     *
     * ⚠ **The two do NOT fail for the same reason, and it is a Map now because of
     * that.** Sony's own app fails at multipoint identically — that is [DEVICE]. It
     * changes the [CUSTOM] button perfectly well, and only this repo cannot — that is
     * [THIS_APP], and it is #965. Both were a plain `Set` until 2026-08-23, under a
     * note on screen reading "not even its own app", which was false for the button.
     */
    private fun readSettings(s: Session): Settings =
        when (val d = s.headphones.driver) {
            is Drivers.SonyXm4 -> {
                val focus = d.readFocus(s.transport)
                Settings(
                    eq = d.readEq(s.transport),
                    bands = d.bands(s.transport),
                    multipoint = d.readMultipoint(s.transport),
                    autoOff = d.readAutoOff(s.transport),
                    soundQuality = d.readSoundQuality(s.transport),
                    button = d.readButton(s.transport)?.name,
                    buttonOptions = d.buttonPresets(s.transport).map { it.name },
                    battery = d.readBattery(s.transport),
                    dsee = d.readSwitch(s.transport, SonyDsee),
                    pauseOnRemoval = d.readSwitch(s.transport, SonyPauseOnRemoval),
                    speakToChat = d.readSwitch(s.transport, SonySpeakToChat),
                    touchPanel = d.readSwitch(s.transport, SonyTouchPanel),
                    voiceGuidance = d.readVoiceGuidance(s.transport),
                    codec = d.readCodec(s.transport),
                    canPowerOff = true,
                    chatDetail = d.readChatDetail(s.transport),
                    // ⚠ ONE read for both — see [Drivers.SonyXm4.readFocus]. Asking
                    // separately cost an extra `66 02` per settings load.
                    focusOnVoice = focus.on,
                    focusOnVoiceSettable = focus.settable,
                    refuses =
                        mapOf(
                            // ⚠ **BUTTON came off this map on 2026-08-24.** It sat here as
                            // THIS_APP for eight days and the cause was ours: the device
                            // sends no alert to a peer that never subscribed, and the
                            // write does not commit until the alert is answered. #965.
                            SettingKind.MULTIPOINT to RefusalReason.DEVICE,
                        ),
                    attempted = true,
                )
            }

            // The SoundLink Revolve — a speaker, so no ANC row and no chips.
            //
            // ⚠ **Every field here was MEASURED on the device 2026-09-03**, not inherited
            // from the QC35's branch: `01 04` standby, `01 02` name, `02 02` battery,
            // `02 05` charger and `05 05` volume all answered in the sweep. ⚠ `readAll`
            // is deliberately NOT used — `01 01` was never driven on this unit, and
            // reusing the QC35's one-exchange trick would be extrapolation dressed as
            // economy.
            Drivers.BoseRevolve -> {
                val battery = BoseBattery.state(s.transport.exchange(BoseBattery.get()))
                // ⚠ `01 03` read DIRECTLY, not via GET_ALL. The Revolve answers it with
                // `a1 00 04 cf de` — byte-identical to the QC35's, so the same decoder
                // applies — while `01 01` GET_ALL has never been driven on this unit.
                val prompts = BoseVoicePrompts.read(s.transport)
                Settings(
                    standby = Drivers.BoseRevolve.readStandby(s.transport),
                    voicePrompts = prompts?.let { BoseVoicePrompts.enabled(it) },
                    promptLanguage = prompts?.let { BoseVoicePromptLanguage.of(it) },
                    supportedLanguages =
                        prompts?.let { BoseVoicePrompts.supported(it) } ?: emptyList(),
                    deviceName = Drivers.BoseRevolve.name(s.transport),
                    canRename = true,
                    // ⚠ The charger bit rides on [Battery.charging], which the QC35 leaves
                    // null because it does not answer `02 05` at all.
                    battery =
                        battery?.copy(charging = Drivers.BoseRevolve.readCharging(s.transport)),
                    loudness = Drivers.BoseRevolve.readVolume(s.transport),
                    attempted = true,
                )
            }

            // ⚠ Not `d.` — matching an `object` does not smart-cast, so this names
            // it again rather than going through the `AncDriver` it is typed as.
            Drivers.BoseQc35 -> {
                // ⚠ ONE exchange, not one per setting — `01 01` GET_ALL is also the
                // device's own enumeration of what it has, which is what settles a
                // function being absent rather than merely quiet.
                val all = Drivers.BoseQc35.readAll(s.transport)
                Settings(
                    standby = all?.standby,
                    selfVoice = all?.sidetone,
                    voicePrompts = all?.voicePrompts,
                    promptLanguage = all?.promptLanguage,
                    supportedLanguages = all?.supportedLanguages ?: emptyList(),
                    devices = Drivers.BoseQc35.readDevices(s.transport),
                    pairing = Drivers.BoseQc35.readPairing(s.transport),
                    canRename = true,
                    deviceName = all?.name,
                    // ⚠ A second exchange, because battery is block 02 and GET_ALL only
                    // covers the block it is asked about.
                    battery = BoseBattery.state(s.transport.exchange(BoseBattery.get())),
                    attempted = true,
                )
            }

            Drivers.BoseQc45 -> {
                // ⚠ **ONE exchange for the whole of block 01** — the reply is the
                // device's own enumeration, and it already carries the tone, the button
                // and multipoint. This branch asked for those three again, individually,
                // immediately after.
                //
                // ✅ Measured on the wire, one card open, 2026-08-28: **18 requests → 12,
                // 946 ms → 710 ms.** Six went rather than three because the whole read
                // cycle runs TWICE per open — which is #1191 and is still open.
                //
                // ⚠ The three payloads were compared byte-for-byte against their
                // individual reads before those reads were deleted. `01 05` and `01 06`
                // already mean different things on these two models, so "it is in the
                // GET_ALL reply" is not on its own a reason to believe it is the same.
                val all = Drivers.BoseQc45.readAll(s.transport)
                Settings(
                    tone = all?.tone,
                    multipoint = all?.multipoint,
                    cncPersistence = all?.cncPersistence,
                    button = all?.button?.name,
                    // ⚠ The device's own named modes, which the two-ended AncMode
                    // cannot express — see CncModes. Read every time the card opens:
                    // the button on the headphones moves the selection.
                    cnc = Drivers.BoseQc45.readModes(s.transport),
                    standby = all?.standby,
                    selfVoice = all?.sidetone,
                    voicePrompts = all?.voicePrompts,
                    promptLanguage = all?.promptLanguage,
                    supportedLanguages = all?.supportedLanguages ?: emptyList(),
                    canRename = true,
                    deviceName = all?.name,
                    attempted = true,
                )
            }

            Drivers.JblBes -> {
                Settings(
                    curve = Drivers.JblBes.readCurve(s.transport),
                    timedOff = Drivers.JblBes.readAutoOff(s.transport),
                    volumeLimit = Drivers.JblBes.readVolumeLimit(s.transport),
                    spatial = Drivers.JblBes.readSpatial(s.transport),
                    voiceAware = Drivers.JblBes.readVoiceAware(s.transport),
                    smartTalk = Drivers.JblBes.readSmartTalk(s.transport),
                    lowVolumeEq = Drivers.JblBes.readLowVolumeEq(s.transport),
                    smartAv = Drivers.JblBes.readSmartAv(s.transport),
                    gestures = Drivers.JblBes.readGestures(s.transport),
                    battery = Drivers.JblBes.readBattery(s.transport),
                    autoPlay = Drivers.JblBes.readAutoPlay(s.transport),
                    balance = Drivers.JblBes.readBalance(s.transport),
                    psap = Drivers.JblBes.readPsap(s.transport),
                    advancedAnc = Drivers.JblBes.readAdvancedAnc(s.transport),
                    voicePrompts = Drivers.JblBes.readVoicePrompts(s.transport),
                    leAudio = Drivers.JblBes.readFeature(s.transport, JblFeature.LE_AUDIO),
                    auracast = Drivers.JblBes.readFeature(s.transport, JblFeature.AURACAST),
                    canPowerOff = true,
                    attempted = true,
                )
            }

            is Drivers.JLabQcy -> {
                // ⚠ **Two reads for one card field.** The JLab keeps the switch and the
                // mode in separate commands, so [Settings.spatial] is only offered when
                // both answer — a Spatial carrying a guessed mode would put a choice on
                // screen that nothing read.
                val on = Drivers.JLabQcy.readSpatial(s.transport)
                val mode = Drivers.JLabQcy.readSpatialMode(s.transport)
                Settings(
                    budBattery = Drivers.JLabQcy.readBattery(s.transport),
                    spatial = if (on != null && mode != null) Spatial(on, mode) else null,
                    spatialModes = listOf(SpatialMode.MUSIC, SpatialMode.MOVIE),
                    jlabEq = Drivers.JLabQcy.readEq(s.transport),
                    jlabEqPresets = Drivers.JLabQcy.readEqPresets(s.transport),
                    jlabTouch = Drivers.JLabQcy.readTouch(s.transport),
                    jlabSafeHearing = Drivers.JLabQcy.readSafeHearing(s.transport),
                    attempted = true,
                )
            }

            else -> {
                Settings()
            }
        }

    /**
     * The block-`01` settings of whatever is on the other end, or null if it is not a Bose.
     *
     * ⚠ **This replaced `Drivers.BoseQc35` written out at five call sites** — including
     * on QC45 sessions, where it worked. The drivers are stateless and the frames are
     * identical, so nothing failed and nothing could; the code simply named the wrong
     * model, and a reader checking whether the QC45 had these settings would have
     * concluded from those five lines that it did not.
     *
     * ⚠ Null here means "not a Bose", which the card never offers these rows for — so it
     * is a programming error rather than a device state. It is reported as
     * [Confirmation.Unverifiable] all the same, because that is the outcome that cannot
     * render as success.
     */
    private val Session.bose: BoseSettingsDriver?
        get() = headphones.driver as? BoseSettingsDriver

    /**
     * Same idiom for the XM4's own settings — the features below have a single
     * implementor, so a per-feature interface would be ceremony; what matters is that
     * a wrong-driver dispatch is a typed [Confirmation.Unverifiable], never a
     * [ClassCastException] on a listener thread.
     */
    private val Session.sony: Drivers.SonyXm4?
        get() = headphones.driver as? Drivers.SonyXm4

    /** Every settings write goes through here: drive it, then re-read the truth. */
    private fun <T> applied(
        address: String,
        what: String,
        describe: (T) -> String,
        body: (Session) -> Confirmation<T>,
    ) = driven(address, what, { it.settingNote(describe) }, body)

    /**
     * [applied] for a write whose outcome is not a [Confirmation].
     *
     * ⚠ **Extracted rather than copied.** Everything below is load-bearing and was learned
     * the hard way — the mode kept across [DeviceState.Busy], the re-read that does not
     * trust the write's own answer, the log line that prints both. A second copy would
     * drift from it, and the JBL gesture write is exactly the caller that needs all three:
     * a refused write there can leave the device in a state the write's answer does not
     * name. See [GestureWrite].
     */
    private fun <O> driven(
        address: String,
        what: String,
        note: (O) -> Note?,
        body: (Session) -> O,
    ) = work.execute {
        holding(address) {
            val s = openIfNeeded(address) ?: return@holding
            // ⚠ **Taken before [DeviceState.Busy] overwrites it**, and kept rather
            // than re-read below. Changing the equaliser is not a question about noise
            // cancelling: re-asking spent a round trip on something nothing had
            // invalidated, and on the JBL — where a reply can be a keepalive that
            // arrived first — it came back empty and the mode silently went blank.
            // Measured 2026-08-16: switching auto power off on the JBL cleared its
            // selected ANC chip, with the headphones still plainly cancelling noise.
            val mode = (card(address)?.state as? DeviceState.Ready)?.mode
            update(address, DeviceState.Busy("$what…"))
            val c = runCatching { body(s) }
            c.onFailure {
                drop(address)
                update(address, DeviceState.Unavailable("lost the connection: ${it.message}"))
            }
            val outcome = c.getOrNull() ?: return@holding
            // ⚠ Re-read rather than assume. `Confirmed` already means a read agreed,
            // but the other two do not, and the row must show what the device says.
            val settings = runCatching { readSettings(s) }.getOrNull() ?: return@holding
            // ⚠ The write's own answer and the refresh's answer, side by side. Three
            // hypotheses about #1107 were formed by reasoning about frames and none
            // survived contact; this prints the disagreement instead of predicting it.
            // ⚠ **`eq` is in here because a bare `Confirmed` is not evidence about
            // WHICH value landed.** A slider dragged too small rounds back to where it
            // started, writes the value already held, and confirms — indistinguishable
            // in the log from a drag that moved a band. Measured 2026-08-24, and it
            // cost a re-run to notice the screen and the log did not disagree because
            // neither of them named a number.
            // ⚠ **The JLab needs its SLOTS beside its curve, for the same reason.** `49`
            // answers a preset index and ten bytes, and 2026-09-02 measured those two
            // disagreeing — the index moved to a preset whose stored curve is flat while
            // the bytes stayed cut. Neither the card nor this log could show which slot
            // the bytes belonged to, so `71` is printed alongside them; the question is
            // not answerable from the curve alone. See [JLabEq].
            Log.i(
                LIVE,
                "$what: wrote=$outcome refresh: eq=${settings.eq?.levels} " +
                    "jlabEq=${settings.jlabEq} slots=${settings.jlabEqPresets} " +
                    "dsee=${settings.dsee} pause=${settings.pauseOnRemoval} " +
                    "chat=${settings.speakToChat} voice=${settings.focusOnVoice}",
            )
            update(
                address,
                DeviceState.Ready(
                    s.headphones.model,
                    s.headphones.driver.offeredModes(),
                    mode,
                    note(outcome),
                ),
            )
            emit(screen.withSettings(address, settings))
        }
    }

    private fun card(address: String) = screen.cards.firstOrNull { it.address == address }

    override fun setEqPreset(address: String, preset: Int) =
        applied<EqSetting>(address, "setting the equaliser", { "preset ${it.preset}" }) {
            val d = it.headphones.driver as? EqDriver ?: return@applied Confirmation.Unverifiable
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
            val d = it.sony ?: return@applied Confirmation.Unverifiable
            d.setEqLevels(it.transport, levels)
        }

    override fun setTone(address: String, bands: BoseBands) =
        applied<BoseBands>(address, "setting the tone controls", { "$it" }) {
            val after =
                Drivers.BoseQc45.writeEq(it.transport, bands)
                    ?: Drivers.BoseQc45.readEq(it.transport)
            when (after) {
                null -> Confirmation.Unverifiable
                bands -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    override fun setMultipoint(address: String, on: Boolean) =
        applied<Boolean>(address, "setting multipoint", { if (it) "on" else "off" }) {
            val d =
                it.headphones.driver as? MultipointDriver
                    ?: return@applied Confirmation.Unverifiable
            d.setMultipoint(it.transport, on)
        }

    /**
     * ⚠ The Status echoes the byte written, unlike multipoint's flags word, so this
     * compares directly — see [BoseCncPersistence].
     */
    override fun setCncPersistence(address: String, on: Boolean) =
        applied<Boolean>(address, "setting noise persistence", { if (it) "on" else "off" }) { s ->
            val d = s.bose ?: return@applied Confirmation.Unverifiable
            d.writeCncPersistence(s.transport, on)
        }

    override fun setAutoOff(address: String, mode: AutoOff) =
        applied<AutoOff>(address, "setting power off", { it.name }) {
            val d = it.sony ?: return@applied Confirmation.Unverifiable
            when (val after = d.writeAutoOff(it.transport, mode) ?: d.readAutoOff(it.transport)) {
                null -> Confirmation.Unverifiable
                mode -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    /**
     * ⚠ **[was] comes from the CARD, which is what the owner was looking at when they
     * tapped.** Re-reading the map first would spend a round trip and still be a guess
     * about the moment between the two frames — and if the two disagreed, the value to
     * put back is the one on screen, not one the device volunteered in between.
     */
    override fun setGesture(address: String, g: Gesture, want: GestureAction) =
        driven(address, "setting ${g.label}", { it.note(GestureAction::label) }) {
            val was = card(address)?.settings?.gestures?.get(g) ?: GestureAction.NONE
            Drivers.JblBes.writeGesture(it.transport, g, want, was)
        }

    override fun setTimedOff(address: String, v: TimedOff) =
        // ⚠ The label names the MINUTES too: a duration chip tapped while the switch is
        // off changes only that byte, and "off → off" would read as a no-op in the log.
        applied<TimedOff>(
            address,
            "setting power off",
            { "${if (it.on) "on" else "off"}, ${it.minutes} min" },
        ) {
            Drivers.JblBes.writeAutoOff(it.transport, v)
            // ⚠ The write's own reply is an ack, so the truth comes from a re-read.
            when (val after = Drivers.JblBes.readAutoOff(it.transport)) {
                null -> Confirmation.Unverifiable
                v -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    /**
     * ⚠ **Confirmed against the slot the DEVICE reports, not the one asked for.** The
     * button on the headphones moves this too, so a write and a button press can race;
     * echoing the request back would show a mode the wearer is not in.
     */
    override fun setCncMode(address: String, slot: Int) =
        applied<CncModes>(
            address,
            "selecting an ANC mode",
            { it.current?.name ?: "slot ${it.active}" },
        ) {
            val after = Drivers.BoseQc45.selectMode(it.transport, slot)
            when {
                after == null -> Confirmation.Unverifiable
                after.active == slot -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
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
            val after = Drivers.BoseQc45.setWindBlock(it.transport, slot, on)
            when {
                after == null -> {
                    Confirmation.Unverifiable
                }

                after.modes.firstOrNull { m -> m.slot == slot }?.windBlock == on -> {
                    Confirmation.Confirmed
                }

                else -> {
                    Confirmation.Contradicted(after)
                }
            }
        }

    override fun createCncMode(address: String, slot: Int, name: BosePromptName, level: Int) =
        applied<CncModes>(
            address,
            "creating an ANC mode",
            { it.modes.firstOrNull { m -> m.slot == slot }?.name ?: "nothing" },
        ) {
            val after = Drivers.BoseQc45.createMode(it.transport, slot, name, level)
            when {
                after == null -> Confirmation.Unverifiable
                after.slots?.holds(slot) == true -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    /**
     * ⚠ **Confirmed by the OCCUPANCY bit, not by the record.** A blanked slot still
     * answers with a full-length record, so reading the name back would report success
     * for a delete whose `1f 08` write never landed — which is exactly the half-done
     * state this repo produced on hardware while decoding the pair.
     */
    override fun deleteCncMode(address: String, slot: Int) =
        applied<CncModes>(
            address,
            "deleting an ANC mode",
            { "${it.modes.count { m -> m.editable }} of your own left" },
        ) {
            val after = Drivers.BoseQc45.deleteMode(it.transport, slot)
            when {
                after == null -> Confirmation.Unverifiable
                after.slots?.holds(slot) == false -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
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
        ) { session ->
            val before = Drivers.BoseQc45.readModes(session.transport)
            val mode = before?.modes?.firstOrNull { it.slot == slot }
            // ⚠ Re-read first: the level goes out inside a record carrying the mode's
            // NAME and its undecoded [BoseCncModes.Mode.nameId], and a stale copy of
            // those would rename the mode as a side effect of moving a slider.
            if (mode == null) {
                Confirmation.Unverifiable
            } else {
                val after = Drivers.BoseQc45.setModeLevel(session.transport, mode, level)
                val got = after?.modes?.firstOrNull { it.slot == slot }
                when {
                    after == null || got == null -> Confirmation.Unverifiable
                    got.level == level -> Confirmation.Confirmed
                    else -> Confirmation.Contradicted(after)
                }
            }
        }

    override fun setStandby(address: String, minutes: Int) =
        applied<BoseStandby>(
            address,
            "setting standby timer",
            // ⚠ Named the way the card names it, so "never" does not appear in the log
            // as "0 min" — which would read as powering off at once.
            { if (it.minutes == 0) "never" else "${it.minutes} min" },
        ) {
            when (val after = it.bose?.writeStandby(it.transport, minutes)) {
                null -> Confirmation.Unverifiable
                BoseStandby(minutes) -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    override fun setName(address: String, name: String) =
        applied<String>(address, "renaming", { it }) {
            when (val after = it.bose?.writeName(it.transport, name)) {
                null -> Confirmation.Unverifiable

                name -> Confirmation.Confirmed

                // ⚠ The device's answer, not the request: if it trimmed or refused the
                // name, what it now reports IS the name, and the card must not disagree.
                else -> Confirmation.Contradicted(after)
            }
        }

    override fun forgetDevice(address: String, device: String) =
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
        }) { Drivers.BoseQc35.forget(it.transport, device) }

    override fun startPairing(address: String) =
        applied<Boolean>(
            address,
            "opening for a new device",
            { if (it) "ready" else "not ready" },
        ) {
            when (Drivers.BoseQc35.startPairing(it.transport)) {
                null -> Confirmation.Unverifiable
                true -> Confirmation.Confirmed
                false -> Confirmation.Contradicted(false)
            }
        }

    override fun setVoicePrompts(address: String, on: Boolean) =
        applied<Boolean>(address, "setting voice prompts", { if (it) "on" else "off" }) {
            when (val after = it.bose?.writeVoicePrompts(it.transport, on)) {
                null -> Confirmation.Unverifiable
                on -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    override fun setPromptLanguage(address: String, language: BoseVoicePromptLanguage) =
        applied<BoseVoicePromptLanguage>(
            address,
            "setting prompt language",
            { it.name.lowercase().replace('_', ' ') },
        ) {
            when (val after = it.bose?.writePromptLanguage(it.transport, language)) {
                null -> Confirmation.Unverifiable
                language -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    override fun setSelfVoice(address: String, level: SidetoneLevel) =
        applied<SidetoneLevel>(address, "setting self voice", { it.name.lowercase() }) {
            when (val after = it.bose?.writeSelfVoice(it.transport, level)) {
                null -> Confirmation.Unverifiable
                level -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    override fun setSpatial(address: String, v: Spatial) =
        applied<Spatial>(
            address,
            "setting spatial sound",
            { "${if (it.on) "on" else "off"}, ${it.mode.name.lowercase()}" },
        ) {
            // ⚠ No re-read for the JBL: `aa 9d` answers with the status frame, not an
            // ack, so the reply IS the read-back. Contrast [setTimedOff], where it is an
            // ack and a second round trip is the only way to know.
            val after =
                when (it.headphones.driver) {
                    // ⚠⚠ **TWO writes, and either can land without the other.** The JLab
                    // has no frame carrying both, so this reports what it can actually
                    // verify: the switch and the mode are re-read separately and a
                    // half-applied edit comes back as Contradicted rather than as a
                    // success. Ordered switch-then-mode so that turning it on and
                    // choosing a mode in one edit leaves the mode as the last word.
                    is Drivers.JLabQcy -> {
                        val on = Drivers.JLabQcy.writeSpatial(it.transport, v.on)
                        val mode = Drivers.JLabQcy.writeSpatialMode(it.transport, v.mode)
                        if (on == null || mode == null) null else Spatial(on, mode)
                    }

                    else -> {
                        Drivers.JblBes.writeSpatial(it.transport, v)
                    }
                }
            when (after) {
                null -> Confirmation.Unverifiable
                v -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    /**
     * ⚠⚠ **Raising this raises how loud the headphones can get.** Writable at Pippijn's
     * explicit request, 2026-09-01. It re-reads rather than trusting the reply: `69`
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
            when (val after = Drivers.JLabQcy.writeSafeHearing(it.transport, level)) {
                null -> Confirmation.Unverifiable
                level -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    /**
     * ⚠⚠ **Can RAISE band levels** — the JLab's stored presets are flat while its live
     * Custom curve is cut in two places. Writable at Pippijn's explicit request.
     */
    override fun setJlabEq(address: String, curve: JLabCurve) =
        applied<JLabCurve>(
            address,
            "setting the equaliser",
            { "preset ${it.preset}" },
        ) {
            when (val after = Drivers.JLabQcy.writeEq(it.transport, curve.preset, curve.levels)) {
                null -> Confirmation.Unverifiable
                curve -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    override fun setVoiceAware(address: String, v: VoiceAware) =
        applied<VoiceAware>(
            address,
            "setting voiceaware",
            { "${if (it.on) "on" else "off"}, ${it.level.name.lowercase()}" },
        ) {
            when (val after = Drivers.JblBes.writeVoiceAware(it.transport, v)) {
                null -> Confirmation.Unverifiable
                v -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    override fun setSmartTalk(address: String, v: SmartTalk) =
        applied<SmartTalk>(
            address,
            "setting smart talk",
            { "${if (it.on) "on" else "off"}, ${it.timeout.seconds} s" },
        ) {
            when (val after = Drivers.JblBes.writeSmartTalk(it.transport, v)) {
                null -> Confirmation.Unverifiable
                v -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    override fun setLowVolumeEq(address: String, on: Boolean) =
        applied<Boolean>(
            address,
            "setting low volume dynamic eq",
            { if (it) "on" else "off" },
        ) {
            when (val after = Drivers.JblBes.writeLowVolumeEq(it.transport, on)) {
                null -> Confirmation.Unverifiable
                on -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    /**
     * The three XM4 on/off settings, all through [SonyXm4.setSwitch], which writes and
     * then **reads back** — the reply is never the evidence on this device.
     *
     * ⚠ Only reachable for the Sony; [sony] returns Unverifiable for anything else
     * rather than silently doing nothing, because a switch that moves and reports
     * success while sending no bytes is worse than one that says it could not.
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
            val d = it.sony ?: return@applied Confirmation.Unverifiable
            d.setVoiceGuidance(it.transport, on)
        }

    override fun setTouchPanel(address: String, on: Boolean) =
        sonySwitch(address, "the touch panel", SonyTouchPanel, on)

    /**
     * Ask the XM4 to change its [CUSTOM] key.
     *
     * ⚠ **This may end by putting a question on the card rather than finishing.** The
     * device will not commit until its alert is answered, and answering yes drops the
     * audio link — so the answer is the owner's, not ours. [answerButton] resumes it.
     */
    override fun setSonyButton(address: String, name: String) =
        work.execute {
            holding(address) {
                val s = openIfNeeded(address) ?: return@holding
                val d = s.headphones.driver as? Drivers.SonyXm4 ?: return@holding
                val action = SonyButton.Action.entries.firstOrNull { it.name == name }
                if (action == null) {
                    update(address, DeviceState.Unavailable("no such button action: $name"))
                    return@holding
                }
                when (d.beginButtonWrite(s.transport, action)) {
                    ButtonWrite.Asks -> {
                        emit(
                            screen.asking(
                                address,
                                "Changing the button disconnects and reconnects the " +
                                    "headphones. Change it to ${pretty(name)}?",
                            ),
                        )
                    }

                    ButtonWrite.Unchanged -> {
                        // ⚠ No alert means nothing changed — including the ordinary
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
                // time and hoping — measured 2026-08-24, where a single attempt after 6 s
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
                // ⚠ A socket that opens is not a device that will answer. That is the
                // shape of precondition this repo has been caught by before: it passes
                // for the wrong reason and takes the question away.
                repeat(RECONNECT_TRIES) {
                    Thread.sleep(RECONNECT_STEP_MS)
                    val again = openIfNeeded(address)
                    if (again != null && refresh(address, again)) return@holding
                    // That session answers nothing; throw it away rather than reuse it.
                    drop(address)
                }
                // ⚠ Say so rather than leave the old value on screen. The write almost
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
            "refresh $address: read button=${read?.button} state=$before " +
                "→ card now ${card(address)?.settings?.button}",
        )
        return read != null
    }

    /**
     * ⚠ **Takes the whole [ChatDetail]**, because the frame carries all three fields.
     * A per-field setter here would have to invent the other two.
     */
    override fun setChatDetail(address: String, detail: ChatDetail) =
        applied<ChatDetail>(address, "setting speak-to-chat detail", { it.sensitivity.name }) {
            val d =
                it.headphones.driver as? Drivers.SonyXm4
                    ?: return@applied Confirmation.Unverifiable
            when (d.writeChatDetail(it.transport, detail)) {
                detail -> Confirmation.Confirmed
                null -> Confirmation.Unverifiable
                else -> Confirmation.Contradicted(detail)
            }
        }

    private fun sonySwitch(address: String, what: String, switch: SonySwitch, on: Boolean) =
        applied<Boolean>(address, "setting $what", { if (it) "on" else "off" }) {
            val d =
                it.headphones.driver as? Drivers.SonyXm4
                    ?: return@applied Confirmation.Unverifiable
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
            val d =
                it.headphones.driver as? Drivers.SonyXm4
                    ?: return@applied Confirmation.Unverifiable
            d.setFocusOnVoice(it.transport, on)
        }

    override fun setSmartAv(address: String, v: SmartAv) =
        applied<SmartAv>(address, "setting smart audio & video", { it.name.lowercase() }) {
            when (val after = Drivers.JblBes.writeSmartAv(it.transport, v)) {
                null -> Confirmation.Unverifiable
                v -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    override fun setAutoPlay(address: String, on: Boolean) =
        applied<Boolean>(address, "setting auto play and pause", { if (it) "on" else "off" }) {
            when (val after = Drivers.JblBes.writeAutoPlay(it.transport, on)) {
                null -> Confirmation.Unverifiable
                on -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    override fun setBalance(address: String, v: Balance) =
        applied<Balance>(address, "setting the balance", { if (it.on) "on" else "off" }) {
            when (val after = Drivers.JblBes.writeBalance(it.transport, v)) {
                null -> Confirmation.Unverifiable
                v -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    override fun setCurve(address: String, curve: EqCurve) =
        applied<EqCurve>(address, "setting the equaliser", { "table ${it.table}" }) {
            when (
                val after =
                    Drivers.JblBes.writeCurve(
                        it.transport,
                        curve.table,
                        curve.bands.map { b ->
                            b.gain
                        },
                    )
            ) {
                null -> Confirmation.Unverifiable
                curve -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    override fun setSoundQuality(address: String, mode: SoundQuality) =
        applied<SoundQuality>(address, "setting sound quality", { it.name }) {
            val d = it.sony ?: return@applied Confirmation.Unverifiable
            val after =
                d.writeSoundQuality(it.transport, mode) ?: d.readSoundQuality(it.transport)
            when (after) {
                null -> Confirmation.Unverifiable
                mode -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    override fun setButton(address: String, action: BoseButton.Action) =
        applied<BoseButton.Action>(address, "setting the button", { it.name }) {
            val after =
                Drivers.BoseQc45.writeButton(it.transport, action)
                    ?: Drivers.BoseQc45.readButton(it.transport)
            when (after) {
                null -> Confirmation.Unverifiable
                action -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    private fun openIfNeeded(address: String): Session? {
        Sessions.existing(address)?.let { return describe(address, it) }
        val device =
            try {
                adapter?.bondedDevices?.firstOrNull { it.address == address }
            } catch (e: SecurityException) {
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
        update(address, DeviceState.Busy("connecting…"))
        var why = "would not connect"
        val session =
            Control.connect(
                context,
                adapter!!,
                device,
                device.name.orEmpty(),
                uuids,
                resolveLe = { model ->
                    update(address, DeviceState.Busy("looking for $model over LE…"))
                    Scan.find(adapter, LE_MATCH[model] ?: model, 25_000)?.device
                },
                onNote = { why = it },
            )
        if (session == null) {
            update(address, DeviceState.Unavailable(why))
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
     * a Connect button, for a device the tile was driving at that moment. Measured
     * 2026-08-16.
     */
    private fun describe(address: String, session: Session): Session {
        update(address, DeviceState.Busy("reading…"))
        val mode = runCatching { session.headphones.driver.read(session.transport) }.getOrNull()
        // The name the device holds beats the bonded record, which for this phone's
        // QC35 is the LE advertisement's truncation of what its owner actually set.
        runCatching { session.headphones.driver.name(session.transport) }
            .getOrNull()
            ?.let { rename(address, it) }
        // ⚠ **Which sentence to show is a decision, and it is made in :protocol.** This
        // used to assume a null mode meant the device had no read command — the JLab's
        // old case — and said so as a fact about the hardware. Every driver reads now,
        // so the reachable case is a read that did not answer, and on 2026-08-17 a stale
        // link had the JBL described as unreadable while its mode was perfectly fine.
        //
        // ⚠ PROBLEM rather than CAUTION for the second: it did not work, and unlike an
        // unconfirmable write there is something the owner can do about it.
        val note =
            when (
                noMode(
                    session.headphones.driver.reads,
                    mode,
                    hasModes =
                        session.headphones.driver.modes
                            .isNotEmpty(),
                )
            ) {
                null -> {
                    null
                }

                NoMode.NO_READ -> {
                    Note(
                        "this one has no read command; it can be set but not read",
                        NoteKind.CAUTION,
                    )
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
                session.headphones.driver.offeredModes(),
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
     * ⚠ **The screen's contents are kept**, so coming back shows the cards
     * immediately rather than blinking through "connecting"; only the radio links go.
     *
     * ⚠ **In split screen this never fires.** Both halves stay resumed, so `onStop`
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

        /** ⚠ Bounded — `3 s × 8` is 24 s, after which the card says so rather than lying. */
        const val RECONNECT_TRIES = 8

        /**
         * How long a control channel is kept after the last thing that needed it.
         *
         * ⚠ **A backstop, not the mechanism.** Releasing on background ([release],
         * from `onStop`) is what actually stops this app squatting on the radio; this
         * only catches a screen left open and forgotten, holding links for an hour.
         *
         * ⚠ **Deliberately long, and it used to be 8 s.** Coexisting with the vendor
         * apps was the original reason to let go quickly — and that reason is gone:
         * Bose Music, Sony Headphones, JBL and JLab are to be uninstalled once this
         * app replaces them (Pippijn, 2026-08-16). With nothing to yield to, an eager
         * release only buys a reconnect the next time its owner taps — a second on
         * RFCOMM and up to 25 on the JBL, whose rotating address must be found by an
         * LE scan first. Two minutes is long enough that no interaction pays that,
         * and short enough that a forgotten screen does not hold five links all day.
         */
        const val IDLE_MS = 120_000L

        /**
         * What a device calls itself over LE, where that differs from its bonded
         * name. ⚠ The JLab advertises no name at all, so it is matched on a stable
         * run inside its Fast Pair service data.
         */
        val LE_MATCH =
            mapOf(
                "JBL Tour One M2" to "JBL TOUR",
                "JLab JBuds Sport ANC 4" to "21 55 35 33",
            )
    }
}
