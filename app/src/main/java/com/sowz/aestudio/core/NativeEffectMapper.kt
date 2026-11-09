package com.sowz.aestudio.core

import java.util.Locale

data class EffectParameterValue(
    val name: String,
    val values: List<Double>,
    val animated: Boolean = false
) {
    val first: Double? get() = values.firstOrNull()?.takeIf { it.isFinite() }
}

data class EffectInstanceValue(
    val layerName: String,
    val matchName: String,
    val displayName: String,
    val parameters: List<EffectParameterValue>
)

data class NativeEffectStack(
    val brightness: Float? = null,
    val contrast: Float? = null,
    val blurSigma: Float? = null,
    val hueDegrees: Float? = null,
    val saturation: Float? = null,
    val lightness: Float? = null,
    val appliedEffects: List<String> = emptyList(),
    val skippedEffects: List<String> = emptyList()
) {
    val hasSupportedValues: Boolean
        get() = brightness != null || contrast != null || blurSigma != null ||
            hueDegrees != null || saturation != null || lightness != null
}

/** Maps only static parameters with an explicit, native SOWZ equivalent. */
object NativeEffectMapper {
    fun fromFfx(effects: List<FfxEffect>): NativeEffectStack =
        map(effects.map { effect ->
            EffectInstanceValue(
                layerName = "FFX preset",
                matchName = effect.matchName,
                displayName = effect.displayName,
                parameters = effect.parameters.map { parameter ->
                    EffectParameterValue(
                        name = parameter.name,
                        values = listOfNotNull(
                            parameter.value,
                            parameter.value2,
                            parameter.value3,
                            parameter.value4
                        ),
                        animated = parameter.isAnimated
                    )
                }
            )
        })

    fun map(effects: List<EffectInstanceValue>): NativeEffectStack {
        var brightness: Float? = null
        var contrast: Float? = null
        var blur: Float? = null
        var hue: Float? = null
        var saturation: Float? = null
        var lightness: Float? = null
        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        for (effect in effects) {
            val identity = (effect.matchName + " " + effect.displayName).lowercase(Locale.ROOT)
            var didMap = false
            var animated = false

            fun read(vararg aliases: String): Double? {
                val parameter = effect.parameters.firstOrNull { candidate ->
                    val name = candidate.name.lowercase(Locale.ROOT).replace('_', ' ').trim()
                    aliases.any { alias ->
                        name == alias.lowercase(Locale.ROOT) ||
                            name.contains(alias.lowercase(Locale.ROOT))
                    }
                } ?: return null
                if (parameter.animated) {
                    animated = true
                    return null
                }
                return parameter.first
            }

            if (identity.contains("brightness") && identity.contains("contrast")) {
                read("brightness")?.let {
                    brightness = (it / 100.0).toFloat().coerceIn(-1f, 1f)
                    didMap = true
                }
                read("contrast")?.let {
                    contrast = (it / 100.0).toFloat().coerceIn(-1f, 1f)
                    didMap = true
                }
            } else if (identity.contains("gaussian blur") || identity.contains("fast box blur")) {
                read("blurriness", "blur amount", "blur radius")?.let {
                    blur = it.toFloat().coerceIn(0f, 40f)
                    didMap = true
                }
            } else if (
                identity.contains("hue/saturation") ||
                identity.contains("hue & saturation") ||
                identity.contains("hue saturation") ||
                identity.contains("adbe hue")
            ) {
                read("master hue", "hue")?.let {
                    hue = it.toFloat().coerceIn(-180f, 180f)
                    didMap = true
                }
                read("master saturation", "saturation")?.let {
                    saturation = it.toFloat().coerceIn(-100f, 100f)
                    didMap = true
                }
                read("master lightness", "lightness")?.let {
                    lightness = it.toFloat().coerceIn(-100f, 100f)
                    didMap = true
                }
            }

            val label = effect.displayName.ifBlank { effect.matchName }
            if (didMap) applied.add(label)
            else skipped.add(if (animated) "$label · animated" else label)
        }

        return NativeEffectStack(
            brightness, contrast, blur, hue, saturation, lightness,
            applied.distinct(), skipped.distinct()
        )
    }
}
