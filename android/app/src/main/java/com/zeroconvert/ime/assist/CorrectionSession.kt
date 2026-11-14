package com.zeroconvert.ime.assist

import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle
import org.json.JSONObject

/** Editor snapshots stay on the host. Adoption, undo and learning state live in Rust. */
class CorrectionSession(private val policy: AssistPolicyTransport = SharedAssistPolicy.native) {
    data class Acceptance(val reading: String, val surface: String)
    private data class Undo(val input: ComposingText, val preview: String)

    private var undo: Undo? = null
    private var state: JSONObject? = null
    val rejectedSurface: String? get() = state?.takeUnless { it.isNull("rejectedSurface") }?.getString("rejectedSurface")
    val isApplied: Boolean get() = state?.let { !it.isNull("undo") } ?: false
    val holdsAutoCommit: Boolean get() = state?.optBoolean("holdAutoCommit") ?: false

    private fun request(op: String, reading: String = "", surface: String = ""): JSONObject =
        JSONObject().put("op", op).put("state", state ?: JSONObject()).put("reading", reading).put("surface", surface)

    fun apply(input: ComposingText, surface: String, preview: String, explicit: Boolean = true): Boolean {
        if (input.isEmpty || input.isAtStartIndex) return false
        val reply = policy.request(request("apply", input.convertTargetBeforeCursor, surface)
            .put("explicit", explicit).put("pendingRomaji", input.convertTargetBeforeCursor.hasPendingRomaji()))
        if (!reply.getBoolean("result")) return false
        val original = input.clone()
        val suffix = original.convertTarget.drop(original.convertTargetCursorPosition)
        input.stopComposition()
        input.insertAtCursorPosition(surface, InputStyle.DIRECT)
        if (suffix.isNotEmpty()) {
            input.insertAtCursorPosition(suffix, InputStyle.FROZEN)
            input.moveCursorFromCursorPosition(-suffix.length)
        }
        undo = Undo(original, preview)
        state = reply.getJSONObject("state")
        return true
    }

    fun revert(input: ComposingText): String? {
        val previous = undo ?: return null
        val reply = policy.request(request("revert"))
        if (!reply.getBoolean("result")) return null
        input.restoreFrom(previous.input)
        state = reply.getJSONObject("state")
        undo = null
        return previous.preview
    }

    fun edited() {
        if (state != null) state = policy.request(request("edited")).getJSONObject("state")
        undo = null
    }

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
}
