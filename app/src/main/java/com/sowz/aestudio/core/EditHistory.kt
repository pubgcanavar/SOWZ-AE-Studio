package com.sowz.aestudio.core

/**
 * Immutable queue state used by the editor's undo/redo history.
 * URI and trim lists must always be index-aligned.
 */
data class ClipQueueSnapshot(
    val uriStrings: List<String>,
    val trims: List<ClipTrimRange>,
    val speeds: List<Float> = List(uriStrings.size) { 1f }
) {
    init {
        require(uriStrings.size == trims.size) { "Each queued source must have exactly one trim range." }
        require(uriStrings.size == speeds.size) { "Each queued source must have exactly one speed value." }
        require(speeds.all { it.isFinite() && it in 0.25f..4f }) {
            "Clip speed must be between 0.25x and 4.0x."
        }
    }
}

/**
 * Bounded snapshot history. Committing a new edit clears the redo branch.
 * The editor stores immutable snapshots, so undo never mutates prior project state.
 */
class EditHistory<T>(
    initialState: T,
    private val capacity: Int = 50
) {
    private val undoStates = mutableListOf<T>()
    private val redoStates = mutableListOf<T>()
    private var currentState: T = initialState

    init {
        require(capacity > 0) { "History capacity must be positive." }
    }

    val canUndo: Boolean get() = undoStates.isNotEmpty()
    val canRedo: Boolean get() = redoStates.isNotEmpty()

    fun commit(nextState: T): Boolean {
        if (nextState == currentState) return false
        undoStates.add(currentState)
        if (undoStates.size > capacity) undoStates.removeAt(0)
        redoStates.clear()
        currentState = nextState
        return true
    }

    fun undo(): T? {
        if (undoStates.isEmpty()) return null
        redoStates.add(currentState)
        currentState = undoStates.removeAt(undoStates.lastIndex)
        return currentState
    }

    fun redo(): T? {
        if (redoStates.isEmpty()) return null
        undoStates.add(currentState)
        if (undoStates.size > capacity) undoStates.removeAt(0)
        currentState = redoStates.removeAt(redoStates.lastIndex)
        return currentState
    }

    fun reset(state: T) {
        undoStates.clear()
        redoStates.clear()
        currentState = state
    }
}
