package org.xinutec.volume

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import org.xinutec.volume.protocol.OutFrame
import org.xinutec.volume.protocol.Transport
import java.io.ByteArrayOutputStream
import java.io.IOException
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
    private var finished: ((sent: ByteArray, got: ByteArray) -> Boolean)? = null,
) : Link {
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

    override fun exchange(packet: OutFrame): ByteArray {
        send(packet)
        val done = finished
        // ⚠ Checked after every chunk, not only on quiet: the terminator can arrive in
        // the same chunk as the frames before it — this device batches.
        return window(perMs, quietMs, ::chunk) { got -> done != null && done(packet.bytes, got) }
    }

    /**
     * ⚠ **Sending an ack restarts the quiet timer.** Having just unblocked a
     * stop-and-wait device is precisely when more is expected, and treating that
     * moment as silence would close the window on the frame the ack just released.
     */
    override fun exchange(packet: OutFrame, acksFor: (ByteArray) -> List<OutFrame>): ByteArray {
        send(packet)
        return window(perMs, quietMs, ::chunk, acking(acksFor, ::send))
    }

    /**
     * Adopt a terminator **after** the device has been identified.
     *
     * ⚠ **A renamed device is identified by ASKING it**, which means the socket is open
     * before anyone knows what is on the other end — so the rule cannot be chosen at
     * construction for exactly the devices most likely to need it. The user's QC35 is
     * called "Example Bose QC35", `Registry.fromAdvertisement` therefore returns null,
     * and the whole early-stop change missed it while looking wired. The card said
     * "**(renamed)**" the entire time.
     */
    fun endsWith(f: (sent: ByteArray, got: ByteArray) -> Boolean) {
        finished = f
    }

    override fun send(packet: OutFrame) {
        socket.outputStream.write(packet.bytes)
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

    private fun readFor(totalMs: Long, quietMs: Long): ByteArray = window(totalMs, quietMs, ::chunk)

    private val buf = ByteArray(4096)

    /**
     * Whatever arrives within [waitMs], or null. Polls rather than blocks: a blocking
     * read on a BT socket cannot be interrupted.
     */
    private fun chunk(waitMs: Long): ByteArray? {
        val input = socket.inputStream
        val until = System.nanoTime() + waitMs * 1_000_000
        while (true) {
            val n = if (input.available() > 0) input.read(buf) else 0
            if (n > 0) return buf.copyOf(n)
            if (System.nanoTime() >= until) return null
            Thread.sleep(20)
        }
    }
}

/**
 * GATT: the JBL, and the only device here not on RFCOMM.
 *
 * ⚠ Reached at a **scanned** address with `autoConnect = false` — see [connect] for
 * why both halves of that matter and how each fails if you get it wrong. The probe
 * ([Gatt]) connects and writes through here too.
 */
class GattTransport private constructor(
    private val gatt: BluetoothGatt,
    private val writeChar: BluetoothGattCharacteristic,
    private val notifications: LinkedBlockingQueue<ByteArray>,
    private val writes: WriteAcks,
    private val perMs: Long,
    private val quietMs: Long,
) : Link {
    /** The outcome of the write in flight, as the GATT callback reports it. */
    internal class WriteAcks {
        @Volatile private var pending = CountDownLatch(0)

        @Volatile var status = BluetoothGatt.GATT_SUCCESS
            private set

        @Volatile var dropped = false
            private set

        fun arm() {
            status = BluetoothGatt.GATT_SUCCESS
            pending = CountDownLatch(1)
        }

        fun written(s: Int) {
            status = s
            pending.countDown()
        }

        fun drop() {
            dropped = true
            pending.countDown()
        }

        fun await(ms: Long) = pending.await(ms, TimeUnit.MILLISECONDS)
    }

    /** Every callback the stack makes on one link, as latches a blocking caller waits on. */
    internal class Callbacks : BluetoothGattCallback() {
        val notifications = LinkedBlockingQueue<ByteArray>()
        val writes = WriteAcks()

        @Volatile private var step = CountDownLatch(1)

        @Volatile var lastStatus = BluetoothGatt.GATT_SUCCESS
            private set

        @Volatile var dead = false
            private set

        /** Expect the next step's callback. */
        fun arm() {
            step = CountDownLatch(1)
        }

        fun await(ms: Long) = step.await(ms, TimeUnit.MILLISECONDS)

        private fun done(status: Int) {
            lastStatus = status
            step.countDown()
        }

        override fun onConnectionStateChange(g: BluetoothGatt, s: Int, new: Int) {
            if (new != BluetoothProfile.STATE_CONNECTED) {
                // Release whatever is waiting, or a dropped link hangs for the full
                // step timeout on every remaining call.
                dead = true
                writes.drop()
            }
            done(s)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, s: Int) = done(s)

        override fun onMtuChanged(g: BluetoothGatt, m: Int, s: Int) = done(s)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, s: Int) =
            done(s)

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            s: Int,
        ) = writes.written(s)

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            notifications.offer(value)
        }
    }

    /**
     * An LE link with its services discovered and no channel bound yet: what the probe
     * maps, and what [subscribe] makes a transport of.
     */
    class Connection internal constructor(
        private val gatt: BluetoothGatt,
        private val cb: Callbacks,
    ) {
        val services: List<BluetoothGattService> get() = gatt.services

        /**
         * Subscribe to [notify] and write through [write], both on [service]. Closes the
         * link when it fails, and says which step did.
         */
        fun subscribe(
            service: UUID,
            write: UUID,
            notify: UUID,
            perMs: Long = 1500,
            quietMs: Long = 500,
        ): GattStep<GattTransport> {
            fun fail(why: String): GattStep<GattTransport> {
                close()
                return GattStep.Failed(why)
            }
            val svc =
                gatt.getService(service)
                    ?: return fail(
                        "service $service not on this device — " +
                            services.joinToString { it.uuid.toString() },
                    )
            val w = svc.getCharacteristic(write) ?: return fail("no write characteristic $write")
            val n = svc.getCharacteristic(notify) ?: return fail("no notify characteristic $notify")
            // Both halves: local routing, then telling the peer. Doing only the first
            // succeeds everywhere and delivers nothing.
            if (!gatt.setCharacteristicNotification(n, true)) {
                return fail("setCharacteristicNotification refused")
            }
            val ccc = n.getDescriptor(CCC) ?: return fail("notify char has no CCC descriptor")
            cb.arm()
            gatt.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (!cb.await(STEP_MS)) return fail("CCC write timed out")
            val t = GattTransport(gatt, w, cb.notifications, cb.writes, perMs, quietMs)
            // Drain what the device volunteers on subscribe, so the first request's
            // window is not polluted by a greeting.
            t.collect(700, 300)
            return GattStep.Done(t)
        }

        fun close() {
            runCatching {
                gatt.disconnect()
                gatt.close()
            }
        }
    }

    companion object {
        private val CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val STEP_MS = 10_000L

        /**
         * Connect [device] over LE and discover its services.
         *
         * ⚠ [device] should come from [Scan], and [autoConnect] stays false for it: a
         * rotating private address never advertises under the same value twice, so an
         * accept-list wait can only time out. Measured on the JBL: `true` gives status
         * 135 after 45 s, `false` connects in about a second.
         */
        fun connect(
            context: Context,
            device: BluetoothDevice,
            autoConnect: Boolean = false,
            connectMs: Long = 20_000,
        ): GattStep<Connection> {
            val cb = Callbacks()
            val g =
                try {
                    device.connectGatt(context, autoConnect, cb, BluetoothDevice.TRANSPORT_LE)
                } catch (e: SecurityException) {
                    return GattStep.Failed("BLUETOOTH_CONNECT not granted: ${e.message}")
                } ?: return GattStep.Failed("connectGatt returned null")
            val c = Connection(g, cb)
            if (!cb.await(connectMs) || cb.dead) {
                c.close()
                return GattStep.Failed(
                    "no LE connection to ${device.address} in ${connectMs}ms (status ${cb.lastStatus})",
                )
            }
            // Before discovery: the default 23-byte MTU truncates any longer reply,
            // and a truncated reply looks like a different, shorter command.
            cb.arm()
            g.requestMtu(517)
            cb.await(STEP_MS)
            cb.arm()
            if (!g.discoverServices()) {
                c.close()
                return GattStep.Failed("discoverServices refused")
            }
            if (!cb.await(STEP_MS)) {
                c.close()
                return GattStep.Failed("service discovery timed out")
            }
            return GattStep.Done(c)
        }

        /** [connect], then [Connection.subscribe]: the transport a session drives. */
        fun open(
            context: Context,
            device: BluetoothDevice,
            service: UUID,
            write: UUID,
            notify: UUID,
        ): GattStep<GattTransport> =
            when (val c = connect(context, device)) {
                is GattStep.Failed -> c
                is GattStep.Done -> c.value.subscribe(service, write, notify)
            }
    }

    /** Whether the link has gone, so a caller can stop rather than fail every write after. */
    val down: Boolean get() = writes.dropped

    override fun exchange(packet: OutFrame): ByteArray {
        // ⚠ **Drop anything already queued before asking.** Whatever is sitting here
        // arrived before this request, so it CANNOT be its reply — it is an
        // unsolicited status frame the device sent on its own. Without this, the
        // next `exchange` returns that stale frame and the caller reads it as the
        // answer: measured on the JBL (#953), where a read a few seconds
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
    override fun exchange(packet: OutFrame, acksFor: (ByteArray) -> List<OutFrame>): ByteArray {
        notifications.clear()
        send(packet)
        return window(perMs, quietMs, ::chunk, acking(acksFor, ::send))
    }

    /**
     * Write, and throw unless the device acknowledged it — as an RFCOMM write throws
     * on a dead socket. An unchecked refusal would read as "the device did not answer".
     */
    override fun send(packet: OutFrame) {
        if (writes.dropped) throw IOException("GATT link is down")
        writes.arm()
        val rc =
            gatt.writeCharacteristic(
                writeChar,
                packet.bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
        if (rc != BluetoothStatusCodes.SUCCESS) throw IOException("GATT write refused, rc=$rc")
        if (!writes.await(STEP_MS)) throw IOException("GATT write not acknowledged")
        if (writes.dropped) throw IOException("GATT link dropped during a write")
        if (writes.status != BluetoothGatt.GATT_SUCCESS) {
            throw IOException("GATT write failed, status ${writes.status}")
        }
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
    private fun collect(totalMs: Long, quietMs: Long): ByteArray = window(totalMs, quietMs, ::chunk)

    private fun chunk(waitMs: Long): ByteArray? = notifications.poll(waitMs, TimeUnit.MILLISECONDS)
}

/** A step of opening a GATT link: what it produced, or why it produced nothing. */
sealed interface GattStep<out T> {
    data class Done<T>(
        val value: T,
    ) : GattStep<T>

    data class Failed(
        val why: String,
    ) : GattStep<Nothing>
}

/**
 * Read one reply window: until [totalMs] passes, until [quietMs] passes with nothing new
 * once something has arrived, or until [onData] says the reply is complete.
 *
 * [next] waits up to the given milliseconds for the next chunk and returns null if none
 * came. Both transports read through here, so a window means the same thing on each.
 */
internal fun window(
    totalMs: Long,
    quietMs: Long,
    next: (waitMs: Long) -> ByteArray?,
    onData: (ByteArray) -> Boolean = { false },
): ByteArray {
    val out = ByteArrayOutputStream()
    val end = System.nanoTime() + totalMs * 1_000_000
    while (true) {
        val left = (end - System.nanoTime()) / 1_000_000
        if (left <= 0) break
        val got = next(minOf(quietMs, left))
        if (got == null) {
            if (out.size() > 0) break
            continue
        }
        out.write(got)
        if (onData(out.toByteArray())) break
    }
    return out.toByteArray()
}

/**
 * An `onData` for [window] that sends each ack [acksFor] finds, once, as it is found.
 *
 * ⚠ **Sending an ack is new data's moment, so the quiet timer restarts with it.** Having
 * just unblocked a stop-and-wait device is when more is expected; treating that moment as
 * silence would close the window on the frame the ack just released.
 */
internal fun acking(
    acksFor: (ByteArray) -> List<OutFrame>,
    send: (OutFrame) -> Unit,
): (ByteArray) -> Boolean {
    var acked = 0
    return { got ->
        val acks = acksFor(got)
        acks.drop(acked).forEach(send)
        acked = acks.size
        false
    }
}
