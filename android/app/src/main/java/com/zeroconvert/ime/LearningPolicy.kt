package com.zeroconvert.ime

enum class LearningMode(val label: String, val storageValue: String) {
    READ_WRITE("学習して履歴を使う", "enabled"),
    READ_ONLY("既存の履歴のみ使う", "read_only"),
    DISABLED("学習も履歴の使用もしない", "disabled");
    companion object {
        fun from(value: String?): LearningMode = when (value?.trim()?.lowercase(java.util.Locale.ROOT)?.replace('-', '_')) {
            "read_only", "readonly", "no_new_learning" -> READ_ONLY
            "disabled" -> DISABLED
            else -> READ_WRITE
        }
    }
}

object LearningPolicy {
    const val KEY = "conversion_learning_mode"
    @Volatile var mode: LearningMode = LearningMode.READ_WRITE
    @Volatile private var writesSuppressed = false
    val canRead: Boolean get() = mode != LearningMode.DISABLED
    val canWrite: Boolean get() = !writesSuppressed && mode == LearningMode.READ_WRITE

    // Device checks must not change persisted preferences or teach their synthetic input.
    internal fun <T> withoutWrites(action: () -> T): T {
        val previous = writesSuppressed
        writesSuppressed = true
        return try { action() } finally { writesSuppressed = previous }
    }
}
