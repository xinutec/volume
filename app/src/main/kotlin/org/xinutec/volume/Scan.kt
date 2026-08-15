package org.xinutec.volume

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import org.xinutec.volume.protocol.Hex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Find a device's **current** LE address.
 *
 * ⚠ These headphones advertise a resolvable private address that rotates, so the
 * address in a bonded-device list, in a Fast Pair greeting, or in yesterday's notes
 * is not the one to connect to today. A direct connect to a stale address does not
 * fail cleanly either — it sat for 45 s and returned status 135, which reads like a
 * protocol problem rather than "that address is nobody".
 *
 * Matching is on anything advertised — name, service UUID, or service-data bytes —
 * because which of those a device offers is not knowable in advance. The JBL puts
 * its name in the scan *response*, which is a separate packet from the
 * advertisement; see the merge note in [run].
 */
object Scan {
    /** What one advertising device looked like, flattened for printing. */
    data class Seen(
        val address: String,
        val name: String?,
        val rssi: Int,
        val services: List<String>,
        /** Service data by 16-bit UUID short code, hex. Fast Pair's `fe2c` is here. */
        val serviceData: Map<String, String>,
        /**
         * ⚠ **Connect through THIS, not through `getRemoteDevice(address)`.** The
         * address here is a random one, and a device built from the string is assumed
         * public — the connection then fails with status 135 after the full timeout,
         * which reads like a protocol fault rather than "wrong address type". The
         * object the scanner hands out carries the type with it.
         */
        val device: BluetoothDevice? = null,
    ) {
        /** Fold a later sighting of the same device in, preferring whatever is known. */
        operator fun plus(other: Seen): Seen =
            Seen(
                address,
                name ?: other.name,
                maxOf(rssi, other.rssi),
                (services + other.services).distinct(),
                serviceData + other.serviceData,
                device ?: other.device,
            )

        /** Everything matchable about this device, lowercased, for [contains]. */
        fun haystack(): String =
            (
                listOf(address, name.orEmpty()) + services +
                    serviceData.map { "${it.key}:${it.value}" }
            ).joinToString(" ").lowercase()

        override fun toString(): String {
            val s = if (services.isEmpty()) "" else "  ${services.joinToString(" ")}"
            val d = serviceData.entries.joinToString("") { "  ${it.key}=${it.value}" }
            return "$address  ${rssi}dBm  ${name ?: "(no name)"}$s$d"
        }
    }

    /**
     * Scan for [timeoutMs], returning every distinct device seen.
     *
     * Stops early once [stopWhen] matches, so resolving a known device does not cost
     * the whole timeout. Low-latency mode deliberately: this runs for seconds under a
     * person waiting at a terminal, not in the background on a battery.
     */
    fun run(
        adapter: BluetoothAdapter,
        timeoutMs: Long,
        stopWhen: (Seen) -> Boolean = { false },
    ): Pair<List<Seen>, String?> {
        val scanner =
            adapter.bluetoothLeScanner ?: return Pair(emptyList(), "no LE scanner (adapter off?)")
        val found = ConcurrentHashMap<String, Seen>()
        val hit = CountDownLatch(1)
        var failure: String? = null

        val callback =
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val record = result.scanRecord
                    val seen =
                        Seen(
                            result.device.address,
                            record?.deviceName,
                            result.rssi,
                            record?.serviceUuids?.map { it.uuid.toString() }.orEmpty(),
                            record?.serviceData.orEmpty().entries.associate { (uuid, bytes) ->
                                // Short code only: the 128-bit form of an assigned UUID
                                // is noise, and `fe2c` is how Fast Pair is written down.
                                uuid.uuid.toString().substring(4, 8) to Hex.format(bytes)
                            },
                            result.device,
                        )
                    // ⚠ MERGE, do not keep-first or overwrite. One device arrives as
                    // several sightings — the advertisement and the scan response are
                    // separate packets, and only one of them carries the name. Keeping
                    // the first hid the JBL behind "(no name)" for a whole round of
                    // "it must not advertise"; overwriting would hide it just as often.
                    val merged =
                        found.compute(
                            seen.address,
                        ) { _, prior -> prior?.plus(seen) ?: seen }
                    if (merged != null && stopWhen(merged)) hit.countDown()
                }

                override fun onScanFailed(errorCode: Int) {
                    failure = "scan failed, error $errorCode"
                    hit.countDown()
                }
            }

        val settings =
            ScanSettings
                .Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
        return try {
            scanner.startScan(emptyList(), settings, callback)
            hit.await(timeoutMs, TimeUnit.MILLISECONDS)
            Pair(found.values.sortedByDescending { it.rssi }, failure)
        } catch (e: SecurityException) {
            Pair(emptyList(), "BLUETOOTH_SCAN not granted: ${e.message}")
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    /**
     * The first advertiser matching [needle] — its name, a service UUID, or
     * service-data bytes — as something connectable.
     *
     * Matching on service data is the fallback for a device advertising no name: the
     * Fast Pair greeting gives a 3-byte model ID, and the same bytes turn up in the
     * `fe2c` service data of the advertisement.
     */
    fun find(adapter: BluetoothAdapter, needle: String, timeoutMs: Long): Seen? {
        val want = needle.lowercase()
        val (seen, _) = run(adapter, timeoutMs) { it.haystack().contains(want) }
        return seen.firstOrNull { it.haystack().contains(want) }
    }
}
