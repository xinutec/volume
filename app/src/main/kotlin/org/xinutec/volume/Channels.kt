package org.xinutec.volume

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
 *  - `df21fe2c-…` is on the JBL *and* the JLab, so it cannot say which vendor a
 *    device is — and it is nonetheless **the control channel for both**. Shared
 *    does not mean useless; it means it answers the second question, not the first.
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
     * The JBL **and** JLab control channel. Both answer the same
     * `[block][cmd][len:2 BE][payload]` protocol on it, which is why one
     * implementation covers both — they are near-certainly the same chipset stack
     * (inferred from the identical framing, not confirmed from a datasheet).
     */
    const val HARMAN = "df21fe2c-2515-4fdb-8886-f12c4d67927c"

    /** Advertised by the JLab and **dead**: both sockets open and never answer. */
    const val JLAB_DECOY_A = "66666666-6666-6666-6666-666666666666"
    const val JLAB_DECOY_B = "99999999-9999-9999-9999-999999999999"

    /** Seen on more than one vendor, so useless for IDENTIFICATION (see the note). */
    val SHARED =
        setOf(
            "00000000-deca-fade-deca-deafdecacaff",
            "81c2e72a-0591-443e-a1ff-05f988593351",
            "931c7e8a-540f-4686-b798-e8df0a2ad9f7",
            "f8d1fbe4-7966-4334-8024-ff96c9330e15",
            HARMAN,
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

    /** Five devices, three wire formats. */
    enum class Protocol {
        /** `3e | type | seq | len(4 BE) | payload | sum | 3c`, see [SonyFrame]. */
        SONY_FRAMED,

        /** `[function block][function][operator][length][payload]`. */
        BOSE,

        /** `[block][cmd][length: 2 bytes BE][payload]`. */
        HARMAN,

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

        // The Harman-family channel. It cannot say WHICH of the two this is, so the
        // name does that — but either way the channel and protocol are already known,
        // which is why an unrecognised name still gets a working connection.
        if (HARMAN in u) {
            val who =
                when {
                    n.startsWith("jbl") -> Vendor.JBL
                    n.startsWith("jlab") -> Vendor.JLAB
                    else -> Vendor.UNKNOWN
                }
            val basis = if (who == Vendor.UNKNOWN) "harman channel, vendor unnamed" else "name"
            return Detection(who, HARMAN, Protocol.HARMAN, basis)
        }

        // A JLab that somehow lacks the Harman channel: its own advertised UUIDs are
        // decoys that open and never answer, so there is nothing to connect to.
        if (JLAB_DECOY_A in u || JLAB_DECOY_B in u) {
            return Detection(Vendor.JLAB, null, Protocol.NONE, "only dead decoy uuids")
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
            u == HARMAN -> "  ← JBL/JLab CONTROL (shared, so not an id)"
            u == JLAB_DECOY_A || u == JLAB_DECOY_B -> "  ← JLab decoy, opens but never answers"
            u in SHARED -> "  ← shared across vendors, not a marker"
            else -> "  ← unknown"
        }
    }
}
