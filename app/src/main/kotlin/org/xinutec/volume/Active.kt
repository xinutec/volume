package org.xinutec.volume

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Which connected pair the audio is actually going to.
 *
 * ⚠ Needed only because a one-tap control cannot ask. With two pairs connected, a
 * tile that guesses would change the ANC of the headphones NOT in your ears and
 * report success — there is no feedback that catches that.
 *
 * ⚠ **`BluetoothA2dp.getActiveDevice()` is exactly this answer and is `@hide`**, so
 * the public route is the audio framework's own output list. It reports what is
 * *available* to route to rather than what is routed, so a single A2DP output is a
 * confident answer and several are not — hence null rather than a guess. [OneButton]
 * is what decides what to do with that null.
 */
object Active {
    fun address(context: Context): String? {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        val a2dp =
            audio
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                // ⚠ Empty for a device without BLUETOOTH_CONNECT, not null — so an
                // ungranted build looks exactly like "nothing is connected".
                .mapNotNull { it.address.takeIf(String::isNotBlank) }
                .distinct()
        return a2dp.singleOrNull()
    }
}
