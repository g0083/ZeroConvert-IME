package com.zeroconvert.ime.assist

import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle

/** Main-thread composition transaction. Persistence happens only after an editor commit succeeds. */
class CorrectionSession {
    data class Acceptance(val reading: String, val surface: String)
    private data class Undo(
        val input: ComposingText,
        val preview: String,
        val surface: String,
        val accepted: List<Acceptance>,
        val protectedSurfaces: List<String>,
        val heldAutoCommit: Boolean
    )

    private var undo: Undo? = null
    private val accepted = mutableListOf<Acceptance>()
    private val protectedSurfaces = mutableListOf<String>()
    private var holdAutoCommit = false
    var rejectedSurface: String? = null
        private set
    val isApplied: Boolean get() = undo != null
    val holdsAutoCommit: Boolean get() = holdAutoCommit

    fun apply(input: ComposingText, surface: String, preview: String, explicit: Boolean = true): Boolean {
        if (input.isEmpty || input.isAtStartIndex || surface.isBlank() || surface.length > 512 || isApplied) return false
        val original = input.clone()
        undo = Undo(original, preview, surface, accepted.toList(), protectedSurfaces.toList(), holdAutoCommit)
        holdAutoCommit = true
        if (explicit) accepted.add(Acceptance(original.convertTargetBeforeCursor, surface))
        protectedSurfaces.add(surface)
        if (accepted.size > 8) accepted.removeAt(0)
        if (protectedSurfaces.size > 8) protectedSurfaces.removeAt(0)
        val suffix = original.convertTarget.drop(original.convertTargetCursorPosition)
        input.stopComposition()
        input.insertAtCursorPosition(surface, InputStyle.DIRECT)
        if (suffix.isNotEmpty()) {
            input.insertAtCursorPosition(suffix, InputStyle.FROZEN)
            input.moveCursorFromCursorPosition(-suffix.length)
        }
        rejectedSurface = null
        return true
    }

    fun revert(input: ComposingText): String? {
        val previous = undo ?: return null
        input.restoreFrom(previous.input)
        accepted.clear()
        accepted.addAll(previous.accepted)
        protectedSurfaces.clear()
        protectedSurfaces.addAll(previous.protectedSurfaces)
        holdAutoCommit = previous.heldAutoCommit
        rejectedSurface = previous.surface
        undo = null
        return previous.preview
    }

    /** Ordinary edits end the one-step undo window, but retain explicitly accepted text for commit. */
    fun edited() {
        // Keep the hold for the first edit after correction. Subsequent ordinary
        // edits may build a new stability window without losing pending learning.
        if (undo == null) holdAutoCommit = false
        undo = null
        rejectedSurface = null
    }

    fun acceptancesForCommit(displayed: String): List<Acceptance> =
        accepted.filter { displayed.contains(it.surface) }.distinct()

    fun fixedPrefix(input: String): String = protectedSurfaces
        .filter { input.startsWith(it) }.maxByOrNull { it.length }.orEmpty()

    fun committed(displayed: String): List<Acceptance> {
        val result = acceptancesForCommit(displayed)
        accepted.removeAll(result.toSet())
        protectedSurfaces.removeAll { displayed.contains(it) }
        undo = null
        holdAutoCommit = false
        return result
    }

    fun clear() {
        undo = null
        accepted.clear()
        protectedSurfaces.clear()
        holdAutoCommit = false
        rejectedSurface = null
    }
}
