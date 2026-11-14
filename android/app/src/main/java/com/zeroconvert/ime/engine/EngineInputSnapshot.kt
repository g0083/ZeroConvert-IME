package com.zeroconvert.ime.engine

import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputPiece
import com.zeroconvert.ime.TouchKeyBias
import org.json.JSONArray
import org.json.JSONObject

internal data class EngineInputElement(val text: String, val style: String, val separator: Boolean)

/** Align measured keys to this snapshot. The shared engine verifies the match again. */
internal fun engineTouchEvidence(input: ComposingText, biases: List<TouchKeyBias>): JSONArray {
    val result = JSONArray()
    if (input.input.size > 128 || biases.isEmpty()) return result
    val offset = input.input.size - biases.size
    if (offset < 0) return result
    for ((index, bias) in biases.withIndex()) {
        val element = input.input[offset + index]
        val typed = (element.piece as? InputPiece.CharPiece)?.char ?: return JSONArray()
        if (typed.lowercaseChar() != bias.typedChar.lowercaseChar()) return JSONArray()
        val alternatives = JSONArray()
        bias.nearbyChars.take(4).forEach { (key, weight) ->
            if (key.lowercaseChar() in 'a'..'z' && weight.isFinite() && weight in 0.2f..1f) {
                alternatives.put(JSONObject().put("key", key.lowercaseChar().toString()).put("weight", weight))
            }
        }
        if (alternatives.length() > 0) result.put(JSONObject().put("inputIndex", offset + index)
            .put("typed", typed.toString()).put("alternatives", alternatives))
    }
    return result
}

/** Join UTF-16 pairs before crossing the UTF-8 ABI; retain every style/separator. */
internal fun engineInputElements(input: ComposingText): List<EngineInputElement> {
    val result = mutableListOf<EngineInputElement>()
    var text = StringBuilder()
    var style = ""
    fun flush() {
        if (text.isNotEmpty()) result.add(EngineInputElement(text.toString(), style, false))
        text = StringBuilder()
    }
    for (element in input.input) {
        if (element.inputStyle.name != style) { flush(); style = element.inputStyle.name }
        when (val piece = element.piece) {
            is InputPiece.CharPiece -> text.append(piece.char)
            is InputPiece.CompositionSeparator -> { flush(); result.add(EngineInputElement("", style, true)) }
        }
    }
    flush()
    return result
}
