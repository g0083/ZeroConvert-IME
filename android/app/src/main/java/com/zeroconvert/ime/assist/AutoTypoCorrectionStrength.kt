package com.zeroconvert.ime.assist

import android.content.SharedPreferences

enum class AutoTypoCorrectionStrength(
    val storageValue: String,
    val label: String,
) {
    OFF("off", "オフ（提案のみ）"),
    CONSERVATIVE("conservative", "ひかえめ（精度重視）"),
    STANDARD("standard", "標準（推奨）"),
    AGGRESSIVE("aggressive", "強め（積極的に補正）");

    val isEnabled: Boolean
        get() = this != OFF

    companion object {
        fun fromStorageValue(value: String?): AutoTypoCorrectionStrength =
            when (value?.trim()?.lowercase()?.replace('-', '_')) {
                "off", "disabled", "none", "false" -> OFF
                "conservative", "low", "cautious", "plan_c" -> CONSERVATIVE
                "aggressive", "high", "strong", "plan_a" -> AGGRESSIVE
                else -> STANDARD
            }

        fun readFrom(
            prefs: SharedPreferences,
            strengthKey: String,
            legacyBoolKey: String,
        ): AutoTypoCorrectionStrength {
            if (prefs.contains(strengthKey)) {
                return fromStorageValue(prefs.getString(strengthKey, null))
            }
            if (prefs.contains(legacyBoolKey)) {
                return if (prefs.getBoolean(legacyBoolKey, true)) STANDARD else OFF
            }
            return STANDARD
        }
    }
}
