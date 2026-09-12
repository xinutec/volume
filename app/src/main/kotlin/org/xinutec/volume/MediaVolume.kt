package org.xinutec.volume

import android.content.Context
import android.media.AudioManager

/**
 * The phone's media level, as a percentage.
 *
 * ⚠ **For an absolute-volume Bluetooth sink this IS the device's own volume**, which
 * is why a card with no control channel can still carry a real slider. The two numbers
 * are one number: the headset's buttons move this, and moving this moves the headset.
 *
 * ⚠ **The scale is the phone's, not 0…100.** `STREAM_MUSIC` has 25 steps on a Pixel 9,
 * so a percentage has to be converted in both directions and cannot round-trip exactly
 * — 62% comes back as 60%. The slider shows steps, so the number the owner sees is one
 * the device can actually sit at.
 */
object MediaVolume {
    /** The level 0…100, or null when there is no audio service to ask. */
    fun percent(context: Context): Int? {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return null
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    /** How many steps the stream has, so a caller can size its control. */
    fun steps(context: Context): Int =
        context
            .getSystemService(
                AudioManager::class.java,
            )?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            ?: 0

    /**
     * Set the level 0…100.
     *
     * ⚠ **`FLAG_SHOW_UI` is deliberately absent.** The system volume panel appearing
     * over this app's own slider is two controls for one number, and the panel steals
     * the touch.
     */
    fun set(context: Context, percent: Int) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val step = (percent.coerceIn(0, 100) * max + 50) / 100
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, step.coerceIn(0, max), 0)
    }
}
