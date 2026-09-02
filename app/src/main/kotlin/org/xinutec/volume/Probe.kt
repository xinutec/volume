package org.xinutec.volume

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import org.xinutec.volume.protocol.OutFrame
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
        payload: OutFrame,
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
                if (payload.bytes.isNotEmpty()) {
                    socket.outputStream.write(payload.bytes)
                    socket.outputStream.flush()
                }
                val got = readFor(socket.inputStream, totalMs, quietMs)
                return Exchange(secure, payload.bytes, got, null)
            } catch (e: IOException) {
                lastError = "${if (secure) "secure" else "insecure"}: ${e.message}"
            } catch (e: SecurityException) {
                // Missing BLUETOOTH_CONNECT — a different failure entirely, and one
                // no retry fixes, so do not burn the insecure attempt on it.
                return Exchange(
                    secure,
                    payload.bytes,
                    ByteArray(0),
                    "SecurityException: ${e.message}",
                )
            } finally {
                runCatching { socket?.close() }
            }
        }
        return Exchange(false, payload.bytes, ByteArray(0), lastError)
    }

    /**
     * Send many packets down ONE socket and report what each drew back.
     *
     * ⚠ **One socket is not merely an optimisation — some commands are only
     * expressible this way.** The Bose ANC write is transactional: the app opens
     * with an operator-`05` (Start) packet and the edit that follows is ignored
     * without it. A tool that opens a fresh connection per packet cannot send it at
     * all, and the failure is silent — the device accepts the packet and echoes the
     * *unchanged* state back, which reads like a wrong field rather than a missing
     * transaction.
     *
     * ⚠ **Not every Bose write needs one**, and assuming so is its own trap: EQ,
     * multipoint and the Action button each took a plain operator-`02` Set and the
     * echoed state changed. `docs/bose-settings.md`.
     *
     * Replies cannot be attributed to sends with certainty: these devices also emit
     * unsolicited notifications, and a slow answer lands in the next packet's
     * window. [onResult] therefore reports what arrived *after* a send, not what
     * the send provably caused.
     *
     * This is protocol-agnostic and will happily send writes. The read-only
     * guarantee belongs to [Sweep], which builds only Get-shaped packets.
     */
    fun exchangeAll(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        uuid: UUID,
        packets: List<OutFrame>,
        perMs: Long,
        quietMs: Long,
        reconnect: Boolean = false,
        /**
         * Given what just arrived, **every** reply to send before the next packet.
         * Sony's protocol needs this: the device expects its DATA frames to be
         * acknowledged, and a peer that never does eventually stops being told
         * anything — which reads as "that command returns no data".
         *
         * ⚠ **A list, because a window can hold more than one frame and each wants its
         * own ack.** This returned a single reply until 2026-08-23 and acked only the
         * last DATA frame. `Drivers.SonyXm4` had the identical defect.
         *
         * ⚠ **WHEN, not how many.** Acking every frame after the window closed still ran
         * one behind, because the XM4 is **stop-and-wait**: it withholds its next DATA
         * frame until the current one is acknowledged. Measured 2026-08-23 — a
         * volunteered `13` battery notify arrived first, the device retransmitted it
         * four times across the remaining 2.5 s, the real answer never came, and the
         * six exchanges after it were each one window late.
         *
         * So this is now called from [readAcking], which acks mid-window. ✅ **That cures
         * it**: the same eight-packet run, re-measured, gave every packet its own answer
         * and exactly one copy of each frame.
         */
        acksFor: (ByteArray) -> List<OutFrame> = { emptyList() },
        onResult: (sent: ByteArray, got: ByteArray, killedLink: Boolean) -> Unit,
    ): String? {
        runCatching { adapter.cancelDiscovery() }
        var socket = open(device, uuid) ?: return "could not connect"
        try {
            // Drain anything the device volunteers on connect, so the first packet's
            // window is not polluted by a greeting.
            readFor(socket.inputStream, 700, 300)
            for (p in packets) {
                try {
                    socket.outputStream.write(p.bytes)
                    socket.outputStream.flush()
                    onResult(p.bytes, readAcking(socket, perMs, quietMs, acksFor), false)
                } catch (e: IOException) {
                    // ⚠ The Fast Pair devices (JBL, JLab) reject a message they do not
                    // like by DROPPING THE LINK rather than answering. A dead socket is
                    // then a result about the packet, not a failure of the run, and
                    // without reconnecting a sweep stops at its first miss.
                    if (!reconnect) return "${e.message}"
                    onResult(p.bytes, ByteArray(0), true)
                    runCatching { socket.close() }
                    socket = open(device, uuid) ?: return "link died and would not reopen"
                    readFor(socket.inputStream, 700, 300)
                }
            }
            return null
        } catch (e: SecurityException) {
            return "SecurityException: ${e.message}"
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Secure first, then insecure — see [exchange] for why both are tried. */
    private fun open(device: BluetoothDevice, uuid: UUID): BluetoothSocket? {
        for (secure in listOf(true, false)) {
            val s =
                runCatching {
                    if (secure) {
                        device.createRfcommSocketToServiceRecord(uuid)
                    } else {
                        device.createInsecureRfcommSocketToServiceRecord(uuid)
                    }
                }.getOrNull() ?: continue
            if (runCatching { s.connect() }.isSuccess) return s
            runCatching { s.close() }
        }
        return null
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

    /**
     * [readFor], but acknowledging each frame the moment it completes.
     *
     * ✅ **This is what closed #1107 for the probe, and [readFor] is what caused it.**
     * The device is stop-and-wait: it withholds its next DATA frame until the current
     * one is acked. Acking only after the window therefore guaranteed that a window
     * opening with a volunteered frame held *only* that frame, with the answer meant
     * for this packet surfacing against the next one — and the device retransmitting
     * the unacked frame four to six times meanwhile. Driven on the XM4 2026-08-23.
     *
     * [acksFor] is called on the buffer so far and returns an ack per DATA frame in
     * order, so the list only grows; [sent] is how much of it has already gone out.
     * Sending an ack restarts the quiet timer, because having just prompted the
     * device is precisely when more is expected.
     */
    private fun readAcking(
        socket: BluetoothSocket,
        totalMs: Long,
        quietMs: Long,
        acksFor: (ByteArray) -> List<OutFrame>,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        val start = System.nanoTime()
        var lastData = start
        var sent = 0
        while ((System.nanoTime() - start) / 1_000_000 < totalMs) {
            val n = if (socket.inputStream.available() > 0) socket.inputStream.read(buf) else 0
            if (n > 0) {
                out.write(buf, 0, n)
                lastData = System.nanoTime()
                val acks = acksFor(out.toByteArray())
                acks.drop(sent).forEach { ack ->
                    socket.outputStream.write(ack.bytes)
                    socket.outputStream.flush()
                    lastData = System.nanoTime()
                }
                sent = acks.size
            } else {
                val quietFor = (System.nanoTime() - lastData) / 1_000_000
                if (out.size() > 0 && quietFor > quietMs) break
                Thread.sleep(20)
            }
        }
        return out.toByteArray()
    }
}
