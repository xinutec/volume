package org.xinutec.volume

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews

/**
 * The same one tap, on the home screen.
 *
 * Wanted alongside the tile because they are reached differently: the shade is two
 * gestures from anywhere, the widget is one from the home screen and can say what
 * mode the headphones are in without being opened.
 *
 * ⚠ **Shows only what it last learned, and says so by not claiming otherwise.** A
 * widget cannot poll — refreshing means opening a control channel to a headphone
 * nobody is looking at, which is exactly what `onStop`'s release exists to prevent.
 * So it renders the result of the last tap and otherwise names the device only. It
 * never displays a mode it has not read.
 */
class AncWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Cheap state only: who is connected, no control channel opened.
        val app = context.applicationContext
        Tap.work.execute {
            val state = Tap.load(app)
            render(app, AppWidgetManager.getInstance(app), state)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TAP) return
        // ⚠ `goAsync` before leaving this thread. A broadcast receiver is dead the
        // moment onReceive returns, and its process becomes a candidate for death
        // with it — an RFCOMM exchange takes longer than that, so without this the
        // write races the system reclaiming us.
        val pending = goAsync()
        val app = context.applicationContext
        Tap.work.execute {
            try {
                val state = Tap.next(app)
                Log.i(LIVE, "widget tap -> ${state.label}: ${state.detail}")
                render(app, AppWidgetManager.getInstance(app), state)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val ACTION_TAP = "org.xinutec.volume.WIDGET_TAP"

        fun render(context: Context, manager: AppWidgetManager, state: Tap.State) {
            val ids = manager.getAppWidgetIds(ComponentName(context, AncWidget::class.java))
            if (ids.isEmpty()) return
            val views =
                RemoteViews(context.packageName, R.layout.anc_widget).apply {
                    setTextViewText(R.id.widget_label, state.label)
                    setTextViewText(R.id.widget_detail, state.detail ?: "")
                    setOnClickPendingIntent(R.id.widget_root, tapIntent(context))
                }
            manager.updateAppWidget(ids, views)
        }

        /**
         * ⚠ `FLAG_IMMUTABLE` is required from API 31 and is right anyway: nothing
         * outside should be able to retarget this at another component.
         */
        fun tapIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, AncWidget::class.java).setAction(ACTION_TAP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
