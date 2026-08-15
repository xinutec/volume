package org.xinutec.volume

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The BLE half of the probe.
 *
 * ⚠ **A vendor control channel need not be RFCOMM.** The JBL Tour One M2's is
 * GATT, and every RFCOMM socket on it — SPP, Fast Pair, three shared UUIDs — is
 * either silent or answering something else. An hour went into "why does SPP not
 * answer" before a snoop capture showed the app had never opened a socket at all.
 * So: when a device is known to be controllable and no RFCOMM channel talks, look
 * at LE before looking for a handshake.
 *
 * Deliberately blocking, like [Probe]. The callbacks arrive on a binder thread, the
 * probe runs on its own thread, and a latch per step keeps the calling code a
 * readable sequence instead of a state machine.
 */
object Gatt {
    /** Whether a write got as far as the peer, and what came back after it. */
    data class Result(
        val sent: ByteArray,
        val received: ByteArray,
        val error: String?,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Result &&
                error == other.error &&
                sent.contentEquals(other.sent) &&
                received.contentEquals(other.received)

        override fun hashCode(): Int {
            var h = sent.contentHashCode()
            h = h * 31 + received.contentHashCode()
            return h * 31 + (error?.hashCode() ?: 0)
        }
    }

    private const val STEP_MS = 10_000L

