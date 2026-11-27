package com.zeroconvert.ime.layout

/**
 * QWERTY layout presets across 4 profiles x 2 modes (Japanese / English),
 * aligned with real-world ZeroCon / Gboard / iOS / Simeji layouts.
 */
object QwertyLayoutPresets {
    private val ROW0_NORMAL = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0')
    private val ROW0_SHIFT = listOf('!', '@', '#', '$', '%', '^', '&', '*', '(', ')')
    private val ROW1 = listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p')
    private val ROW2_EN = listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l')
    private val ROW2_JP_10 = listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', 'ー')
    private val ROW3 = listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')

    fun specFor(profile: KeyboardLayoutProfile, isJapanese: Boolean): QwertyGridSpec {
        return when (profile) {
            KeyboardLayoutProfile.ZEROCON -> zeroConQwerty(isJapanese)
            KeyboardLayoutProfile.IOS -> iosQwerty(isJapanese)
            KeyboardLayoutProfile.GBOARD -> gboardQwerty(isJapanese)
            KeyboardLayoutProfile.SIMEJI -> simejiQwerty(isJapanese)
        }
    }

    private fun zeroConQwerty(isJapanese: Boolean): QwertyGridSpec {
        val bottomLeft = buildList {
            add(KeySlotSpec("r4_aa1", KeyAction.ModeCycleAa1, "あa1", weight = 1.35f, role = KeyVisualRole.MODIFIER))
            if (isJapanese) {
                add(KeySlotSpec("r4_comma_jp", KeyAction.CommitChar('、', '、'), "、", weight = 0.85f, role = KeyVisualRole.MODIFIER))
                add(KeySlotSpec("r4_question", KeyAction.CommitChar('？', '？'), "？", weight = 0.85f, role = KeyVisualRole.MODIFIER))
            } else {
                add(KeySlotSpec("r4_question", KeyAction.CommitChar('?', '?'), "?", weight = 0.85f, role = KeyVisualRole.MODIFIER))
                add(KeySlotSpec("r4_comma_en", KeyAction.CommitChar(',', ','), ",", weight = 0.85f, role = KeyVisualRole.MODIFIER))
            }
        }
        val period = if (isJapanese) '。' else '.'
        val bottomRight = listOf(
            KeySlotSpec("r4_period", KeyAction.CommitChar(period, period), period.toString(), weight = 0.85f, role = KeyVisualRole.MODIFIER),
            KeySlotSpec("r4_left", KeyAction.CursorLeft, "◀", weight = 0.85f, role = KeyVisualRole.MODIFIER, supportsRepeat = true),
            KeySlotSpec("r4_right", KeyAction.CursorRight, "▶", weight = 0.85f, role = KeyVisualRole.MODIFIER, supportsRepeat = true),
            KeySlotSpec("r4_enter", KeyAction.Enter, "Enter", weight = 1.35f, role = KeyVisualRole.ACCENT)
        )
        return QwertyGridSpec(
            profile = KeyboardLayoutProfile.ZEROCON,
            isJapanese = isJapanese,
            numberRowNormal = ROW0_NORMAL,
            numberRowShift = ROW0_SHIFT,
            row1 = ROW1,
            row2 = if (isJapanese) ROW2_JP_10 else ROW2_EN,
            row2Staggered = !isJapanese,
            row3 = ROW3,
            bottomLeft = bottomLeft,
            bottomRight = bottomRight,
            spaceLabel = if (isJapanese) "日本語" else "English",
            spaceWeight = 2.4f,
            splitSpaceWeight = 1.2f
        )
    }

    // iOS Romaji / English QWERTY:
    // Row 2: 9-key staggered (a..l)
    // Row 4: [123] [言語] [絵文字] [空白 / space] [。 / .] [改行 / return]
    private fun iosQwerty(isJapanese: Boolean): QwertyGridSpec {
        val bottomLeft = listOf(
            KeySlotSpec("r4_aa1", KeyAction.ModeCycleAa1, "123", weight = 1.25f, role = KeyVisualRole.MODIFIER),
            KeySlotSpec("r4_globe", KeyAction.GlobeOrImePicker, "言語", weight = 0.95f, role = KeyVisualRole.MODIFIER),
            KeySlotSpec("r4_emoji", KeyAction.SwitchToEmoji, "絵文字", weight = 0.9f, role = KeyVisualRole.MODIFIER)
        )
        val period = if (isJapanese) '。' else '.'
        val bottomRight = listOf(
            KeySlotSpec("r4_period", KeyAction.CommitChar(period, period), period.toString(), weight = 0.85f, role = KeyVisualRole.MODIFIER),
            KeySlotSpec("r4_enter", KeyAction.Enter, if (isJapanese) "改行" else "return", weight = 1.75f, role = KeyVisualRole.ACCENT)
        )
        return QwertyGridSpec(
            profile = KeyboardLayoutProfile.IOS,
            isJapanese = isJapanese,
            numberRowNormal = ROW0_NORMAL,
            numberRowShift = ROW0_SHIFT,
            row1 = ROW1,
            row2 = ROW2_EN,
            row2Staggered = true,
            row3 = ROW3,
            bottomLeft = bottomLeft,
            bottomRight = bottomRight,
            spaceLabel = if (isJapanese) "空白" else "space",
            spaceWeight = 3.8f,
            splitSpaceWeight = 1.6f
        )
    }

