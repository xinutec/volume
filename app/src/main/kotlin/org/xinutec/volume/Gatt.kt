package org.xinutec.volume

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import org.xinutec.volume.protocol.OutFrame
import java.io.IOException
import java.util.UUID

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

    /**
     * Connect and report every service, characteristic and property.
     *
     * The alternative is guessing which of a chip vendor's several published UUID
     * pairs a given device actually implements, which is how an hour goes missing.
     * Properties matter as much as UUIDs: a characteristic with no NOTIFY cannot be
     * a reply path however plausible its name.
     *
     * No service is opened and nothing is written, so this is safe against anything.
     */
    fun map(
        context: Context,
        device: BluetoothDevice,
        connectMs: Long,
    ): Pair<List<String>, String?> {
        val c =
            when (val step = GattTransport.connect(context, device, connectMs = connectMs)) {
                is GattStep.Failed -> return Pair(emptyList(), step.why)
                is GattStep.Done -> step.value
            }
        val lines = ArrayList<String>()
        try {
            c.services.forEach { svc ->
                lines.add(svc.uuid.toString())
                svc.characteristics.forEach { ch ->
                    lines.add("    ${ch.uuid}  ${flags(ch.properties)}")
                }
            }
        } finally {
            c.close()
        }
        return Pair(lines, null)
    }

    private fun flags(p: Int): String =
        listOfNotNull(
            "read".takeIf { p and BluetoothGattCharacteristic.PROPERTY_READ != 0 },
            "write".takeIf { p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 },
            "write-nr".takeIf { p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 },
            "NOTIFY".takeIf { p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 },
            "indicate".takeIf { p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 },
        ).joinToString(" ")

    /**
     * Connect [device] over LE, subscribe to [notify], and write each of [packets]
     * to [write], reporting what was notified after each.
     *
     * The connection and every write go through [GattTransport] — the same checks the
     * app's sessions make, so a probe and a session cannot disagree about what a refused
     * or unacknowledged write looks like.
     *
     * ⚠ [device] should come from [Scan], not from `getRemoteDevice(mac)`: it is reached
     * at an **LE** address, which is neither its BR/EDR one nor stable. [autoConnect] is
     * for a device whose address is fixed — see [GattTransport.connect].
     *
     * Replies cannot be attributed to writes with certainty — the device also notifies
     * unprompted (battery every ten seconds on the JBL), so a quiet window after a write
     * is a heuristic, exactly as in [Probe.exchangeAll].
     */
    fun exchange(
        context: Context,
        device: BluetoothDevice,
        service: UUID,
        write: UUID,
        notify: UUID,
        packets: List<OutFrame>,
        perMs: Long,
        quietMs: Long,
        autoConnect: Boolean,
        connectMs: Long,
        onResult: (Result) -> Unit,
    ): String? {
        val c =
            when (val step = GattTransport.connect(context, device, autoConnect, connectMs)) {
                is GattStep.Failed -> return step.why
                is GattStep.Done -> step.value
            }
        val t =
            when (val step = c.subscribe(service, write, notify, perMs, quietMs)) {
                is GattStep.Failed -> return step.why
                is GattStep.Done -> step.value
            }
        try {
            for (p in packets) {
                try {
                    onResult(Result(p.bytes, t.exchange(p), null))
                } catch (e: IOException) {
                    onResult(Result(p.bytes, ByteArray(0), e.message))
                    if (t.down) return "link dropped"
                }
            }
            return null
        } catch (e: SecurityException) {
            return "SecurityException: ${e.message}"
        } finally {
            t.close()
        }
    }
}
