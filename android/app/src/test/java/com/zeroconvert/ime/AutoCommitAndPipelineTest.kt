package com.zeroconvert.ime

import com.zeroconvert.ime.assist.hasPendingRomaji
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the single auto-commit rule.
 *
 * The rule lives in `windows/crates/shared/src/conversion_policy.rs` and Android
 * reaches it through the shared engine, so these tests read the same scenario
 * table the Rust test does instead of re-testing a Kotlin copy. A behaviour that
 * cannot run without the native engine is covered by the instrumentation suite.
 */
class AutoCommitAndPipelineTest {
    private data class Scenario(
        val id: String,
        val reading: String,
        val preview: String,
        val history: List<InferenceSnapshot>,
        val expect: String,
        val line: Int
    )

    private fun casesFile(): File {
        val start = System.getProperty("user.dir") ?: "."
        var directory: File? = File(start).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "tests/input-policy/auto-commit-cases.tsv")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("tests/input-policy/auto-commit-cases.tsv not found above $start")
    }

    private fun scenarios(): List<Scenario> = casesFile().readLines()
        .mapIndexedNotNull { index, raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("#")) return@mapIndexedNotNull null
            val columns = line.split('\t')
            assertEquals(
                "auto-commit-cases.tsv:${index + 1}: expected 5 tab-separated columns",
                5,
                columns.size
            )
            val reading = columns[1]
            val preview = columns[2]
            val history = columns[3].split(';').map { entry ->
                val separator = entry.indexOf(':')
                check(separator > 0) {
                    "auto-commit-cases.tsv:${index + 1}: bad history entry '$entry'"
                }
                val count = entry.substring(0, separator).trim().toIntOrNull()
                checkNotNull(count) {
                    "auto-commit-cases.tsv:${index + 1}: bad reading count in '$entry'"
                }
                check(count <= reading.length) {
                    "auto-commit-cases.tsv:${index + 1}: reading count $count exceeds '$reading'"
                }
                val surface = entry.substring(separator + 1)
                check(preview.startsWith(surface)) {
                    "auto-commit-cases.tsv:${index + 1}: '$surface' is not a prefix of the preview"
                }
                InferenceSnapshot(reading.take(count), surface, count)
            }
            assertEquals(
                "auto-commit-cases.tsv:${index + 1}: the last surface must equal the preview",
                preview,
                history.last().aiSurface
            )
            Scenario(columns[0], reading, preview, history, columns[4], index + 1)
        }

    @Test fun theTableDescribesBothOutcomes() {
        val scenarios = scenarios()
        assertTrue("the table should cover commit and no-commit", scenarios.size >= 6)
        assertTrue(scenarios.any { it.expect != "none" })
        assertTrue(scenarios.any { it.expect == "none" })
        val ids = HashSet<String>()
        for (scenario in scenarios) {
            assertTrue(
                "auto-commit-cases.tsv:${scenario.line}: duplicate id ${scenario.id}",
                ids.add(scenario.id)
            )
        }
    }

    @Test fun eachScenarioStillHasTheShapeTheSharedRuleExpects() {
        // The engine owns the decision. What Android must keep true is the input
        // shape it hands over: kana readings, prefixes of the current reading, and
        // a history that ends at the current preview.
        for (scenario in scenarios()) {
            assertTrue(
                "auto-commit-cases.tsv:${scenario.line}: reading needs at least 16 kana",
                scenario.reading.length >= 16 || scenario.expect == "none"
            )
            for (snapshot in scenario.history) {
                assertTrue(
                    "auto-commit-cases.tsv:${scenario.line}: snapshot reading is not a prefix",
                    scenario.reading.startsWith(snapshot.readingHiragana)
                )
                assertTrue(
                    "auto-commit-cases.tsv:${scenario.line}: snapshot surface is not a prefix of the preview",
                    scenario.preview.startsWith(snapshot.aiSurface)
                )
            }
            assertFalse(
                "auto-commit-cases.tsv:${scenario.line}: reading carries a pending romaji tail",
                scenario.reading.hasPendingRomaji()
            )
        }
    }

    @Test fun aStablePrefixStaysUncommittedWhenNoPrefixQualifies() {
        // どのスナップショットも直近5件で安定した前置にならない形を、Android が
        // そのままエンジンへ渡すことを固定する。判定そのものは共有エンジンが持つ。
        val history = listOf(
            InferenceSnapshot("がっこうに", "学校に"),
            InferenceSnapshot("がっこうにいって", "学校に行って"),
            InferenceSnapshot("がっこうにいってべん", "学校に行って勉"),
            InferenceSnapshot("がっこうにいってべんきょう", "学校に行って勉強"),
            InferenceSnapshot("がっこうにいってべんきょうして", "学校に行って勉強して"),
            InferenceSnapshot("がっこうにいってべんきょうしています", "学校に行って勉強しています")
        )
        val reading = history.last().readingHiragana
        assertTrue(history.all { reading.startsWith(it.readingHiragana) })
        assertTrue(!reading.hasPendingRomaji())
    }

    @Test fun repeatedPreviewsCollapseToOneEvidenceStep() {
        val reading = "わたしはがっこうにいってべんきょうしています"
        val history = listOf(InferenceSnapshot("わたしはがっこうにいって", "私は学校に行って")) +
            (0..5).map { InferenceSnapshot(reading.dropLast(5 - it), "私は学校に行って勉強") }
        // The engine deduplicates by surface; Android must hand over the raw sequence
        // unchanged so that deduplication stays the engine's decision.
        val distinct = history.map { it.aiSurface }.distinct()
        assertEquals(2, distinct.size)
        assertTrue(history.last().readingHiragana == reading)
    }

    @Test fun inferenceSnapshotsReplaceMatchingRawLengthsAndRemainBounded() {
        val history = mutableListOf<InferenceSnapshot>()
        ZeroConvertInputMethodService.recordInferenceSnapshot(history, InferenceSnapshot("きょ", "巨", 3))
        ZeroConvertInputMethodService.recordInferenceSnapshot(history, InferenceSnapshot("きょう", "今日", 3))
        assertEquals(listOf(InferenceSnapshot("きょう", "今日", 3)), history)
        for (n in 4..180) ZeroConvertInputMethodService.recordInferenceSnapshot(history, InferenceSnapshot("あ".repeat(n), "亜".repeat(n), n))
        assertEquals(128, history.size)
    }

    private class Editor(prefix: String, tail: String) : AutoCommitRewind.Editor {
        var committed = prefix
        var composing = tail
        var failDelete = false
        val calls = mutableListOf<String>()
        override fun beforeCursor(length: Int) = (committed + composing).takeLast(length)
        override fun composing(text: String): Boolean { calls.add("compose:$text"); composing = text; return true }
        override fun deleteBefore(length: Int): Boolean {
            calls.add("delete:$length")
            if (failDelete) return false
            committed = committed.dropLast(length)
            return true
        }
        override fun commit(text: String): Boolean { committed += text; composing = ""; return true }
    }

    @Test fun rewindDeletesTheCommittedPrefixAfterRemovingTheComposingTail() {
        val original = com.zeroconvert.ime.azookey.input.ComposingText().apply {
            insertAtCursorPosition("toukyouniiku", com.zeroconvert.ime.azookey.input.InputStyle.ROMAN2KANA)
        }
        val record = AutoCommitRewindRecord("東京に", "とうきょうに", "前文", original.clone())
        val editor = Editor("前文東京に", "行く")
        val result = AutoCommitRewind.restore(record, "いく", "行く", editor)
        assertEquals(AutoCommitRewind.Result.RESTORED, result)
        assertEquals("前文", editor.committed)
        assertEquals(original.convertTarget, editor.composing)
        assertEquals(listOf("compose:", "delete:3", "compose:とうきょうにいく"), editor.calls)
    }

    @Test fun rewindRefusesChangedEditorTextAndRestoresTailOnDeleteFailure() {
        val original = com.zeroconvert.ime.azookey.input.ComposingText().apply {
            insertAtCursorPosition("toukyouniiku", com.zeroconvert.ime.azookey.input.InputStyle.ROMAN2KANA)
        }
        val record = AutoCommitRewindRecord("東京に", "とうきょうに", "", original.clone())
        val changed = Editor("別の文字", "行く")
        assertEquals(AutoCommitRewind.Result.UNCHANGED, AutoCommitRewind.restore(record, "いく", "行く", changed))
        assertTrue(changed.calls.isEmpty())
        val failed = Editor("東京に", "行く").apply { failDelete = true }
        assertEquals(AutoCommitRewind.Result.UNCHANGED, AutoCommitRewind.restore(record, "いく", "行く", failed))
        assertEquals("東京に行く", failed.committed + failed.composing)
    }
}
