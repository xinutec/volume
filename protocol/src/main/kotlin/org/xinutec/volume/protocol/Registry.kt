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
     * Wake a Bose BMAP session — a QC35 has been seen answering NOTHING until this is sent.
     *
     * ⚠ **REFUTED as a rule, 2026-08-29 — this is a STATE, not a property of a fresh
     * socket.** On a virgin session after a power cycle, with the activity and every vendor
     * app stopped, the same QC35 answered `01 06` cold four times, including once after a
     * five-minute idle gap. So the wake is kept because it costs one read, **NOT** because
     * a fresh socket is known to need it.
     *
     * ✅ **The state is per-DEVICE-SESSION, not per-socket** (2026-08-29): the wake was sent
     * on one socket and a later, separate socket answered without a block-`00` of its own.
     * So this is sent once per session and the cost is one read, not one read per socket.
     *
     * ⚠ What induces it is still unknown, and the two obvious answers are both spent.
     * **Idle is out to 75 minutes** (2026-08-29: one cold read after 4500 s untouched,
     * answered). **A Bluetooth stack restart does not induce it** either — though that
     * control moved two variables at once and settles less than it was written up as. The
     * **A phone reboot does not induce it** — 2026-08-30 caught a silence 26.3 HOURS after
     * boot, 13 s after the headset reconnected, which retires both clocks. That sitting also
     * showed the silence covers `04 04` as well as block `01`, so it is every block except
     * `00`. What survives is how long the HEADSET was powered off, confounded with the gap
     * since last contact. #1232.
     *
     * ⚠⚠ **Measured on a QC35, 2026-08-28, and it made the device unusable from this app.**
     * Every `01 06`, `01 02` and `01 01` sent on a new socket went out on the wire and drew
     * no reply at all — four in a row in one socket, then more across 28 minutes and two
     * reconnections. The snoop shows the frames leaving and nothing coming back, while the
     * device's *other* RFCOMM channel answered normally throughout. It reads exactly like
     * broken headphones. Send any block-`00` read first and every one of those functions
     * answers immediately.
     *
     * ⚠ **Block 00, not one magic frame**: `00 01` and `00 02` were each shown to work on a
     * fresh socket. And it is not "the first frame is swallowed" — four consecutive reads
     * with no block-`00` among them drew nothing.
     *
     * ⚠ **Harmless on a QC45**, which needs no waking and answers `00 01` with its protocol
     * version (`1.1.0`, against the QC35's `1.0.4`). So it is sent unconditionally rather
     * than per model — a device that does not need it pays one cheap read.
     */
    fun wakeBose(t: Transport) {
        t.exchange(byteArrayOf(0x00, 0x01, 0x01, 0x00))
    }

    /**
     * Tell a QC45 from a QC35 by **asking it**, for the case the SDP record cannot.
     *
     * `01 06` is the QC35's ANC function and one the QC45 reports unsupported, so a
     * single read separates them — and a read is safe on headphones someone is
     * wearing, which a probing write would not be.
     *
     * ⚠ **Wakes the session first.** Without it a QC35 answers nothing here and is
     * reported "unidentified", which is what it did all afternoon on 2026-08-28 — see
     * [wakeBose]. The null return below cannot tell a silent device from an asleep one.
     *
     * Returns null if it answers neither way; the caller should say "unidentified"
     * rather than pick one, because the two tables disagree about what `01 06`
     * even means.
     */
    fun identifyBose(t: Transport): AncDriver? {
        wakeBose(t)
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
