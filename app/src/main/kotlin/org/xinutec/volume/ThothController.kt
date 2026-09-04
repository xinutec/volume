package org.xinutec.volume

import android.content.Context
import android.util.Log
import org.xinutec.volume.protocol.InputPick
import org.xinutec.volume.protocol.PairPatch
import org.xinutec.volume.protocol.THOTH_DEFAULT_HOST
import org.xinutec.volume.protocol.ThothClient
import org.xinutec.volume.protocol.ThothInput
import org.xinutec.volume.protocol.ThothReach
import org.xinutec.volume.protocol.ThothRefused
import org.xinutec.volume.protocol.ThothScreen
import org.xinutec.volume.protocol.pickIs
import org.xinutec.volume.protocol.speakers
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Tag for the Mac link: what was asked, what refused, and why the card went blank.
 *
 * `adb logcat -s VolumeThoth`. Separate from [LIVE] because the two answer different
 * questions and the Bluetooth one is noisy.
 */
internal const val THOTH = "VolumeThoth"

/**
 * The Mac's audio, kept in step with the screen.
 *
 * ⚠ **Its own thread, NOT [Sessions.work].** That executor is single-threaded on
 * purpose — two Bluetooth connects at once contend for the radio — and the JBL's LE
 * scan can hold it for twenty-five seconds. A poll queued behind that would leave the
 * Mac card frozen for the length of a headphone connect, and a headphone connect
 * queued behind a stalled HTTP read would be worse. They share nothing, so they queue
 * separately.
 *
 * The shape is the web client's, because the server was designed around it: state is
 * DERIVED live from the hardware on every read, so anything changed elsewhere — the
 * Mac's own settings, a browser, another phone — exists only until re-read. Hence a
 * poll; hence a poll that runs only while nothing is being dragged.
 */
