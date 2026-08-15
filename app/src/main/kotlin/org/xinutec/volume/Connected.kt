package org.xinutec.volume

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Which bonded devices are actually here.
 *
 * ⚠ **Bonded is not connected**, and the first version of this screen conflated
 * them — it listed a paired speaker that was not in the room and offered a Connect
 * that could only fail slowly. Nothing in this app can be driven over a link that
 * does not exist, so the list is built from this.
 *
 * A2DP and headset, unioned: a pair of headphones is on at least one of them, and
 * which one depends on whether something is playing. `BluetoothManager`'s own
 * `getConnectedDevices` only answers for GATT, so the profile proxies are the
 * public route to the BR/EDR answer.
 */
object Connected {
    private const val PROXY_MS = 3000L

    fun addresses(context: Context, adapter: BluetoothAdapter): Set<String> =
        (
            forProfile(context, adapter, BluetoothProfile.A2DP) +
                forProfile(context, adapter, BluetoothProfile.HEADSET)
        ).toSet()

    private fun forProfile(
        context: Context,
        adapter: BluetoothAdapter,
        profile: Int,
    ): List<String> {
        var proxy: BluetoothProfile? = null
        val ready = CountDownLatch(1)
        val listener =
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, service: BluetoothProfile) {
                    proxy = service
                    ready.countDown()
                }

                override fun onServiceDisconnected(p: Int) {
                    ready.countDown()
                }
            }
        return try {
            if (!adapter.getProfileProxy(context, listener, profile)) return emptyList()
            ready.await(PROXY_MS, TimeUnit.MILLISECONDS)
            proxy?.connectedDevices.orEmpty().map(BluetoothDevice::getAddress)
        } catch (e: SecurityException) {
            emptyList()
        } finally {
            // Proxies are a limited resource and leak across activity restarts.
            proxy?.let { runCatching { adapter.closeProfileProxy(profile, it) } }
        }
    }
}
