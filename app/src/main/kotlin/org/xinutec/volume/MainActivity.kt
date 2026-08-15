package org.xinutec.volume

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID

/**
 * The #783 probe, driven from the terminal.
 *
 * There is a screen only so a run can be watched while the headphones are on your
 * head; everything it prints also goes to logcat under [TAG], which is where it is
 * meant to be read from.
 *
 * ```
 * # what is bonded, and which control channels each one advertises
 * am start -n org.xinutec.volume/.MainActivity --es op list
 *
 * # one Sony-framed exchange (type/seq default to 0c/00)
 * am start -n org.xinutec.volume/.MainActivity --es op send \
 *   --es mac AA:BB:CC:DD:EE:FF --es uuid 96cc203e-5068-46ad-b32d-e316f5e069ba \
 *   --es payload 0000 --es type 0c
 *
 * # bytes verbatim, no framing — for the Bose/JBL channels, whose shape is unknown
 * am start -n org.xinutec.volume/.MainActivity --es op send --ez raw true \
 *   --es mac AA:BB:CC:DD:EE:FF --es uuid 00001101-0000-1000-8000-00805f9b34fb \
 *   --es payload 00010305
 * ```
 *
 * ⚠ Hearing safety: this sends whatever it is given. Probe with reads. Do not use
 * a volume command as the round-trip proof, and restore any level touched for a
 * test in the same step.
 */
