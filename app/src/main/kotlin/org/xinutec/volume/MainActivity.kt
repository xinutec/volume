package org.xinutec.volume

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import org.xinutec.volume.protocol.AncMode
import org.xinutec.volume.protocol.AutoOff
import org.xinutec.volume.protocol.BoseBands
import org.xinutec.volume.protocol.BoseButton
import org.xinutec.volume.protocol.BoseEq
import org.xinutec.volume.protocol.ButtonWrite
import org.xinutec.volume.protocol.Channels
import org.xinutec.volume.protocol.Confirmation
import org.xinutec.volume.protocol.Drivers
import org.xinutec.volume.protocol.Hex
import org.xinutec.volume.protocol.SonyButton
import org.xinutec.volume.protocol.SonyDsee
import org.xinutec.volume.protocol.SonyEq
import org.xinutec.volume.protocol.SonyFrame
import org.xinutec.volume.protocol.SonyPauseOnRemoval
import org.xinutec.volume.protocol.SonySpeakToChat
import org.xinutec.volume.protocol.SonyTouchPanel
import org.xinutec.volume.protocol.SoundQuality
import org.xinutec.volume.protocol.Sweep
import org.xinutec.volume.protocol.Transport
import org.xinutec.volume.protocol.set
import org.xinutec.volume.protocol.setEq
import org.xinutec.volume.protocol.setMultipoint
import java.util.UUID

/**
 * The #783 probe, driven from the terminal.
 *
 * ⚠ **Ops normally run in [ProbeService], not here.** An activity can be told to start
 * and never receive the intent (#967); a service cannot. This is the same [Probes] with
 * a different [emit] — one that also appends to a screen, so a run can be watched while
 * the headphones are on your head. `probe.sh` reaches it with `VOLUME_PROBE_ACTIVITY=1`.
 *
 * ⚠ Hearing safety: this sends whatever it is given. Probe with reads. Do not use
 * a volume command as the round-trip proof, and restore any level touched for a
 * test in the same step.
 */
class MainActivity : Activity() {
    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view =
            TextView(this).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setPadding(24, 64, 24, 24)
                setTextIsSelectable(true)
                setTextColor(Color.WHITE)
            }
        setContentView(ScrollView(this).apply { addView(view) })
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val op = intent?.getStringExtra("op") ?: "list"
        // ⚠ **First, and before anything can fail** — this line is how the caller knows
        // the op it sent is the op that ran. `am start` can resume this activity without
        // delivering the new intent, in which case the previous run's extras are what
        // executes and its transcript is what a reader attributes to the new run. Two
        // measured instances, and neither was caught by anything but reading the bytes.
        // `probe.sh` refuses a log that does not carry its own id. #967.
        intent?.getStringExtra("run")?.let { emit("run $it") }
        // Off the main thread: connect() blocks for seconds and the read loop longer.
        Thread {
            runCatching { Probes(this, ::emit).dispatch(op, intent) }
                .onFailure { emit("FAILED: $it") }
        }.start()
    }

    private fun emit(line: String) {
        Log.i(TAG, line)
        runOnUiThread { view.append(line + "\n") }
    }

    companion object {
        const val TAG = "volume-probe"
    }
}
