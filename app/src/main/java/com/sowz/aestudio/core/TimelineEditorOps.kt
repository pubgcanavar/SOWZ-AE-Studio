package com.sowz.aestudio.core

enum class TransformProperty {
    OPACITY, POSITION_X, POSITION_Y, SCALE_X, SCALE_Y, ROTATION
}

/** Pure composition edits; UI, preview and export consume the same resulting model. */
object TimelineEditorOps {
    fun addLayer(project: TimelineProject, layer: EditLayer): TimelineProject {
        require(project.layers.size < 256) { "A project can contain at most 256 layers." }
        require(project.layers.none { it.id == layer.id }) { "Layer id already exists." }
        require(layer.timelineEndMs <= 86_400_000L) { "Layer extends past the 24-hour project limit." }
        return project.copy(layers = project.layers + layer)
    }

    fun moveLayer(project: TimelineProject, layerId: String, timelineStartMs: Long): TimelineProject {
        require(timelineStartMs >= 0L) { "Layer start cannot be negative." }
        val layer = project.layers.firstOrNull { it.id == layerId }
            ?: throw IllegalArgumentException("Layer does not exist.")
        require(timelineStartMs + layer.durationMs <= 86_400_000L) {
            "Layer extends past the 24-hour project limit."
        }
        return project.replaceLayer(layer.copy(timelineStartMs = timelineStartMs))
    }

    fun setLayerEnabled(project: TimelineProject, layerId: String, enabled: Boolean): TimelineProject {
        val layer = requireLayer(project, layerId)
        return project.replaceLayer(layer.copy(enabled = enabled))
    }

    fun addKeyframe(
        project: TimelineProject,
        layerId: String,
        property: TransformProperty,
        timelineTimeMs: Long,
        value: Float,
        interpolation: KeyframeInterpolation = KeyframeInterpolation.LINEAR
    ): TimelineProject {
        require(timelineTimeMs >= 0L && timelineTimeMs <= project.durationMs) {
            "Keyframe time is outside the composition."
        }
        val layer = requireLayer(project, layerId)
        require(timelineTimeMs >= layer.timelineStartMs && timelineTimeMs <= layer.timelineEndMs) {
            "Keyframe time must be inside the selected layer."
        }
        val localTime = timelineTimeMs - layer.timelineStartMs
        val keyframe = FloatKeyframe(localTime, value, interpolation)
        val updated = when (property) {
            TransformProperty.OPACITY -> layer.copy(opacity = layer.opacity.withKeyframe(keyframe))
            TransformProperty.POSITION_X -> layer.copy(positionX = layer.positionX.withKeyframe(keyframe))
            TransformProperty.POSITION_Y -> layer.copy(positionY = layer.positionY.withKeyframe(keyframe))
            TransformProperty.SCALE_X -> layer.copy(scaleX = layer.scaleX.withKeyframe(keyframe))
            TransformProperty.SCALE_Y -> layer.copy(scaleY = layer.scaleY.withKeyframe(keyframe))
            TransformProperty.ROTATION -> layer.copy(rotationDegrees = layer.rotationDegrees.withKeyframe(keyframe))
        }
        return project.replaceLayer(updated)
    }

    fun propertyValueAt(
        project: TimelineProject,
        layerId: String,
        property: TransformProperty,
        timelineTimeMs: Long
    ): Float {
        val layer = requireLayer(project, layerId)
        val localTime = (timelineTimeMs - layer.timelineStartMs).coerceAtLeast(0L)
        return when (property) {
            TransformProperty.OPACITY -> layer.opacity.valueAt(localTime)
            TransformProperty.POSITION_X -> layer.positionX.valueAt(localTime)
            TransformProperty.POSITION_Y -> layer.positionY.valueAt(localTime)
            TransformProperty.SCALE_X -> layer.scaleX.valueAt(localTime)
            TransformProperty.SCALE_Y -> layer.scaleY.valueAt(localTime)
            TransformProperty.ROTATION -> layer.rotationDegrees.valueAt(localTime)
        }
    }

    fun adjacentKeyframeTime(
        project: TimelineProject,
        layerId: String,
        property: TransformProperty,
        playheadMs: Long,
        forward: Boolean
    ): Long? {
        val layer = requireLayer(project, layerId)
        val frames = keyframes(layer, property).map { layer.timelineStartMs + it.timeMs }.sorted()
        return if (forward) frames.firstOrNull { it > playheadMs }
        else frames.lastOrNull { it < playheadMs }
    }

    fun keyframeTimes(layer: EditLayer): List<Long> =
        listOf(layer.opacity, layer.positionX, layer.positionY, layer.scaleX, layer.scaleY, layer.rotationDegrees)
            .flatMap { it.keyframes }
            .map { layer.timelineStartMs + it.timeMs }
            .distinct()
            .sorted()

    private fun keyframes(layer: EditLayer, property: TransformProperty): List<FloatKeyframe> =
        when (property) {
            TransformProperty.OPACITY -> layer.opacity.keyframes
            TransformProperty.POSITION_X -> layer.positionX.keyframes
            TransformProperty.POSITION_Y -> layer.positionY.keyframes
            TransformProperty.SCALE_X -> layer.scaleX.keyframes
            TransformProperty.SCALE_Y -> layer.scaleY.keyframes
            TransformProperty.ROTATION -> layer.rotationDegrees.keyframes
        }

    private fun requireLayer(project: TimelineProject, id: String): EditLayer =
        project.layers.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Layer does not exist.")
}
