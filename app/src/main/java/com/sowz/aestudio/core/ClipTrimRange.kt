package com.sowz.aestudio.core

/**
 * Source-time range for one sequence entry.
 *
 * A null end means "use the complete source through its end". This keeps older
 * .sowzproj files valid without probing all media while opening the project.
 */
data class ClipTrimRange(
    val startMs: Long = 0L,
    val endMs: Long? = null
) {
    init {
        require(startMs >= 0L) { "Clip IN point cannot be negative." }
        require(endMs == null || endMs > startMs) { "Clip OUT point must be after its IN point." }
    }

    fun clampToDuration(sourceDurationMs: Long): ClipTrimRange {
        require(sourceDurationMs > 0L) { "Source duration must be positive." }
        require(startMs < sourceDurationMs) { "Clip IN point is outside the source duration." }
        val safeEnd = (endMs ?: sourceDurationMs).coerceAtMost(sourceDurationMs)
        require(safeEnd > startMs) { "Clip range is empty after applying the source duration." }
        return ClipTrimRange(startMs, safeEnd)
    }
}
