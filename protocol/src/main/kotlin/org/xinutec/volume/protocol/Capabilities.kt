package org.xinutec.volume.protocol

/**
 * BES settings both JBL models answer with the same frames.
 *
 * Writes are `set*` and say what the device then holds. Which reply is evidence —
 * the reply itself, or a re-read after an ack — is the implementing driver's to know.
 */
interface JblSharedSettings {
    fun readAutoOff(t: Transport): TimedOff?

    fun setAutoOff(t: Transport, v: TimedOff): Confirmation<TimedOff>

    fun readAutoPlay(t: Transport): Boolean?

    fun setAutoPlay(t: Transport, on: Boolean): Confirmation<Boolean>

    fun readBalance(t: Transport): Balance?

    fun setBalance(t: Transport, v: Balance): Confirmation<Balance>

    fun readGestures(t: Transport): Map<Gesture, GestureAction>?

    fun writeGesture(
        t: Transport,
        g: Gesture,
        want: GestureAction,
        was: GestureAction,
    ): GestureWrite

    fun readVoiceAware(t: Transport): VoiceAware?

    fun setVoiceAware(t: Transport, v: VoiceAware): Confirmation<VoiceAware>

    fun readVoicePrompts(t: Transport): Boolean?

    fun readAdvancedAnc(t: Transport): AdvancedAnc?
}

/** Spatial sound: the switch and its mode, written together and read back. */
interface SpatialDriver {
    fun setSpatial(t: Transport, v: Spatial): Confirmation<Spatial>
}

/** Smart Audio & Video, whose payloads differ per model. */
interface SmartAvDriver {
    fun readSmartAv(t: Transport): SmartAv?

    fun setSmartAv(t: Transport, v: SmartAv): Confirmation<SmartAv>
}