class ThothController(
    context: Context,
    private val onScreen: (ThothScreen) -> Unit,
) {
    private val prefs = context.getSharedPreferences("thoth", Context.MODE_PRIVATE)
    private val work = Executors.newSingleThreadScheduledExecutor()
    private val client = ThothClient(ThothHttp { host })

    /** Where to look. Persisted, because a LAN address outlives an install. */
    @Volatile
    var host: String = prefs.getString("host", null) ?: THOTH_DEFAULT_HOST
        private set

    private var screen = ThothScreen.looking(host)
    private var poller: ScheduledFuture<*>? = null
    private var ticks = 0L

    // Coalescing. One pending patch and one pending level per cabinet, flushed at
    // most every FLUSH_MS — a drag is a change per frame and must not be a request
    // per frame. Touched only on `work`.
    private var pending = PairPatch()
    private val pendingCabinets = mutableMapOf<String, Double>()
    private var flushDue = false
    private var inFlight = false
    private var lastActivity = 0L

    /**
     * Follow the Mac while the screen is up.
     *
     * ⚠ The first emit is queued onto `work`, not run here. [start] is called from
     * `onStart` — the UI thread — and everything below `screen` is otherwise touched
     * only by the poll and the flush, both of which run on this executor. Emitting
     * inline would be the one write from a second thread, which is exactly the kind of
     * race that shows up as a card that is occasionally a poll behind.
     */
    fun start() {
        if (poller != null) return
        work.execute { emit(screen.copy(host = host)) }
        poller = work.scheduleWithFixedDelay(::tick, 0, POLL_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Off screen: stop asking.
     *
     * Nothing to release — HTTP holds no link between requests — so unlike the
     * headphones this is purely about not waking the radio for a card nobody is
     * looking at.
     */
    fun stop() {
        poller?.cancel(false)
        poller = null
    }

    /** Point the card somewhere else, and look there now. */
    fun setHost(next: String) {
        val cleaned = next.trim().ifEmpty { THOTH_DEFAULT_HOST }
        host = cleaned
        prefs.edit().putString("host", cleaned).apply()
        work.execute {
            screen = ThothScreen.looking(cleaned)
            emit(screen)
            tick()
        }
    }

    // ---- reading -----------------------------------------------------------

    /**
     * One poll.
     *
     * ⚠ **Skipped entirely while an edit is in flight or just finished.** The server
     * derives its answer from the hardware, so a poll that overlaps a drag returns
     * the level from before the last write and drags the thumb backwards. The web
     * client learned this first; the quiet window is the same 2.5 s.
     */
    private fun tick() {
        if (!idle()) return
        val n = ticks++
        try {
            val pair = client.pair()
            val cabinets = client.cabinets()
            // Read every tick: the pin re-asserting, System Settings, or a
            // Bluetooth mic connecting all change this behind our back, and the pin
            // putting the default back is exactly what somebody would want to see.
            val input = client.input()
            // The output device list is the one thing here that essentially never
            // moves — a speaker has to be switched on or unpaired — so it is read a
            // quarter as often as the levels it is a picker for.
            val outputs =
                if (n % DEVICES_EVERY == 0L || screen.outputs.isEmpty()) {
                    client.devices().speakers()
                } else {
                    screen.outputs
                }
            emit(
                ThothScreen(
                    host = host,
                    reach = ThothReach.LIVE,
                    pair = pair,
                    outputs = outputs,
                    input = input,
                    cabinets = cabinets,
                    refusal = screen.refusal,
                ),
            )
        } catch (e: Exception) {
            // Anything at all: unroutable, refused, timed out, a body that did not
            // parse. ⚠ All of it is "the Mac is not answering me", which is a true
            // sentence and the one on the card — not a silent empty card.
            Log.i(THOTH, "poll $host: ${e.javaClass.simpleName}: ${e.message}")
            emit(ThothScreen.away(host))
        }
    }

    // ---- writing -----------------------------------------------------------

    /** Queue a pair change; sent within [FLUSH_MS]. */
    fun push(patch: PairPatch) =
        work.execute {
            pending = pending.and(patch)
            lastActivity = System.currentTimeMillis()
            scheduleFlush()
        }

    /** Queue a cabinet level; sent within [FLUSH_MS]. */
    fun pushCabinet(cabinet: String, volume: Double) =
        work.execute {
            pendingCabinets[cabinet] = volume
            lastActivity = System.currentTimeMillis()
            scheduleFlush()
        }

    private fun scheduleFlush() {
        if (flushDue) return
        flushDue = true
        work.schedule(::flush, FLUSH_MS, TimeUnit.MILLISECONDS)
    }

    private fun flush() {
        flushDue = false
        val patch = pending
        val cabinets = pendingCabinets.toMap()
        pending = PairPatch()
        pendingCabinets.clear()
        if (patch.empty && cabinets.isEmpty()) return
        inFlight = true
        try {
            if (!patch.empty) {
                val next = client.setPair(patch)
                // ⚠ The reply is adopted for a STRUCTURAL change and dropped for a
                // level one. Picking a speaker or flipping stereo wants to show at
                // once, and nothing is being dragged; a volume reply landing
                // mid-drag would re-seed the thumb from a value two frames old.
                // Levels are re-read by the poll once the drag settles.
                if (patch.left != null || patch.right != null || patch.stereo != null) {
                    emit(screen.copy(pair = next, refusal = null))
                } else if (screen.refusal != null) {
                    emit(screen.copy(refusal = null))
                }
            }
            for ((cabinet, volume) in cabinets) client.setCabinet(cabinet, volume)
        } catch (e: ThothRefused) {
            // The server was reached and said no, in words meant to be read.
            Log.i(THOTH, "refused: ${e.status} ${e.reason}")
            emit(screen.copy(refusal = e.reason))
        } catch (e: Exception) {
            Log.i(THOTH, "write $host: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            inFlight = false
            lastActivity = System.currentTimeMillis()
        }
    }

    /**
     * Re-lock the two speakers' clocks. Immediate — there is nothing to coalesce.
     *
     * ⚠ A failure is put on the CARD, not only in the log. The button's whole job is
     * to fix drift nobody can hear until they listen for it, so a press that silently
     * did nothing is indistinguishable from a press that worked.
     */
    fun recalibrate() =
        work.execute {
            try {
                emit(screen.copy(pair = client.recalibrate(), refusal = null))
            } catch (e: Exception) {
                Log.i(THOTH, "recalibrate: ${e.message}")
                emit(screen.copy(refusal = "Re-sync failed — ${e.message}"))
            }
            lastActivity = System.currentTimeMillis()
        }

    /**
     * Choose the default input.
     *
     * ⚠ Which call this makes is [ThothInput.pickIs]'s decision, not this method's: a
     * plain switch while a pin is standing is undone by the server's own guard within
     * a second or two, so the pick has to MOVE the pin instead.
     */
    fun chooseInput(uid: String) =
        write { input ->
            when (input.pickIs()) {
                InputPick.SET -> client.setInput(uid)
                InputPick.REPIN -> client.setInputPin(uid)
            }
        }

    /** Pin the live default, or clear the pin. */
    fun pinInput(on: Boolean) = write { input -> client.setInputPin(if (on) input.current else "") }

    private fun write(call: (ThothInput) -> ThothInput) =
        work.execute {
            val input = screen.input ?: return@execute
            try {
                emit(screen.copy(input = call(input), refusal = null))
            } catch (e: ThothRefused) {
                emit(screen.copy(refusal = e.reason))
            } catch (e: Exception) {
                Log.i(THOTH, "input: ${e.message}")
            }
            lastActivity = System.currentTimeMillis()
        }

    // ---- plumbing ----------------------------------------------------------

    /** No edit pending, none in flight, and none just finished. */
    private fun idle(): Boolean =
        pending.empty &&
            pendingCabinets.isEmpty() &&
            !inFlight &&
            System.currentTimeMillis() - lastActivity >= QUIET_MS

    private fun emit(next: ThothScreen) {
        screen = next
        onScreen(next)
    }

    private companion object {
        const val POLL_MS = 3_000L
        const val FLUSH_MS = 80L
        const val QUIET_MS = 2_500L
        const val DEVICES_EVERY = 4L
    }
}
