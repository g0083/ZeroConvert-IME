package com.zeroconvert.ime

import com.zeroconvert.ime.azookey.input.ComposingText

internal sealed interface ImeUndoEntry {
    data class JapaneseComposing(
        val previousComposing: ComposingText,
        val touchBiases: List<TouchKeyBias>,
        val previousLeftContext: String
    ) : ImeUndoEntry

    data class EnglishComposing(
        val previousText: String,
        val touchBiases: List<TouchKeyBias>
    ) : ImeUndoEntry

    data class CommittedText(
        val committedText: String,
        val previousComposing: ComposingText? = null,
        val previousEnglish: String = "",
        val previousLeftContext: String = "",
        val japaneseTouchBiases: List<TouchKeyBias> = emptyList(),
        val englishTouchBiases: List<TouchKeyBias> = emptyList(),
        val cursorOffsetFromEnd: Int = 0
    ) : ImeUndoEntry

    data class DeletedText(
        val deletedText: String
    ) : ImeUndoEntry

    data class ReplacedCommittedText(
        val previousText: String,
        val currentText: String
    ) : ImeUndoEntry
}

/**
 * Bounded stack of recent IME edits used by the 12-key Undo key.
 */
internal class ImeUndoHistory(
    private val maxEntries: Int = 32
) {
    private val entries = ArrayDeque<ImeUndoEntry>()

    val size: Int get() = entries.size

    fun isEmpty(): Boolean = entries.isEmpty()

    fun push(entry: ImeUndoEntry) {
        if (maxEntries <= 0) return
        if (entries.size >= maxEntries) {
            entries.removeFirst()
        }
        entries.addLast(entry)
    }

    /**
     * Merges a toggle-cycle replacement (e.g. 'a' -> 'b') into the top [ImeUndoEntry.CommittedText]
     * so a subsequent Undo removes the final cycled character cleanly.
     */
    fun updateLatestCommittedSuffix(oldLength: Int, newText: String): Boolean {
        if (oldLength <= 0 || entries.isEmpty()) return false
        val last = entries.lastOrNull() as? ImeUndoEntry.CommittedText ?: return false
        if (last.committedText.length < oldLength) return false
        entries.removeLast()
        entries.addLast(
            last.copy(
                committedText = last.committedText.dropLast(oldLength) + newText
            )
        )
        return true
    }

    fun pop(): ImeUndoEntry? = entries.removeLastOrNull()

    fun clear() {
        entries.clear()
    }
}