    // Gboard (Android) Japanese QWERTY (verified from official manual):
    // Row 2 (JP): 10-key (a..l + ー), (EN): 9-key staggered
    // Row 4: [あa1] [、/,] [絵文字] [日本語 / English] [。/.] [◀] [▶] [Enter]
    private fun gboardQwerty(isJapanese: Boolean): QwertyGridSpec {
        val comma = if (isJapanese) '、' else ','
        val period = if (isJapanese) '。' else '.'
        val bottomLeft = listOf(
            KeySlotSpec("r4_aa1", KeyAction.ModeCycleAa1, "あa1", weight = 1.35f, role = KeyVisualRole.MODIFIER),
            KeySlotSpec("r4_comma", KeyAction.CommitChar(comma, comma), comma.toString(), weight = 0.85f, role = KeyVisualRole.MODIFIER),
            KeySlotSpec("r4_emoji", KeyAction.SwitchToEmoji, "絵文字", weight = 0.85f, role = KeyVisualRole.MODIFIER)
        )
        val bottomRight = listOf(
            KeySlotSpec("r4_period", KeyAction.CommitChar(period, period), period.toString(), weight = 0.85f, role = KeyVisualRole.MODIFIER),
            KeySlotSpec("r4_left", KeyAction.CursorLeft, "◀", weight = 0.85f, role = KeyVisualRole.MODIFIER, supportsRepeat = true),
            KeySlotSpec("r4_right", KeyAction.CursorRight, "▶", weight = 0.85f, role = KeyVisualRole.MODIFIER, supportsRepeat = true),
            KeySlotSpec("r4_enter", KeyAction.Enter, "Enter", weight = 1.35f, role = KeyVisualRole.ACCENT)
        )
        return QwertyGridSpec(
            profile = KeyboardLayoutProfile.GBOARD,
            isJapanese = isJapanese,
            numberRowNormal = ROW0_NORMAL,
            numberRowShift = ROW0_SHIFT,
            row1 = ROW1,
            row2 = if (isJapanese) ROW2_JP_10 else ROW2_EN,
            row2Staggered = !isJapanese,
            row3 = ROW3,
            bottomLeft = bottomLeft,
            bottomRight = bottomRight,
            spaceLabel = if (isJapanese) "日本語" else "English",
            spaceWeight = 2.4f,
            splitSpaceWeight = 1.2f
        )
    }

    // Simeji (Android) Japanese QWERTY:
    // Row 2 (JP): 10-key (a..l + ー), (EN): 9-key staggered
    // Row 4: [あA] [記号] [、/,] [空白 / Space] [。/.] [◀] [▶] [Enter]
    private fun simejiQwerty(isJapanese: Boolean): QwertyGridSpec {
        val comma = if (isJapanese) '、' else ','
        val period = if (isJapanese) '。' else '.'
        val bottomLeft = listOf(
            KeySlotSpec("r4_aa1", KeyAction.ModeCycleAa1, "あA", weight = 1.25f, role = KeyVisualRole.MODIFIER),
            KeySlotSpec("r4_symbols", KeyAction.SwitchToSymbols, "記号", weight = 0.9f, role = KeyVisualRole.MODIFIER),
            KeySlotSpec("r4_comma", KeyAction.CommitChar(comma, comma), comma.toString(), weight = 0.8f, role = KeyVisualRole.MODIFIER)
        )
        val bottomRight = listOf(
            KeySlotSpec("r4_period", KeyAction.CommitChar(period, period), period.toString(), weight = 0.8f, role = KeyVisualRole.MODIFIER),
            KeySlotSpec("r4_left", KeyAction.CursorLeft, "◀", weight = 0.8f, role = KeyVisualRole.MODIFIER, supportsRepeat = true),
            KeySlotSpec("r4_right", KeyAction.CursorRight, "▶", weight = 0.8f, role = KeyVisualRole.MODIFIER, supportsRepeat = true),
            KeySlotSpec("r4_enter", KeyAction.Enter, "Enter", weight = 1.35f, role = KeyVisualRole.ACCENT)
        )
        return QwertyGridSpec(
            profile = KeyboardLayoutProfile.SIMEJI,
            isJapanese = isJapanese,
            numberRowNormal = ROW0_NORMAL,
            numberRowShift = ROW0_SHIFT,
            row1 = ROW1,
            row2 = if (isJapanese) ROW2_JP_10 else ROW2_EN,
            row2Staggered = !isJapanese,
            row3 = ROW3,
            bottomLeft = bottomLeft,
            bottomRight = bottomRight,
            spaceLabel = if (isJapanese) "空白" else "Space",
            spaceWeight = 2.4f,
            splitSpaceWeight = 1.2f
        )
    }
}
