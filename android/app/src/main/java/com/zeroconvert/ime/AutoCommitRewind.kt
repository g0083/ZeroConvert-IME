package com.zeroconvert.ime

/** Editor calls are injectable so failure paths can be tested without the Android stub runtime. */
internal object AutoCommitRewind {
    interface Editor {
        fun beforeCursor(length: Int): String?
        fun composing(text: String): Boolean
        fun deleteBefore(length: Int): Boolean
        fun commit(text: String): Boolean
    }
    enum class Result { RESTORED, UNCHANGED, COMMITTED_FALLBACK, RESTORE_PENDING }

    fun restore(record: AutoCommitRewindRecord, currentReading: String, tailPreview: String, editor: Editor): Result {
        val snapshot = record.originalInput ?: return Result.UNCHANGED
        if (snapshot.convertTarget != record.committedReading + currentReading) return Result.UNCHANGED
        val expected = record.committedSurface + tailPreview
        if (editor.beforeCursor(expected.length) != expected) return Result.UNCHANGED
        // Remove the composing tail first. Deleting directly before the cursor
        // while it is present would delete tail characters instead of the committed prefix.
        if (!editor.composing("")) return Result.UNCHANGED
        if (!editor.deleteBefore(record.committedSurface.length)) {
            if (editor.composing(tailPreview)) return Result.UNCHANGED
            return if (editor.commit(tailPreview)) Result.COMMITTED_FALLBACK else Result.UNCHANGED
        }
        if (editor.composing(snapshot.convertTarget)) return Result.RESTORED
        return if (editor.commit(snapshot.convertTarget)) Result.COMMITTED_FALLBACK else Result.RESTORE_PENDING
    }
}
