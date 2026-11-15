package com.zeroconvert.ime.assist

internal data class MaskedNamText(val text: String, val digits: List<String>)

internal object NamMasking {
    private fun normalizeAsciiDigit(ch: Char): Char? = when (ch) {
        in '0'..'9' -> ch
        in '０'..'９' -> ('0'.code + (ch.code - '０'.code)).toChar()
        else -> null
    }

    fun mask(text: String): MaskedNamText {
        var hasDigits = false
        for (ch in text) {
            if (normalizeAsciiDigit(ch) != null) {
                hasDigits = true
                break
            }
        }
        if (!hasDigits) return MaskedNamText(text, emptyList())
        val masked = StringBuilder(text.length)
        val digits = ArrayList<String>()
        val current = StringBuilder()
        for (ch in text) {
            val ascii = normalizeAsciiDigit(ch)
            if (ascii != null) {
                current.append(ascii)
            } else {
                if (current.isNotEmpty()) {
                    digits.add(current.toString())
                    current.setLength(0)
                    masked.append("nam")
                }
                masked.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            digits.add(current.toString())
            masked.append("nam")
        }
        return MaskedNamText(masked.toString(), digits)
    }

    fun restore(surface: String, digits: List<String>): String {
        if (digits.isEmpty() || !surface.contains("nam")) return surface
        fun replacePass(input: String, startIdx: Int, fallback: Boolean): Pair<String, Int> {
            val out = StringBuilder(input.length)
            var cursor = 0
            var digitIdx = startIdx
            while (digitIdx < digits.size) {
                val pos = input.indexOf("nam", cursor)
                if (pos < 0) break
                out.append(input, cursor, pos)
                val afterPos = pos + 3
                val keepLiteral = if (!fallback) {
                    afterPos < input.length && input[afterPos].let { it in 'a'..'w' || it in 'y'..'z' }
                } else {
                    val prevIsLower = out.isNotEmpty() && out[out.length - 1] in 'a'..'z'
                    val afterStartsE = afterPos < input.length && input[afterPos] == 'e'
                    val afterStartsIng = input.startsWith("ing", afterPos) &&
                        (afterPos + 3 >= input.length || input[afterPos + 3] !in 'a'..'z')
                    prevIsLower || afterStartsE || afterStartsIng
                }
                if (keepLiteral) {
                    out.append("nam")
                } else {
                    out.append(digits[digitIdx])
                    digitIdx++
                }
                cursor = afterPos
            }
            out.append(input, cursor, input.length)
            return out.toString() to digitIdx
        }
        val (firstPass, consumed) = replacePass(surface, 0, false)
        return if (consumed < digits.size && firstPass.contains("nam")) {
            replacePass(firstPass, consumed, true).first
        } else {
            firstPass
        }
    }
}
