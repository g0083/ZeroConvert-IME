package com.zeroconvert.ime.layout

import com.zeroconvert.ime.KeyboardMode

/**
 * 12-key (Flick) layout presets across 4 profiles x 3 modes (Japanese / English / Numeric),
 * aligned with real-world ZeroCon / Gboard / iOS / Simeji side-column layouts.
 */
object FlickLayoutPresets {

    fun specFor(
        profile: KeyboardLayoutProfile,
        mode: KeyboardMode,
        keepModeSwitchAtBottomLeft: Boolean = false
    ): FlickGridSpec {
        val sideColumns = when (profile) {
            KeyboardLayoutProfile.ZEROCON -> zeroConSides(mode)
            KeyboardLayoutProfile.IOS -> iosSides(mode, keepModeSwitchAtBottomLeft)
            KeyboardLayoutProfile.GBOARD -> gboardSides()
            KeyboardLayoutProfile.SIMEJI -> simejiSides()
        }
        return FlickGridSpec(
            profile = profile,
            mode = mode,
            sideColumns = sideColumns
        )
    }

    // ZeroCon standard:
    // Left:  [Undo] [Left] [Jp/En (or !?# in numeric)] [Aa1]
    // Right: [BS]   [Right] [Space]                    [Enter]
    private fun zeroConSides(mode: KeyboardMode): FlickSideColumnsSpec {
        val leftRow2 = if (mode == KeyboardMode.NUMERIC) {
            KeySlotSpec("flick_l2_symbols", KeyAction.SwitchToSymbols, "!?#", role = KeyVisualRole.MODIFIER)
        } else {
            KeySlotSpec("flick_l2_jpen", KeyAction.ToggleJpEn, "あ/a", role = KeyVisualRole.MODIFIER)
        }
        return FlickSideColumnsSpec(
            left = listOf(
                KeySlotSpec("flick_l0_undo", KeyAction.Undo, "元に戻す", role = KeyVisualRole.MODIFIER),
                KeySlotSpec("flick_l1_left", KeyAction.CursorLeft, "◀", role = KeyVisualRole.MODIFIER, supportsRepeat = true),
                leftRow2,
                KeySlotSpec("flick_l3_aa1", KeyAction.ModeCycleAa1, "あa1", role = KeyVisualRole.MODIFIER)
            ),
            right = listOf(
                KeySlotSpec("flick_r0_bs", KeyAction.Backspace, "削除", role = KeyVisualRole.MODIFIER, supportsRepeat = true),
                KeySlotSpec("flick_r1_right", KeyAction.CursorRight, "▶", role = KeyVisualRole.MODIFIER, supportsRepeat = true),
                KeySlotSpec("flick_r2_space", KeyAction.Space, "空白", role = KeyVisualRole.SPACE),
                KeySlotSpec("flick_r3_enter", KeyAction.Enter, "確定", role = KeyVisualRole.ACCENT)
            )
        )
    }

    // iOS Japanese Kana 12-key:
    // Left:  [123] [Undo] [ABC/あいう] [Globe]
    // When opened from QWERTY (keepModeSwitchAtBottomLeft = true), keep ModeCycleAa1 at left[3]
    // so repeated taps on bottom-left cycle cleanly back to Japanese without jumping rows.
    // Right: [BS]  [Right] [Space]     [Return]
    private fun iosSides(
        mode: KeyboardMode,
        keepModeSwitchAtBottomLeft: Boolean
    ): FlickSideColumnsSpec {
        val modeSwitchSlot = KeySlotSpec(
            id = if (keepModeSwitchAtBottomLeft) "flick_l3_aa1" else "flick_l2_aa1",
            action = KeyAction.ModeCycleAa1,
            label = when (mode) {
                KeyboardMode.JAPANESE -> "ABC"
                KeyboardMode.ENGLISH -> "123"
                KeyboardMode.NUMERIC -> "あいう"
            },
            role = KeyVisualRole.MODIFIER
        )
        val globeSlot = KeySlotSpec(
            id = if (keepModeSwitchAtBottomLeft) "flick_l2_globe" else "flick_l3_globe",
            action = KeyAction.GlobeOrImePicker,
            label = "言語",
            role = KeyVisualRole.MODIFIER
        )
        return FlickSideColumnsSpec(
            left = listOf(
                KeySlotSpec("flick_l0_symbols", KeyAction.SwitchToSymbols, "123", role = KeyVisualRole.MODIFIER),
                KeySlotSpec("flick_l1_undo", KeyAction.Undo, "元に戻す", role = KeyVisualRole.MODIFIER),
                if (keepModeSwitchAtBottomLeft) globeSlot else modeSwitchSlot,
                if (keepModeSwitchAtBottomLeft) modeSwitchSlot else globeSlot
            ),
            right = listOf(
                KeySlotSpec("flick_r0_bs", KeyAction.Backspace, "削除", role = KeyVisualRole.MODIFIER, supportsRepeat = true),
                KeySlotSpec("flick_r1_right", KeyAction.CursorRight, "▶", role = KeyVisualRole.MODIFIER, supportsRepeat = true),
                KeySlotSpec("flick_r2_space", KeyAction.Space, "空白", role = KeyVisualRole.SPACE),
                KeySlotSpec("flick_r3_enter", KeyAction.Enter, "改行", role = KeyVisualRole.ACCENT)
            )
        )
    }

