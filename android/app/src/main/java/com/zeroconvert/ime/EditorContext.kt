package com.zeroconvert.ime

/** Separates confirmed editor text from our current composing preview. */
internal object EditorContext {
    const val MAX_CODE_POINTS = 512
    fun bounded(text: String): String {
        val count = text.codePointCount(0, text.length)
        return if (count <= MAX_CODE_POINTS) text else text.substring(text.offsetByCodePoints(0, count - MAX_CODE_POINTS))
    }

    fun confirmedLeft(beforeCursor: String?, composingPreview: String, previewCursor: Int = composingPreview.length): String? {
        if (beforeCursor == null || !ComposingEditorState.isBoundary(composingPreview, previewCursor)) return null
        val prefix = composingPreview.take(previewCursor)
        if (!beforeCursor.endsWith(prefix)) return null
        return bounded(beforeCursor.dropLast(prefix.length))
    }
}
