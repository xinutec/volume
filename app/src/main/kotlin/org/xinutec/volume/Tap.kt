package org.xinutec.volume

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import org.xinutec.volume.protocol.Channels
import org.xinutec.volume.protocol.OneButton
import org.xinutec.volume.protocol.Registry
import org.xinutec.volume.protocol.resulting
import org.xinutec.volume.protocol.set

/**
 * One control, one tap — shared by the Quick Settings tile and the home-screen
 * widget, which are the same behaviour behind two different pieces of Android
 * plumbing. Keeping it in one place is not tidiness: they would otherwise drift, and
 * the drift would be in *which device gets written to*, which nothing on either
 * surface would reveal.
 *
 * ⚠ **Everything here blocks and none of it may run on the main thread.**
 * [Connected.addresses] waits on a latch released by `getProfileProxy`'s callback,
 * and that callback is delivered on the MAIN thread — so calling from there waits
 * out the full timeout and returns an empty set, which reads as "nothing connected"
 * with the headphones plainly connected. Use [work].
 */
object Tap {
    /**
     * ⚠ **[Sessions]'s thread, not one of our own.** Sharing the *session* is not
     * enough: a second executor means the screen's read and a tile's write interleave
     * frames on one channel, and the replies come back attached to the wrong request.
     * Measured — the tile confirmed ANC and the next read said OFF, twice, while the
     * app was refreshing on the other thread. It reads as a flaky device.
     *
     * One radio, one session store, one thread.
     */
    val work get() = Sessions.work

    /** What a surface should say. Never a claim beyond what was actually read. */
    data class State(
        val available: Boolean,
        val label: String,
        val detail: String?,
    )

    /** Who is here, cheaply — without opening a control channel to ask. */
    fun load(context: Context): State {
        val adapter =
            context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return State(false, "Volume", "no Bluetooth")
        if (!adapter.isEnabled) return State(false, "Volume", "Bluetooth off")
        val here = drivable(context, adapter)
        val target = OneButton.target(here.map { it.first }, Active.address(context))
        return when {
            here.isEmpty() -> State(false, "Volume", "nothing connected")

            // ⚠ Refuses rather than guessing. Writing to the pair that is NOT in your
            // ears reports success exactly like writing to the right one, so there is
            // no feedback that would ever catch it.
            target == null -> State(false, "Volume", "two connected — open the app")

            else -> State(true, here.first { it.first == target }.second, "tap to change")
        }
    }

    /**
     * Open, read, move to the next mode, report what the device says.
     *
     * ⚠ The session is closed immediately rather than leased. Neither surface has a
     * screen to come back to, so nothing would reuse the channel, and holding it
     * would leave a live link owned by something already gone.
     */
    fun next(context: Context): State {
        val adapter =
            context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return State(false, "Volume", "no Bluetooth")
        val here = drivable(context, adapter)
        val target =
            OneButton.target(here.map { it.first }, Active.address(context))
                ?: return load(context)
        return Sessions.holding(target) { drive(context, adapter, target) }
    }

    /**
     * ⚠ **Reuses the process's session, never opens a second one.** A headphone
     * accepts one control channel: with the app on screen holding the JBL's GATT
     * client, opening another for a tile tap fails with `GATT would not open` — which
     * is what happened, repeatedly, and read as a device fault rather than as this
     * app fighting itself. In split screen the app is never stopped, so it never let
     * go on its own.
     */
    private fun drive(
        context: Context,
        adapter: android.bluetooth.BluetoothAdapter,
        target: String,
    ): State {
        Sessions.existing(target)?.let { return exchange(target, it) }
        val device =
            adapter.bondedDevices.orEmpty().firstOrNull { it.address == target }
                ?: return State(false, "Volume", "no longer bonded")
        val uuids =
            device.uuids
                ?.map { it.uuid.toString() }
                ?.toSet()
                .orEmpty()
        var why = "would not connect"
        val session =
            Control.connect(
                context,
                adapter,
                device,
                device.name.orEmpty(),
                uuids,
                resolveLe = { model ->
                    Scan.find(adapter, LE_MATCH[model] ?: model, 25_000)?.device
                },
                onNote = { why = it },
            ) ?: return State(false, device.name ?: "Volume", why)
        // ⚠ Handed to [Sessions], NOT closed here. The screen may want it a moment
        // later, and its lease is what decides when it goes.
        Sessions.remember(target, session)
        return exchange(target, session)
    }

    private fun exchange(target: String, s: Session): State {
        val driver = s.headphones.driver
        val current = runCatching { driver.read(s.transport) }.getOrNull()
        val to = OneButton.next(driver.modes.toList(), current)
        val confirmation = runCatching { driver.set(s.transport, to) }.getOrNull()
        Log.i(LIVE, "tap: $target $current -> $to ($confirmation)")
        // ⚠ Tell the screen. It shares the session but would otherwise keep showing
        // the mode it last read, while the shade changed it underneath.
        Sessions.changed(target)
        return State(
            available = true,
            label = s.headphones.model,
            // ⚠ Says what was CONFIRMED. An unconfirmable write (the JLab cannot
            // read back) must not print the mode as though it had been read —
            // that is the laundering `Confirmation` exists to stop.
            detail = confirmation?.resulting(to)?.let(::label) ?: "${label(to)} sent",
        )
    }

    /** Bonded, connected, and something we know how to drive: address to name. */
    private fun drivable(context: Context, adapter: BluetoothAdapter): List<Pair<String, String>> {
        val here = Connected.addresses(context, adapter)
        return try {
            adapter.bondedDevices
                .orEmpty()
                .filter { it.address in here }
                .filter { d ->
                    val uuids =
                        d.uuids
                            ?.map { it.uuid.toString().lowercase() }
                            ?.toSet()
                            .orEmpty()
                    Registry.fromAdvertisement(d.name.orEmpty(), uuids) != null ||
                        Channels.SPP in uuids
                }.map { it.address to (it.name ?: it.address) }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /** Same LE naming quirks as the app's list; see `DeviceController.LE_MATCH`. */
    private val LE_MATCH =
        mapOf(
            "JBL Tour One M2" to "JBL TOUR",
            "JLab JBuds Sport ANC 4" to "21 55 35 33",
        )
}