class MainActivity : Activity() {
    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view =
            TextView(this).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setPadding(24, 64, 24, 24)
                setTextIsSelectable(true)
                setTextColor(Color.WHITE)
            }
        setContentView(ScrollView(this).apply { addView(view) })
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val op = intent?.getStringExtra("op") ?: "list"
        // Off the main thread: connect() blocks for seconds and the read loop longer.
        Thread { runCatching { dispatch(op, intent) }.onFailure { emit("FAILED: $it") } }.start()
    }

    private fun dispatch(op: String, intent: Intent?) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            emit("no Bluetooth adapter")
            return
        }
        if (!adapter.isEnabled) {
            emit("Bluetooth is OFF — nothing to probe")
            return
        }
        when (op) {
            "list" -> list(adapter)
            "send" -> send(adapter, intent ?: return)
            "sweep" -> sweep(adapter, intent ?: return)
            "seq" -> seq(adapter, intent ?: return)
            "gatt" -> gatt(adapter, intent ?: return)
            "scan" -> scan(adapter, intent)
            "gattmap" -> gattmap(adapter, intent ?: return)
            else -> emit("unknown op '$op' — use list, scan, send, sweep, seq, gatt or gattmap")
        }
    }

    private fun list(adapter: android.bluetooth.BluetoothAdapter) {
        val bonded =
            try {
                adapter.bondedDevices
            } catch (e: SecurityException) {
                emit("BLUETOOTH_CONNECT not granted: ${e.message}")
                return
            }
        emit("${bonded.size} bonded devices")
        bonded.sortedBy { it.name ?: it.address }.forEach { d ->
            val uuids = d.uuids?.map { it.uuid.toString() }.orEmpty()
            val detected = Channels.detect(d.name.orEmpty(), uuids.toSet())
            emit("")
            emit("${d.name ?: "(unnamed)"}  ${d.address}")
            emit("  detected: $detected")
            uuids.forEach { emit("    $it${Channels.annotate(it)}") }
        }
    }

    private fun send(adapter: android.bluetooth.BluetoothAdapter, intent: Intent) {
        val mac = intent.getStringExtra("mac")
        val uuid = intent.getStringExtra("uuid")
        if (mac == null || uuid == null) {
            emit("send needs --es mac and --es uuid")
            return
        }
        val raw = intent.getBooleanExtra("raw", false)
        val payload = Hex.parse(intent.getStringExtra("payload") ?: "")
        val type = Hex.parse(intent.getStringExtra("type") ?: "0c").first()
        val seq = Hex.parse(intent.getStringExtra("seq") ?: "00").first()
        val totalMs = intent.getIntExtra("wait", 3000).toLong()
        val quietMs = intent.getIntExtra("quiet", 600).toLong()

        val wire = if (raw) payload else SonyFrame.encode(type, seq, payload)

        emit("→ ${if (raw) "raw" else "sony type=%02x seq=%02x".format(type, seq)} to $mac")
        emit("  uuid    $uuid")
        emit("  payload ${Hex.format(payload)}")
        emit("  wire    ${Hex.format(wire)}")

        val device = adapter.getRemoteDevice(mac)
        val r = Probe.exchange(adapter, device, UUID.fromString(uuid), wire, totalMs, quietMs)

        emit("")
        if (r.error != null) {
            emit("✗ no exchange — ${r.error}")
            emit("  if it says 'socket might closed', the vendor app probably holds")
            emit("  the channel: adb shell am force-stop <vendor package>")
            return
        }
        emit("✓ connected (${if (r.secure) "secure" else "insecure"} socket)")
        if (r.received.isEmpty()) {
            emit("← nothing in ${totalMs}ms — channel open, command not answered")
            return
        }
        emit("← ${r.received.size} bytes")
        emit("  ${Hex.format(r.received)}")
        if (!raw) {
            val frames = SonyFrame.decodeAll(r.received)
            if (frames.isEmpty()) {
                emit("  (no complete Sony frame — framing hypothesis may be wrong)")
            } else {
                frames.forEach { emit("  $it") }
            }
        }
    }

    /**
     * Walk a protocol's read surface and print only what answered.
     *
     * Prints the silent count rather than each silent packet: a sweep is mostly
     * silence, and 300 lines of "nothing" buries the dozen that matter.
     */
    private fun sweep(adapter: android.bluetooth.BluetoothAdapter, intent: Intent) {
        val mac = intent.getStringExtra("mac")
        val uuid = intent.getStringExtra("uuid")
        val proto = intent.getStringExtra("proto")
        if (mac == null || uuid == null || proto == null) {
            emit("sweep needs --es mac, --es uuid and --es proto (bose|fastpair)")
            return
        }
        val blocks = Sweep.range(intent.getStringExtra("blocks") ?: "00-12")
        val fns = Sweep.range(intent.getStringExtra("fns") ?: "00-0f")
        val packets = Sweep.packets(proto, blocks, fns)

        emit("sweep $proto on $mac — ${packets.size} GET packets")
        emit("  blocks ${"%02x".format(blocks.first)}-${"%02x".format(blocks.last)}")
        emit("  fns    ${"%02x".format(fns.first)}-${"%02x".format(fns.last)}")

        var answered = 0
        var silent = 0
        var dropped = 0
        val err =
            Probe.exchangeAll(
                adapter,
                adapter.getRemoteDevice(mac),
                UUID.fromString(uuid),
                packets,
                intent.getIntExtra("per", 400).toLong(),
                intent.getIntExtra("quiet", 150).toLong(),
                intent.getBooleanExtra("reconnect", false),
            ) { sent, got, killedLink ->
                when {
                    killedLink -> {
                        dropped++
                        emit("  ${Hex.format(sent)}  ->  DROPPED THE LINK")
                    }

                    got.isEmpty() -> {
                        silent++
                    }

                    else -> {
                        answered++
                        emit("  ${Hex.format(sent)}  ->  ${Hex.format(got)}")
                    }
                }
            }
        if (err != null) {
            emit("✗ sweep failed — $err")
            return
        }
        emit("done: $answered answered, $silent silent, $dropped killed the link")
    }

    /**
     * Send an explicit list of packets down one socket, in order.
     *
     * This is the write tool. A Bose edit is a transaction — an operator-`05`
     * Start, then the change — so it cannot be expressed one-packet-per-socket,
     * which is what [send] does. Comma-separated hex:
     *
     * ```
     * --es packets 1f010500,1f0602270200...,01050100
     * ```
     */
    private fun seq(adapter: android.bluetooth.BluetoothAdapter, intent: Intent) {
        val mac = intent.getStringExtra("mac")
        val uuid = intent.getStringExtra("uuid")
        val spec = intent.getStringExtra("packets")
        if (mac == null || uuid == null || spec == null) {
            emit("seq needs --es mac, --es uuid and --es packets (comma-separated hex)")
            return
        }
        val raw = spec.split(",").filter { it.isNotBlank() }.map { Hex.parse(it.trim()) }
        // ⚠ Sony needs a SESSION, not a packet. Its PARAM reads answer only inside one
        // where the device's DATA frames are acknowledged; one-shot `send` gets the
        // ACK and nothing else, which reads exactly like "this command returns no
        // data". `--ez sony true` frames each payload and acks what comes back.
        val sony = intent.getBooleanExtra("sony", false)
        val packets =
            if (sony) {
                raw.mapIndexed { n, p ->
                    SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, (n % 2).toByte(), p)
                }
            } else {
                raw
            }
        val ackWith: (ByteArray) -> ByteArray? =
            if (!sony) {
                { null }
            } else {
                { got ->
                    // Acknowledge only what the device SENT as data; acking its acks
                    // would have us talking to ourselves.
                    SonyFrame
                        .decodeAll(got)
                        .lastOrNull { it.type == SonyFrame.TYPE_DATA_MDR }
                        ?.let { f ->
                            SonyFrame.encode(
                                SonyFrame.TYPE_ACK,
                                (f.seq.toInt() xor 1).toByte(),
                                ByteArray(0),
                            )
                        }
                }
            }
        val how = if (sony) " (sony framed, acked)" else ""
        emit("seq: ${packets.size} packets on one socket to $mac$how")
        var i = 0
        val err =
            Probe.exchangeAll(
                adapter,
                adapter.getRemoteDevice(mac),
                UUID.fromString(uuid),
                packets,
                intent.getIntExtra("per", 900).toLong(),
                intent.getIntExtra("quiet", 350).toLong(),
                intent.getBooleanExtra("reconnect", false),
                ackWith,
            ) { sent, got, _ ->
                i++
                emit("  [$i] → ${Hex.format(sent)}")
                emit("      ← ${if (got.isEmpty()) "(nothing)" else Hex.format(got)}")
                if (sony) {
                    SonyFrame.decodeAll(got).forEach { emit("        $it") }
                }
            }
        if (err != null) emit("✗ seq failed — $err") else emit("done")
    }

    /** What is advertising right now, and under which address. */
    private fun scan(adapter: android.bluetooth.BluetoothAdapter, intent: Intent?) {
        val ms = intent?.getIntExtra("ms", 8000)?.toLong() ?: 8000L
        emit("scanning ${ms}ms…")
        val (seen, err) = Scan.run(adapter, ms)
        if (err != null) emit("⚠ $err")
        emit("${seen.size} advertisers")
        seen.forEach { emit("  $it") }
    }

    /** Every GATT service and characteristic a device offers, with its properties. */
    private fun gattmap(adapter: android.bluetooth.BluetoothAdapter, intent: Intent) {
        val name =
            intent.getStringExtra("name") ?: run {
                emit("gattmap needs --es name")
                return
            }
        emit("resolving '$name' by LE scan…")
        val seen = Scan.find(adapter, name, intent.getIntExtra("scan", 25000).toLong())
        if (seen?.device == null) {
            emit("✗ nothing advertising anything containing '$name'")
            return
        }
        emit("  $seen")
        val (lines, err) =
            Gatt.map(
                this,
                seen.device,
                intent.getIntExtra("connect", 20000).toLong(),
            )
        lines.forEach { emit("  $it") }
        if (err != null) emit("✗ $err")
    }

    /**
     * The GATT counterpart of [seq]: one LE connection, several writes, in order.
     *
     * Defaults to the BES command service, since that is the one channel found this
     * way so far; `--es service/write/notify` override it for the next device.
     *
     * ```
     * --es name "JBL TOUR" --es packets aa910111
     * ```
     *
     * ⚠ Prefer `--es name` over `--es mac`. The address these devices advertise
     * rotates, so a literal one goes stale — and connecting to a stale address fails
     * slowly and misleadingly rather than saying "no such device".
     */
    private fun gatt(adapter: android.bluetooth.BluetoothAdapter, intent: Intent) {
        val spec = intent.getStringExtra("packets")
        val name = intent.getStringExtra("name")
        val mac = intent.getStringExtra("mac")
        if (spec == null || (name == null && mac == null)) {
            emit("gatt needs --es packets, and --es name (preferred) or --es mac")
            return
        }
        val device =
            if (name != null) {
                emit("resolving '$name' by LE scan…")
                // 25 s, not 10: these devices advertise in bursts and rotate address
                // between them, and a window that misses one reports "not advertising"
                // — indistinguishable from "not there". The scan stops on the first
                // match, so a generous window costs nothing when the device is present.
                val seen = Scan.find(adapter, name, intent.getIntExtra("scan", 25000).toLong())
                if (seen?.device == null) {
                    emit("✗ nothing advertising anything containing '$name'")
                    return
                }
                emit("  $seen")
                seen.device
            } else {
                adapter.getRemoteDevice(mac)
            }
        val service = intent.getStringExtra("service") ?: Channels.BES_GATT_SERVICE
        val write = intent.getStringExtra("write") ?: Channels.BES_GATT_WRITE
        val notify = intent.getStringExtra("notify") ?: Channels.BES_GATT_NOTIFY
        val packets = spec.split(",").filter { it.isNotBlank() }.map { Hex.parse(it.trim()) }

        emit("gatt: ${packets.size} writes to ${device.address}")
        emit("  service $service")
        var i = 0
        val err =
            Gatt.exchange(
                this,
                device,
                UUID.fromString(service),
                UUID.fromString(write),
                UUID.fromString(notify),
                packets,
                intent.getIntExtra("per", 1500).toLong(),
                intent.getIntExtra("quiet", 500).toLong(),
                // Direct by default — see Gatt.exchange: an accept-list connect cannot
                // catch a rotating address. `--ez auto true` for a device whose address
                // is fixed and which may not be advertising yet.
                intent.getBooleanExtra("auto", false),
                intent.getIntExtra("connect", 20000).toLong(),
            ) { r ->
                i++
                emit("  [$i] → ${Hex.format(r.sent)}")
                emit("      ← ${if (r.received.isEmpty()) "(nothing)" else Hex.format(r.received)}")
                r.error?.let { emit("      ⚠ $it") }
            }
        if (err != null) emit("✗ gatt failed — $err") else emit("done")
    }

    private fun emit(line: String) {
        Log.i(TAG, line)
        runOnUiThread { view.append(line + "\n") }
    }

    companion object {
        const val TAG = "volume-probe"
    }
}
