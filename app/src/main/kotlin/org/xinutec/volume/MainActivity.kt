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
            else -> emit("unknown op '$op' — use list, send, sweep or seq")
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
            emit("sweep needs --es mac, --es uuid and --es proto (bose|harman)")
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
        val packets = spec.split(",").filter { it.isNotBlank() }.map { Hex.parse(it.trim()) }
        emit("seq: ${packets.size} packets on one socket to $mac")
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
            ) { sent, got, _ ->
                i++
                emit("  [$i] → ${Hex.format(sent)}")
                emit("      ← ${if (got.isEmpty()) "(nothing)" else Hex.format(got)}")
            }
        if (err != null) emit("✗ seq failed — $err") else emit("done")
    }

    private fun emit(line: String) {
        Log.i(TAG, line)
        runOnUiThread { view.append(line + "\n") }
    }

    companion object {
        const val TAG = "volume-probe"
    }
}
