package org.xinutec.volume.protocol

/**
 * Which control channel to open on a bonded device, and which protocol it speaks.
 *
 * Everything here was driven against the real headphones on 2026-08-15 — each
 * mapping below has had a command sent to it and an answer come back. See
 * `docs/protocols.md` for the bytes. **Re-measure before trusting it**; a firmware
 * update can change what a device advertises.
 *
 * ⚠ **"Which vendor is this?" and "which channel do I open?" have DIFFERENT
 * answers, and conflating them is what cost the most time here.** Two independent
 * traps, in opposite directions:
 *
 *  - The QC45 advertises `9b26d8c0-…`, which looks exactly like a vendor control
 *    channel. **It is not the one the Bose app uses** — the protocol is on plain
 *    SPP. So `9b26d8c0` identifies the device and must NOT be opened.
 *  - `df21fe2c-…` is on the JBL *and* the JLab and answers a documented protocol —
 *    but it is not either vendor's, and it is **not a control channel at all**. See
 *    [FAST_PAIR].
 *
 * So [Detection] carries a vendor, a channel, and a protocol as three separate
 * things, and [SHARED] governs only the first.
 */
object Channels {
    /** Identifies the Sony, and is also its channel. */
    const val SONY = "96cc203e-5068-46ad-b32d-e316f5e069ba"

    /** Identifies a Bose QC45. ⚠ NOT its channel — Bose speaks on [SPP]. */
    const val BOSE_MUSIC = "9b26d8c0-a8ed-440b-95b0-c4714a518bcc"

    /** The Bose channel, for both the QC45 and the QC35 (RFCOMM channel 8). */
    const val SPP = "00001101-0000-1000-8000-00805f9b34fb"

    /**
     * **Google Fast Pair Message Stream**, which the JBL and the JLab both implement
     * because both are Fast Pair certified. Not JBL's, not JLab's, not Harman's:
     * `[message group][message code][length: 2 BE][payload]`.
     *
     * ⚠ **It is a device-state channel, not a control channel.** It carries model
     * ID, battery and firmware — no ANC, no EQ. Vendor control lives elsewhere and
     * is not yet spoken; `docs/protocols.md` has the evidence and what is left.
     *
     * Identified from the payload shapes, not from a spec handshake: 3-byte model
     * ID, 8-byte session nonce, battery as one value (over-ear) or three (earbuds),
     * and `ff 01 <len> <group><code>` acknowledgements.
     */
    const val FAST_PAIR = "df21fe2c-2515-4fdb-8886-f12c4d67927c"

    /**
     * BES chip OTA service — named in the JBL app's own `BesSdkConstants`, so this is
     * a chipset record and not, as it first read, a JLab oddity. It opens and never
     * answers because an OTA session is not a command session.
     */
    const val BES_OTA = "66666666-6666-6666-6666-666666666666"

    /** On the JLab, opens, never answers, and is not named in either vendor app. */
    const val JLAB_UNIDENTIFIED = "99999999-9999-9999-9999-999999999999"

    /**
     * The BES command service, over **GATT** — this is where JBL control actually
     * lives, and it is not RFCOMM at all. The UUID is ASCII: `65 78 63 65 6c …` is
     * "excelpoint.com", BES's parent. Named `UUID_SERVICE_CMD_RXTX_UUID_00` and
     * friends in the JBL app; confirmed against a snoop capture of it working.
     *
     * ⚠ Reached at the device's **LE** address, not the BR/EDR one the RFCOMM
     * channels use, and that address rotates. See `docs/protocols.md`.
     */
    const val BES_GATT_SERVICE = "65786365-6c70-6f69-6e74-2e636f6d0000"

    /** Notifications come out of here (`…0001`), and writes go into `…0002`. */
    const val BES_GATT_NOTIFY = "65786365-6c70-6f69-6e74-2e636f6d0001"
    const val BES_GATT_WRITE = "65786365-6c70-6f69-6e74-2e636f6d0002"

    /** Seen on more than one vendor, so useless for IDENTIFICATION (see the note). */
    val SHARED =
        setOf(
            "00000000-deca-fade-deca-deafdecacaff",
            "81c2e72a-0591-443e-a1ff-05f988593351",
            // Answers a fourth framing on the JBL — `fe 03 01 04` then 16 zero bytes.
            // Not identified, and not pursued: it is on the Sony and both Bose too,
            // so whatever it is, it is not the JBL's control channel.
            "931c7e8a-540f-4686-b798-e8df0a2ad9f7",
            "f8d1fbe4-7966-4334-8024-ff96c9330e15",
            FAST_PAIR,
        )

