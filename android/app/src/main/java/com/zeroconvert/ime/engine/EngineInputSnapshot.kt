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

private fun normalizeQwertyHintChar(c: Char): Char? {
    val lowered = c.lowercaseChar()
    return when {
        lowered in 'a'..'z' -> lowered
        lowered == 'ー' || lowered == '-' -> '-'
        else -> null
    }
}

private data class RawRomajiMapping(
    val raw: String,
    val charEntries: List<Pair<Char, Int>>,
)

private fun buildRawRomajiMapping(input: ComposingText): RawRomajiMapping? {
    if (!input.isAtEndIndex || input.input.isEmpty()) return null
    val out = StringBuilder(input.input.size)
    val charEntries = ArrayList<Pair<Char, Int>>(input.input.size)
    var pendingSeparatorAfterSingleN = false
    for (element in input.input) {
        when (val piece = element.piece) {
            is InputPiece.CompositionSeparator -> {
                val len = out.length
                if (len >= 1 && (out[len - 1] == 'n' || out[len - 1] == 'N')) {
                    pendingSeparatorAfterSingleN = len < 2 || (out[len - 2] != 'n' && out[len - 2] != 'N')
                }
            }
            is InputPiece.CharPiece -> {
                if (element.inputStyle != InputStyle.ROMAN2KANA) return null
                val normalized = normalizeQwertyHintChar(piece.char) ?: return null
                if (pendingSeparatorAfterSingleN) {
                    if (normalized in "aiueoyn") {
                        out.append('n')
                    }
                    pendingSeparatorAfterSingleN = false
                }
                val rawIndex = out.length
                out.append(normalized)
                charEntries.add(normalized to rawIndex)
            }
        }
    }
    if (out.length !in 2..192) return null
    return RawRomajiMapping(out.toString(), charEntries)
}

/** Extract aligned lowercase ASCII romaji (and `-` for `ー`) for continuous QWERTY input. */
internal fun engineRawRomaji(input: ComposingText): String = buildRawRomajiMapping(input)?.raw.orEmpty()

private fun isFlickHintAltChar(ch: Char): Boolean =
    (ch in 'ぁ'..'ゖ') || ch == 'ー' || ch == '（' || ch == '）' || ch == '〜'

/**
 * Format measured 12-key flick gesture hints aligned to direct kana elements as `"idx:ch=w,...;..."`.
 * Returns empty when the composing buffer is not pure continuous `DIRECT` kana input at end index.
 */
internal fun engineFlickTouchHints(input: ComposingText, biases: List<TouchKeyBias>): String {
    if (!input.isAtEndIndex || biases.isEmpty()) return ""
    val directChars = ArrayList<Char>(input.input.size)
    for (element in input.input) {
        if (element.inputStyle != InputStyle.DIRECT) return ""
        val ch = (element.piece as? InputPiece.CharPiece)?.char ?: return ""
        directChars.add(ch)
    }
    if (directChars.size !in 2..128) return ""
    val offset = directChars.size - biases.size
    if (offset < 0) return ""
    val parts = ArrayList<String>(biases.size)
    for ((index, bias) in biases.withIndex()) {
        val rawIndex = offset + index
        if (directChars[rawIndex] != bias.typedChar) return ""
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
    if (biases.isEmpty()) return ""
    val mapping = buildRawRomajiMapping(input) ?: return ""
    val offset = mapping.charEntries.size - biases.size
    if (offset < 0) return ""
    val parts = ArrayList<String>(biases.size)
    for ((index, bias) in biases.withIndex()) {
        val (expected, rawIndex) = mapping.charEntries[offset + index]
        val biasChar = normalizeQwertyHintChar(bias.typedChar) ?: return ""
        if (expected != biasChar) return ""
        val alts = bias.nearbyChars.mapNotNull { (key, weight) ->
            val alt = normalizeQwertyHintChar(key)
            if (alt != null && alt != expected && weight.isFinite() && weight in 0.2f..1f) {
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
        val typedNorm = normalizeQwertyHintChar(typed)
        val biasNorm = normalizeQwertyHintChar(bias.typedChar)
        if (typedNorm == null || typedNorm != biasNorm) return JSONArray()
        val alternatives = JSONArray()
        bias.nearbyChars.take(4).forEach { (key, weight) ->
            val alt = normalizeQwertyHintChar(key)
            if (alt != null && alt != typedNorm && weight.isFinite() && weight in 0.2f..1f) {
                alternatives.put(JSONObject().put("key", alt.toString()).put("weight", weight))
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
