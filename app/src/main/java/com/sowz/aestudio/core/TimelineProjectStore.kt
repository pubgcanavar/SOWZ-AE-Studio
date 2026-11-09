package com.sowz.aestudio.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned, bounded JSON persistence for the editable composition graph.
 * File references remain Android content URIs; media bytes are never copied into the project.
 */
object TimelineProjectStore {
    private const val KIND = "SOWZ-COMPOSITION"
    private const val VERSION = 1
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_LAYERS = 256
    private const val MAX_KEYFRAMES_PER_PROPERTY = 20_000

    fun encode(project: TimelineProject): String {
        require(project.layers.size <= MAX_LAYERS) { "A project can contain at most $MAX_LAYERS layers." }
        val json = JSONObject()
            .put("kind", KIND)
            .put("schemaVersion", VERSION)
            .put("id", project.id)
            .put("name", project.name)
            .put("width", project.width)
            .put("height", project.height)
            .put("frameRate", project.frameRate)
            .put("durationMs", project.durationMs)
            .put("layers", JSONArray().apply {
                project.layers.forEach { layer -> put(layerToJson(layer)) }
            })
        val result = json.toString(2)
        require(result.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "The project exceeds the 16 MB size limit."
        }
        return result
    }

    fun decode(text: String): TimelineProject {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "Project file exceeds the 16 MB safety limit."
        }
        val root = JSONObject(text)
        require(root.optString("kind") == KIND) { "This is not a SOWZ composition project." }
        require(root.optInt("schemaVersion") == VERSION) { "Unsupported composition project version." }
        val layersJson = root.optJSONArray("layers") ?: JSONArray()
        require(layersJson.length() <= MAX_LAYERS) { "Project contains too many layers." }
        val layers = ArrayList<EditLayer>(layersJson.length())
        for (index in 0 until layersJson.length()) {
            val layer = layersJson.optJSONObject(index) ?: error("Layer $index is not valid JSON.")
            layers.add(layerFromJson(layer))
        }
        return TimelineProject(
            id = root.optString("id").ifBlank { "sowz-project" },
            name = root.optString("name").ifBlank { "Untitled Composition" },
            width = root.optInt("width", 1080),
            height = root.optInt("height", 1920),
            frameRate = root.optInt("frameRate", 30),
            durationMs = root.optLong("durationMs", 30_000L),
            layers = layers
        )
    }

    private fun layerToJson(layer: EditLayer) = JSONObject()
        .put("id", layer.id)
        .put("name", layer.name)
        .put("kind", layer.kind.name)
        .put("sourceUri", layer.sourceUri)
        .put("timelineStartMs", layer.timelineStartMs)
        .put("sourceInMs", layer.sourceInMs)
        .put("sourceOutMs", layer.sourceOutMs)
        .put("zIndex", layer.zIndex)
        .put("enabled", layer.enabled)
        .put("opacity", animatedToJson(layer.opacity))
        .put("positionX", animatedToJson(layer.positionX))
        .put("positionY", animatedToJson(layer.positionY))
        .put("scaleX", animatedToJson(layer.scaleX))
        .put("scaleY", animatedToJson(layer.scaleY))
        .put("rotationDegrees", animatedToJson(layer.rotationDegrees))
        .put("contentText", layer.contentText)
        .put("textColorArgb", layer.textColorArgb)
        .put("textSizePx", layer.textSizePx.toDouble())

    private fun animatedToJson(animated: AnimatedFloat) = JSONObject()
        .put("initialValue", animated.initialValue.toDouble())
        .put("keyframes", JSONArray().apply {
            animated.keyframes.sortedBy { it.timeMs }.forEach { keyframe ->
                put(
                    JSONObject()
                        .put("timeMs", keyframe.timeMs)
                        .put("value", keyframe.value.toDouble())
                        .put("interpolation", keyframe.interpolation.name)
                )
            }
        })

    private fun layerFromJson(json: JSONObject): EditLayer {
        val kind = runCatching {
            LayerKind.valueOf(json.optString("kind"))
        }.getOrElse { error("Layer kind is not supported.") }

        return EditLayer(
            id = json.optString("id"),
            name = json.optString("name"),
            kind = kind,
            sourceUri = json.optString("sourceUri").takeIf { it.isNotBlank() && it != "null" },
            timelineStartMs = json.optLong("timelineStartMs", 0L),
            sourceInMs = json.optLong("sourceInMs", 0L),
            sourceOutMs = json.optLong("sourceOutMs", 1L),
            zIndex = json.optInt("zIndex", 0),
            enabled = json.optBoolean("enabled", true),
            opacity = animatedFromJson(json.optJSONObject("opacity"), 1f),
            positionX = animatedFromJson(json.optJSONObject("positionX"), 0f),
            positionY = animatedFromJson(json.optJSONObject("positionY"), 0f),
            scaleX = animatedFromJson(json.optJSONObject("scaleX"), 1f),
            scaleY = animatedFromJson(json.optJSONObject("scaleY"), 1f),
            rotationDegrees = animatedFromJson(json.optJSONObject("rotationDegrees"), 0f),
            contentText = json.optString("contentText").takeIf { it.isNotBlank() && it != "null" },
            textColorArgb = json.optInt("textColorArgb", -1),
            textSizePx = json.optDouble("textSizePx", 64.0).toFloat()
        )
    }

    private fun animatedFromJson(json: JSONObject?, defaultValue: Float): AnimatedFloat {
        if (json == null) return AnimatedFloat(defaultValue)
        val initial = json.optDouble("initialValue", defaultValue.toDouble()).toFloat()
        require(initial.isFinite()) { "Animated property has a non-finite initial value." }
        val framesJson = json.optJSONArray("keyframes") ?: JSONArray()
        require(framesJson.length() <= MAX_KEYFRAMES_PER_PROPERTY) {
            "Animated property contains too many keyframes."
        }
        val frames = ArrayList<FloatKeyframe>(framesJson.length())
        for (index in 0 until framesJson.length()) {
            val frame = framesJson.optJSONObject(index) ?: error("Keyframe $index is not valid JSON.")
            val interpolation = runCatching {
                KeyframeInterpolation.valueOf(frame.optString("interpolation", "LINEAR"))
            }.getOrElse { error("Unsupported keyframe interpolation.") }
            frames.add(
                FloatKeyframe(
                    timeMs = frame.optLong("timeMs", -1L),
                    value = frame.optDouble("value", Double.NaN).toFloat(),
                    interpolation = interpolation
                )
            )
        }
        return AnimatedFloat(initial, frames)
    }
}
