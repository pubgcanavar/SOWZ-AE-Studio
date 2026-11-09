package com.sowz.aestudio.core

/**
 * Project-level settings for the optional background audio sequence.
 * Gain is linear and intentionally bounded to Media3's supported 0..1 range.
 */
data class AudioTrackSettings(
    val gain: Float = 0.70f
) {
    init {
        require(gain.isFinite() && gain in 0f..1f) {
            "Background audio gain must be between 0.0 and 1.0."
        }
    }

    val percentage: Int
        get() = (gain * 100f).toInt().coerceIn(0, 100)
}
