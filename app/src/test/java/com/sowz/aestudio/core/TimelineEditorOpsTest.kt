package com.sowz.aestudio.core

import org.junit.Assert.*
import org.junit.Test

class TimelineEditorOpsTest {
    private fun sampleProject() = TimelineProject(
        id = "p1",
        name = "Composition",
        durationMs = 10_000L,
        layers = listOf(
            EditLayer(
                id = "video-1",
                name = "Video 1",
                kind = LayerKind.VIDEO,
                sourceUri = "content://video",
                timelineStartMs = 0L,
                sourceInMs = 0L,
                sourceOutMs = 5_000L
            )
        )
    )

    @Test
    fun canCreateIndependentOverlappingVideoLayers() {
        val base = sampleProject()
        val overlay = EditLayer(
            id = "video-2",
            name = "Overlay",
            kind = LayerKind.VIDEO,
            sourceUri = "content://overlay",
            timelineStartMs = 1_000L,
            sourceInMs = 0L,
            sourceOutMs = 3_000L,
            zIndex = 1
        )
        val updated = TimelineEditorOps.addLayer(base, overlay)
        assertEquals(2, updated.layers.size)
        assertEquals(listOf("video-1", "video-2"), updated.visibleLayersAt(2_000L).map { it.id })
    }

    @Test
    fun movingLayerDoesNotChangeItsSourceTrim() {
        val updated = TimelineEditorOps.moveLayer(sampleProject(), "video-1", 2_500L)
        val layer = updated.layers.single()
        assertEquals(2_500L, layer.timelineStartMs)
        assertEquals(0L, layer.sourceInMs)
        assertEquals(5_000L, layer.sourceOutMs)
    }

    @Test
    fun keyframesAreStoredRelativeToLayerAndNavigableOnCompositionClock() {
        var project = sampleProject()
        project = TimelineEditorOps.moveLayer(project, "video-1", 1_000L)
        project = TimelineEditorOps.addKeyframe(
            project, "video-1", TransformProperty.POSITION_X, 1_500L, 0.4f
        )
        project = TimelineEditorOps.addKeyframe(
            project, "video-1", TransformProperty.POSITION_X, 3_000L, -0.2f
        )
        val layer = project.layers.single()
        assertEquals(500L, layer.positionX.keyframes.first().timeMs)
        assertEquals(0.4f, TimelineEditorOps.propertyValueAt(
            project, "video-1", TransformProperty.POSITION_X, 1_500L
        ), 0.0001f)
        assertEquals(3_000L, TimelineEditorOps.adjacentKeyframeTime(
            project, "video-1", TransformProperty.POSITION_X, 1_500L, true
        ))
    }

    @Test
    fun serializationRoundTripsAnimatedLayers() {
        var project = sampleProject()
        project = TimelineEditorOps.addKeyframe(
            project, "video-1", TransformProperty.OPACITY, 1_500L, 0.3f,
            KeyframeInterpolation.HOLD
        )
        val encoded = TimelineProjectStore.encode(project)
        val decoded = TimelineProjectStore.decode(encoded)
        assertEquals(project.id, decoded.id)
        assertEquals(project.layers.single().opacity, decoded.layers.single().opacity)
        assertEquals(project.layers.single(), decoded.layers.single())
    }
}
