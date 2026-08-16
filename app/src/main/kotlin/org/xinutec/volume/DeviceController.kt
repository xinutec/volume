package org.xinutec.volume

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import org.xinutec.volume.protocol.AncMode
import org.xinutec.volume.protocol.AutoOff
import org.xinutec.volume.protocol.BoseBands
import org.xinutec.volume.protocol.BoseButton
import org.xinutec.volume.protocol.Confirmation
import org.xinutec.volume.protocol.DeviceCard
import org.xinutec.volume.protocol.DeviceState
import org.xinutec.volume.protocol.Drivers
import org.xinutec.volume.protocol.Emptiness
import org.xinutec.volume.protocol.EqSetting
import org.xinutec.volume.protocol.MultipointDriver
import org.xinutec.volume.protocol.Note
import org.xinutec.volume.protocol.NoteKind
import org.xinutec.volume.protocol.Registry
import org.xinutec.volume.protocol.Screen
import org.xinutec.volume.protocol.SettingKind
import org.xinutec.volume.protocol.Settings
import org.xinutec.volume.protocol.SoundQuality
import org.xinutec.volume.protocol.Wearable
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
        // ⚠ First, because SPP does not distinguish headphones from a laptop or a
        // speaker — and the speakers are always in the room. `Crowley` and the
        // SoundLink both listed as drivable, with a Connect that could only fail.
        if (!Wearable.couldBeHeadphones(d.bluetoothClass?.deviceClass ?: 0)) return false
        val uuids =
            d.uuids
                ?.map { it.uuid.toString() }
                ?.toSet()
                .orEmpty()
        if (Registry.fromAdvertisement(d.name.orEmpty(), uuids) != null) return true
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
                s.headphones.driver.modes
                    .toList(),
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
                update(address, DeviceState.Busy("reading settings…"))
                val settings = runCatching { readSettings(s) }.getOrNull()
                if (settings == null) {
                    drop(address)
                    update(address, DeviceState.Unavailable("lost the connection while reading"))
                    return@holding
                }
                // Put the card back to Ready before attaching, or `withSettings`
                // finds a Busy card and drops the read on the floor.
                describe(address, s)
                emit(screen.withSettings(address, settings))
            }
        }

    /**
     * ⚠ **`refuses` is not a guess about the device — it is measured, on 2026-08-16.**
     * The XM4 acks `d8 d2 01 01` and `f8 06 01 31` and then ignores both, and Sony's
     * own app fails at multipoint identically. The QC45 accepts the same two settings
     * from this code. So the set is per-driver and is stated where a future session
     * will see it next to the evidence, rather than being rediscovered by a user
     * watching a switch spring back.
     */
    private fun readSettings(s: Session): Settings =
        when (val d = s.headphones.driver) {
            is Drivers.SonyXm4 -> {
                Settings(
                    eq = d.readEq(s.transport),
                    bands = d.bands(s.transport),
                    multipoint = d.readMultipoint(s.transport),
                    autoOff = d.readAutoOff(s.transport),
                    soundQuality = d.readSoundQuality(s.transport),
                    button = d.readButton(s.transport)?.name,
                    refuses = setOf(SettingKind.MULTIPOINT, SettingKind.BUTTON),
                )
            }

            // ⚠ Not `d.` — matching an `object` does not smart-cast, so this names
            // it again rather than going through the `AncDriver` it is typed as.
            Drivers.BoseQc45 -> {
                Settings(
                    tone = Drivers.BoseQc45.readEq(s.transport),
                    multipoint = Drivers.BoseQc45.readMultipoint(s.transport),
                    button = Drivers.BoseQc45.readButton(s.transport)?.name,
                )
            }

            else -> {
                Settings()
            }
        }

    /** Every settings write goes through here: drive it, then re-read the truth. */
    private fun <T> applied(
        address: String,
        what: String,
        describe: (T) -> String,
        body: (Session) -> Confirmation<T>,
    ) = work.execute {
        holding(address) {
            val s = openIfNeeded(address) ?: return@holding
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
            update(
                address,
                DeviceState.Ready(
                    s.headphones.model,
                    s.headphones.driver.modes
                        .toList(),
                    runCatching { s.headphones.driver.read(s.transport) }.getOrNull(),
                    outcome.settingNote(describe),
                ),
            )
            emit(screen.withSettings(address, settings))
        }
    }

    override fun setEqPreset(address: String, preset: Int) =
        applied<EqSetting>(address, "setting the equaliser", { "preset ${it.preset}" }) {
            (it.headphones.driver as Drivers.SonyXm4).setEq(it.transport, preset)
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
            (it.headphones.driver as MultipointDriver).setMultipoint(it.transport, on)
        }

    override fun setAutoOff(address: String, mode: AutoOff) =
        applied<AutoOff>(address, "setting power off", { it.name }) {
            val d = it.headphones.driver as Drivers.SonyXm4
            when (val after = d.writeAutoOff(it.transport, mode) ?: d.readAutoOff(it.transport)) {
                null -> Confirmation.Unverifiable
                mode -> Confirmation.Confirmed
                else -> Confirmation.Contradicted(after)
            }
        }

    override fun setSoundQuality(address: String, mode: SoundQuality) =
        applied<SoundQuality>(address, "setting sound quality", { it.name }) {
            val d = it.headphones.driver as Drivers.SonyXm4
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
        update(
            address,
            DeviceState.Ready(
                session.headphones.model,
                session.headphones.driver.modes
                    .toList(),
                mode,
                // ⚠ CAUTION, not a failure: this device has no read command, so
                // "no mode" is its permanent normal state, not a lost reading.
                if (mode == null) {
                    Note("this one reports no mode; it can be set but not read", NoteKind.CAUTION)
                } else {
                    null
                },
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

    private companion object {
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
