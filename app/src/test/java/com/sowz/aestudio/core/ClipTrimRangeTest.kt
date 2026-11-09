package com.sowz.aestudio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ClipTrimRangeTest {
    @Test fun defaultRangeMeansFullSource() {
        val range = ClipTrimRange()
        assertEquals(0L, range.startMs)
        assertNull(range.endMs)
    }

    @Test fun rangeIsClampedToSourceDuration() {
        assertEquals(
            ClipTrimRange(1_000L, 9_000L),
            ClipTrimRange(1_000L, 15_000L).clampToDuration(9_000L)
        )
    }

    @Test fun fullSourceRangeBecomesExplicitWhenDurationIsKnown() {
        assertEquals(ClipTrimRange(0L, 12_500L), ClipTrimRange().clampToDuration(12_500L))
    }

    @Test fun negativeInPointIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ClipTrimRange(-1L, 1_000L) }
    }

    @Test fun outPointMustFollowInPoint() {
        assertThrows(IllegalArgumentException::class.java) { ClipTrimRange(1_000L, 1_000L) }
    }

    @Test fun inPointBeyondSourceIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ClipTrimRange(10_000L).clampToDuration(9_000L) }
    }

    @Test fun sourceDurationMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { ClipTrimRange().clampToDuration(0L) }
    }
}
