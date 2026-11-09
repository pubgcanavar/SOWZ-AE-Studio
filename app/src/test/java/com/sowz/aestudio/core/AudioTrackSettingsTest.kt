package com.sowz.aestudio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioTrackSettingsTest {
    @Test fun defaultMusicGainIsSeventyPercent() {
        assertEquals(70, AudioTrackSettings().percentage)
    }

    @Test fun percentageReflectsLinearGain() {
        assertEquals(35, AudioTrackSettings(0.35f).percentage)
        assertEquals(100, AudioTrackSettings(1f).percentage)
    }

    @Test fun gainBelowZeroIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { AudioTrackSettings(-0.1f) }
    }

    @Test fun gainAboveOneIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { AudioTrackSettings(1.1f) }
    }

    @Test fun fullGainKeepsOriginalVideoAudioAtOneHundredPercent() {
        assertEquals(100, AudioTrackSettings(1f).percentage)
    }

    @Test fun zeroGainMutesTheTrack() {
        assertEquals(0, AudioTrackSettings(0f).percentage)
    }

    @Test fun nonFiniteGainIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { AudioTrackSettings(Float.NaN) }
    }
}
