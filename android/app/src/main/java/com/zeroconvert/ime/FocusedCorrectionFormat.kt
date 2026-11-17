package com.zeroconvert.ime

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import java.text.BreakIterator
import java.util.Locale

internal data class FocusedCorrectionParts(
    val original: String,
    val arrow: String,
    val before: String,
    val changed: String,
    val after: String
) {
    val text: String
        get() = original + arrow + before + changed + after

    fun toAnnotatedString(
        originalColor: Color,
        targetColor: Color,
        highlightBackground: Color
    ): AnnotatedString = buildAnnotatedString {
        if (original.isNotEmpty()) {
            withStyle(SpanStyle(color = originalColor)) {
                append(original)
            }
        }
        if (arrow.isNotEmpty()) {
            withStyle(SpanStyle(color = originalColor)) {
                append(arrow)
            }
        }
        if (before.isNotEmpty()) {
            withStyle(SpanStyle(color = targetColor)) {
                append(before)
            }
        }
        if (changed.isNotEmpty()) {
            withStyle(
                SpanStyle(
                    color = targetColor,
                    fontWeight = FontWeight.SemiBold,
                    background = highlightBackground
                )
            ) {
                append(changed)
            }
        }
        if (after.isNotEmpty()) {
            withStyle(SpanStyle(color = targetColor)) {
                append(after)
            }
        }
    }
}

internal object FocusedCorrectionFormat {
    private const val ELLIPSIS = "…"
    private const val MAX_DP_CELLS = 20000
    private const val MAX_MERGE_GAP = 2
    private const val MAX_MERGED_HUNK_LEN = 6
    private const val MAX_COMBINED_SPAN = 8
    private val DIFF_CAPS = intArrayOf(8, 6, 4, 3, 2)

    internal data class Hunk(
        val aStart: Int,
        val aEnd: Int,
        val bStart: Int,
        val bEnd: Int
    )

    private data class SideWindow(
        val before: String,
        val changed: String,
        val after: String
    ) {
        val joined: String
            get() = before + changed + after
    }

    fun fit(
        source: String,
        replacement: String,
        fits: (FocusedCorrectionParts) -> Boolean
    ): FocusedCorrectionParts {
        val a = graphemes(source)
        val b = graphemes(replacement)
        val hunk = extractFocusedHunk(a, b)
            ?: return fitEqualFallback(b, fits)

        // 1. Prefer spaced "…誤 → 正…" and compact "…誤→正…" while keeping surrounding context.
        for (arrow in listOf(" → ", "→")) {
            for (ctx in 3 downTo 1) {
                val cand = buildCandidate(a, b, hunk, ctx, Int.MAX_VALUE, arrow)
                if (fits(cand)) return cand
            }
        }

        // 2. Tighter widths: keep both sides of the diff (with ellipses first, then bare 誤→正).
        val zeroWithEllipses = buildCandidate(a, b, hunk, 0, Int.MAX_VALUE, "→")
        if (fits(zeroWithEllipses)) return zeroWithEllipses
        val bareZero = FocusedCorrectionParts(
            original = a.subList(hunk.aStart, hunk.aEnd).joinToString(""),
            arrow = "→",
            before = "",
            changed = b.subList(hunk.bStart, hunk.bEnd).joinToString(""),
            after = ""
        )
        if (fits(bareZero)) return bareZero
        for (cap in DIFF_CAPS) {
            val aChangedLen = hunk.aEnd - hunk.aStart
            val bChangedLen = hunk.bEnd - hunk.bStart
            if (aChangedLen <= cap && bChangedLen <= cap) continue
            val cand = buildCandidate(a, b, hunk, 0, cap, "→")
            if (fits(cand)) return cand
        }

        // 2. When space is too tight for both sides (e.g. while "戻す" is visible),
        // drop the original side first so the corrected segment remains readable.
        for (arrow in listOf("→", "")) {
            for (ctx in 2 downTo 0) {
                val win = buildSideWindow(b, hunk.bStart, hunk.bEnd, ctx, Int.MAX_VALUE)
                val cand = FocusedCorrectionParts("", arrow, win.before, win.changed, win.after)
                if (fits(cand)) return cand
            }
            for (cap in DIFF_CAPS) {
                val bChangedLen = hunk.bEnd - hunk.bStart
                if (bChangedLen <= cap) continue
                val win = buildSideWindow(b, hunk.bStart, hunk.bEnd, 0, cap)
                val cand = FocusedCorrectionParts("", arrow, win.before, win.changed, win.after)
                if (fits(cand)) return cand
            }
        }

        // 3. Last resort: keep the trailing characters of the corrected text.
        return fitTailWindow(b, hunk.bEnd, fits)
    }

