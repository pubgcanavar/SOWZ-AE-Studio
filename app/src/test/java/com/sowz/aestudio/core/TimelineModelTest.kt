package com.sowz.aestudio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineModelTest {
    @Test fun interpolatesLinearKeyframes() {
        val animation = AnimatedFloat(
            initialValue = 0f,
            keyframes = listOf(
                FloatKeyframe(0L, 0f),
                FloatKeyframe(1_000L, 10f)
            )
        )
        assertEquals(0f, animation.valueAt(0L), 0.001f)
        assertEquals(5f, animation.valueAt(500L), 0.001f)
        assertEquals(10f, animation.valueAt(1_000L), 0.001f)
    }

    @Test fun holdKeyframeKeepsItsValueUntilTheNextKeyframe() {
        val animation = AnimatedFloat(
            0f,
            listOf(
                FloatKeyframe(100L, 2f, KeyframeInterpolation.HOLD),
                FloatKeyframe(500L, 8f)
            )
        )
        assertEquals(2f, animation.valueAt(400L), 0.001f)
        assertEquals(8f, animation.valueAt(500L), 0.001f)
    }

    @Test fun sourceTimeFollowsTimelineOffset() {
        val layer = EditLayer(
            id = "v1", name = "clip", kind = LayerKind.VIDEO, sourceUri = "content://clip",
            timelineStartMs = 1_000L, sourceInMs = 2_000L, sourceOutMs = 7_000L
        )
        assertEquals(3_500L, layer.sourceTimeAt(2_500L))
        assertTrue(layer.containsTimelineTime(1_000L))
        assertFalse(layer.containsTimelineTime(6_000L))
    }

    @Test fun splittingPreservesSourceContinuity() {
        val source = EditLayer(
            id = "v1", name = "clip", kind = LayerKind.VIDEO, sourceUri = "content://clip",
            timelineStartMs = 0L, sourceInMs = 1_000L, sourceOutMs = 11_000L
        )
        val project = TimelineProject("p1", "Test", durationMs = 20_000L, layers = listOf(source))
        val split = project.splitLayer("v1", 4_000L, "v2")
        assertEquals(5_000L, split.layers[0].sourceOutMs)
        assertEquals(5_000L, split.layers[1].sourceInMs)
        assertEquals(6_000L, split.layers[1].timelineEndMs)
        assertEquals("v2", split.layers[1].id)
    }

    @Test fun visibleLayersRespectTimingAndLayerOrder() {
        val upper = EditLayer(
            id = "upper", name = "upper", kind = LayerKind.SHAPE, sourceUri = null,
            timelineStartMs = 100L, sourceInMs = 0L, sourceOutMs = 500L, zIndex = 2
        )
        val lower = EditLayer(
            id = "lower", name = "lower", kind = LayerKind.TEXT, sourceUri = null,
            timelineStartMs = 0L, sourceInMs = 0L, sourceOutMs = 1_000L, zIndex = 1
        )
        val project = TimelineProject("p1", "Test", durationMs = 1_000L, layers = listOf(upper, lower))
        assertEquals(listOf("lower", "upper"), project.visibleLayersAt(150L).map { it.id })
        assertEquals(listOf("lower"), project.visibleLayersAt(750L).map { it.id })
    }

    @Test fun frameAndTimeConversionsAreBounded() {
        val project = TimelineProject("p1", "Test", frameRate = 30, durationMs = 10_000L)
        assertEquals(30L, project.timeToFrame(1_000L))
        assertEquals(1_000L, project.frameToTime(30L))
        assertEquals(300L, project.timeToFrame(50_000L))
    }
}
