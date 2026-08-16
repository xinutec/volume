package org.xinutec.volume

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import org.xinutec.volume.protocol.AncMode
import org.xinutec.volume.protocol.Channels
import org.xinutec.volume.protocol.OneButton
import org.xinutec.volume.protocol.Registry
import org.xinutec.volume.protocol.resulting
import org.xinutec.volume.protocol.set
import java.util.concurrent.Executors

/**
 * ANC from the Quick Settings shade, without opening anything.
 *
 * The point of a native app: changing noise cancelling is the one thing done daily,
 * and going through a launcher to do it is most of the cost of doing it at all.
 *
 * ⚠ **The work does NOT belong to this service.** A tile lives about as long as the
 * shade is open, and a tap costs a second on RFCOMM — up to 25 on the JBL, whose
 * address rotates and must be scanned for. So the exchange runs on a process-level
 * executor and the service only reports on it: pulling the shade down mid-tap loses
 * the label update, never the write.
 *
 * ⚠ Which pair and which mode are decided in [OneButton], tested, because both are
 * wrong in ways this screen could not show — see [Active] on the two-pairs case.
 */
class AncTileService : TileService() {
    /**
     * ⚠ **Off the main thread, and this is not a nicety.** [Connected.addresses]
     * waits on a latch that `getProfileProxy` releases from its service callback —
     * and that callback is delivered on the MAIN thread. Called from here directly it
     * blocks the very looper that would answer it, waits out the full timeout and
     * returns an empty set, so the tile reported "nothing connected" with the
     * headphones plainly connected in the panel above it. It reproduces every time
     * and it looks exactly like a device-detection bug.
     */
    override fun onStartListening() {
        super.onStartListening()
        val context = applicationContext
        work.execute { show(Shown.load(context)) }
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        // Feedback first: the exchange takes a second or more, and a tile that sits
        // unchanged reads as a tap that missed.
        tile.subtitle = "…"
        tile.updateTile()
        work.execute {
            val next = tap(applicationContext)
            // ⚠ Every outcome, not just the ones that drove something. A tap that
            // found nothing to act on used to log nothing at all, which is
            // indistinguishable from a tile that never fired — and that ambiguity
            // has already cost time twice in this repo.
            Log.i(LIVE, "tile tap -> ${next.label}: ${next.subtitle}")
            show(next)
        }
    }

    private fun show(state: Shown) {
        val tile = qsTile
        // ⚠ A null tile is why a correct-looking service shows nothing, and it is
        // silent — so say which happened rather than returning into the dark.
        Log.i(LIVE, "tile show: $state (qsTile=${tile != null})")
        if (tile == null) return
        tile.state = if (state.available) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
        tile.label = state.label
        tile.subtitle = state.subtitle
        tile.icon = Icon.createWithResource(this, R.drawable.ic_anc)
        tile.updateTile()
    }

    /** What the tile says: never a claim beyond what was actually read. */
    private data class Shown(
        val available: Boolean,
        val label: String,
        val subtitle: String?,
    ) {
        companion object {
            /** Cheap: who is here, without opening a control channel to ask. */
            fun load(context: Context): Shown {
                val adapter =
                    context.getSystemService(BluetoothManager::class.java)?.adapter
                        ?: return Shown(false, "Volume", "no Bluetooth")
                if (!adapter.isEnabled) return Shown(false, "Volume", "Bluetooth off")
                val here = drivable(context, adapter)
                val target = OneButton.target(here.map { it.first }, Active.address(context))
                return when {
                    here.isEmpty() -> Shown(false, "Volume", "nothing connected")

                    // ⚠ Available, not unavailable: the pair is there and the write
                    // would work — we simply may not pick FOR its owner which of two
                    // it lands on, so say that rather than looking broken.
                    target == null -> Shown(false, "Volume", "two connected — open the app")

                    else -> Shown(true, here.first { it.first == target }.second, "tap to change")
                }
            }
        }
    }

    private companion object {
        /**
         * Process-level, and deliberately not the service's: it must outlive the
         * shade closing. Single-threaded for the same reason as [DeviceController]'s
         * — two connects at once contend for one radio.
         */
        val work = Executors.newSingleThreadExecutor()

        /** Bonded, connected and something we know how to drive: address to name. */
        fun drivable(context: Context, adapter: BluetoothAdapter): List<Pair<String, String>> {
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

        /**
         * One tap: open, read, move to the next mode, report what the device says.
         *
         * ⚠ The session is closed immediately rather than leased. A tile has no
         * screen to come back to, so nothing would reuse the channel, and holding it
         * would leave a live link owned by a service that is already gone.
         */
        fun tap(context: Context): Shown {
            val adapter =
                context.getSystemService(BluetoothManager::class.java)?.adapter
                    ?: return Shown(false, "Volume", "no Bluetooth")
            val here = drivable(context, adapter)
            val target =
                OneButton.target(here.map { it.first }, Active.address(context))
                    ?: return Shown.load(context)
            val device =
                adapter.bondedDevices.orEmpty().firstOrNull { it.address == target }
                    ?: return Shown(false, "Volume", "no longer bonded")
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
                ) ?: return Shown(false, device.name ?: "Volume", why)
            return session.use { s ->
                val driver = s.headphones.driver
                val current = runCatching { driver.read(s.transport) }.getOrNull()
                val next = OneButton.next(driver.modes.toList(), current)
                val confirmation = runCatching { driver.set(s.transport, next) }.getOrNull()
                Log.i(LIVE, "tile: $target $current -> $next ($confirmation)")
                Shown(
                    available = true,
                    label = s.headphones.model,
                    // ⚠ Says what was CONFIRMED. An unconfirmable write (the JLab
                    // cannot read back) must not print the mode as though it were
                    // read — that is the laundering `Confirmation` exists to stop.
                    subtitle = confirmation?.resulting(next)?.let(::label) ?: "${label(next)} sent",
                )
            }
        }

        /** Same LE naming quirks as the app's list; see `DeviceController.LE_MATCH`. */
        val LE_MATCH =
            mapOf(
                "JBL Tour One M2" to "JBL TOUR",
                "JLab JBuds Sport ANC 4" to "21 55 35 33",
            )
    }
}