    private fun fitEqualFallback(
        tokens: List<String>,
        fits: (FocusedCorrectionParts) -> Boolean
    ): FocusedCorrectionParts {
        if (tokens.isEmpty()) return FocusedCorrectionParts("", "", "", "", "")
        val full = FocusedCorrectionParts("", "", "", tokens.joinToString(""), "")
        if (fits(full)) return full
        for (keep in (tokens.size - 1) downTo 1) {
            val cand = FocusedCorrectionParts("", "", ELLIPSIS, tokens.subList(tokens.size - keep, tokens.size).joinToString(""), "")
            if (fits(cand)) return cand
        }
        val ellipsisOnly = FocusedCorrectionParts("", "", "", ELLIPSIS, "")
        return if (fits(ellipsisOnly)) ellipsisOnly else FocusedCorrectionParts("", "", "", "", "")
    }

    private fun fitTailWindow(
        tokens: List<String>,
        focusEnd: Int,
        fits: (FocusedCorrectionParts) -> Boolean
    ): FocusedCorrectionParts {
        val anchorEnd = focusEnd.coerceIn(1, tokens.size)
        val suffixHasEllipsis = anchorEnd < tokens.size
        val suffix = if (suffixHasEllipsis) ELLIPSIS else ""
        for (keep in anchorEnd downTo 1) {
            val start = anchorEnd - keep
            val prefix = if (start > 0) ELLIPSIS else ""
            val changed = tokens.subList(start, anchorEnd).joinToString("")
            val cand = FocusedCorrectionParts("", "", prefix, changed, suffix)
            if (fits(cand)) return cand
        }
        val lastToken = FocusedCorrectionParts("", "", "", tokens[anchorEnd - 1], "")
        if (fits(lastToken)) return lastToken
        val ellipsisOnly = FocusedCorrectionParts("", "", "", ELLIPSIS, "")
        return if (fits(ellipsisOnly)) ellipsisOnly else FocusedCorrectionParts("", "", "", "", "")
    }

    private fun buildCandidate(
        a: List<String>,
        b: List<String>,
        hunk: Hunk,
        contextChars: Int,
        maxChangedChars: Int,
        arrow: String
    ): FocusedCorrectionParts {
        val origWin = buildSideWindow(a, hunk.aStart, hunk.aEnd, contextChars, maxChangedChars)
        val replWin = buildSideWindow(b, hunk.bStart, hunk.bEnd, contextChars, maxChangedChars)
        return FocusedCorrectionParts(
            original = origWin.joined,
            arrow = arrow,
            before = replWin.before,
            changed = replWin.changed,
            after = replWin.after
        )
    }

    private fun buildSideWindow(
        tokens: List<String>,
        start: Int,
        end: Int,
        contextChars: Int,
        maxChangedChars: Int
    ): SideWindow {
        val changed = tokens.subList(start, end)
        var right = (end + contextChars).coerceAtMost(tokens.size)
        if (right == tokens.size - 1) right = tokens.size
        val after = tokens.subList(end, right).joinToString("") +
            if (right < tokens.size) ELLIPSIS else ""
        if (changed.size > maxChangedChars) {
            val trimmed = changed.subList(changed.size - maxChangedChars, changed.size)
            return SideWindow(
                before = ELLIPSIS,
                changed = trimmed.joinToString(""),
                after = after
            )
        }
        var left = (start - contextChars).coerceAtLeast(0)
        if (left == 1) left = 0
        val before = (if (left > 0) ELLIPSIS else "") + tokens.subList(left, start).joinToString("")
        return SideWindow(
            before = before,
            changed = changed.joinToString(""),
            after = after
        )
    }

