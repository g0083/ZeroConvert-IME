package com.zeroconvert.ime.azookey.input

import kotlin.math.max
import kotlin.math.min

/**
 * azooKey's ComposingText ported to Kotlin.
 *
 * Manages:
 * - `input`: user raw input sequence (e.g. ['k', 'y', 'o', 'u'])
 * - `convertTarget`: converted kana/surface sequence (e.g. "きょう")
 * - `convertTargetCursorPosition`: cursor position within convertTarget
 *
 * Designed to preserve independent segment boundaries so that cursor movement,
 * backspace deletion, and long input never break or drop text.
 */
class ComposingText(
    var convertTargetCursorPosition: Int = 0,
    val input: MutableList<InputElement> = mutableListOf(),
    var convertTarget: String = ""
) {

    data class InputElement(
        var piece: InputPiece,
        var inputStyle: InputStyle
    ) {
        constructor(char: Char, inputStyle: InputStyle) : this(InputPiece.CharPiece(char), inputStyle)
    }

    private data class IndexPair(
        val inputIndex: Int,
        val surfaceIndex: Int
    )

    internal data class ConvertTargetElement(
        val string: MutableList<Char>,
        val inputStyle: InputStyle,
        val cachedTable: InputTable?
    )

    val isEmpty: Boolean get() = convertTarget.isEmpty()
    val isAtEndIndex: Boolean get() = convertTarget.length == convertTargetCursorPosition
    val isAtStartIndex: Boolean get() = convertTargetCursorPosition == 0

    val convertTargetBeforeCursor: String get() = convertTarget.take(convertTargetCursorPosition)

    companion object {
        private fun commonPrefixLength(a: CharSequence, b: CharSequence): Int {
            var i = 0
            val len = min(a.length, b.length)
            while (i < len && a[i] == b[i]) {
                i++
            }
            return i
        }

        private fun updateConvertTargetElements(
            currentElements: MutableList<ConvertTargetElement>,
            newElement: InputElement
        ): Int {
            if (currentElements.isEmpty()) {
                if (newElement.piece is InputPiece.CompositionSeparator) return 0
                val table = when (newElement.inputStyle) {
                    InputStyle.DIRECT, InputStyle.FROZEN -> null
                    InputStyle.ROMAN2KANA -> DefaultRomanToKana.table
                }
                val s = mutableListOf<Char>()
                if (table != null) {
                    table.apply(s, newElement.piece)
                } else {
                    when (val p = newElement.piece) {
                        is InputPiece.CharPiece -> s.add(p.char)
                        is InputPiece.CompositionSeparator -> {}
                    }
                }
                currentElements.add(ConvertTargetElement(s, newElement.inputStyle, table))
                return 0
            }

            val lastIndex = currentElements.size - 1
            val last = currentElements[lastIndex]
            if (last.inputStyle == newElement.inputStyle) {
                val table = last.cachedTable
                return if (table != null) {
                    table.apply(last.string, newElement.piece)
                } else {
                    when (val p = newElement.piece) {
                        is InputPiece.CharPiece -> {
                            last.string.add(p.char)
                            0
                        }
                        is InputPiece.CompositionSeparator -> 0
                    }
                }
            } else {
                val table = when (newElement.inputStyle) {
                    InputStyle.DIRECT, InputStyle.FROZEN -> null
                    InputStyle.ROMAN2KANA -> DefaultRomanToKana.table
                }
                val s = mutableListOf<Char>()
                if (table != null) {
                    table.apply(s, newElement.piece)
                } else {
                    when (val p = newElement.piece) {
                        is InputPiece.CharPiece -> s.add(p.char)
                        is InputPiece.CompositionSeparator -> {}
                    }
                }
                currentElements.add(ConvertTargetElement(s, newElement.inputStyle, table))
                return 0
            }
        }

        fun getConvertTarget(elements: List<InputElement>): String {
            val list = mutableListOf<ConvertTargetElement>()
            for (element in elements) {
                updateConvertTargetElements(list, element)
            }
            val sb = java.lang.StringBuilder()
            for (item in list) {
                for (ch in item.string) {
                    sb.append(ch)
                }
            }
            return sb.toString()
        }
    }

    private fun getIndependentSegmentBoundaries(): MutableList<IndexPair> {
        val boundaries = mutableListOf(IndexPair(inputIndex = 0, surfaceIndex = 0))
        val converting = mutableListOf<ConvertTargetElement>()
        var convertedLength = 0

        for ((currentInputIndex, element) in input.withIndex()) {
            val previousElementCount = converting.size
            val previousTailCount = converting.lastOrNull()?.string?.size ?: 0
            val deletedCount = updateConvertTargetElements(converting, element)

            val previousConvertedLength = convertedLength
            val currentTailCount = converting.lastOrNull()?.string?.size ?: 0
            if (converting.size == previousElementCount) {
                convertedLength += currentTailCount - previousTailCount
            } else {
                convertedLength += currentTailCount
            }

            while (boundaries.isNotEmpty()) {
                val last = boundaries.last()
                if (last.surfaceIndex <= previousConvertedLength - deletedCount) {
                    break
                }
                boundaries.removeAt(boundaries.size - 1)
            }
            boundaries.add(IndexPair(inputIndex = currentInputIndex + 1, surfaceIndex = convertedLength))
        }

        return boundaries
    }

    /**
     * Finds the index in `input` corresponding to `targetSurfaceIndex`.
     * If the cursor falls in the middle of a roman-to-kana conversion unit,
     * the affected segment is converted to frozen characters so that the boundary becomes valid.
     */
    fun forceGetInputCursorPosition(targetSurfaceIndex: Int): Int {
        if (targetSurfaceIndex <= 0) return 0
        if (targetSurfaceIndex >= convertTarget.length) return input.size

        val boundaries = getIndependentSegmentBoundaries()
        var cursorSegmentEnd = IndexPair(inputIndex = input.size, surfaceIndex = convertTarget.length)
        var cursorSegmentStart = IndexPair(inputIndex = 0, surfaceIndex = 0)

        while (boundaries.isNotEmpty()) {
            val independentStart = boundaries.removeAt(boundaries.size - 1)
            if (independentStart.surfaceIndex == targetSurfaceIndex) {
                return independentStart.inputIndex
            } else if (independentStart.surfaceIndex < targetSurfaceIndex) {
                cursorSegmentStart = independentStart
                break
            }
            if (independentStart.surfaceIndex < cursorSegmentEnd.surfaceIndex) {
                cursorSegmentEnd = independentStart
            }
        }

        // Replace the segment containing targetSurfaceIndex with frozen kana
        val cursorSegmentChars = convertTarget.substring(cursorSegmentStart.surfaceIndex, cursorSegmentEnd.surfaceIndex)
        val frozenElements = cursorSegmentChars.map { InputElement(it, InputStyle.FROZEN) }

        val removeCount = cursorSegmentEnd.inputIndex - cursorSegmentStart.inputIndex
        for (i in 0 until removeCount) {
            input.removeAt(cursorSegmentStart.inputIndex)
        }
        input.addAll(cursorSegmentStart.inputIndex, frozenElements)

        return targetSurfaceIndex - cursorSegmentStart.surfaceIndex + cursorSegmentStart.inputIndex
    }

    fun insertAtCursorPosition(string: String, inputStyle: InputStyle) {
        val elements = string.map { InputElement(it, inputStyle) }
        insertAtCursorPosition(elements)
    }

    fun insertAtCursorPosition(elements: List<InputElement>) {
        if (elements.isEmpty()) return
        val elementsToInsert = elements.toMutableList()
        var inputCursorPosition = forceGetInputCursorPosition(convertTargetCursorPosition)

        // If cursor is not at the end, prevent subsequent roman2kana merges by appending a composition separator
        if (!isAtEndIndex) {
            elementsToInsert.add(InputElement(InputPiece.CompositionSeparator, InputStyle.FROZEN))
        }

        val leftInputIndex = inputCursorPosition - 1
        if (leftInputIndex in input.indices) {
            val left = input[leftInputIndex]
            if (left.piece is InputPiece.CompositionSeparator && left.inputStyle == InputStyle.FROZEN) {
                input.removeAt(leftInputIndex)
                inputCursorPosition = leftInputIndex
            }
        }

        input.addAll(inputCursorPosition, elementsToInsert)

        val oldPrefix = convertTarget.take(convertTargetCursorPosition)
        val newTargetForPrefix = getConvertTarget(input.take(inputCursorPosition + elementsToInsert.size))

        val cpl = commonPrefixLength(oldPrefix, newTargetForPrefix)
        val deletedFromOld = oldPrefix.length - cpl
        val addedFromNew = newTargetForPrefix.length - cpl

        convertTarget = getConvertTarget(input)
        convertTargetCursorPosition = convertTargetCursorPosition - deletedFromOld + addedFromNew
    }

    fun deleteBackwardFromCursorPosition(count: Int) {
        val actualCount = min(convertTargetCursorPosition, count)
        if (actualCount <= 0) return

        var targetCursorPosition = forceGetInputCursorPosition(convertTargetCursorPosition - actualCount)
        var inputCursorPosition = forceGetInputCursorPosition(convertTargetCursorPosition)

        val targetLeft = targetCursorPosition - 1
        if (targetLeft in input.indices) {
            val left = input[targetLeft]
            if (left.piece is InputPiece.CompositionSeparator && left.inputStyle == InputStyle.FROZEN) {
                input.removeAt(targetLeft)
                targetCursorPosition = targetLeft
                inputCursorPosition -= 1
            }
        }

        if (targetCursorPosition == 0 || inputCursorPosition == input.size) {
            val removeCount = inputCursorPosition - targetCursorPosition
            for (i in 0 until removeCount) {
                input.removeAt(targetCursorPosition)
            }
        } else {
            val removeCount = inputCursorPosition - targetCursorPosition
            for (i in 0 until removeCount) {
                input.removeAt(targetCursorPosition)
            }
            input.add(targetCursorPosition, InputElement(InputPiece.CompositionSeparator, InputStyle.FROZEN))
        }

        convertTargetCursorPosition -= actualCount
        convertTarget = getConvertTarget(input)
    }

    fun deleteForwardFromCursorPosition(count: Int) {
        val actualCount = min(convertTarget.length - convertTargetCursorPosition, count)
        if (actualCount <= 0) return
        convertTargetCursorPosition += actualCount
        deleteBackwardFromCursorPosition(actualCount)
    }

    fun moveCursorFromCursorPosition(count: Int): Int {
        val target = max(0, min(convertTarget.length, convertTargetCursorPosition + count))
        val moved = target - convertTargetCursorPosition
        convertTargetCursorPosition = target
        return moved
    }

    fun prefixComplete(surfaceCount: Int) {
        val count = min(convertTarget.length, max(0, surfaceCount))
        if (count == 0) return
        val index = forceGetInputCursorPosition(count)

        for (i in 0 until index) {
            input.removeAt(0)
        }
        convertTarget = convertTarget.drop(count)
        convertTargetCursorPosition = max(0, convertTargetCursorPosition - count)
        if (convertTargetCursorPosition == 0 && convertTarget.isNotEmpty()) {
            convertTargetCursorPosition = convertTarget.length
        }
    }

    fun prefixToCursorPosition(): ComposingText {
        val index = forceGetInputCursorPosition(convertTargetCursorPosition)
        return ComposingText(
            convertTargetCursorPosition = convertTargetCursorPosition,
            input = input.take(index).map { it.copy() }.toMutableList(),
            convertTarget = convertTarget.take(convertTargetCursorPosition)
        )
    }

    fun stopComposition() {
        input.clear()
        convertTarget = ""
        convertTargetCursorPosition = 0
    }

    fun clone(): ComposingText {
        return ComposingText(
            convertTargetCursorPosition = convertTargetCursorPosition,
            input = input.map { it.copy() }.toMutableList(),
            convertTarget = convertTarget
        )
    }
}