    /** The 0x2902 Client Characteristic Configuration every notify subscription needs. */
    private val CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Connect and report every service, characteristic and property.
     *
     * The alternative is guessing which of a chip vendor's several published UUID
     * pairs a given device actually implements, which is how an hour goes missing.
     * Properties matter as much as UUIDs: a characteristic with no NOTIFY cannot be
     * a reply path however plausible its name.
     */
    fun map(
        context: Context,
        device: BluetoothDevice,
        connectMs: Long,
    ): Pair<List<String>, String?> {
        val lines = ArrayList<String>()
        val err =
            exchange(
                context,
                device,
                // No service is opened: an empty packet list means discovery runs and
                // nothing is written, so this is safe against anything.
                ANY,
                ANY,
                ANY,
                emptyList(),
                0,
                0,
                false,
                connectMs,
                onDiscovered = { services ->
                    services.forEach { svc ->
                        lines.add(svc.uuid.toString())
                        svc.characteristics.forEach { c ->
                            val p = c.properties
                            val flags =
                                buildString {
                                    if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                                        append("read ")
                                    }
                                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                                        append("write ")
                                    }
                                    if (p and
                                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                                    ) {
                                        append("write-nr ")
                                    }
                                    if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                                        append("NOTIFY ")
                                    }
                                    if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                                        append("indicate ")
                                    }
                                }
                            lines.add("    ${c.uuid}  ${flags.trim()}")
                        }
                    }
                },
            ) {}
        return Pair(lines, err)
    }

    /** Placeholder for [map], which opens no service. Never matches a real one. */
    private val ANY = UUID.fromString("00000000-0000-0000-0000-000000000000")

    /**
     * Connect [device] over LE, subscribe to [notify], and write each of [packets]
     * to [write], reporting what was notified after each.
     *
     * ⚠ [device] should come from [Scan], not from `getRemoteDevice(mac)`. It is
     * reached at an **LE** address, which is neither its BR/EDR one nor stable, and
     * a device built from an address string is assumed to be a public one.
     *
     * ⚠ [autoConnect] must be **false** for an address that came from a scan, which
     * is the opposite of the usual advice. `autoConnect` puts the address on the
     * controller's accept list and waits for it to advertise again — but a rotating
     * private address never advertises under the same value twice, so the wait can
     * only time out. Direct connect uses the address while it is still current.
     * Measured both ways on the JBL: `true` gives status 135 after the full 45 s,
     * `false` connects in about a second.
     *
     * Replies cannot be attributed to writes with certainty — the device also
     * notifies unprompted (battery every ten seconds on the JBL), so a quiet window
     * after a write is a heuristic, exactly as in [Probe.exchangeAll].
     */
    fun exchange(
        context: Context,
        device: BluetoothDevice,
        service: UUID,
        write: UUID,
        notify: UUID,
        packets: List<ByteArray>,
        perMs: Long,
        quietMs: Long,
        autoConnect: Boolean,
        connectMs: Long,
        /** Called once with everything discovered, before any packet is written. */
        onDiscovered: (List<android.bluetooth.BluetoothGattService>) -> Unit = {},
        onResult: (Result) -> Unit,
    ): String? {
        val notifications = LinkedBlockingQueue<ByteArray>()
        var connected = CountDownLatch(1)
        var step = CountDownLatch(1)
        var lastStatus = BluetoothGatt.GATT_SUCCESS
        var disconnected = false

        val callback =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    lastStatus = status
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        connected.countDown()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        // Release whatever is waiting, or a dropped link hangs for the
                        // full step timeout on every remaining packet.
                        disconnected = true
                        connected.countDown()
                        step.countDown()
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    lastStatus = status
                    step.countDown()
                }

                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    lastStatus = status
                    step.countDown()
                }

                override fun onDescriptorWrite(
                    g: BluetoothGatt,
                    d: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    lastStatus = status
                    step.countDown()
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    c: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    lastStatus = status
                    step.countDown()
                }

                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    c: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    notifications.offer(value)
                }
            }

        val gatt =
            try {
                device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: SecurityException) {
                return "BLUETOOTH_CONNECT not granted: ${e.message}"
            } ?: return "connectGatt returned null"

        try {
            if (!connected.await(connectMs, TimeUnit.MILLISECONDS) || disconnected) {
                return "no LE connection to ${device.address} in ${connectMs}ms " +
                    "(status $lastStatus)"
            }

            // ⚠ Before discovery, not after: the default 23-byte MTU truncates every
            // reply longer than 20 bytes, and the JBL's EQ and device-info answers run
            // past 100. A truncated reply looks like a different, shorter command.
            step = CountDownLatch(1)
            gatt.requestMtu(517)
            step.await(STEP_MS, TimeUnit.MILLISECONDS)

            step = CountDownLatch(1)
            if (!gatt.discoverServices()) return "discoverServices refused"
            if (!step.await(STEP_MS, TimeUnit.MILLISECONDS)) return "service discovery timed out"
            onDiscovered(gatt.services)
            if (packets.isEmpty()) return null

            val svc =
                gatt.getService(service)
                    ?: return "service $service not on this device — " +
                        gatt.services.joinToString { it.uuid.toString() }
            val writeChar = svc.getCharacteristic(write) ?: return "no write characteristic $write"
            val notifyChar =
                svc.getCharacteristic(notify) ?: return "no notify characteristic $notify"

            // Two halves, and both are required: the first is local routing, the second
            // is what actually tells the peer to send. Doing only the first is a classic
            // silent-GATT bug — everything succeeds and nothing ever arrives.
            if (!gatt.setCharacteristicNotification(notifyChar, true)) {
                return "setCharacteristicNotification refused"
            }
            val ccc = notifyChar.getDescriptor(CCC) ?: return "notify char has no CCC descriptor"
            step = CountDownLatch(1)
            gatt.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (!step.await(STEP_MS, TimeUnit.MILLISECONDS)) return "CCC write timed out"

            // Drain what the device volunteers on subscribe, so the first packet's
            // window is not polluted by a greeting — the mistake that made a Fast Pair
            // hello read as an answer.
            collect(notifications, 700, 300)

            for (p in packets) {
                step = CountDownLatch(1)
                val rc =
                    gatt.writeCharacteristic(
                        writeChar,
                        p,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    )
                if (rc != BluetoothGatt.GATT_SUCCESS) {
                    onResult(Result(p, ByteArray(0), "write refused, rc=$rc"))
                    continue
                }
                val acked = step.await(STEP_MS, TimeUnit.MILLISECONDS)
                if (disconnected) {
                    onResult(Result(p, ByteArray(0), "link dropped on this packet"))
                    return "link dropped"
                }
                val err =
                    when {
                        !acked -> "write not acknowledged"
                        lastStatus != BluetoothGatt.GATT_SUCCESS -> "write status $lastStatus"
                        else -> null
                    }
                onResult(Result(p, collect(notifications, perMs, quietMs), err))
            }
            return null
        } catch (e: SecurityException) {
            return "SecurityException: ${e.message}"
        } finally {
            runCatching {
                gatt.disconnect()
                gatt.close()
            }
        }
    }

    /**
     * Concatenate notifications until the link goes quiet for [quietMs] or [totalMs]
     * elapses. Flattened rather than kept as separate frames because a long reply
     * arrives split across notifications and the split is an artefact of the MTU.
     */
    private fun collect(
        queue: LinkedBlockingQueue<ByteArray>,
        totalMs: Long,
        quietMs: Long,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val start = System.nanoTime()
        while ((System.nanoTime() - start) / 1_000_000 < totalMs) {
            val next = queue.poll(quietMs, TimeUnit.MILLISECONDS)
            if (next == null) {
                if (out.size() > 0) break
            } else {
                out.write(next)
            }
        }
        return out.toByteArray()
    }
}
