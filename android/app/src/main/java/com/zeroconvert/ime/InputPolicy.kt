package com.zeroconvert.ime

/**
 * Input policy shared with the Windows host.
 *
 * The canonical definitions live in
 * `windows/crates/shared/src/conversion_policy.rs`, which is compiled into the
 * shared engine and published by the `inputPolicy` operation. That payload is
 * applied once during [com.zeroconvert.ime.engine.WindowsEngine.initialize], so
 * no JNI call is added to the key path.
 *
 * The tables below are the boot fallback for JVM tests and for a native load
 * failure. They must stay identical to the Rust source; both are verified
 * against `tests/input-policy/cases.tsv`, so a drift fails a test rather than
 * silently changing input behaviour.
 */
object InputPolicy {
    const val VERSION = 1

    private val DEFAULT_IMMEDIATE_COMMIT = "!@#$%^&*()"

    private val DEFAULT_PUNCTUATION: Map<Char, Char> = mapOf(
        '、' to '、',
        '。' to '。',
        '，' to '，',
        '．' to '．',
        '？' to '？',
        '！' to '！',
        ',' to '、',
        '.' to '。',
        '?' to '？',
        '!' to '！'
    )

    private val DEFAULT_SYMBOL_FULLWIDTH: Map<String, Boolean> = mapOf(
        "0" to false, "1" to false, "2" to false, "3" to false, "4" to false,
        "5" to false, "6" to false, "7" to false, "8" to false, "9" to false,
        "!" to true, "\"" to true, "#" to false, "$" to false, "%" to false,
        "&" to false, "'" to true, "(" to true, ")" to true, "*" to true,
        "+" to true, "," to true, "-" to true, "." to true, "/" to true,
        ":" to true, ";" to true, "<" to true, "=" to true, ">" to true,
        "?" to true, "@" to false, "[" to true, "\\" to false, "]" to true,
        "^" to false, "_" to false, "`" to false, "{" to true, "|" to false,
        "}" to true, "~" to true
    )

    @Volatile private var immediateCommitChars: String = DEFAULT_IMMEDIATE_COMMIT
    @Volatile private var punctuation: Map<Char, Char> = DEFAULT_PUNCTUATION
    @Volatile private var symbolFullwidth: Map<String, Boolean> = DEFAULT_SYMBOL_FULLWIDTH

    /** True once the shared engine has supplied the tables for this process. */
    @Volatile var fromSharedEngine: Boolean = false
        private set

    /**
     * Applies the policy published by the shared engine.
     *
     * A missing or unknown version is rejected so a newer engine cannot change
     * behaviour this build does not understand; the fallback tables stay active.
     * Empty tables are rejected too: a truncated payload must not disable input.
     */
    fun apply(
        version: Int,
        immediateCommit: String?,
        readingPunctuation: List<Pair<Char, Char>>,
        defaultFullwidth: List<Pair<String, Boolean>>
    ): Boolean {
        if (version != VERSION) return false
        if (immediateCommit.isNullOrEmpty()) return false
        if (readingPunctuation.isEmpty() || defaultFullwidth.isEmpty()) return false

        immediateCommitChars = immediateCommit
        punctuation = readingPunctuation.toMap()
        symbolFullwidth = defaultFullwidth.toMap()
        fromSharedEngine = true
        return true
    }

    /** Restores the compiled fallback tables. Used by tests. */
    internal fun reset() {
        immediateCommitChars = DEFAULT_IMMEDIATE_COMMIT
        punctuation = DEFAULT_PUNCTUATION
        symbolFullwidth = DEFAULT_SYMBOL_FULLWIDTH
        fromSharedEngine = false
    }

    /** Shifted symbols that end the Android composition immediately. */
    fun isImmediateCommitChar(ch: Char): Boolean = immediateCommitChars.indexOf(ch) >= 0

    /** Punctuation normalized to the mark the Japanese reading stores. */
    fun punctuationForReading(ch: Char): Char? = punctuation[ch]

    fun shouldComposePunctuation(ch: Char, pendingRomaji: Boolean): Boolean =
        !pendingRomaji && punctuationForReading(ch) != null

    /** Windows `UserAction::Number` appends ASCII digits to the reading. */
    fun isCompositionDigit(ch: Char): Boolean = ch in '0'..'9'

    /** Translation mode reflects digits and punctuation into the reading literally. */
    fun isTranslationLiteralChar(ch: Char): Boolean =
        isImmediateCommitChar(ch) || isCompositionDigit(ch) || ch in '０'..'９' ||
            punctuationForReading(ch) != null

    /** Default fullwidth state of a convertible symbol, or null when unknown. */
    fun symbolDefaultFullwidth(symbol: String): Boolean? = symbolFullwidth[symbol]
}
