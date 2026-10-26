package com.zeroconvert.ime.assist

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class MacroCandidate(val trigger: String, val expansion: String, val kind: String)

object DynamicMacro {
    fun currentExpansions(input: String): List<MacroCandidate> {
        val now = LocalDate.now()
        val time = LocalTime.now()
        val list = mutableListOf<MacroCandidate>()
        if (input.contains("きょう") || input.contains("今日")) {
            list.add(MacroCandidate("きょう", now.format(DateTimeFormatter.ofPattern("yyyy年M月d日")), "date"))
        }
        if (input.contains("いま") || input.contains("今")) {
            list.add(MacroCandidate("いま", time.format(DateTimeFormatter.ofPattern("H時m分")), "time"))
        }
        // simple arithmetic: detect "1+2" style
        val mathRegex = Regex("(\\d+)\\s*([+\\-*/])\\s*(\\d+)")
        mathRegex.find(input)?.let { m ->
            val a = m.groupValues[1].toDouble()
            val op = m.groupValues[2]
            val b = m.groupValues[3].toDouble()
            val result = when (op) {
                "+" -> a + b; "-" -> a - b; "*" -> a * b; "/" -> if (b != 0.0) a / b else null
                else -> null
            }
            if (result != null) list.add(MacroCandidate(m.value, result.toString(), "calc"))
        }
        return list
    }

    fun expandDynamicMacros(input: String): List<MacroCandidate> = currentExpansions(input)
}