    /** The Bluetooth SIG's own, by short code. Never a vendor control channel. */
    private val STANDARD =
        mapOf(
            "1101" to "SPP",
            "1103" to "DUN",
            "1104" to "IrMCSync",
            "1105" to "OBEX push",
            "1106" to "OBEX file transfer",
            "1108" to "headset",
            "110a" to "A2DP source",
            "110b" to "A2DP sink",
            "110c" to "AVRCP target",
            "110e" to "AVRCP",
            "1112" to "headset AG",
            "1115" to "PAN",
            "111e" to "handsfree",
            "111f" to "handsfree AG",
            "112d" to "SAP",
            "112f" to "PBAP",
            "1132" to "MAP",
            "1133" to "MNS",
        )

    enum class Vendor { SONY, BOSE, JBL, JLAB, UNKNOWN }

    /** What a channel speaks — only formats that have actually answered us. */
    enum class Protocol {
        /** `3e | type | seq | len(4 BE) | payload | sum | 3c`, see [SonyFrame]. */
        SONY_FRAMED,

        /** `[function block][function][operator][length][payload]`. */
        BOSE,

        /** `[message group][message code][length: 2 BE][payload]`, see [FAST_PAIR]. */
        FAST_PAIR,

        NONE,
    }

    /**
     * @param channel the UUID to actually open, or null when there is nothing to talk to.
     * @param basis how the vendor was decided — printed, because a name-based guess
     *   and a UUID match deserve different amounts of trust from whoever reads it.
     */
    data class Detection(
        val vendor: Vendor,
        val channel: String?,
        val protocol: Protocol,
        val basis: String,
    ) {
        override fun toString(): String =
            if (channel == null) {
                "$vendor ($basis)"
            } else {
                "$vendor / $protocol via $channel ($basis)"
            }
    }

    fun detect(name: String, uuids: Set<String>): Detection {
        val u = uuids.map { it.lowercase() }.toSet()
        val n = name.lowercase()

        // Unique markers first: a UUID match is the only evidence that cannot be
        // coincidence. Note the channel is not always the marker.
        if (SONY in u) return Detection(Vendor.SONY, SONY, Protocol.SONY_FRAMED, "unique uuid")
        if (BOSE_MUSIC in u) {
            // Identified by 9b26d8c0, but talked to over SPP. Driven and confirmed.
            return Detection(Vendor.BOSE, SPP, Protocol.BOSE, "unique uuid, speaks on spp")
        }

        // Fast Pair. Every certified device has it, so it names no vendor — the name
        // does that. It is offered as the channel because it is the only one on these
        // two that answers, but it buys state, not control.
        if (FAST_PAIR in u) {
            val who =
                when {
                    n.startsWith("jbl") -> Vendor.JBL
                    n.startsWith("jlab") -> Vendor.JLAB
                    else -> Vendor.UNKNOWN
                }
            val basis = if (who == Vendor.UNKNOWN) "fast pair, vendor unnamed" else "name"
            return Detection(who, FAST_PAIR, Protocol.FAST_PAIR, basis)
        }

        // A JLab without Fast Pair: what is left is an OTA service and one unknown,
        // neither of which answers, so there is nothing to connect to.
        if (BES_OTA in u || JLAB_UNIDENTIFIED in u) {
            return Detection(Vendor.JLAB, null, Protocol.NONE, "only silent uuids")
        }

        // A Bose QC35 advertises plain SPP and nothing else that identifies it, so
        // the name is all there is — and the answer says so.
        if (SPP in u) {
            return if ("bose" in n) {
                Detection(Vendor.BOSE, SPP, Protocol.BOSE, "name, spp is ambiguous")
            } else {
                Detection(Vendor.UNKNOWN, SPP, Protocol.NONE, "spp present, vendor unidentified")
            }
        }
        return Detection(Vendor.UNKNOWN, null, Protocol.NONE, "no control channel advertised")
    }

    /** A trailing note for the `list` output, so a reader is not left guessing. */
    fun annotate(uuid: String): String {
        val u = uuid.lowercase()
        STANDARD[u.substring(4, 8)]?.let {
            if (u.endsWith("-0000-1000-8000-00805f9b34fb")) {
                return if (u == SPP) "  ← SPP (the BOSE control channel)" else "  ← $it"
            }
        }
        return when {
            u == SONY -> "  ← SONY CONTROL"
            u == BOSE_MUSIC -> "  ← identifies a QC45, but it speaks on SPP"
            u == FAST_PAIR -> "  ← Fast Pair message stream (state, not control; not an id)"
            u == BES_OTA -> "  ← BES chip OTA service, silent outside an OTA session"
            u == JLAB_UNIDENTIFIED -> "  ← on the JLab, opens but never answers"
            u in SHARED -> "  ← shared across vendors, not a marker"
            else -> "  ← unknown"
        }
    }
}
