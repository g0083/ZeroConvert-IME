package com.zeroconvert.ime

import android.content.SharedPreferences

enum class AutoCommitMode(
    val storageValue: String,
    val label: String,
) {
    OFF("off", "オフ"),
    STANDARD("standard", "通常（推奨）"),
    FAST("fast", "早め（速度重視）");

    val isEnabled: Boolean
        get() = this != OFF

    companion object {
        fun fromStorageValue(value: String?): AutoCommitMode =
            when (value?.trim()?.lowercase()?.replace('-', '_')) {
                "off", "disabled", "none", "false" -> OFF
                "fast", "early", "aggressive", "quick" -> FAST
                else -> STANDARD
            }

        fun readFrom(
            prefs: SharedPreferences,
            modeKey: String,
            legacyBoolKey: String,
        ): AutoCommitMode {
            if (prefs.contains(modeKey)) {
                return fromStorageValue(prefs.getString(modeKey, null))
            }
            if (prefs.contains(legacyBoolKey)) {
                return if (prefs.getBoolean(legacyBoolKey, true)) STANDARD else OFF
            }
            return STANDARD
        }
    }
}
