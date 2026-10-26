package com.zeroconvert.ime

data class DictionarySegment(
    val reading: String, val surface: String, val lcid: Int, val rcid: Int, val mid: Int, val cost: Float,
    val correctionKind: Int = 0, val originalStart: Int = -1, val originalEnd: Int = -1,
    val lexicalReading: String = reading
)

internal object DictionarySegments {
    private fun decode(hex: String): String {
        require(hex.length in 2..8192 && hex.length % 2 == 0)
        val bytes = ByteArray(hex.length / 2) { index ->
            val first = hex[index * 2].digitToIntOrNull(16) ?: error("Invalid segment encoding")
            val second = hex[index * 2 + 1].digitToIntOrNull(16) ?: error("Invalid segment encoding")
            ((first shl 4) or second).toByte()
        }
        val value = bytes.toString(Charsets.UTF_8)
        require(value.toByteArray(Charsets.UTF_8).contentEquals(bytes))
        require(value.none { it.isISOControl() || it in '\uEE00'..'\uEE0F' })
        return value
    }

    fun parse(encoded: String, reading: String, surface: String): List<DictionarySegment> = try {
        require(encoded.length <= 65536)
        val records = encoded.split('|')
        require(records.size in 1..512)
        var offset = 0
        val segments = records.map { record ->
            val parts = record.split(':')
            require(parts.size == 6 || parts.size == 10)
            val cost = parts[5].toFloat()
            require(cost.isFinite())
            val ruby = decode(parts[0])
            val end = offset + ruby.codePointCount(0, ruby.length)
            val kind = if (parts.size == 10) parts[6].toInt() else 0
            require(kind in 0..3)
            val startOffset = if (parts.size == 10) parts[7].toInt() else -1
            val endOffset = if (parts.size == 10) parts[8].toInt() else -1
            if (kind != 0) require(startOffset == offset && endOffset == end)
            offset = end
            DictionarySegment(ruby, decode(parts[1]), parts[2].toInt(), parts[3].toInt(), parts[4].toInt(), cost,
                kind, startOffset, endOffset, if (parts.size == 10) decode(parts[9]) else ruby)
        }
        require(segments.joinToString("") { it.reading } == reading && segments.joinToString("") { it.surface } == surface)
        segments
    } catch (_: Exception) { emptyList() }
}
