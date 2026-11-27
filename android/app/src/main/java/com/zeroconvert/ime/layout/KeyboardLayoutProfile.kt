package com.zeroconvert.ime.layout

import android.content.SharedPreferences

/**
 * Keyboard layout profiles selectable independently for 12-key (Flick) and QWERTY modes.
 */
enum class KeyboardLayoutProfile(
    val id: String,
    val label: String,
    val summary: String
) {
    ZEROCON(
        id = "zerocon",
        label = "ZeroCon標準",
        summary = "左に戻す・あ/a、右に矢印・確定"
    ),
    IOS(
        id = "ios",
        label = "iOS標準風",
        summary = "左に123・戻す・ABC・言語を配置"
    ),
    GBOARD(
        id = "gboard",
        label = "Gboard風",
        summary = "左に戻す・矢印・記号/絵文字・あa1"
    ),
    SIMEJI(
        id = "simeji",
        label = "Simeji風",
        summary = "左に記号・矢印・絵文字・あAを配置"
    );

    companion object {
        fun fromId(id: String?): KeyboardLayoutProfile =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: ZEROCON
    }
}

object LayoutProfilePreferences {
    const val KEY_LAYOUT_PROFILE_FLICK = "layout_profile_flick"
    const val KEY_LAYOUT_PROFILE_QWERTY = "layout_profile_qwerty"

    fun readFlickProfile(prefs: SharedPreferences): KeyboardLayoutProfile =
        KeyboardLayoutProfile.fromId(prefs.getString(KEY_LAYOUT_PROFILE_FLICK, KeyboardLayoutProfile.ZEROCON.id))

    fun readQwertyProfile(prefs: SharedPreferences): KeyboardLayoutProfile =
        KeyboardLayoutProfile.fromId(prefs.getString(KEY_LAYOUT_PROFILE_QWERTY, KeyboardLayoutProfile.ZEROCON.id))
}
