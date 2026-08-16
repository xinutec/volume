package org.xinutec.volume

import android.util.Log
import org.xinutec.volume.protocol.Leases
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The process's control channels — **one owner, because there is one radio**.
 *
 * ⚠ **This exists because the app and the tile fought each other.** They ran in the
 * same process with separate session maps, so with the screen up and the app holding
 * the JBL's GATT client, a tile or widget tap opened a *second* client to the same
 * device and got `GATT would not open`. The phone kept Volume in a split screen, so
 * the app was permanently resumed and the tile was permanently broken.
 *
 * ⚠ **`mCurrentFocus` naming another app does NOT mean this one is stopped.** In
 * split screen both halves are resumed and only one has focus, so `onStop` never
 * fires and nothing is ever released. That misreading cost the wrong diagnosis
 * first: the app was blamed for holding nothing while it held an open connection.
 *
 * Everything runs on one thread for the same reason it is one owner: two connects at
 * once contend for the radio, and what that produces looks like a protocol fault.
 */
object Sessions {
    /**
     * How long a channel is kept after the last thing that needed it.
     *
     * A backstop, not the mechanism — [releaseAll] on backgrounding is what stops
     * this app squatting. Long, because nothing is waiting for the channel (the
     * vendor apps are being uninstalled) and a reopen costs its owner a second on
     * RFCOMM, more on a device that must be scanned for first.
     */
    private const val IDLE_MS = 120_000L

    val work = Executors.newSingleThreadScheduledExecutor()

    private val open = ConcurrentHashMap<String, Session>()
    private val leases = Leases(IDLE_MS)

    init {
        work.scheduleWithFixedDelay(::sweep, IDLE_MS, IDLE_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Anyone who wants telling when a device's mode was changed from elsewhere.
     *
     * ⚠ Exists because the tile and the screen are now two views of one device. A
     * tap from the shade drove the JBL between ANC and Ambient while the card still
     * read "Noise cancelling" — the surfaces shared a session but not the news.
     *
     * ⚠ A *notification*, not a poll. Nothing here may go and ask a device what it
     * is doing; re-reading a headphone nobody is looking at is exactly what
     * `onStop`'s release exists to prevent.
     */
    private val watchers = CopyOnWriteArrayList<(String) -> Unit>()

    fun watch(f: (String) -> Unit) {
        watchers += f
    }

    fun unwatch(f: (String) -> Unit) {
        watchers -= f
    }

    /** Something changed [address] — tell whoever is showing it. */
    fun changed(address: String) = watchers.forEach { runCatching { it(address) } }

    fun existing(address: String): Session? = open[address]

    fun remember(address: String, session: Session) {
        open[address] = session
    }

    fun held(): Set<String> = open.keys.toSet()

    /**
     * Run [body] with [address]'s lease held, and give it back however that ends.
     *
     * ⚠ The `finally` is the point: an early return would leave the address marked
     * in-flight for ever, and [Leases] deliberately never expires in-flight work — so
     * the channel would be held until the process died, which is the bug this whole
     * mechanism exists to prevent, rebuilt one level down.
     *
     * ⚠ Not reentrant. Callers bracket; the thing they call must not bracket again.
     */
    fun <T> holding(address: String, body: () -> T): T {
        leases.begin(address)
        try {
            return body()
        } finally {
            // ⚠ `containsKey` spelled out: `address in open` on a ConcurrentHashMap
            // resolves to `containsValue` (KT-18053) — it would compare an address
            // against Session objects, always miss, and forget every lease.
            if (open.containsKey(address)) {
                leases.end(address, System.currentTimeMillis())
            } else {
                leases.forget(address)
            }
        }
    }

    fun drop(address: String) {
        leases.forget(address)
        open.remove(address)?.let { runCatching { it.close() } }
    }

    /** Let go of everything now — nothing is on screen to justify holding it. */
    fun releaseAll() =
        work.execute {
            if (open.isEmpty()) return@execute
            Log.i(LIVE, "releasing all: ${open.keys.joinToString()}")
            open.keys.toList().forEach(::drop)
        }

    private fun sweep() {
        // Already on the work thread — scheduled there — so no `execute` wrapper.
        val due = leases.expired(System.currentTimeMillis())
        if (due.isEmpty()) return
        Log.i(LIVE, "lease expired, releasing: ${due.joinToString()}")
        due.forEach(::drop)
    }
}
