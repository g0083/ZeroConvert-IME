package com.zeroconvert.ime

/**
 * Committed live-conversion prefix carried into the next conversion so the shared
 * engine can rebuild the full reading instead of converting only the tail.
 *
 * The shared Swift side re-validates these values before use and is authoritative
 * for the final acceptance decision; [create] only prevents obviously unusable
 * continuations from being sent.
 */
internal data class ConversionContinuation(val reading: String, val surface: String) {
    companion object {
        const val MAX_READING_CHARS = 64
        const val MAX_SURFACE_CHARS = 128

        /** Null when either side is missing, non-kana reading, or over the shared caps. */
        fun create(reading: String, surface: String): ConversionContinuation? {
            if (reading.isEmpty() || surface.isEmpty()) return null
            if (reading.codePointCount(0, reading.length) > MAX_READING_CHARS) return null
            if (surface.codePointCount(0, surface.length) > MAX_SURFACE_CHARS) return null
            if (!reading.all { it in '\u3041'..'\u3096' || it == '\u30FC' || it == '\u309D' || it == '\u309E' }) return null
            return ConversionContinuation(reading, surface)
        }

        /**
         * Continuation for a live auto-commit or a partial clause commit.
         *
         * [beforeReading] is the full reading before the committed prefix was removed,
         * [remainingReading] is what is still being composed, and [committedSurface] is
         * the surface that was just committed. Null unless the remaining reading is the
         * unchanged tail of the pre-commit reading.
         */
        fun derive(beforeReading: String, remainingReading: String, committedSurface: String): ConversionContinuation? {
            if (remainingReading.isEmpty() || committedSurface.isEmpty() || !beforeReading.endsWith(remainingReading)) return null
            return create(beforeReading.removeSuffix(remainingReading), committedSurface)
        }
    }
}
