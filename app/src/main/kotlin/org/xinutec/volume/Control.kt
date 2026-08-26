package org.xinutec.volume

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import org.xinutec.volume.protocol.BoseFrame
import org.xinutec.volume.protocol.Channels
import org.xinutec.volume.protocol.Drivers
import org.xinutec.volume.protocol.Headphones
import org.xinutec.volume.protocol.Registry
import org.xinutec.volume.protocol.Route
import org.xinutec.volume.protocol.Transport
import java.io.Closeable
import java.util.UUID

/** An open connection to a headphone, plus what it turned out to be. */
class Session(
    val headphones: Headphones,
    val transport: Transport,
    private val closeable: Closeable,
) : Closeable by closeable

/**
 * Getting from "a bonded device" to "a driver on an open channel".
 *
 * This is the only place that knows both halves, and it is in `:app` because every
 * step of it is I/O. What it must *decide* — which driver, which route — is in
 * `:protocol` and tested there.
 */
object Control {
    /**
     * Connect to [bonded] and return a driven session, or null with a reason logged.
     *
     * @param resolveLe how to find a GATT device's current LE address. Injected
     *   rather than called directly so this stays testable-ish and because the scan
     *   is slow enough that a caller may want to reuse a result.
     */
    fun connect(
        context: Context,
        adapter: BluetoothAdapter,
        bonded: BluetoothDevice,
        name: String,
        uuids: Set<String>,
        resolveLe: (String) -> BluetoothDevice?,
        onNote: (String) -> Unit = {},
    ): Session? {
        val known = Registry.fromAdvertisement(name, uuids)
        if (known != null) return open(context, adapter, bonded, known, resolveLe, onNote)

        // Nothing in the record settles it. If it offers SPP it may still be a Bose
        // whose owner renamed it — ask, with a read, rather than guess.
        if (Channels.SPP !in uuids.map { it.lowercase() }) {
            onNote("no known control channel on '$name'")
            return null
        }
        onNote("'$name' is unidentified from its record — asking it")
        val t =
            RfcommTransport.open(adapter, bonded, UUID.fromString(Channels.SPP))
                ?: run {
                    onNote("SPP would not open")
                    return null
                }
        val driver = Registry.identifyBose(t)
        if (driver == null) {
            onNote("it answered 01 06 in neither shape — leaving it unidentified")
            t.close()
            return null
        }
        val model = if (driver === org.xinutec.volume.protocol.Drivers.BoseQc45) "QC45" else "QC35"
        onNote("identified by read: Bose $model")
        runCatching { driver.prepare(t) }
        return Session(
            Headphones(
                Channels.Vendor.BOSE,
                "Bose $model (renamed)",
                Route.Rfcomm(Channels.SPP),
                driver,
            ),
            t,
            t,
        )
    }

    private fun open(
        context: Context,
        adapter: BluetoothAdapter,
        bonded: BluetoothDevice,
        h: Headphones,
        resolveLe: (String) -> BluetoothDevice?,
        onNote: (String) -> Unit,
    ): Session? =
        when (val r = h.route) {
            is Route.Rfcomm -> {
                // ⚠ The early-stop rule is BOSE'S, so it is passed only on Bose's
                // channel. The JLab speaks BES over this same SPP uuid, and its framing
                // has no operator byte to read a terminator out of — handing it Bose's
                // rule would end its reads on a coincidence.
                val ends =
                    if (r.uuid.equals(Channels.SPP, ignoreCase = true) &&
                        h.driver is Drivers.BoseQc35
                    ) {
                        BoseFrame::terminates
                    } else {
                        null
                    }
                val t =
                    RfcommTransport.open(
                        adapter,
                        bonded,
                        UUID.fromString(r.uuid),
                        finished = ends,
                    )
                if (t == null) {
                    onNote("${h.model}: ${r.uuid} would not open — is a vendor app holding it?")
                    null
                } else {
                    runCatching { h.driver.prepare(t) }
                    Session(h, t, t)
                }
            }

            is Route.Gatt -> {
                // ⚠ The bonded BR/EDR device is the wrong object here: this one is
                // reached over LE at an address that rotates, so it must be scanned
                // for and connected through the scanner's own device.
                val le = resolveLe(h.model)
                if (le == null) {
                    onNote("${h.model}: not advertising right now")
                    null
                } else {
                    val t =
                        GattTransport.open(
                            context,
                            le,
                            UUID.fromString(r.service),
                            UUID.fromString(r.write),
                            UUID.fromString(r.notify),
                        )
                    if (t == null) {
                        onNote("${h.model}: GATT would not open at ${le.address}")
                        null
                    } else {
                        runCatching { h.driver.prepare(t) }
                        Session(h, t, t)
                    }
                }
            }
        }
}
