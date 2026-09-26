package org.xinutec.volume.protocol

/** BES settings both JBL models answer with the same frames. */
interface JblSharedSettings {
    fun readAutoOff(t: Transport): TimedOff?

    /** Returns nothing: the reply is an ack; [readAutoOff] is the read-back. */
    fun writeAutoOff(t: Transport, v: TimedOff)

    fun readAutoPlay(t: Transport): Boolean?

    fun writeAutoPlay(t: Transport, on: Boolean): Boolean?

    fun readBalance(t: Transport): Balance?

    fun writeBalance(t: Transport, v: Balance): Balance?

    fun readGestures(t: Transport): Map<Gesture, GestureAction>?

    fun writeGesture(
        t: Transport,
        g: Gesture,
        want: GestureAction,
        was: GestureAction,
    ): GestureWrite

    fun readVoiceAware(t: Transport): VoiceAware?

    fun writeVoiceAware(t: Transport, v: VoiceAware): VoiceAware?

    fun readVoicePrompts(t: Transport): Boolean?

    fun readAdvancedAnc(t: Transport): AdvancedAnc?
}

/** Spatial sound: the switch and its mode, written together and read back. */
interface SpatialDriver {
    fun writeSpatial(t: Transport, v: Spatial): Spatial?
}

/** Smart Audio & Video, whose payloads differ per model. */
interface SmartAvDriver {
    fun readSmartAv(t: Transport): SmartAv?

    fun writeSmartAv(t: Transport, v: SmartAv): SmartAv?
}
