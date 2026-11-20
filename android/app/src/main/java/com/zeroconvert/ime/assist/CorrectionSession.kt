package com.zeroconvert.ime.assist

import com.zeroconvert.ime.TouchKeyBias
import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputPiece
import com.zeroconvert.ime.azookey.input.InputStyle
import org.json.JSONObject

/** Editor snapshots stay on the host. Adoption, undo and learning state live in Rust. */
class CorrectionSession(private val policy: AssistPolicyTransport = SharedAssistPolicy.native) {
    data class Acceptance(val reading: String, val surface: String)
    data class RefinementContext(
        val originInput: ComposingText,
        val originReading: String,
        val originBiases: List<TouchKeyBias>,
        val appliedPrefix: String,
    )
    private data class Undo(
        val originalInput: ComposingText,
        val originalPrefixElements: List<ComposingText.InputElement>,
        val originalPrefixCursor: Int,
        val originalPreview: String,
        val originalPrefixBiases: List<TouchKeyBias>,
        val explicit: Boolean,
        val refined: Boolean,
        val appliedSurface: String,
        val appliedPrefixElements: List<ComposingText.InputElement>,
    )

    private var undo: Undo? = null
    private var state: JSONObject? = null
    val rejectedSurface: String? get() = state?.takeUnless { it.isNull("rejectedSurface") }?.getString("rejectedSurface")
    val isApplied: Boolean
        get() = undo != null && (state?.optJSONObject("undo")?.let { !it.optBoolean("editedAfterApply", false) } ?: false)
    val isRefined: Boolean
        get() = undo?.refined == true
    val isImmediateBackspaceUndo: Boolean
        get() = isApplied && undo?.refined == false
    val canRevert: Boolean
        get() = undo != null && state?.let { !it.isNull("undo") } == true
    val holdsAutoCommit: Boolean get() = state?.optBoolean("holdAutoCommit") ?: false

    private fun request(op: String, reading: String = "", surface: String = ""): JSONObject =
        JSONObject().put("op", op).put("state", state ?: JSONObject()).put("reading", reading).put("surface", surface)

    fun buildAutoRefinementContext(
        input: ComposingText,
        currentBiases: List<TouchKeyBias> = emptyList(),
    ): RefinementContext? {
        val current = undo ?: return null
        if (current.explicit ||
            isApplied ||
            !isReadingOnlyCorrection(current.appliedSurface) ||
            !input.isAtEndIndex ||
            !hasIntactAppliedPrefix(current, input)
        ) {
            return null
        }
        val trailingElements = input.input.drop(current.appliedPrefixElements.size)
        if (trailingElements.isEmpty()) return null
        val (rebuilt, nextCursor) = buildDifferentialRevertElements(current, input) ?: return null
        val originReading = ComposingText.getConvertTarget(rebuilt)
        if (originReading.isEmpty()) return null
        val originInput = ComposingText(
            convertTargetCursorPosition = nextCursor.coerceIn(0, originReading.length),
            input = rebuilt.toMutableList(),
            convertTarget = originReading,
        )
        val originCharCount = current.originalPrefixElements.count { it.piece is InputPiece.CharPiece }
        val trailingCharCount = trailingElements.count { it.piece is InputPiece.CharPiece }
        val inputCharCount = input.input.count { it.piece is InputPiece.CharPiece }
        val appliedCharCount = current.appliedPrefixElements.count { it.piece is InputPiece.CharPiece }
        val trailingBiases = if (currentBiases.size == inputCharCount) {
            currentBiases.drop(appliedCharCount)
        } else {
            emptyList()
        }
        val originBiases = if (
            current.originalPrefixBiases.size == originCharCount &&
            trailingBiases.size == trailingCharCount &&
            trailingCharCount > 0
        ) {
            current.originalPrefixBiases + trailingBiases
        } else {
            emptyList()
        }
        return RefinementContext(
            originInput = originInput,
            originReading = originReading,
            originBiases = originBiases,
            appliedPrefix = current.appliedSurface,
        )
    }

