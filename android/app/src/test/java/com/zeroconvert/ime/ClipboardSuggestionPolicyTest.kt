package com.zeroconvert.ime

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test

class ClipboardSuggestionPolicyTest {
    private val now = 10_000_000L
    private fun entry(ageMillis: Long = 0, pinned: Boolean = false, id: Long = 1) =
        ClipboardEntry(id = id, text = "検証用$id", timestamp = now - ageMillis, pinned = pinned)
    private fun select(vararg history: ClipboardEntry, minutes: Int = 3, at: Long = now) =
        ClipboardSuggestionPolicy.select(history.toList(), minutes, at)

    @Test fun defaultsAndStoredValuesAreBounded() {
        assertEquals(3, ClipboardSuggestionPolicy.DEFAULT_MINUTES)
        assertEquals(0, ClipboardSuggestionPolicy.normalizeMinutes(-1))
        assertEquals(30, ClipboardSuggestionPolicy.normalizeMinutes(Int.MAX_VALUE))
    }

    @Test fun noHistoryOrOnlyPinnedEntriesShowsNothing() {
        assertNull(select())
        assertNull(select(entry(pinned = true)))
    }

    @Test fun pinnedHeadDoesNotHideARecentUnpinnedEntry() {
        val recent = entry(1_000, id = 2)
        assertEquals(recent, select(entry(pinned = true), recent))
    }

    @Test fun pinReorderingDoesNotDetermineTheNewestSuggestion() {
        val recent = entry(1_000, id = 2)
        assertEquals(recent, select(entry(50_000), recent, entry(pinned = true, id = 3)))
    }

    @Test fun expiresExactlyAtTheConfiguredDeadline() {
        val copied = entry()
        assertEquals(copied, select(copied, at = now + 179_999))
        assertNull(select(copied, at = now + 180_000))
        assertNull(select(copied, at = now + 180_001))
    }

    @Test fun disabledSettingHidesFreshEntries() {
        assertNull(select(entry(), minutes = 0))
    }

    @Test fun changingDurationUsesTheOriginalAdditionTime() {
        val copied = entry(120_000)
        assertNull(select(copied, minutes = 1))
        assertEquals(copied, select(copied, minutes = 3))
        assertEquals(60_000L, ClipboardSuggestionPolicy.remainingMillis(copied, 3, now))
    }

    @Test fun reopeningDoesNotRestartTheDisplayTime() {
        val copied = entry()
        assertNotNull(select(copied, at = now + 60_000))
        assertNull(select(copied, at = now + 180_000))
    }

    @Test fun unpinningDoesNotRefreshAnOldEntry() {
        assertNull(select(entry(600_000, pinned = true).copy(pinned = false)))
    }

    @Test fun futureAndMalformedOldTimestampsAreHidden() {
        assertNull(select(entry(-1)))
        assertNull(select(entry().copy(timestamp = Long.MIN_VALUE)))
    }

    @Test fun filteringPreservesHistoryAndPinnedItems() {
        val history = listOf(entry(pinned = true), entry(600_000, id = 2))
        val original = history.toList()
        assertNull(ClipboardSuggestionPolicy.select(history, 3, now))
        assertEquals(original, history)
    }

    @Test fun selectAllReturnsMultipleActiveEntriesOrderedNewestFirst() {
        val oldestActive = entry(120_000, id = 1)
        val middleActive = entry(30_000, id = 2)
        val newestActive = entry(1_000, id = 3)
        val pinned = entry(500, pinned = true, id = 4)
        val expired = entry(180_000, id = 5)

        val selected = ClipboardSuggestionPolicy.selectAll(
            listOf(oldestActive, pinned, expired, newestActive, middleActive),
            minutes = 3,
            now = now
        )
        assertEquals(listOf(newestActive, middleActive, oldestActive), selected)
    }

    @Test fun selectAllPreservesHistoryOrderOnEqualTimestamps() {
        val first = entry(1_000, id = 1)
        val second = entry(1_000, id = 2)
        assertEquals(
            listOf(first, second),
            ClipboardSuggestionPolicy.selectAll(listOf(first, second), minutes = 3, now = now)
        )
    }

