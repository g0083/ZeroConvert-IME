package com.zeroconvert.ime

import android.net.Uri
import java.util.concurrent.TimeUnit

data class ClipboardEntry(
    val id: Long = System.nanoTime(),
    val text: String? = null,
    val imageUri: Uri? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val entityType: String? = null
) {
    val isImage: Boolean get() = imageUri != null
    val preview: String get() = when {
        text != null && text.isNotBlank() -> text.replace("\n", " ").take(40)
        imageUri != null -> "画像"
        else -> ""
    }

    fun isExpired(retentionHours: Int): Boolean {
        if (pinned) return false
        val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(retentionHours.toLong())
        return timestamp < cutoff
    }
}
