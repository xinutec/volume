package org.xinutec.volume

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import org.xinutec.volume.protocol.AncMode
import org.xinutec.volume.protocol.DeviceCard
import org.xinutec.volume.protocol.DeviceState
import org.xinutec.volume.protocol.Note
import org.xinutec.volume.protocol.NoteKind
import org.xinutec.volume.protocol.Registry
import org.xinutec.volume.protocol.Screen
import org.xinutec.volume.protocol.note
import org.xinutec.volume.protocol.resulting
import org.xinutec.volume.protocol.set
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
) {
    private val work = Executors.newSingleThreadExecutor()
    private val sessions = ConcurrentHashMap<String, Session>()
    private var screen = Screen(emptyList())

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
            val adapter = adapter ?: return@execute emit(Screen(emptyList()))
            val here = Connected.addresses(context, adapter)
            val bonded =
                try {
                    adapter.bondedDevices.orEmpty()
                } catch (e: SecurityException) {
                    emit(Screen(emptyList()))
                    return@execute
                }
            val listed =
                bonded
                    .filter { it.address in here && drivable(it) }
                    .sortedBy { it.name ?: it.address }
            emit(
                Screen(
                    listed.map {
                        DeviceCard(it.name ?: "(unnamed)", it.address, DeviceState.Idle)
                    },
                ),
            )
            // Sequentially, on this one thread: two connects at once contend for the
            // radio, and what that produces looks like a protocol fault.
            listed.forEach { openIfNeeded(it.address) }
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
        if (Registry.fromAdvertisement(d.name.orEmpty(), uuids) != null) return true
        return org.xinutec.volume.protocol.Channels.SPP in uuids.map { it.lowercase() }
    }

    fun connect(address: String) = work.execute { openIfNeeded(address) }

    fun set(address: String, mode: AncMode) =
        work.execute {
            val s = openIfNeeded(address) ?: return@execute
            update(address, DeviceState.Busy("setting ${mode.name.lowercase()}…"))
            val result = runCatching { s.headphones.driver.set(s.transport, mode) }
            result.onFailure {
                drop(address)
                update(address, DeviceState.Unavailable("lost the connection: ${it.message}"))
            }
            val c = result.getOrNull() ?: return@execute
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

    private fun openIfNeeded(address: String): Session? {
        sessions[address]?.let { return it }
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
        sessions[address] = session
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

    private fun drop(address: String) {
        sessions.remove(address)?.let { runCatching { it.close() } }
    }

    fun closeAll() {
        sessions.keys.toList().forEach(::drop)
        work.shutdownNow()
    }

    private fun update(address: String, state: DeviceState) = emit(screen.with(address, state))

    private fun rename(address: String, name: String) = emit(screen.renamed(address, name))

    private fun emit(next: Screen) {
        screen = next
        onScreen(next)
    }

    private companion object {
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
