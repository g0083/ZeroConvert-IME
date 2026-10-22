package com.zeroconvert.ime.azookey.input

sealed interface InputPiece {
    data class CharPiece(val char: Char) : InputPiece
    object CompositionSeparator : InputPiece
}

enum class InputStyle {
    DIRECT,
    ROMAN2KANA,
    FROZEN
}
