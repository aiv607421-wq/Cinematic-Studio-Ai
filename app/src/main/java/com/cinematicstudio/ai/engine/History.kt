package com.cinematicstudio.ai.engine

import com.cinematicstudio.ai.data.model.EditorProject

/**
 * Snapshot based undo/redo history.
 *
 * Each committed change pushes the previous project value onto the undo stack,
 * so undo/redo can never partially corrupt a project. The stack is bounded to
 * keep memory usage predictable on long editing sessions.
 */
class History(
    initial: EditorProject,
    private val limit: Int = DEFAULT_LIMIT,
) {
    private val undoStack = ArrayDeque<EditorProject>()
    private val redoStack = ArrayDeque<EditorProject>()
    private var current: EditorProject = initial

    val project: EditorProject get() = current
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Commits [next] as the new current state. No-op when the value is identical,
     * so repeated saves do not pollute the history.
     */
    fun commit(next: EditorProject) {
        if (next == current) return
        undoStack.addLast(current)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
        current = next
    }

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(current)
        current = previous
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(current)
        current = next
        return true
    }

    /** Replaces the whole project (used when reloading from disk). */
    fun reset(project: EditorProject) {
        undoStack.clear()
        redoStack.clear()
        current = project
    }

    companion object {
        const val DEFAULT_LIMIT = 80
    }
}
