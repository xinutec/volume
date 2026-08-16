package org.xinutec.volume.protocol

/**
 * Whether a bonded device could be headphones, from its class of device.
 *
 * The list is built from what is actually paired to this phone, and the shape of
 * that data decides the design:
 *
 * | class      | device                                   |
 * |------------|------------------------------------------|
 * | `0x240418` | Bose QC45 — headphones                   |
 * | `0x240404` | JBL Tour One M2, JLab, Sony XM4 — headset|
 * | `0x240414` | ACTON II, Crowley, SoundLink — speakers  |
 * | `0x2A4104` | HORUS — computer                         |
 * | `0x7C0104` | a laptop                                 |
 * | `0x001F00` | **the renamed QC35**, and a Fitbit       |
 *
 * ⚠ **So this DENIES, it does not allow.** The obvious version — accept the three
 * headphone classes — would hide the QC35, which reports `0x001F00`, uncategorised,
 * exactly like a fitness tracker. Losing a device we can demonstrably drive is far
 * worse than listing one we cannot, because the second is visible and the first is
 * not: it just quietly is not there. Anything uncategorised therefore falls through
 * to the caller's channel check.
 */
object Wearable {
    /** Major class, the top of the 24-bit class of device. */
    private fun major(deviceClass: Int) = deviceClass and 0x1F00

    /** Minor + major, which is what `BluetoothClass.getDeviceClass()` returns. */
    private fun minor(deviceClass: Int) = deviceClass and 0x1FFC

    private const val AUDIO_VIDEO = 0x0400
    private const val LOUDSPEAKER = 0x0414
    private const val HIFI_AUDIO = 0x0428
    private const val VIDEO_DISPLAY = 0x0410
    private const val SET_TOP_BOX = 0x0424
    private const val CAR_AUDIO = 0x0420
    private const val COMPUTER = 0x0100
    private const val PHONE = 0x0200
    private const val NETWORKING = 0x0300
    private const val PERIPHERAL = 0x0500
    private const val IMAGING = 0x0600

    /**
     * False only when the class positively says this is something else.
     *
     * ⚠ A speaker is the case that matters. `Crowley` and the SoundLink both offer
     * SPP and are always in the room, so without this they list as drivable
     * headphones with a Connect button that can only fail slowly.
     */
    fun couldBeHeadphones(deviceClass: Int): Boolean {
        if (major(deviceClass) in setOf(COMPUTER, PHONE, NETWORKING, PERIPHERAL, IMAGING)) {
            return false
        }
        if (major(deviceClass) == AUDIO_VIDEO) {
            return minor(deviceClass) !in
                setOf(LOUDSPEAKER, HIFI_AUDIO, VIDEO_DISPLAY, SET_TOP_BOX, CAR_AUDIO)
        }
        // Uncategorised, wearable, health, anything unknown: not ruled out. The
        // caller still has to find a control channel on it.
        return true
    }
}