    // Gboard (Android) Japanese 12-key:
    // Left:  [Undo] [Left] [Symbols/Emoji icon+記] [Aa1]
    // Right: [BS]   [Right] [Space]                [Enter]
    private fun gboardSides(): FlickSideColumnsSpec {
        return FlickSideColumnsSpec(
            left = listOf(
                KeySlotSpec("flick_l0_undo", KeyAction.Undo, "元に戻す", role = KeyVisualRole.MODIFIER),
                KeySlotSpec("flick_l1_left", KeyAction.CursorLeft, "◀", role = KeyVisualRole.MODIFIER, supportsRepeat = true),
                KeySlotSpec("flick_l2_emoji_sym", KeyAction.SwitchToSymbols, "記", role = KeyVisualRole.MODIFIER),
                KeySlotSpec("flick_l3_aa1", KeyAction.ModeCycleAa1, "あa1", role = KeyVisualRole.MODIFIER)
            ),
            right = listOf(
                KeySlotSpec("flick_r0_bs", KeyAction.Backspace, "削除", role = KeyVisualRole.MODIFIER, supportsRepeat = true),
                KeySlotSpec("flick_r1_right", KeyAction.CursorRight, "▶", role = KeyVisualRole.MODIFIER, supportsRepeat = true),
                KeySlotSpec("flick_r2_space", KeyAction.Space, "空白", role = KeyVisualRole.SPACE),
                KeySlotSpec("flick_r3_enter", KeyAction.Enter, "確定", role = KeyVisualRole.ACCENT)
            )
        )
    }

    // Simeji (Android) Japanese 12-key:
    // Left:  [記号] [Left] [Emoji] [あA]
    // Right: [BS]   [Right] [Space] [Enter]
    private fun simejiSides(): FlickSideColumnsSpec {
        return FlickSideColumnsSpec(
            left = listOf(
                KeySlotSpec("flick_l0_symbols", KeyAction.SwitchToSymbols, "記号", role = KeyVisualRole.MODIFIER),
                KeySlotSpec("flick_l1_left", KeyAction.CursorLeft, "◀", role = KeyVisualRole.MODIFIER, supportsRepeat = true),
                KeySlotSpec("flick_l2_emoji", KeyAction.SwitchToEmoji, "絵文字", role = KeyVisualRole.MODIFIER),
                KeySlotSpec("flick_l3_aa1", KeyAction.ModeCycleAa1, "あA", role = KeyVisualRole.MODIFIER)
            ),
            right = listOf(
                KeySlotSpec("flick_r0_bs", KeyAction.Backspace, "削除", role = KeyVisualRole.MODIFIER, supportsRepeat = true),
                KeySlotSpec("flick_r1_right", KeyAction.CursorRight, "▶", role = KeyVisualRole.MODIFIER, supportsRepeat = true),
                KeySlotSpec("flick_r2_space", KeyAction.Space, "空白", role = KeyVisualRole.SPACE),
                KeySlotSpec("flick_r3_enter", KeyAction.Enter, "確定", role = KeyVisualRole.ACCENT)
            )
        )
    }
}
