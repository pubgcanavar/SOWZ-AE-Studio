package com.sowz.aestudio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ClipQueueOperationsTest {
    @Test fun movingAClipChangesItsExportOrder() {
        val clips = listOf("opening.mp4", "middle.mp4", "ending.mp4")
        assertEquals(
            listOf("middle.mp4", "opening.mp4", "ending.mp4"),
            ClipQueueOperations.move(clips, 1, 0)
        )
    }

    @Test fun movingDownPreservesEveryOtherClip() {
        val clips = listOf("a.mp4", "b.mp4", "c.mp4")
        assertEquals(
            listOf("a.mp4", "c.mp4", "b.mp4"),
            ClipQueueOperations.move(clips, 1, 2)
        )
    }

    @Test fun removingOneEntryDoesNotChangeTheSourceList() {
        val clips = listOf("a.mp4", "b.mp4", "c.mp4")
        assertEquals(listOf("a.mp4", "c.mp4"), ClipQueueOperations.remove(clips, 1))
        assertEquals(listOf("a.mp4", "b.mp4", "c.mp4"), clips)
    }

    @Test fun duplicateSourceCanRemainTwiceInTheSequence() {
        val clips = listOf("a.mp4", "a.mp4", "b.mp4")
        assertEquals(listOf("a.mp4", "b.mp4"), ClipQueueOperations.remove(clips, 1))
    }

    @Test fun invalidMoveIndexesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ClipQueueOperations.move(listOf("a.mp4"), 0, 1)
        }
    }
}
