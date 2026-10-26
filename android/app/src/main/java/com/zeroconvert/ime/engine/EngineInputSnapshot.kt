package com.zeroconvert.ime.engine

import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputPiece

internal data class EngineInputElement(val text: String, val style: String, val separator: Boolean)

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
