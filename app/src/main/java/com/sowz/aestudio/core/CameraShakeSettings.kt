package com.sowz.aestudio.core

import kotlin.math.floor
import kotlin.math.sin

data class CameraShakeSample(
    val xPixels: Float,
    val yPixels: Float,
    val rotationDegrees: Float
)

/** Deterministic time-based shake, stable when the user scrubs to an earlier frame. */
data class CameraShakeSettings(
    val amplitudePixels: Float = 8f,
    val frequencyHz: Float = 8f,
    val rotationDegrees: Float = 1.5f,
    val seed: Int = 1
) {
    init {
        require(amplitudePixels in 0f..64f)
        require(frequencyHz in 0.25f..30f)
        require(rotationDegrees in 0f..20f)
    }

    fun sampleAt(timeMs: Long): CameraShakeSample {
        val time = timeMs.coerceAtLeast(0L).toDouble() / 1000.0 * frequencyHz
        return CameraShakeSample(
            noise(time, seed.toDouble()) * amplitudePixels,
            noise(time, seed.toDouble() + 31.7) * amplitudePixels,
            noise(time, seed.toDouble() + 67.4) * rotationDegrees
        )
    }

    private fun noise(time: Double, offset: Double): Float {
        val cell = floor(time)
        val fraction = time - cell
        val smooth = fraction * fraction * (3.0 - 2.0 * fraction)
        val a = hash(cell + offset)
        val b = hash(cell + 1.0 + offset)
        return (a + (b - a) * smooth).toFloat()
    }

    private fun hash(value: Double): Double {
        val raw = sin(value * 127.1 + seed * 17.0) * 43758.5453
        return ((raw - floor(raw)) * 2.0 - 1.0).coerceIn(-1.0, 1.0)
    }
}