    fun apply(
        input: ComposingText,
        surface: String,
        preview: String,
        explicit: Boolean = true,
        currentBiases: List<TouchKeyBias> = emptyList(),
    ): Boolean {
        if (input.isEmpty || input.isAtStartIndex) return false
        val activeRefinement = buildAutoRefinementContext(input, currentBiases)
            ?.takeIf { !surface.startsWith(it.appliedPrefix) }
        val op = if (activeRefinement != null) "refine" else "apply"
        val policyReading = activeRefinement?.originReading ?: input.convertTargetBeforeCursor
        val reply = policy.request(
            request(op, policyReading, surface)
                .put("explicit", explicit)
                .put("pendingRomaji", input.convertTargetBeforeCursor.hasPendingRomaji())
        )
        if (!reply.getBoolean("result")) return false
        val original = input.clone()
        val originalPrefix = original.clone().prefixToCursorPosition()
        val suffix = original.convertTarget.drop(original.convertTargetCursorPosition)
        input.stopComposition()
        input.insertAtCursorPosition(surface, InputStyle.DIRECT)
        val appliedPrefixElements = input.input.map { it.copy() }
        if (suffix.isNotEmpty()) {
            input.insertAtCursorPosition(suffix, InputStyle.FROZEN)
            input.moveCursorFromCursorPosition(-suffix.length)
        }
        undo = if (activeRefinement != null) {
            val origin = activeRefinement.originInput.clone()
            Undo(
                originalInput = origin,
                originalPrefixElements = origin.input.map { it.copy() },
                originalPrefixCursor = origin.convertTargetCursorPosition,
                originalPreview = activeRefinement.originReading,
                originalPrefixBiases = activeRefinement.originBiases,
                explicit = explicit,
                refined = true,
                appliedSurface = surface,
                appliedPrefixElements = appliedPrefixElements,
            )
        } else {
            val origCharCount = original.input.count { it.piece is InputPiece.CharPiece }
            val prefixCharCount = originalPrefix.input.count { it.piece is InputPiece.CharPiece }
            val prefixBiases = if (currentBiases.size == origCharCount) {
                currentBiases.take(prefixCharCount)
            } else {
                emptyList()
            }
            Undo(
                originalInput = original,
                originalPrefixElements = originalPrefix.input.map { it.copy() },
                originalPrefixCursor = originalPrefix.convertTargetCursorPosition,
                originalPreview = preview,
                originalPrefixBiases = prefixBiases,
                explicit = explicit,
                refined = false,
                appliedSurface = surface,
                appliedPrefixElements = appliedPrefixElements,
            )
        }
        state = reply.getJSONObject("state")
        return true
    }

    private fun hasIntactAppliedPrefix(u: Undo, input: ComposingText): Boolean {
        if (!input.convertTarget.startsWith(u.appliedSurface)) return false
        if (input.convertTargetCursorPosition < u.appliedSurface.length) return false
        if (input.input.size < u.appliedPrefixElements.size) return false
        for (i in u.appliedPrefixElements.indices) {
            if (input.input[i] != u.appliedPrefixElements[i]) return false
        }
        return true
    }

    fun reconcileWithInput(input: ComposingText): Boolean {
        val current = undo ?: return false
        if (hasIntactAppliedPrefix(current, input)) return true
        discardUndo()
        return false
    }

    private fun buildDifferentialRevertElements(
        u: Undo,
        input: ComposingText,
    ): Pair<List<ComposingText.InputElement>, Int>? {
        if (!hasIntactAppliedPrefix(u, input)) return null
        val trailingElements = input.input.drop(u.appliedPrefixElements.size).map { it.copy() }
        val rebuilt = ArrayList<ComposingText.InputElement>(u.originalPrefixElements.size + trailingElements.size + 1)
        u.originalPrefixElements.forEach { rebuilt.add(it.copy()) }
        if (u.originalPrefixElements.lastOrNull()?.inputStyle == InputStyle.ROMAN2KANA &&
            trailingElements.firstOrNull()?.inputStyle == InputStyle.ROMAN2KANA
        ) {
            rebuilt.add(ComposingText.InputElement(InputPiece.CompositionSeparator, InputStyle.FROZEN))
        }
        rebuilt.addAll(trailingElements)
        val cursorAfterPrefix = (input.convertTargetCursorPosition - u.appliedSurface.length).coerceAtLeast(0)
        return rebuilt to (u.originalPrefixCursor + cursorAfterPrefix)
    }

