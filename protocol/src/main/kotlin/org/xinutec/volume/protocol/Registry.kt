package org.xinutec.volume.protocol

/** How to reach a device's control channel. Implemented by `:app`, chosen here. */
sealed interface Route {
    /** An RFCOMM socket on [uuid]. */
    data class Rfcomm(
        val uuid: String,
    ) : Route

    /**
     * A GATT connection: subscribe [notify], write [write].
     *
     * ⚠ No address here on purpose. A GATT device is reached at an LE address that
     * **rotates**, so the address is a scan result, not a property of the model.
     */
    data class Gatt(
        val service: String,
        val write: String,
        val notify: String,
    ) : Route
}

/** A device we know how to drive: where to connect, and what to say once there. */
data class Headphones(
    val vendor: Channels.Vendor,
    val model: String,
    val route: Route,
    val driver: AncDriver,
)

/**
 * Which driver a bonded device gets.
 *
 * ⚠ **Identification and routing are still different questions** — the trap
 * `Channels` exists for — and this adds a third: two devices can share a vendor, a
 * channel and a protocol and still need *different tables*. The QC45 and QC35 do.
 */
object Registry {
    /**
     * From what the device advertises alone.
     *
     * Returns null when the SDP record cannot settle it, which is not a failure to
     * paper over: Pippijn's own QC35 is renamed "LE-Pippijn Headphon" and
     * advertises nothing but standard and shared UUIDs, so a name-based guess would
     * be wrong for anyone who renamed a QC45 instead. Use [identify] then.
     */
    fun fromAdvertisement(name: String, uuids: Set<String>): Headphones? {
        val d = Channels.detect(name, uuids)
        val n = name.lowercase()
        return when {
            d.vendor == Channels.Vendor.SONY -> {
                // ⚠ A fresh driver, not a shared one: the Sony's sequence bit is
                // per-connection state, and two pairs sharing a counter would each
                // see the other's frames as retransmissions.
                Headphones(
                    d.vendor,
                    "Sony WH-1000XM4",
                    Route.Rfcomm(Channels.SONY),
                    Drivers.SonyXm4(),
                )
            }

            // The QC45 is the only Bose that carries a unique marker.
            Channels.BOSE_MUSIC in uuids.map { it.lowercase() } -> {
                Headphones(d.vendor, "Bose QC45", Route.Rfcomm(Channels.SPP), Drivers.BoseQc45)
            }

            d.vendor == Channels.Vendor.BOSE && "qc35" in n -> {
                Headphones(d.vendor, "Bose QC35", Route.Rfcomm(Channels.SPP), Drivers.BoseQc35)
            }

            d.vendor == Channels.Vendor.JBL -> {
                Headphones(
                    d.vendor,
                    "JBL Tour One M2",
                    Route.Gatt(
                        Channels.BES_GATT_SERVICE,
                        Channels.BES_GATT_WRITE,
                        Channels.BES_GATT_NOTIFY,
                    ),
                    Drivers.JblBes,
                )
            }

            d.vendor == Channels.Vendor.JLAB -> {
                Headphones(
                    d.vendor,
                    "JLab JBuds Sport ANC 4",
                    Route.Rfcomm(Channels.SPP),
                    Drivers.JLabQcy,
                )
            }

            else -> {
                null
            }
        }
    }

    /**
     * Tell a QC45 from a QC35 by **asking it**, for the case the SDP record cannot.
     *
     * `01 06` is the QC35's ANC function and one the QC45 reports unsupported, so a
     * single read separates them — and a read is safe on headphones someone is
     * wearing, which a probing write would not be.
     *
     * Returns null if it answers neither way; the caller should say "unidentified"
     * rather than pick one, because the two tables disagree about what `01 06`
     * even means.
     */
    fun identifyBose(t: Transport): AncDriver? {
        val r = t.exchange(byteArrayOf(0x01, 0x06, 0x01, 0x00))
        val operator = r.getOrNull(2) ?: return null
        return when (operator) {
            // 04 is the Error operator: the function is not on this model.
            0x04.toByte() -> Drivers.BoseQc45

            // 03 is Status: it answered with a value, so the function exists.
            0x03.toByte() -> Drivers.BoseQc35

            else -> null
        }
    }
}