    @Test fun upsertEntriesMovesRecopiedEntryToTopAndRefreshesTimestamp() {
        val oldest = ClipboardEntry(id = 1, text = "最初", timestamp = now - 300_000)
        val middle = ClipboardEntry(id = 2, text = "真ん中", timestamp = now - 60_000)
        val newest = ClipboardEntry(id = 3, text = "最新", timestamp = now - 10_000)
        val history = mutableListOf(newest, middle, oldest)

        val changed = ClipboardHistoryPolicy.upsertEntries(
            history,
            listOf(ClipboardEntry(id = 99, text = "最初", timestamp = now))
        )

        assertTrue(changed)
        assertEquals(listOf("最初", "最新", "真ん中"), history.map { it.text })
        assertEquals(1L, history.first().id)
        assertEquals(now, history.first().timestamp)
        assertEquals(
            listOf("最初", "最新", "真ん中"),
            ClipboardSuggestionPolicy.selectAll(history, minutes = 3, now = now).map { it.text }
        )
    }

    @Test fun upsertEntriesRefreshesTimestampWhenRecopyingTopEntry() {
        val expiredTop = ClipboardEntry(id = 1, text = "先頭", timestamp = now - 600_000)
        val history = mutableListOf(expiredTop)

        assertTrue(
            ClipboardHistoryPolicy.upsertEntries(
                history,
                listOf(ClipboardEntry(id = 2, text = "先頭", timestamp = now))
            )
        )
        assertEquals(1, history.size)
        assertEquals(now, history.first().timestamp)
        assertEquals("先頭", ClipboardSuggestionPolicy.select(history, minutes = 3, now = now)?.text)
    }

    @Test fun upsertEntriesPreservesPinnedFlagWhenMovingToTop() {
        val pinnedOld = ClipboardEntry(id = 1, text = "固定", timestamp = now - 600_000, pinned = true)
        val otherPinned = ClipboardEntry(id = 2, text = "別の固定", timestamp = now - 10_000, pinned = true)
        val history = mutableListOf(otherPinned, pinnedOld)

        ClipboardHistoryPolicy.upsertEntries(
            history,
            listOf(ClipboardEntry(id = 3, text = "固定", timestamp = now))
        )

        assertEquals(listOf("固定", "別の固定"), history.map { it.text })
        assertTrue(history.first().pinned)
        assertEquals(now, history.first().timestamp)
    }

    @Test fun upsertEntriesKeepsCopiedTextBeforeExtractedEntitiesAndUpdatesEntityType() {
        val history = mutableListOf<ClipboardEntry>()
        val withSentence = listOf(
            ClipboardEntry(id = 12, text = "連絡先 user@example.com", timestamp = now + 100),
            ClipboardEntry(id = 13, text = "user@example.com", entityType = "email", timestamp = now + 100)
        )
        ClipboardHistoryPolicy.upsertEntries(history, withSentence)
        assertEquals(listOf("連絡先 user@example.com", "user@example.com"), history.map { it.text })
        assertNull(history[0].entityType)
        assertEquals("email", history[1].entityType)

        // Direct copy of the email promotes it to the top as ordinary clipboard content.
        ClipboardHistoryPolicy.upsertEntries(
            history,
            ClipboardHistoryPolicy.buildTextEntries("user@example.com", now + 200)
        )
        assertEquals(listOf("user@example.com", "連絡先 user@example.com"), history.map { it.text })
        assertNull(history[0].entityType)
    }

    @Test fun buildTextEntriesPlacesOriginalTextBeforeExtractedPhoneNumber() {
        val history = mutableListOf<ClipboardEntry>()
        val entries = ClipboardHistoryPolicy.buildTextEntries("お問い合わせ番号 090-1234-5678 です", now)
        ClipboardHistoryPolicy.upsertEntries(history, entries)

        assertEquals(listOf("お問い合わせ番号 090-1234-5678 です", "090-1234-5678"), history.map { it.text })
        assertNull(history[0].entityType)
        assertEquals("phone", history[1].entityType)
        assertEquals("電話", ClipboardHistoryPolicy.entityBadgeLabel(history[1].entityType))
        assertEquals("メール", ClipboardHistoryPolicy.entityBadgeLabel("email"))
        assertEquals("URL", ClipboardHistoryPolicy.entityBadgeLabel("url"))
        assertNull(ClipboardHistoryPolicy.entityBadgeLabel(null))
        assertEquals(
            listOf("お問い合わせ番号 090-1234-5678 です", "090-1234-5678"),
            ClipboardSuggestionPolicy.selectAll(history, minutes = 3, now = now).map { it.text }
        )

        val exactPhoneHistory = mutableListOf<ClipboardEntry>()
        ClipboardHistoryPolicy.upsertEntries(
            exactPhoneHistory,
            ClipboardHistoryPolicy.buildTextEntries("03-1234-5678", now)
        )
        assertEquals(listOf("03-1234-5678"), exactPhoneHistory.map { it.text })
        assertNull(exactPhoneHistory.single().entityType)
    }