    fun previewRevertedTarget(input: ComposingText): String? {
        val previous = undo ?: return null
        if (isApplied) return previous.originalPreview
        val (rebuilt, _) = buildDifferentialRevertElements(previous, input) ?: return null
        return ComposingText.getConvertTarget(rebuilt)
    }

    fun revert(input: ComposingText): String? {
        val previous = undo ?: return null
        val immediate = isApplied
        val differential = if (!immediate) {
            buildDifferentialRevertElements(previous, input) ?: run {
                discardUndo()
                return null
            }
        } else {
            null
        }
        val reply = policy.request(request("revert"))
        if (!reply.getBoolean("result")) return null
        state = reply.getJSONObject("state")
        undo = null
        if (immediate) {
            input.restoreFrom(previous.originalInput)
            return previous.originalPreview
        }
        val (rebuilt, nextCursor) = differential!!
        input.input.clear()
        input.input.addAll(rebuilt)
        input.convertTarget = ComposingText.getConvertTarget(rebuilt)
        input.convertTargetCursorPosition = nextCursor.coerceIn(0, input.convertTarget.length)
        return input.convertTarget
    }

    fun autoRevert(
        input: ComposingText,
        currentBiases: List<TouchKeyBias> = emptyList(),
    ): Pair<String, List<TouchKeyBias>>? {
        val ctx = buildAutoRefinementContext(input, currentBiases) ?: return null
        val reply = policy.request(
            request(
                op = "autoRevert",
                reading = ctx.originReading,
                surface = input.convertTargetBeforeCursor,
            )
        )
        if (!reply.getBoolean("result")) return null
        state = reply.getJSONObject("state")
        undo = null
        input.restoreFrom(ctx.originInput)
        return ctx.originReading to ctx.originBiases
    }

    fun edited() {
        if (state != null) state = policy.request(request("edited")).getJSONObject("state")
    }

    fun discardUndo() {
        undo = null
        if (state != null) state = policy.request(request("discardUndo")).getJSONObject("state")
    }

    fun isRejected(input: String, candidate: String): Boolean =
        state?.let { policy.request(request("isRejected", reading = input, surface = candidate)).getBoolean("result") } ?: false

    private fun acceptances(op: String, displayed: String): List<Acceptance> {
        if (state == null) return emptyList()
        val reply = policy.request(request(op, surface = displayed))
        state = reply.getJSONObject("state")
        val entries = reply.getJSONArray("result")
        return (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            Acceptance(entry.getString("reading"), entry.getString("surface"))
        }
    }

    fun acceptancesForCommit(displayed: String): List<Acceptance> = acceptances("acceptances", displayed)

    fun fixedPrefix(input: String): String = if (state == null) "" else
        policy.request(request("fixedPrefix", reading = input)).getString("result")

    fun committed(displayed: String): List<Acceptance> {
        val result = acceptances("committed", displayed)
        undo = null
        return result
    }

    fun clear() {
        state = null
        undo = null
    }

    companion object {
        /**
         * True when a typo correction repaired only the hiragana reading so normal
         * conversion should convert it instead of locking the surface literal.
         */
        fun isReadingOnlyCorrection(surface: String): Boolean =
            surface.isNotEmpty() && surface.all { ch ->
                ch in '\u3041'..'\u3096' ||
                    ch == '\u309D' ||
                    ch == '\u309E' ||
                    ch == '\u30FC' ||
                    ch == '、' ||
                    ch == '。' ||
                    ch == '！' ||
                    ch == '？' ||
                    ch == '!' ||
                    ch == '?' ||
                    ch == '・'
            }

        /**
         * Drops fixed-surface locking when [protectedPrefix] is only a repaired reading,
         * allowing Swift/Zenzai to convert the whole reading normally.
         */
        fun fixedSurfacePrefixForConversion(protectedPrefix: String): String =
            if (isReadingOnlyCorrection(protectedPrefix)) "" else protectedPrefix

        /**
         * Matches pending typo-learning acceptances against either the committed surface
         * or its underlying repaired reading.
         */
        fun committedMatchTarget(displayedText: String, reading: String = ""): String =
            if (reading.isNotEmpty() && reading != displayedText) "$displayedText\n$reading" else displayedText
    }
}
