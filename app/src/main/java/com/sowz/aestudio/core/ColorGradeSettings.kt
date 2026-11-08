package com.sowz.aestudio.core

/**
 * Native Media3 HSL colour grade settings shared by preview and export.
 * Hue is measured in degrees; saturation and lightness use Media3's -100..100 range.
 */
data class ColorGradeSettings(
    val hueAdjustmentDegrees: Float = 0f,
    val saturationAdjustment: Float = 0f,
    val lightnessAdjustment: Float = 0f
) {
    init {
        require(hueAdjustmentDegrees.isFinite() && hueAdjustmentDegrees in -180f..180f) {
            "Hue adjustment must be between -180 and 180 degrees."
        }
        require(saturationAdjustment.isFinite() && saturationAdjustment in -100f..100f) {
            "Saturation adjustment must be between -100 and 100."
        }
        require(lightnessAdjustment.isFinite() && lightnessAdjustment in -100f..100f) {
            "Lightness adjustment must be between -100 and 100."
        }
    }
}
