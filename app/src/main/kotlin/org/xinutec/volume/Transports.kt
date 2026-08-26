package org.xinutec.volume

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import org.xinutec.volume.protocol.Transport
import java.io.Closeable
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * RFCOMM: Bose, Sony and the JLab.
 *
 * ⚠ **This holds its connection open for its whole life**, as does [GattTransport],
 * because that is what [Transport] means and what the devices require. A per-packet
 * implementation would satisfy the type and silently break Bose writes and Sony
 * reads — the failure looks like a wrong field, not like a disconnection.
 */
class RfcommTransport private constructor(
    private val socket: BluetoothSocket,
    private val perMs: Long,
    private val quietMs: Long,
    /**
     * ⚠ **When the PROTOCOL says the exchange is finished**, so the read can stop
     * instead of waiting out [quietMs] on a device that has already answered.
     *
     * Null for channels with no such rule, which keeps the timeout behaviour exactly as
     * it was — Sony's framing is escaped and length-prefixed but its exchanges are a
     * session rather than a request and a reply, and inventing a terminator for it would
     * be guessing at the one protocol here that has already punished guessing.
     */
    private val finished: ((sent: ByteArray, got: ByteArray) -> Boolean)? = null,
) : Transport,
    Closeable {
    companion object {
        /**
         * Open [uuid] on [device], draining whatever it volunteers on connect.
         *
         * ⚠ The drain is not tidiness. These devices greet, and an ungreeted first
         * exchange returns the greeting — which reads exactly like an answer to
         * whatever you asked first, and did once.
         */
        fun open(
            adapter: BluetoothAdapter,
            device: BluetoothDevice,
            uuid: UUID,
            perMs: Long = 1500,
            quietMs: Long = 400,
            finished: ((sent: ByteArray, got: ByteArray) -> Boolean)? = null,
        ): RfcommTransport? {
            runCatching { adapter.cancelDiscovery() }
            for (secure in listOf(true, false)) {
                val s =
                    runCatching {
                        if (secure) {
                            device.createRfcommSocketToServiceRecord(uuid)
                        } else {
                            device.createInsecureRfcommSocketToServiceRecord(uuid)
                        }
                    }.getOrNull() ?: continue
                if (runCatching { s.connect() }.isSuccess) {
                    val t = RfcommTransport(s, perMs, quietMs, finished)
                    t.readFor(700, 300)
                    return t
                }
                runCatching { s.close() }
            }
            return null
        }
    }

    override fun exchange(packet: ByteArray): ByteArray {
        send(packet)
        return drain(
            socket.inputStream,
            perMs,
            quietMs,
            finished?.let { f ->
                { got -> f(packet, got) }
            },
        )
    }

    /**
     * ⚠ **Sending an ack restarts the quiet timer.** Having just unblocked a
     * stop-and-wait device is precisely when more is expected, and treating that
     * moment as silence would close the window on the frame the ack just released.
     */
    override fun exchange(packet: ByteArray, acksFor: (ByteArray) -> List<ByteArray>): ByteArray {
        send(packet)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        val start = System.nanoTime()
        var lastData = start
        var acked = 0
        while ((System.nanoTime() - start) / 1_000_000 < perMs) {
            val n = if (socket.inputStream.available() > 0) socket.inputStream.read(buf) else 0
            if (n > 0) {
                out.write(buf, 0, n)
                lastData = System.nanoTime()
                val acks = acksFor(out.toByteArray())
                acks.drop(acked).forEach {
                    send(it)
                    lastData = System.nanoTime()
                }
                acked = acks.size
            } else {
                if (out.size() > 0 && (System.nanoTime() - lastData) / 1_000_000 > quietMs) break
                Thread.sleep(20)
            }
        }
        return out.toByteArray()
    }

    override fun send(packet: ByteArray) {
        socket.outputStream.write(packet)
        socket.outputStream.flush()
    }

    /**
     * ⚠ A **shorter** window than [exchange] uses. Nothing was sent, so there is no
     * round trip to wait out — this is only asking whether the device has since said
     * anything, and paying the full 1.5 s for "no" on every settings read would be felt.
     */
    override fun receive(): ByteArray = readFor(perMs / 3, quietMs)

    override fun close() {
        runCatching { socket.close() }
    }

    /** Poll rather than block: a blocking read on a BT socket cannot be interrupted. */
    private fun readFor(totalMs: Long, quietMs: Long): ByteArray =
        drain(socket.inputStream, totalMs, quietMs)

    private fun drain(
        input: InputStream,
        totalMs: Long,
        quietMs: Long,
        done: ((ByteArray) -> Boolean)? = null,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        val start = System.nanoTime()
        var lastData = start
        while ((System.nanoTime() - start) / 1_000_000 < totalMs) {
            val n = if (input.available() > 0) input.read(buf) else 0
            if (n > 0) {
                out.write(buf, 0, n)
                lastData = System.nanoTime()
                // ⚠ Checked after every read, not only on quiet: the terminator can
                // arrive in the same chunk as the frames before it — this device batches.
                if (done != null && done(out.toByteArray())) break
            } else {
                if (out.size() > 0 && (System.nanoTime() - lastData) / 1_000_000 > quietMs) break
                Thread.sleep(20)
            }
        }
        return out.toByteArray()
    }
}