    @Test fun dismissingOneSuggestionKeepsRemainingSuggestionsAndRecopyReactivatesOnlyThatEntry() {
        val first = ClipboardEntry(id = 1, text = "1件目", timestamp = now - 3_000)
        val second = ClipboardEntry(id = 2, text = "2件目", timestamp = now - 2_000)
        val third = ClipboardEntry(id = 3, text = "3件目", timestamp = now - 1_000)
        val history = mutableListOf(third, second, first)

        var dismissed = setOf(third.id)
        assertEquals(
            listOf(second, first),
            ClipboardSuggestionPolicy.selectAll(history, minutes = 3, now = now, dismissedIds = dismissed)
        )

        dismissed = dismissed + second.id
        assertEquals(
            listOf(first),
            ClipboardSuggestionPolicy.selectAll(history, minutes = 3, now = now, dismissedIds = dismissed)
        )

        val recopyThird = listOf(ClipboardEntry(id = 99, text = "3件目", timestamp = now))
        assertTrue(ClipboardHistoryPolicy.upsertEntries(history, recopyThird))
        dismissed = ClipboardHistoryPolicy.clearDismissedForEntries(history, recopyThird, dismissed)
        assertEquals(setOf(second.id), dismissed)
        assertEquals(
            listOf("3件目", "1件目"),
            ClipboardSuggestionPolicy.selectAll(history, minutes = 3, now = now, dismissedIds = dismissed).map { it.text }
        )
    }

    @Test fun clipboardEditSessionComposesCommitsDeletesAndMovesCursorInPlace() {
        val notifications = mutableListOf<List<Int>>()
        val session = ClipboardEditSession(entry(id = 42), "初期値") { oldS, oldE, newS, newE, cs, ce ->
            notifications += listOf(oldS, oldE, newS, newE, cs, ce)
        }
        assertEquals("初期値", session.currentText)
        assertEquals(3, session.uiState.value.selectionStart)
        assertEquals(3, session.uiState.value.selectionEnd)

        assertTrue(session.setComposingText("へん", 1))
        assertEquals("初期値へん", session.currentText)
        assertEquals(3, session.uiState.value.composingStart)
        assertEquals(5, session.uiState.value.composingEnd)
        assertEquals("へん", session.getTextBeforeCursor(2, 0)?.toString())

        assertTrue(session.commitText("変換", 1))
        assertEquals("初期値変換", session.currentText)
        assertEquals(-1, session.uiState.value.composingStart)
        assertEquals(-1, session.uiState.value.composingEnd)
        assertEquals(5, session.uiState.value.selectionEnd)

        assertTrue(session.setUserSelection(3, 3))
        assertEquals("初期値", session.getTextBeforeCursor(10, 0)?.toString())
        assertEquals("変換", session.getTextAfterCursor(10, 0)?.toString())

        assertTrue(session.deleteSurroundingText(1, 0))
        assertEquals("初期変換", session.currentText)
        assertEquals(2, session.uiState.value.selectionEnd)

        assertTrue(session.dispatchKeyCode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(3, session.uiState.value.selectionEnd)
        assertTrue(session.dispatchKeyCode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(2, session.uiState.value.selectionEnd)
        assertTrue(session.dispatchKeyCode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        assertEquals("初期\n変換", session.currentText)
        assertEquals(3, session.uiState.value.selectionEnd)
        assertTrue(session.performEditorAction(EditorInfo.IME_ACTION_NONE))
        assertEquals("初期\n\n変換", session.currentText)

        assertTrue(session.setSelection(0, 2))
        assertEquals("初期", session.getSelectedText(0)?.toString())
        assertTrue(session.dispatchKeyCode(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        assertEquals("\n\n変換", session.currentText)

        assertTrue(session.selectAll())
        assertEquals("\n\n変換", session.getSelectedText(0)?.toString())
        assertTrue(session.commitText("😀", 1))
        assertEquals("😀", session.currentText)
        assertTrue(session.deleteSurroundingTextInCodePoints(1, 0))
        assertEquals("", session.currentText)

        val beforeBatchCount = notifications.size
        assertTrue(session.beginBatchEdit())
        assertTrue(session.setComposingText("あいう", 1))
        assertTrue(session.setSelection(1, 1))
        assertEquals(beforeBatchCount, notifications.size)
        assertFalse(session.endBatchEdit())
        assertEquals(beforeBatchCount + 1, notifications.size)
        assertEquals(listOf(0, 0, 1, 1, 0, 3), notifications.last())

        val extracted = session.getExtractedText(null, 0)
        assertEquals("あいう", extracted.text.toString())
        assertEquals(0, extracted.startOffset)
        assertEquals(1, extracted.selectionStart)
        assertEquals(1, extracted.selectionEnd)
    }
}
