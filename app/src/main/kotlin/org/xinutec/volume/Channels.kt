package org.xinutec.volume

/**
 * Which vendor control channel a bonded device advertises, decided from its SDP
 * record.
 *
 * The UUID sets below were read off the Pixel 9 on 2026-08-15 with
 * `adb shell dumpsys bluetooth_manager`. **Re-measure before trusting them** — a
 * firmware update can change what a device advertises.
 *
 * ⚠ **Most of the interesting-looking UUIDs are not vendor markers.** Three
 * (`81c2e72a`, `931c7e8a`, `f8d1fbe4`) appear on the Sony *and* the JBL *and* the
 * unidentified Bose; `df21fe2c` on the JBL *and* the JLab; and
 * `00000000-deca-fade-…` on the Sony, the QC45 and the unidentified Bose alike.
 * That last one matters because task #783 annotates it "Bose proprietary" — it
 * isn't, and a build that keyed on it would have opened the wrong channel. Only
 * UUIDs seen on exactly one vendor may discriminate, which is why [SHARED] is a
 * list this file asserts about rather than a comment.
 */
object Channels {
    const val SONY = "96cc203e-5068-46ad-b32d-e316f5e069ba"
    const val BOSE_MUSIC = "9b26d8c0-a8ed-440b-95b0-c4714a518bcc"
    const val SPP = "00001101-0000-1000-8000-00805f9b34fb"
    const val JLAB_A = "66666666-6666-6666-6666-666666666666"
    const val JLAB_B = "99999999-9999-9999-9999-999999999999"

    /** Seen on more than one vendor, so useless for identification. */
    val SHARED =
        setOf(
            "00000000-deca-fade-deca-deafdecacaff",
            "81c2e72a-0591-443e-a1ff-05f988593351",
            "931c7e8a-540f-4686-b798-e8df0a2ad9f7",
            "f8d1fbe4-7966-4334-8024-ff96c9330e15",
            "df21fe2c-2515-4fdb-8886-f12c4d67927c",
        )

    /** The Bluetooth SIG's own, by short code. Never a control channel. */
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

    enum class Vendor { SONY, BOSE_MUSIC, BOSE_CONNECT, JBL, JLAB, UNKNOWN }

    /**
     * @param uuid the channel to open, or null when nothing addressable was found.
     * @param basis how it was decided — printed, because a name-based guess and a
     *   UUID match deserve different amounts of trust from whoever reads the output.
     */
    data class Detection(
        val vendor: Vendor,
        val uuid: String?,
        val basis: String,
    ) {
        override fun toString(): String =
            if (uuid == null) "$vendor ($basis)" else "$vendor via $uuid ($basis)"
    }

    fun detect(name: String, uuids: Set<String>): Detection {
        val u = uuids.map { it.lowercase() }.toSet()
        // Unique markers first: a UUID match is the only evidence here that cannot
        // be coincidence.
        if (SONY in u) return Detection(Vendor.SONY, SONY, "unique uuid")
        if (BOSE_MUSIC in u) return Detection(Vendor.BOSE_MUSIC, BOSE_MUSIC, "unique uuid")
        if (JLAB_A in u || JLAB_B in u) {
            return Detection(Vendor.JLAB, if (JLAB_A in u) JLAB_A else JLAB_B, "unique uuid")
        }

        // Everything left that carries SPP is ambiguous by SDP alone: the JBL Tour
        // One M2 advertises no unique UUID at all, and a Bose Connect-era QC35
        // advertises plain SPP too. So the name is all there is, and the answer says so.
        if (SPP in u) {
            val n = name.lowercase()
            return when {
                n.startsWith("jbl") -> Detection(Vendor.JBL, SPP, "name, spp is ambiguous")
                n.startsWith("jlab") -> Detection(Vendor.JLAB, SPP, "name, spp is ambiguous")
                "bose" in n -> Detection(Vendor.BOSE_CONNECT, SPP, "name, spp is ambiguous")
                else -> Detection(Vendor.UNKNOWN, SPP, "spp present, vendor unidentified")
            }
        }
        return Detection(Vendor.UNKNOWN, null, "no control channel advertised")
    }

    /** A trailing note for the `list` output, so a reader is not left guessing. */
    fun annotate(uuid: String): String {
        val u = uuid.lowercase()
        STANDARD[u.substring(4, 8)]?.let {
            if (u.endsWith("-0000-1000-8000-00805f9b34fb")) return "  ← $it"
        }
        return when {
            u == SONY -> "  ← SONY CONTROL"
            u == BOSE_MUSIC -> "  ← BOSE MUSIC CONTROL"
            u == JLAB_A || u == JLAB_B -> "  ← JLAB CONTROL"
            u in SHARED -> "  ← shared across vendors, not a marker"
            else -> "  ← unknown"
        }
    }
}