/**
 * GATT: the JBL, and the only device here not on RFCOMM.
 *
 * ⚠ Reached at a **scanned** address with `autoConnect = false` — see [Gatt] for
 * why both halves of that matter and how each fails if you get it wrong.
 */
class GattTransport private constructor(
    private val gatt: BluetoothGatt,
    private val writeChar: BluetoothGattCharacteristic,
    private val notifications: LinkedBlockingQueue<ByteArray>,
    private val perMs: Long,
    private val quietMs: Long,
    /**
     * ⚠ **When the PROTOCOL says the exchange is finished**, so the read can stop
     * instead of waiting out [quietMs] on a device that has already answered.
     *
     * Null for channels with no such rule, which keeps the timeout behaviour exactly as
     * it was — Sony's framing is escaped and length-prefixed but its exchanges are a
     * session rather than a request and a reply, and inventing a terminator for it would
     * be guessing at the one protocol here that has already punished guessing.
     */
    private val finished: ((sent: ByteArray, got: ByteArray) -> Boolean)? = null,
) : Transport,
    Closeable {
    companion object {
        private val CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val STEP_MS = 10_000L

        fun open(
            context: Context,
            device: BluetoothDevice,
            service: UUID,
            write: UUID,
            notify: UUID,
            connectMs: Long = 20_000,
            perMs: Long = 1500,
            quietMs: Long = 500,
        ): GattTransport? {
            val notifications = LinkedBlockingQueue<ByteArray>()
            var step = CountDownLatch(1)
            var dead = false
            val cb =
                object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, s: Int, new: Int) {
                        if (new != BluetoothProfile.STATE_CONNECTED) dead = true
                        step.countDown()
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, s: Int) = step.countDown()

                    override fun onMtuChanged(g: BluetoothGatt, m: Int, s: Int) = step.countDown()

                    override fun onDescriptorWrite(
                        g: BluetoothGatt,
                        d: BluetoothGattDescriptor,
                        s: Int,
                    ) = step.countDown()

                    override fun onCharacteristicWrite(
                        g: BluetoothGatt,
                        c: BluetoothGattCharacteristic,
                        s: Int,
                    ) = step.countDown()

                    override fun onCharacteristicChanged(
                        g: BluetoothGatt,
                        c: BluetoothGattCharacteristic,
                        value: ByteArray,
                    ) {
                        notifications.offer(value)
                    }
                }

            val g =
                device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE) ?: return null
            if (!step.await(connectMs, TimeUnit.MILLISECONDS) || dead) {
                runCatching { g.close() }
                return null
            }
            // Before discovery: the default 23-byte MTU truncates any longer reply,
            // and a truncated reply looks like a different, shorter command.
            step = CountDownLatch(1)
            g.requestMtu(517)
            step.await(STEP_MS, TimeUnit.MILLISECONDS)

            step = CountDownLatch(1)
            g.discoverServices()
            step.await(STEP_MS, TimeUnit.MILLISECONDS)

            val svc = g.getService(service)
            val w = svc?.getCharacteristic(write)
            val n = svc?.getCharacteristic(notify)
            val ccc = n?.getDescriptor(CCC)
            if (w == null || n == null || ccc == null) {
                runCatching {
                    g.disconnect()
                    g.close()
                }
                return null
            }
            // Both halves: local routing, then telling the peer. Doing only the first
            // succeeds everywhere and delivers nothing.
            g.setCharacteristicNotification(n, true)
            step = CountDownLatch(1)
            g.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            step.await(STEP_MS, TimeUnit.MILLISECONDS)

            val t = GattTransport(g, w, notifications, perMs, quietMs)
            t.collect(700, 300)
            return t
        }
    }

    override fun exchange(packet: ByteArray): ByteArray {
        // ⚠ **Drop anything already queued before asking.** Whatever is sitting here
        // arrived before this request, so it CANNOT be its reply — it is an
        // unsolicited status frame the device sent on its own. Without this, the
        // next `exchange` returns that stale frame and the caller reads it as the
        // answer: measured on the JBL 2026-08-16 (#953), where a read a few seconds
        // after a confirmed ANC write reported OFF, every time, worn or not. Two
        // reads back to back gave `OFF then AMBIENT` — the device was never wrong,
        // the queue was one frame behind.
        //
        // ⚠ The greeting drained at [open] is the same trap, once. This is it
        // recurring inside a session that stays open, which only became possible
        // when sessions started being reused rather than closed after each use.
        notifications.clear()
        send(packet)
        return collect(perMs, quietMs)
    }

    /**
     * ⚠ **No device on this transport acks anything**, so [acksFor] returns an empty
     * list for every one of them and this is [exchange] with a callback that never
     * fires. It is implemented rather than refused because a transport that threw here
     * would make the interface a lie about what a caller may do — and the day a GATT
     * device does want acking, this is where it goes.
     */
    override fun exchange(packet: ByteArray, acksFor: (ByteArray) -> List<ByteArray>): ByteArray {
        notifications.clear()
        send(packet)
        val out = java.io.ByteArrayOutputStream()
        val start = System.nanoTime()
        var acked = 0
        while ((System.nanoTime() - start) / 1_000_000 < perMs) {
            val next = notifications.poll(quietMs, TimeUnit.MILLISECONDS)
            if (next == null) {
                if (out.size() > 0) break
            } else {
                out.write(next)
                val acks = acksFor(out.toByteArray())
                acks.drop(acked).forEach { send(it) }
                acked = acks.size
            }
        }
        return out.toByteArray()
    }

    override fun send(packet: ByteArray) {
        gatt.writeCharacteristic(
            writeChar,
            packet,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
    }

    /**
     * ⚠ **Does NOT clear first, and that is the whole difference from [exchange].**
     * There, a queued frame cannot be the reply to a request not yet sent, so it is
     * dropped. Here nothing is being asked, so a queued frame is exactly what the
     * caller wants — it is the late answer or the volunteered notification.
     *
     * The two are one line apart on purpose: #953 was caused by the clear being
     * missing, and this is the one place it must not happen.
     */
    override fun receive(): ByteArray = collect(perMs / 3, quietMs)

    override fun close() {
        runCatching {
            gatt.disconnect()
            gatt.close()
        }
    }

    /** Flatten notifications: a long reply is split by MTU, and the split is noise. */
    private fun collect(totalMs: Long, quietMs: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val start = System.nanoTime()
        while ((System.nanoTime() - start) / 1_000_000 < totalMs) {
            val next = notifications.poll(quietMs, TimeUnit.MILLISECONDS)
            if (next == null) {
                if (out.size() > 0) break
            } else {
                out.write(next)
            }
        }
        return out.toByteArray()
    }
}
