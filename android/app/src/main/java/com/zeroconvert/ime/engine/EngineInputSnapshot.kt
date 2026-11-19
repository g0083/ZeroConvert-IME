package com.zeroconvert.ime.engine

import com.zeroconvert.ime.JapaneseInputLayout
import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputPiece
import com.zeroconvert.ime.azookey.input.InputStyle
import com.zeroconvert.ime.TouchKeyBias
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class EngineInputElement(val text: String, val style: String, val separator: Boolean)

/**
 * Determine `"flick"` vs `"qwerty"` from the active Japanese layout and snapshot styles.
 * Mixed/romaji buffers fall back safely to `"qwerty"` mode.
 */
internal fun engineInputMode(input: ComposingText, layout: JapaneseInputLayout): String {
    if (layout == JapaneseInputLayout.FLICK_12KEY &&
        input.input.isNotEmpty() &&
        input.input.all { it.inputStyle == InputStyle.DIRECT }
    ) {
        return "flick"
    }
    return "qwerty"
}

/** Extract aligned lowercase ASCII romaji when the whole buffer uses continuous QWERTY romaji input. */
internal fun engineRawRomaji(input: ComposingText): String {
    if (!input.isAtEndIndex || input.input.size !in 2..192) return ""
    val out = StringBuilder(input.input.size)
    for (element in input.input) {
        if (element.inputStyle != InputStyle.ROMAN2KANA) return ""
        val ch = (element.piece as? InputPiece.CharPiece)?.char?.lowercaseChar() ?: return ""
        if (ch !in 'a'..'z') return ""
        out.append(ch)
    }
    return out.toString()
}

private fun isFlickHintAltChar(ch: Char): Boolean =
    (ch in 'ぁ'..'ゖ') || ch == 'ー' || ch == '（' || ch == '）' || ch == '〜'

/**
 * Format measured 12-key flick gesture hints aligned to direct kana elements as `"idx:ch=w,...;..."`.
 * Returns empty when the composing buffer is not pure continuous `DIRECT` kana input at end index.
 */
internal fun engineFlickTouchHints(input: ComposingText, biases: List<TouchKeyBias>): String {
    if (!input.isAtEndIndex || input.input.size !in 2..128 || biases.isEmpty()) return ""
    val offset = input.input.size - biases.size
    if (offset < 0) return ""
    for (element in input.input) {
        if (element.inputStyle != InputStyle.DIRECT) return ""
        if (element.piece !is InputPiece.CharPiece) return ""
    }
    val parts = ArrayList<String>(biases.size)
    for ((index, bias) in biases.withIndex()) {
        val rawIndex = offset + index
        val typed = (input.input[rawIndex].piece as? InputPiece.CharPiece)?.char ?: return ""
        if (typed != bias.typedChar) return ""
        val alts = bias.nearbyChars.mapNotNull { (alt, weight) ->
            if (isFlickHintAltChar(alt) && weight.isFinite() && weight in 0.2f..1.0f) {
                "$alt=${String.format(Locale.ROOT, "%.2f", weight)}"
            } else {
                null
            }
        }.take(4)
        if (alts.isNotEmpty()) {
            parts.add("$rawIndex:${alts.joinToString(",")}")
        }
    }
    return parts.joinToString(";")
}

/**
 * Format measured touch boundary hints aligned to [engineRawRomaji] as `"idx:alt=w,...;..."`.
 * Returns empty when the composing buffer cannot be aligned cleanly to QWERTY romaji indices.
 */
internal fun engineTouchHints(input: ComposingText, biases: List<TouchKeyBias>): String {
    val raw = engineRawRomaji(input)
    if (raw.isEmpty() || biases.isEmpty()) return ""
    val offset = raw.length - biases.size
    if (offset < 0) return ""
    val parts = ArrayList<String>(biases.size)
    for ((index, bias) in biases.withIndex()) {
        val rawIndex = offset + index
        if (raw[rawIndex] != bias.typedChar.lowercaseChar()) return ""
        val alts = bias.nearbyChars.mapNotNull { (key, weight) ->
            val alt = key.lowercaseChar()
            if (alt in 'a'..'z' && weight.isFinite() && weight in 0.2f..1f) {
                "$alt=${String.format(Locale.ROOT, "%.2f", weight)}"
            } else {
                null
            }
        }.take(4)
        if (alts.isNotEmpty()) {
            parts.add("$rawIndex:${alts.joinToString(",")}")
        }
    }
    return parts.joinToString(";")
}

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
