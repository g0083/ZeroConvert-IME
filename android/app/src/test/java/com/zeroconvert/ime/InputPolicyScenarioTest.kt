package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the Kotlin policy against the same table the shared Rust policy is
 * tested with. A drift between `InputPolicy.kt` and
 * `windows/crates/shared/src/conversion_policy.rs` fails here.
 *
 * The fallback tables are tested on purpose: they are what an initialization
 * failure leaves in place, so they must already match the specification.
 */
class InputPolicyScenarioTest {
    private data class Scenario(
        val id: String,
        val operation: String,
        val input: Char,
        val expect: String,
        val line: Int
    )

    private fun casesFile(): File {
        val start = System.getProperty("user.dir") ?: "."
        var directory: File? = File(start).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "tests/input-policy/cases.tsv")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("tests/input-policy/cases.tsv not found above $start")
    }

    private fun scenarios(): List<Scenario> = casesFile().readLines()
        .mapIndexedNotNull { index, raw ->
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("#")) return@mapIndexedNotNull null
            val columns = line.split('\t')
            assertEquals("cases.tsv:${index + 1}: expected 4 tab-separated columns", 4, columns.size)
            val input = columns[2]
            assertEquals("cases.tsv:${index + 1}: input must be one character", 1, input.length)
            Scenario(columns[0], columns[1], input[0], columns[3], index + 1)
        }

    private fun outcome(scenario: Scenario): String = when (scenario.operation) {
        "immediate" -> if (InputPolicy.isImmediateCommitChar(scenario.input)) "commit" else "compose"
        "punctuation" -> InputPolicy.punctuationForReading(scenario.input)?.toString() ?: "none"
        "compose_punctuation" ->
            if (InputPolicy.shouldComposePunctuation(scenario.input, pendingRomaji = false)) "compose" else "commit"
        "compose_punctuation_pending" ->
            if (InputPolicy.shouldComposePunctuation(scenario.input, pendingRomaji = true)) "compose" else "commit"
        "digit" -> if (InputPolicy.isCompositionDigit(scenario.input)) "compose" else "commit"
        "translation_literal" -> if (InputPolicy.isTranslationLiteralChar(scenario.input)) "literal" else "plain"
        "width" -> when (InputPolicy.symbolDefaultFullwidth(scenario.input.toString())) {
            true -> "full"
            false -> "half"
            null -> "none"
        }
        else -> error("cases.tsv:${scenario.line}: unknown operation ${scenario.operation}")
    }

    @Test fun everyScenarioMatchesTheFallbackPolicy() {
        val scenarios = scenarios()
        assertTrue("cases.tsv should cover the whole policy surface", scenarios.size >= 40)
        val ids = HashSet<String>()
        for (scenario in scenarios) {
            assertTrue("cases.tsv:${scenario.line}: duplicate id ${scenario.id}", ids.add(scenario.id))
            assertEquals(
                "cases.tsv:${scenario.line}: ${scenario.id} (${scenario.input})",
                scenario.expect,
                outcome(scenario)
            )
        }
    }

    @Test fun everyOperationInTheTableIsExercised() {
        val operations = scenarios().map { it.operation }.toSet()
        for (expected in listOf(
            "immediate", "punctuation", "compose_punctuation", "compose_punctuation_pending",
            "digit", "translation_literal", "width"
        )) {
            assertTrue("no cases for $expected", expected in operations)
        }
    }

    @Test fun theSharedPayloadPathReplacesAndRestoresTheFallbackTables() {
        // Deliberately different from the fallback in both tables so a payload
        // that is parsed but not installed cannot pass.
        val applied = InputPolicy.apply(
            version = InputPolicy.VERSION,
            immediateCommit = "!",
            readingPunctuation = listOf(',' to '、', '.' to '。'),
            defaultFullwidth = listOf("@" to true, "!" to true)
        )
        try {
            assertTrue(applied)
            assertTrue(InputPolicy.fromSharedEngine)
            assertEquals('、', InputPolicy.punctuationForReading(','))
            assertFalse(InputPolicy.isImmediateCommitChar('@'))
            assertEquals(true, InputPolicy.symbolDefaultFullwidth("@"))
        } finally {
            InputPolicy.reset()
        }
        assertFalse(InputPolicy.fromSharedEngine)
        assertTrue(InputPolicy.isImmediateCommitChar('@'))
        assertEquals(false, InputPolicy.symbolDefaultFullwidth("@"))
        assertEquals(null, InputPolicy.punctuationForReading(';'))
    }

    @Test fun unknownOrEmptyPoliciesAreRejected() {
        assertFalse(
            InputPolicy.apply(
                version = InputPolicy.VERSION + 1,
                immediateCommit = "@",
                readingPunctuation = listOf(',' to '、'),
                defaultFullwidth = listOf("@" to true)
            )
        )
        assertFalse(
            InputPolicy.apply(
                version = InputPolicy.VERSION,
                immediateCommit = "",
                readingPunctuation = listOf(',' to '、'),
                defaultFullwidth = listOf("@" to true)
            )
        )
        assertFalse(
            InputPolicy.apply(
                version = InputPolicy.VERSION,
                immediateCommit = "!",
                readingPunctuation = emptyList(),
                defaultFullwidth = emptyList()
            )
        )
        assertFalse(InputPolicy.fromSharedEngine)
        assertTrue(InputPolicy.isImmediateCommitChar('!'))
    }
}
