package com.sowz.aestudio.core

/**
 * Small deterministic timeline model shared by the editor UI, preview and export pipeline.
 * Time is always expressed in milliseconds; frames are derived from composition frame rate.
 */
enum class LayerKind { VIDEO, IMAGE, AUDIO, TEXT, SHAPE }
enum class KeyframeInterpolation { LINEAR, HOLD }

data class FloatKeyframe(
    val timeMs: Long,
    val value: Float,
    val interpolation: KeyframeInterpolation = KeyframeInterpolation.LINEAR
) {
    init {
        require(timeMs >= 0) { "Keyframe time cannot be negative." }
        require(value.isFinite()) { "Keyframe value must be finite." }
    }
}

data class AnimatedFloat(
    val initialValue: Float,
    val keyframes: List<FloatKeyframe> = emptyList()
) {
    init {
        require(initialValue.isFinite()) { "Initial value must be finite." }
        require(keyframes.map { it.timeMs }.distinct().size == keyframes.size) {
            "An animated property cannot contain two keyframes at the same time."
        }
    }

    private val ordered: List<FloatKeyframe> = keyframes.sortedBy { it.timeMs }

    fun valueAt(timeMs: Long): Float {
        if (ordered.isEmpty()) return initialValue
        if (timeMs <= ordered.first().timeMs) return ordered.first().value
        if (timeMs >= ordered.last().timeMs) return ordered.last().value

        val leftIndex = ordered.indexOfLast { it.timeMs <= timeMs }.coerceAtLeast(0)
        val left = ordered[leftIndex]
        val right = ordered[leftIndex + 1]
        if (left.interpolation == KeyframeInterpolation.HOLD) return left.value
        val span = (right.timeMs - left.timeMs).toFloat()
        if (span <= 0f) return right.value
        val progress = (timeMs - left.timeMs).toFloat() / span
        return left.value + (right.value - left.value) * progress
    }

    fun withKeyframe(keyframe: FloatKeyframe): AnimatedFloat =
        copy(keyframes = keyframes.filterNot { it.timeMs == keyframe.timeMs } + keyframe)
}

data class EditLayer(
    val id: String,
    val name: String,
    val kind: LayerKind,
    val sourceUri: String?,
    val timelineStartMs: Long,
    val sourceInMs: Long,
    val sourceOutMs: Long,
    val zIndex: Int = 0,
    val enabled: Boolean = true,
    val opacity: AnimatedFloat = AnimatedFloat(1f),
    val positionX: AnimatedFloat = AnimatedFloat(0f),
    val positionY: AnimatedFloat = AnimatedFloat(0f),
    val scaleX: AnimatedFloat = AnimatedFloat(1f),
    val scaleY: AnimatedFloat = AnimatedFloat(1f),
    val rotationDegrees: AnimatedFloat = AnimatedFloat(0f)
) {
    init {
        require(id.isNotBlank()) { "Layer id is required." }
        require(name.isNotBlank()) { "Layer name is required." }
        require(timelineStartMs >= 0) { "Layer start cannot be negative." }
        require(sourceInMs >= 0 && sourceOutMs > sourceInMs) { "Layer source range is invalid." }
        require(kind !in setOf(LayerKind.VIDEO, LayerKind.IMAGE, LayerKind.AUDIO) || !sourceUri.isNullOrBlank()) {
            "Media layers need a source URI."
        }
    }

    val durationMs: Long get() = sourceOutMs - sourceInMs
    val timelineEndMs: Long get() = timelineStartMs + durationMs

    fun containsTimelineTime(timeMs: Long): Boolean =
        enabled && timeMs >= timelineStartMs && timeMs < timelineEndMs

    fun sourceTimeAt(timelineTimeMs: Long): Long =
        sourceInMs + (timelineTimeMs - timelineStartMs).coerceIn(0L, durationMs)
}

data class TimelineProject(
    val id: String,
    val name: String,
    val width: Int = 1080,
    val height: Int = 1920,
    val frameRate: Int = 30,
    val durationMs: Long = 30_000L,
    val layers: List<EditLayer> = emptyList()
) {
    init {
        require(id.isNotBlank() && name.isNotBlank()) { "Project id and name are required." }
        require(width in 16..16_384 && height in 16..16_384) { "Composition dimensions are out of range." }
        require(frameRate in 1..120) { "Frame rate must be between 1 and 120." }
        require(durationMs in 1..86_400_000L) { "Composition duration must be between 1 ms and 24 hours." }
        require(layers.map { it.id }.distinct().size == layers.size) { "Layer ids must be unique." }
    }

    fun visibleLayersAt(timeMs: Long): List<EditLayer> =
        layers.filter { it.containsTimelineTime(timeMs) }.sortedBy { it.zIndex }

    fun replaceLayer(updated: EditLayer): TimelineProject {
        require(layers.any { it.id == updated.id }) { "Layer does not exist in this project." }
        return copy(layers = layers.map { if (it.id == updated.id) updated else it })
    }

    fun removeLayer(layerId: String): TimelineProject =
        copy(layers = layers.filterNot { it.id == layerId })

    fun moveLayer(layerId: String, newStartMs: Long): TimelineProject {
        require(newStartMs >= 0) { "Layer start cannot be negative." }
        val layer = layers.firstOrNull { it.id == layerId }
            ?: throw IllegalArgumentException("Layer does not exist in this project.")
        return replaceLayer(layer.copy(timelineStartMs = newStartMs))
    }

    fun splitLayer(layerId: String, atTimelineMs: Long, newRightId: String): TimelineProject {
        require(newRightId.isNotBlank() && layers.none { it.id == newRightId }) { "New layer id must be unique." }
        val layer = layers.firstOrNull { it.id == layerId }
            ?: throw IllegalArgumentException("Layer does not exist in this project.")
        require(atTimelineMs > layer.timelineStartMs && atTimelineMs < layer.timelineEndMs) {
            "Split point must be inside the layer."
        }
        val sourceSplit = layer.sourceInMs + (atTimelineMs - layer.timelineStartMs)
        val left = layer.copy(sourceOutMs = sourceSplit)
        val right = layer.copy(
            id = newRightId,
            name = "${layer.name} (split)",
            timelineStartMs = atTimelineMs,
            sourceInMs = sourceSplit,
            zIndex = layer.zIndex
        )
        val index = layers.indexOf(layer)
        val nextLayers = layers.toMutableList().apply {
            this[index] = left
            add(index + 1, right)
        }
        return copy(layers = nextLayers)
    }

    fun timeToFrame(timeMs: Long): Long =
        ((timeMs.coerceAtLeast(0L) * frameRate) / 1000L).coerceAtMost((durationMs * frameRate) / 1000L)

    fun frameToTime(frame: Long): Long =
        ((frame.coerceAtLeast(0L) * 1000L) / frameRate).coerceAtMost(durationMs)
}
