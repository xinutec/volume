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
        /** What to look for in an LE scan, which is how the rotating address is found. */
        val advertises: String,
    ) : Route
}

/** A device we know how to drive: where to connect, and what to say once there. */
data class Headphones(
    val vendor: Channels.Vendor,
    val model: String,
    val route: Route,
    val driver: Driver,
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
     * be wrong for anyone who renamed a QC45 instead. Use [identifyBose] then.
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

            // ⚠ **Before the QC35's own rule would ever see it**, and named by MODEL for
            // the same reason every other branch here is: `Drivers.BoseRevolve` has no ANC
            // and reports so, and handing a speaker the QC35's driver would offer chips
            // for a control it does not have. ⚠ `identifyBose` cannot help — it separates
            // the two headphones by asking `01 06`, which a Revolve answers "unsupported"
            // exactly as a QC45 does.
            d.vendor == Channels.Vendor.BOSE && "revolve" in n -> {
                Headphones(
                    d.vendor,
                    "Bose SoundLink Revolve",
                    Route.Rfcomm(Channels.SPP),
                    Drivers.BoseRevolve,
                )
            }

            // ⚠⚠ **Its own driver, and the reason is the whole of 2026-09-13.** This
            // pair answers on the same service as the M2, to the same name frame, and
            // then disagrees about ANC in both directions: `aa 91 01 11` reports a
            // confirmed TalkThru as ANC or as Ambient depending on when you ask, and
            // `aa 91`'s setter cannot select ANC at all. Handing it [Drivers.JblBes]
            // would have produced a card that reads the wrong mode and has a chip that
            // does nothing — which is exactly what a vendor-only branch did here for
            // nine days. See [Drivers.JblLivePro2] for the measurements.
            d.vendor == Channels.Vendor.JBL && "live pro 2" in n -> {
                Headphones(
                    d.vendor,
                    "JBL LIVE PRO 2",
                    Route.Gatt(
                        Channels.BES_GATT_SERVICE,
                        Channels.BES_GATT_WRITE,
                        Channels.BES_GATT_NOTIFY,
                        advertises = "JBL LIVE PRO 2",
                    ),
                    Drivers.JblLivePro2,
                )
            }

            // ⚠⚠ **The MODEL, not just the vendor — and this branch was vendor-only
            // until 2026-09-12**, which is nine days after the identical fix went in
            // for JLab immediately below and did not get carried across. A LIVE PRO 2
            // TWS was named "JBL Tour One M2" on screen and sent the app hunting for
            // the wrong model over LE; Pippijn watched it do that.
            //
            // ⛔ The stake is the one the JLab comment names: BES is where `aa 95`
            // factory reset lives, so a second JBL model driven by the Tour One M2's
            // frames is not a wrong reading, it is an unknown write.
            d.vendor == Channels.Vendor.JBL && "tour one m2" in n -> {
                Headphones(
                    d.vendor,
                    "JBL Tour One M2",
                    Route.Gatt(
                        Channels.BES_GATT_SERVICE,
                        Channels.BES_GATT_WRITE,
                        Channels.BES_GATT_NOTIFY,
                        advertises = "JBL TOUR",
                    ),
                    Drivers.JblBes,
                )
            }

            // ⚠⚠ **The MODEL, not just the vendor — and this branch was vendor-only
            // until 2026-09-03.** It named every JLab "JBuds Sport ANC 4" and handed it
            // that driver, which is the mistake `the two bose models do not share a
            // driver` exists to forbid one vendor over. ⛔ It matters more here than
            // there: this protocol's id space is a Realtek SDK's and **holds a factory
            // reset**, and nothing measured says which id — so a second JLab model
            // driven by these frames is not a wrong reading, it is an unknown write.
            //
            // ⚠ **The cost is that a RENAMED one stops being driven**, and unlike Bose
            // there is no recovery: [identifyBose] has no JLab counterpart, so this
            // returns null and the device simply is not offered. That is the failure
            // worth having — undriven is visible and fixable, mis-driven is neither.
            d.vendor == Channels.Vendor.JLAB && "jbuds sport anc 4" in n -> {
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
     * Whether to list a bonded device: known from its record, or headphones on SPP that
     * [identifyBose] can ask. One rule, so the app and the tile list the same devices.
     */
    fun drivable(name: String, uuids: Set<String>, deviceClass: Int): Boolean =
        fromAdvertisement(name, uuids) != null ||
            (
                Wearable.couldBeHeadphones(
                    deviceClass,
                ) && Channels.SPP in uuids.map { it.lowercase() }
            )

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
     * control moved two variables at once and so settles less than a clean one would. The
     * **A phone reboot does not induce it** — 2026-08-30 caught a silence 26.3 HOURS after
     * boot, 13 s after the headset reconnected, which retires both clocks. That sitting also
     * showed the silence covers `04 04` as well as block `01`, so it is every block except
     * `00`. **The cause is how long the HEADSET was powered off**, isolated by a matched
     * control: 20 min 51 s off was silent, while 20 min 30 s of no contact with the headset
     * on and awake answered. ~20 s off answers, so it is the duration, not the act. The
     * threshold is somewhere in 20 s … 20.9 min and was not chased, because this read is sent
     * unconditionally and no branch asks. #1232.
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
        t.exchange(OutFrame(byteArrayOf(0x00, 0x01, 0x01, 0x00)))
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
    fun identifyBose(t: Transport): BoseIdentity {
        wakeBose(t)
        val r = t.exchange(OutFrame(byteArrayOf(0x01, 0x06, 0x01, 0x00)))
        val operator = r.getOrNull(2) ?: return BoseIdentity.Silent(r.size)
        return when (operator) {
            // 04 is the Error operator: the function is not on this model.
            0x04.toByte() -> BoseIdentity.Known(Drivers.BoseQc45, "QC45")

            // 03 is Status: it answered with a value, so the function exists.
            0x03.toByte() -> BoseIdentity.Known(Drivers.BoseQc35, "QC35")

            else -> BoseIdentity.Unexpected(r)
        }
    }
}

/**
 * What `01 06` produced, which is THREE outcomes and was two.
 *
 * ⚠⚠ **"It answered in neither shape" was printed for SILENCE.** `identifyBose`
 * returned null both when the operator byte was unrecognised and when there was no
 * reply to take one from, and the screen's one sentence claimed an answer either way.
 * On 2026-09-12 that sentence sat under a device which — measured eight different ways
 * — never answers anything at all, and it was convincing enough to briefly overturn the
 * evening's conclusion. A device that says nothing and a device that says something
 * unexpected are different facts about it, and only one of them is worth probing again.
 */
sealed interface BoseIdentity {
    data class Known(
        val driver: Driver,
        val model: String,
    ) : BoseIdentity

    /** It replied, and the operator byte was neither Status nor Error. */
    data class Unexpected(
        val reply: ByteArray,
    ) : BoseIdentity

    /** Nothing came back — [bytes] is what arrived, 0 when the read timed out empty. */
    data class Silent(
        val bytes: Int,
    ) : BoseIdentity
}
