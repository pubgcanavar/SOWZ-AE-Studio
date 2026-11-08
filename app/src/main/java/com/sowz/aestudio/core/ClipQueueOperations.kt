package com.sowz.aestudio.core

/**
 * Immutable list operations for the editor's ordered clip sequence.
 *
 * The Activity replaces its current sequence with these results, so the same order
 * is used by project serialization and by the Media3 export builder.
 */
object ClipQueueOperations {
    fun <T> move(items: List<T>, fromIndex: Int, toIndex: Int): List<T> {
        require(fromIndex in items.indices) { "Source clip index is out of range." }
        require(toIndex in items.indices) { "Destination clip index is out of range." }
        if (fromIndex == toIndex) return items.toList()

        val result = items.toMutableList()
        val item = result.removeAt(fromIndex)
        result.add(toIndex, item)
        return result
    }

    fun <T> remove(items: List<T>, index: Int): List<T> {
        require(index in items.indices) { "Clip index is out of range." }
        return items.filterIndexed { currentIndex, _ -> currentIndex != index }
    }
}
