package com.zeroconvert.ime

import android.content.SharedPreferences

data class TouchAssistSettings(val hitAreaEnabled: Boolean, val correctionEnabled: Boolean) {
    companion object {
        // Keep the original key for predictive hit areas and migrate its former
        // correction behaviour once, before either independent switch is edited.
        const val KEY_HIT_AREA = "dynamic_touch_assist"
        const val KEY_CORRECTION = "touch_typo_correction"

        fun load(preferences: SharedPreferences): TouchAssistSettings {
            val hitArea = preferences.getBoolean(KEY_HIT_AREA, true)
            val correction = preferences.getBoolean(KEY_CORRECTION, hitArea)
            if (!preferences.contains(KEY_CORRECTION)) {
                preferences.edit().putBoolean(KEY_CORRECTION, correction).apply()
            }
            return TouchAssistSettings(hitArea, correction)
        }
    }
}
