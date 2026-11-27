package com.zeroconvert.ime

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.compositionLocalOf

/**
 * The editor action the Enter key stands for, taken from the focused editor's
 * `EditorInfo.imeOptions`.
 *
 * `null` (see [forEditor]) means the key keeps its layout's default glyph: the
 * return arrow on QWERTY, "確定" on the 12-key and numeric grids. That is the answer for a
 * multiline editor (`IME_FLAG_NO_ENTER_ACTION`) and while a conversion is in
 * progress, where Enter confirms the composition instead of submitting the field.
 *
 * Only `EditorInfo` constants are read, so JVM unit tests can exercise this
 * without the Android framework.
 */
enum class EnterKeyAction(val label: String, val editorAction: Int) {
    GO("移動", EditorInfo.IME_ACTION_GO),
    SEARCH("検索", EditorInfo.IME_ACTION_SEARCH),
    SEND("送信", EditorInfo.IME_ACTION_SEND),
    NEXT("次へ", EditorInfo.IME_ACTION_NEXT),
    DONE("完了", EditorInfo.IME_ACTION_DONE),
    PREVIOUS("前へ", EditorInfo.IME_ACTION_PREVIOUS);

    companion object {
        fun forEditor(imeOptions: Int, isComposing: Boolean): EnterKeyAction? {
            // Enter confirms the conversion first; the field's action applies to the
            // next press, when nothing is left to confirm.
            if (isComposing) return null
            // A multiline editor opts out of editor actions, so Enter must stay a newline.
            if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
            val action = imeOptions and EditorInfo.IME_MASK_ACTION
            return entries.firstOrNull { it.editorAction == action }
        }
    }
}

/**
 * The action shown on the Enter key, or `null` for the layout's default glyph.
 * [KeyboardRootView] provides it once per frame from the service's `imeOptions` state
 * and the composition state, so every Enter key in every layout agrees.
 */
val LocalEnterKeyAction = compositionLocalOf<EnterKeyAction?> { null }
