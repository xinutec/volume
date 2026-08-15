package org.xinutec.volume

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * One RFCOMM exchange, reported in full.
 *
 * #783 asks for raw bytes rather than a verdict, because #785 is built from the
 * bytes. So every field here is what actually crossed the wire, and the
 * interpretation is left to the caller.
 */
data class Exchange(
    val secure: Boolean,
    val sent: ByteArray,
    val received: ByteArray,
    val error: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is Exchange &&
            secure == other.secure &&
            error == other.error &&
            sent.contentEquals(other.sent) &&
            received.contentEquals(other.received)

    override fun hashCode(): Int {
        var h = secure.hashCode()
        h = h * 31 + sent.contentHashCode()
        h = h * 31 + received.contentHashCode()
        return h * 31 + (error?.hashCode() ?: 0)
    }
}

object Probe {
    /**
     * Open [uuid] on [device], write [payload], and read whatever comes back.
     *
     * Tries the secure socket first and falls back to the insecure one. That is not
     * cargo cult: an RFCOMM connect to a vendor channel commonly fails with "read
     * failed, socket might closed" when the remote declines the SSP-authenticated
     * link, and the insecure variant is what the vendor apps effectively get. Which
     * one worked is reported, because it is part of the answer.
     *
     * ⚠ Only one app may hold a device's RFCOMM channel. If the vendor app is
     * running this will fail to connect, and that failure says nothing about
     * whether the protocol is speakable — force-stop it first.
     */
    fun exchange(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        uuid: UUID,
        payload: ByteArray,
        totalMs: Long,
        quietMs: Long,
    ): Exchange {
        // Discovery starving the connect attempt is a classic; cheap to rule out.
        runCatching { adapter.cancelDiscovery() }

        var lastError: String? = null
        for (secure in listOf(true, false)) {
            var socket: BluetoothSocket? = null
            try {
                socket =
                    if (secure) {
                        device.createRfcommSocketToServiceRecord(uuid)
                    } else {
                        device.createInsecureRfcommSocketToServiceRecord(uuid)
                    }
                socket.connect()
                if (payload.isNotEmpty()) {
                    socket.outputStream.write(payload)
                    socket.outputStream.flush()
                }
                val got = readFor(socket.inputStream, totalMs, quietMs)
                return Exchange(secure, payload, got, null)
            } catch (e: IOException) {
                lastError = "${if (secure) "secure" else "insecure"}: ${e.message}"
            } catch (e: SecurityException) {
                // Missing BLUETOOTH_CONNECT — a different failure entirely, and one
                // no retry fixes, so do not burn the insecure attempt on it.
                return Exchange(secure, payload, ByteArray(0), "SecurityException: ${e.message}")
            } finally {
                runCatching { socket?.close() }
            }
        }
        return Exchange(false, payload, ByteArray(0), lastError)
    }

    /**
     * Collect bytes until the link goes quiet for [quietMs], or [totalMs] elapses.
     *
     * Polls `available()` rather than blocking in `read()`: a blocking read on a
     * Bluetooth socket cannot be interrupted, so a device that answers nothing would
     * wedge the probe thread until the socket is closed under it.
     */
    private fun readFor(input: InputStream, totalMs: Long, quietMs: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        val start = System.nanoTime()
        var lastData = start
        while ((System.nanoTime() - start) / 1_000_000 < totalMs) {
            val n = if (input.available() > 0) input.read(buf) else 0
            if (n > 0) {
                out.write(buf, 0, n)
                lastData = System.nanoTime()
            } else {
                val quietFor = (System.nanoTime() - lastData) / 1_000_000
                if (out.size() > 0 && quietFor > quietMs) break
                Thread.sleep(20)
            }
        }
        return out.toByteArray()
    }
}