    internal fun extractFocusedHunk(a: List<String>, b: List<String>): Hunk? {
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) {
            prefix++
        }
        var suffix = 0
        while (
            suffix < a.size - prefix &&
            suffix < b.size - prefix &&
            a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
        ) {
            suffix++
        }
        if (prefix + suffix >= a.size && prefix + suffix >= b.size) {
            return null
        }

        val hunks = extractDiffHunks(a, b, prefix, suffix)
        if (hunks.size == 1) return hunks[0]

        val first = hunks.first()
        val last = hunks.last()
        if (
            last.aEnd - first.aStart <= MAX_COMBINED_SPAN &&
            last.bEnd - first.bStart <= MAX_COMBINED_SPAN
        ) {
            return Hunk(first.aStart, last.aEnd, first.bStart, last.bEnd)
        }

        for (i in hunks.indices.reversed()) {
            val hunk = hunks[i]
            val changedSource = a.subList(hunk.aStart, hunk.aEnd)
            if (changedSource.any { token -> token.any { ch -> ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' } }) {
                return hunk
            }
        }
        return last
    }

    private fun extractDiffHunks(
        a: List<String>,
        b: List<String>,
        prefix: Int,
        suffix: Int
    ): List<Hunk> {
        val aMid = a.subList(prefix, a.size - suffix)
        val bMid = b.subList(prefix, b.size - suffix)
        val matches = computeLcsMatches(aMid, bMid)
        if (matches.isNullOrEmpty()) {
            return listOf(Hunk(prefix, a.size - suffix, prefix, b.size - suffix))
        }

        val raw = ArrayList<Hunk>()
        var curA = 0
        var curB = 0
        for ((mA, mB) in matches + (aMid.size to bMid.size)) {
            if (mA > curA || mB > curB) {
                raw.add(Hunk(prefix + curA, prefix + mA, prefix + curB, prefix + mB))
            }
            curA = mA + 1
            curB = mB + 1
        }
        if (raw.isEmpty()) {
            return listOf(Hunk(prefix, a.size - suffix, prefix, b.size - suffix))
        }

        val merged = ArrayList<Hunk>(raw.size)
        merged.add(raw[0])
        for (i in 1 until raw.size) {
            val prev = merged.last()
            val next = raw[i]
            val gapA = next.aStart - prev.aEnd
            val gapB = next.bStart - prev.bEnd
            val combinedALen = next.aEnd - prev.aStart
            val combinedBLen = next.bEnd - prev.bStart
            if (
                gapA == gapB &&
                gapA <= MAX_MERGE_GAP &&
                combinedALen <= MAX_MERGED_HUNK_LEN &&
                combinedBLen <= MAX_MERGED_HUNK_LEN
            ) {
                merged[merged.lastIndex] = Hunk(prev.aStart, next.aEnd, prev.bStart, next.bEnd)
            } else {
                merged.add(next)
            }
        }
        return merged
    }

    private fun computeLcsMatches(aMid: List<String>, bMid: List<String>): List<Pair<Int, Int>>? {
        val m = aMid.size
        val n = bMid.size
        if (m == 0 || n == 0 || m.toLong() * n.toLong() > MAX_DP_CELLS) {
            return null
        }
        val stride = n + 1
        val dp = IntArray((m + 1) * stride)
        for (i in (m - 1) downTo 0) {
            val row = i * stride
            val nextRow = (i + 1) * stride
            for (j in (n - 1) downTo 0) {
                dp[row + j] = if (aMid[i] == bMid[j]) {
                    1 + dp[nextRow + j + 1]
                } else {
                    maxOf(dp[nextRow + j], dp[row + j + 1])
                }
            }
        }
        val matches = ArrayList<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < m && j < n) {
            when {
                aMid[i] == bMid[j] -> {
                    matches.add(i to j)
                    i++
                    j++
                }
                dp[(i + 1) * stride + j] >= dp[i * stride + j + 1] -> i++
                else -> j++
            }
        }
        return matches
    }

    private fun graphemes(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val out = ArrayList<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            out.add(text.substring(start, end))
            start = end
            end = iterator.next()
        }
        return out
    }
}
