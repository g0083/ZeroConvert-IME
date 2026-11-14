package com.zeroconvert.ime

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class TouchAssistSettingsTest {
    @Test fun legacyOffIsRetainedWhenHitAreaIsEnabledLater() {
        val preferences = Preferences(mapOf(TouchAssistSettings.KEY_HIT_AREA to false))
        assertEquals(TouchAssistSettings(false, false), TouchAssistSettings.load(preferences.instance))
        preferences.instance.edit().putBoolean(TouchAssistSettings.KEY_HIT_AREA, true).apply()
        assertEquals(TouchAssistSettings(true, false), TouchAssistSettings.load(preferences.instance))
    }

    @Test fun defaultCorrectionRemainsEnabledWhenHitAreaIsDisabledLater() {
        val preferences = Preferences(emptyMap())
        assertEquals(TouchAssistSettings(true, true), TouchAssistSettings.load(preferences.instance))
        preferences.instance.edit().putBoolean(TouchAssistSettings.KEY_HIT_AREA, false).apply()
        assertEquals(TouchAssistSettings(false, true), TouchAssistSettings.load(preferences.instance))
    }

    @Test fun allFourExplicitCombinationsSurviveReload() {
        for (hitArea in listOf(false, true)) {
            for (correction in listOf(false, true)) {
                val preferences = Preferences(mapOf(
                    TouchAssistSettings.KEY_HIT_AREA to hitArea,
                    TouchAssistSettings.KEY_CORRECTION to correction
                ))
                repeat(2) {
                    assertEquals(TouchAssistSettings(hitArea, correction),
                        TouchAssistSettings.load(preferences.instance))
                }
                preferences.instance.edit().putBoolean(TouchAssistSettings.KEY_CORRECTION, !correction).apply()
                assertEquals(TouchAssistSettings(hitArea, !correction),
                    TouchAssistSettings.load(preferences.instance))
            }
        }
    }

    /** Android interfaces backed by memory, including editor apply semantics. */
    private class Preferences(initial: Map<String, Boolean>) {
        private val values = initial.toMutableMap()
        private val pending = mutableMapOf<String, Boolean>()
        private val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, arguments ->
            when (method.name) {
                "putBoolean" -> {
                    pending[arguments!![0] as String] = arguments[1] as Boolean
                    proxy
                }
                "apply" -> {
                    values.putAll(pending)
                    pending.clear()
                    null
                }
                else -> error("Unexpected editor operation ${method.name}")
            }
        } as SharedPreferences.Editor

        val instance = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, arguments ->
            when (method.name) {
                "getBoolean" -> values[arguments!![0] as String] ?: arguments[1]
                "contains" -> values.containsKey(arguments!![0] as String)
                "edit" -> editor
                else -> error("Unexpected preference operation ${method.name}")
            }
        } as SharedPreferences
    }
}
