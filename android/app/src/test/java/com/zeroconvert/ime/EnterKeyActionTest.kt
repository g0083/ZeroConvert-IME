package com.zeroconvert.ime

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * EnterKeyAction only reads EditorInfo constants, so these cases run on the JVM with the
 * default-value Android stubs.
 */
class EnterKeyActionTest {
    @Test fun nextActionShowsNext() {
        assertEquals(EnterKeyAction.NEXT, EnterKeyAction.forEditor(EditorInfo.IME_ACTION_NEXT, false))
    }

    @Test fun searchActionShowsSearch() {
        assertEquals(EnterKeyAction.SEARCH, EnterKeyAction.forEditor(EditorInfo.IME_ACTION_SEARCH, false))
        assertEquals("検索", EnterKeyAction.SEARCH.label)
    }

    @Test fun sendActionShowsSend() {
        assertEquals(EnterKeyAction.SEND, EnterKeyAction.forEditor(EditorInfo.IME_ACTION_SEND, false))
        assertEquals("送信", EnterKeyAction.SEND.label)
    }

    @Test fun goDoneAndPreviousActionsMapToLabels() {
        assertEquals(EnterKeyAction.GO, EnterKeyAction.forEditor(EditorInfo.IME_ACTION_GO, false))
        assertEquals(EnterKeyAction.DONE, EnterKeyAction.forEditor(EditorInfo.IME_ACTION_DONE, false))
        assertEquals(EnterKeyAction.PREVIOUS, EnterKeyAction.forEditor(EditorInfo.IME_ACTION_PREVIOUS, false))
        assertEquals("移動", EnterKeyAction.GO.label)
        assertEquals("完了", EnterKeyAction.DONE.label)
        assertEquals("前へ", EnterKeyAction.PREVIOUS.label)
    }

    @Test fun unspecifiedAndNoneKeepTheDefaultGlyph() {
        assertNull(EnterKeyAction.forEditor(0, false))
        assertNull(EnterKeyAction.forEditor(EditorInfo.IME_ACTION_UNSPECIFIED, false))
        assertNull(EnterKeyAction.forEditor(EditorInfo.IME_ACTION_NONE, false))
    }

    @Test fun multilineEditorKeepsTheDefaultGlyph() {
        assertNull(
            EnterKeyAction.forEditor(
                EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_ENTER_ACTION, false
            )
        )
        assertNull(
            EnterKeyAction.forEditor(
                EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_ENTER_ACTION, false
            )
        )
    }

    @Test fun compositionKeepsTheDefaultGlyph() {
        assertNull(EnterKeyAction.forEditor(EditorInfo.IME_ACTION_SEARCH, true))
        assertNull(EnterKeyAction.forEditor(EditorInfo.IME_ACTION_SEND, true))
    }

    @Test fun unrelatedFlagsDoNotHideTheAction() {
        assertEquals(
            EnterKeyAction.SEND,
            EnterKeyAction.forEditor(
                EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, false
            )
        )
        assertEquals(
            EnterKeyAction.SEARCH,
            EnterKeyAction.forEditor(
                EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI, false
            )
        )
    }
}
