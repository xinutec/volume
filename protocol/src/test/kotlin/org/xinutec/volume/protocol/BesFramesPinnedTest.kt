package org.xinutec.volume.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every BES request this repo builds, byte for byte.
 *
 * ⚠ **Pinned from the builders as they stood before the codec computed lengths**, so
 * a builder moved onto [Bes] cannot change a byte unnoticed. These are the frames the
 * JBLs were driven with; `bes-frames.txt` holds them, one `name=hex` per line.
 */
class BesFramesPinnedTest {
    @Test
    fun `every builder sends what it always sent`() {
        for ((name, frame) in FRAMES) {
            assertEquals(name, PINNED[name], Hex.format(frame.bytes))
        }
        assertEquals(PINNED.keys, FRAMES.map { it.first }.toSet())
    }

    companion object {
        val FRAMES: List<Pair<String, OutFrame>> =
            buildList {
                add("battery.get" to JblBattery.get())
                add("battery.getSdk" to JblBattery.getSdk())
                add("gestures.get" to JblGestures.get())
                for (g in Gesture.entries) {
                    for (a in GestureAction.entries) {
                        add("gestures.set.$g.$a" to JblGestures.set(g, a))
                    }
                }
                add("autoPlay.get" to JblAutoPlay.get())
                for (on in listOf(true, false)) add("autoPlay.set.$on" to JblAutoPlay.set(on))
                add("balance.get" to JblBalance.get())
                for (on in listOf(true, false)) {
                    for (level in listOf(0, 100, 255)) {
                        add("balance.set.$on.$level" to JblBalance.set(Balance(on, level)))
                    }
                }
                add("psap.get" to JblPsap.get())
                add("powerOff.off" to JblPowerOff.off())
                add("advancedAnc.get" to JblAdvancedAnc.get())
                add("voicePrompts.get" to JblVoicePrompts.get())
                add("autoOff.get" to JblAutoOff.get())
                for (on in listOf(true, false)) {
                    for (m in JBL_IDLE_MINUTES) {
                        add("autoOff.set.$on.$m" to JblAutoOff.set(TimedOff(on, m)))
                    }
                }
                add("eq.get" to JblEq.get())
                add("safeSound.get" to JblSafeSound.get())
                add("spatial.get" to JblSpatial.get())
                for (on in listOf(true, false)) {
                    for (m in SpatialMode.entries) {
                        add("spatial.set.$on.$m" to JblSpatial.set(Spatial(on, m)))
                    }
                }
                add("voiceAware.get" to JblVoiceAware.get())
                for (on in listOf(true, false)) {
                    for (l in VoiceLevel.entries) {
                        add("voiceAware.set.$on.$l" to JblVoiceAware.set(VoiceAware(on, l)))
                    }
                }
                add("smartTalk.get" to JblSmartTalk.get())
                for (on in listOf(true, false)) {
                    for (x in TalkTimeout.entries) {
                        add("smartTalk.set.$on.$x" to JblSmartTalk.set(SmartTalk(on, x)))
                    }
                }
                add("lowVolumeEq.get" to JblLowVolumeEq.get())
                for (on in listOf(true, false)) add("lowVolumeEq.set.$on" to JblLowVolumeEq.set(on))
                add("smartAv.get" to JblSmartAv.get())
                for (v in SmartAv.entries) add("smartAv.set.$v" to JblSmartAv.set(v))
                for ((v, payload) in Drivers.JblLivePro2.SMART_AV) {
                    add("smartAv.set.livePro2.$v" to JblSmartAv.set(payload))
                }
                for (k in JblFeature.Key.entries) {
                    add("feature.get.$k" to JblFeature.get(k))
                    for (on in listOf(true, false)) {
                        add("feature.set.$k.$on" to JblFeature.set(k, on))
                    }
                }
                add("beeping.get" to JblBeeping.get())
                for (b in Bud.entries) {
                    for (on in listOf(true, false)) {
                        add("beeping.set.$b.$on" to JblBeeping.set(b, on))
                    }
                }
                add("inEar.get" to JblInEar.get())
                add("eqPreset.get" to JblEqPreset.get())
                for (p in JblEqPreset.NAMES.keys.sorted()) {
                    add("eqPreset.set.$p" to JblEqPreset.set(p))
                }
            }

        val PINNED: Map<String, String> =
            BesFramesPinnedTest::class.java
                .getResource("/bes-frames.txt")!!
                .readText()
                .lines()
                .filter { it.isNotBlank() }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
    }
}
