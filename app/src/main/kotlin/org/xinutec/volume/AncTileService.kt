package org.xinutec.volume

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * ANC from the Quick Settings shade, without opening anything.
 *
 * The point of a native app: changing noise cancelling is the one thing done daily,
 * and going through a launcher to do it is most of the cost of doing it at all.
 *
 * ⚠ **The work does NOT belong to this service.** A tile lives about as long as the
 * shade is open, and a tap costs a second or more. So the exchange runs on [Tap]'s
 * process-level executor and the service only reports on it: closing the shade
 * mid-tap loses the label update, never the write.
 *
 * ⚠ **Reinstalling the app removes this tile from the panel**, silently — the
 * `sysui_qs_tiles` setting drops it and nothing says so. Re-add with
 * `adb shell cmd statusbar add-tile org.xinutec.volume/.AncTileService`, and note
 * `cmd statusbar click-tile` only reaches a bound service, i.e. while the shade is
 * open (`cmd statusbar expand-settings` first). Both of those looked like a dead
 * tile for several rounds.
 */
class AncTileService : TileService() {
    /**
     * ⚠ **Off the main thread, and this is not a nicety.** [Connected.addresses]
     * waits on a latch that `getProfileProxy` releases from its service callback, and
     * that callback is delivered on the MAIN thread. Called from here directly it
     * blocks the very looper that would answer it, waits out the full timeout and
     * returns an empty set — so the tile read "nothing connected" with the headphones
     * plainly connected in the panel above it. Reproduces every time, and it looks
     * exactly like a device-detection bug.
     */
    override fun onStartListening() {
        super.onStartListening()
        val context = applicationContext
        Tap.work.execute { show(Tap.load(context)) }
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        // Feedback first: the exchange takes a second or more, and a tile that sits
        // unchanged reads as a tap that missed.
        tile.subtitle = "…"
        tile.updateTile()
        val context = applicationContext
        Tap.work.execute {
            val next = Tap.next(context)
            // ⚠ Every outcome, not only the ones that drove something. A tap that
            // found nothing to act on used to log nothing at all, which is
            // indistinguishable from a tile that never fired.
            Log.i(LIVE, "tile tap -> ${next.label}: ${next.detail}")
            show(next)
        }
    }

    private fun show(state: Tap.State) {
        val tile = qsTile
        // ⚠ A null tile is why a correct-looking service shows nothing, and it is
        // silent — so say which happened rather than returning into the dark.
        Log.i(LIVE, "tile show: $state (qsTile=${tile != null})")
        if (tile == null) return
        tile.state = if (state.available) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
        tile.label = state.label
        tile.subtitle = state.detail
        tile.icon = Icon.createWithResource(this, R.drawable.ic_anc)
        tile.updateTile()
    }
}
