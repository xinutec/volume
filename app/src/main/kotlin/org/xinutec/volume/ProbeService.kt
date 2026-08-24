package org.xinutec.volume

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * The probe, driven as a **service** rather than an activity.
 *
 * ⚠ **This exists because `am start` can fail to deliver an intent, silently.** When
 * the probe activity is already alive, `am` answers "its current task has been brought
 * to the front", `onNewIntent` does not fire, and the run does not happen — while the
 * previous run's transcript sits in the log looking exactly like the new one's. Twice
 * measured, cause never established, and the only reliable workaround was
 * `am force-stop`, which tears the app out of whatever split screen it was in. #967.
 *
 * A started service has no equivalent state: `onStartCommand` is called for **every**
 * start, by contract, and a service is not in the window stack so nothing on screen
 * moves. That is a structural argument, not a measured one — see below.
 *
 * ⚠ **The delivery bug did not reproduce on 2026-08-24 (20 runs, four conditions,
 * all green), so this fix has NOT been demonstrated to cure the symptom.** What was
 * verified is that the service path delivers reliably and disturbs nothing. If a
 * skipped run ever reappears, `probe.sh`'s run-id check is what will say so.
 *
 * ⚠ **Foreground, and not for show.** A background service started from `adb` is
 * refused outright on this Android; and a probe run holds an RFCOMM socket for tens of
 * seconds, which is exactly what `connectedDevice` is for. The notification is the
 * price of the guarantee that the work is not killed mid-exchange.
 */
class ProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground()
        val op = intent?.getStringExtra("op") ?: "list"
        // ⚠ First, and before anything can fail — `probe.sh` refuses a transcript that
        // does not carry its own id, which is what makes a skipped run loud. #967.
        intent?.getStringExtra("run")?.let { emit("run $it") }
        // Off the main thread: an exchange blocks for seconds and a GATT map longer.
        Thread {
            runCatching { Probes(this, ::emit).dispatch(op, intent) }
                .onFailure { emit("FAILED: $it") }
            // ⚠ [startId], not a bare stop: a second op arriving while this one runs
            // must not be cancelled by the first one finishing.
            stopSelf(startId)
        }.start()
        // ⚠ NOT sticky. A redelivered probe intent would re-send whatever bytes it
        // carried, to headphones nobody is looking at, after a restart nobody asked
        // for — the same hazard as the repeated write in #967 and worse for being
        // unattended.
        return START_NOT_STICKY
    }

    private fun foreground() {
        val channel =
            NotificationChannel(
                CHANNEL,
                "Probe runs",
                // ⚠ MIN, so a headless instrument does not buzz the phone on every read.
                NotificationManager.IMPORTANCE_MIN,
            )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(
            1,
            Notification
                .Builder(this, CHANNEL)
                .setContentTitle("Volume probe")
                .setSmallIcon(R.drawable.ic_anc)
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun emit(line: String) = Log.i(MainActivity.TAG, line).let { }

    companion object {
        private const val CHANNEL = "probe"
    }
}
