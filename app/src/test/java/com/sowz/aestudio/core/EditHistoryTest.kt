package com.sowz.aestudio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {
    @Test fun undoReturnsPreviousCommittedState() {
        val history = EditHistory(listOf("a"))
        history.commit(listOf("a", "b"))
        assertEquals(listOf("a"), history.undo())
        assertTrue(history.canRedo)
    }

    @Test fun redoRestoresUndoneState() {
        val history = EditHistory(listOf("a"))
        history.commit(listOf("a", "b"))
        history.undo()
        assertEquals(listOf("a", "b"), history.redo())
    }

    @Test fun newEditClearsRedoBranch() {
        val history = EditHistory(listOf("a"))
        history.commit(listOf("a", "b"))
        history.undo()
        history.commit(listOf("a", "c"))
        assertFalse(history.canRedo)
        assertEquals(listOf("a"), history.undo())
    }

    @Test fun equalStateDoesNotCreateHistoryEntry() {
        val history = EditHistory(listOf("a"))
        assertFalse(history.commit(listOf("a")))
        assertFalse(history.canUndo)
    }

    @Test fun historyIsBounded() {
        val history = EditHistory(0, capacity = 2)
        history.commit(1)
        history.commit(2)
        history.commit(3)
        assertEquals(2, history.undo())
        assertEquals(1, history.undo())
        assertNull(history.undo())
    }

    @Test fun resetClearsBothStacks() {
        val history = EditHistory("first")
        history.commit("second")
        history.undo()
        history.reset("loaded")
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo())
    }
}
