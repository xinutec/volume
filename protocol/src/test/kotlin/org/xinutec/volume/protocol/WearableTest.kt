package org.xinutec.volume.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every value here was read off Pippijn's phone on 2026-08-16
 * (`dumpsys bluetooth_manager`), not invented — a class of device made up to suit
 * the rule would prove only that the rule matches itself.
 */
class WearableTest {
    @Test
    fun `the four drivable pairs are not ruled out`() {
        assertTrue(Wearable.couldBeHeadphones(0x240418)) // Bose QC45
        assertTrue(Wearable.couldBeHeadphones(0x240404)) // JBL, JLab, Sony XM4
    }

    /**
     * ⚠ **The one that decides the design.** The renamed QC35 reports `0x001F00` —
     * uncategorised, byte-identical to a Fitbit. An allow-list of headphone classes
     * would silently drop a device this app drives today, and a missing card is
     * invisible in a way a spurious one is not.
     */
    @Test
    fun `an uncategorised device is NOT ruled out`() {
        assertTrue(Wearable.couldBeHeadphones(0x001F00))
    }

    /**
     * ⚠ The case this exists for: speakers that offer SPP and never leave the room,
     * so they list as drivable and their Connect button can only fail slowly.
     */
    @Test
    fun `speakers are ruled out`() {
        assertTrue(Wearable.couldBeHeadphones(0x240418)) // control: audio, not a speaker
        // ⚠ Still false, and still right: this says what the CLASS claims. The
        // SoundLink Revolve is listed anyway because `DeviceController.drivable`
        // asks the Registry FIRST and a positive identification beats this.
        assertFalse(Wearable.couldBeHeadphones(0x240414)) // ACTON II, Crowley, SoundLink
    }

    @Test
    fun `computers are ruled out`() {
        assertFalse(Wearable.couldBeHeadphones(0x2A4104)) // HORUS
        assertFalse(Wearable.couldBeHeadphones(0x7C0104)) // a laptop
    }

    @Test
    fun `other audio equipment is ruled out`() {
        assertFalse(Wearable.couldBeHeadphones(0x240428)) // hi-fi
        assertFalse(Wearable.couldBeHeadphones(0x240420)) // car audio
        assertFalse(Wearable.couldBeHeadphones(0x240410)) // a display
    }

    @Test
    fun `phones and peripherals are ruled out`() {
        assertFalse(Wearable.couldBeHeadphones(0x5A020C)) // a phone
        assertFalse(Wearable.couldBeHeadphones(0x000540)) // a keyboard
    }

    /** A hands-free headset is a headset. */
    @Test
    fun `handsfree is allowed`() {
        assertTrue(Wearable.couldBeHeadphones(0x240408))
    }
}
