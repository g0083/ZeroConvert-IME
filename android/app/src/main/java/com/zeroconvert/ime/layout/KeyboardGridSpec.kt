package com.zeroconvert.ime.layout

import com.zeroconvert.ime.FlickKeyDef
import com.zeroconvert.ime.KeyboardMode

sealed interface KeyAction {
    data class CommitChar(
        val normal: Char,
        val shifted: Char = normal,
        val upFlick: Char? = null,
        val shiftedUpFlick: Char? = null,
        val longPressChars: List<Char> = emptyList()
    ) : KeyAction

    data class FlickKey(
        val def: FlickKeyDef,
        val isRow3FirstSpecial: Boolean = false
    ) : KeyAction

    data object Backspace : KeyAction
    data object Space : KeyAction
    data object Enter : KeyAction
    data object Shift : KeyAction
    data object Undo : KeyAction
    data object CursorLeft : KeyAction
    data object CursorRight : KeyAction
    data object ModeCycleAa1 : KeyAction
    data object ToggleJpEn : KeyAction
    data object SwitchToSymbols : KeyAction
    data object SwitchToEmoji : KeyAction
    data object SwitchToClipboard : KeyAction
    data object GlobeOrImePicker : KeyAction
}

enum class KeyVisualRole {
    CHARACTER,
    MODIFIER,
    ACCENT,
    SPACE
}

data class KeySlotSpec(
    val id: String,
    val action: KeyAction,
    val label: String,
    val subLabel: String? = null,
    val weight: Float = 1f,
    val role: KeyVisualRole = KeyVisualRole.CHARACTER,
    val supportsRepeat: Boolean = false,
    val startSpacerWeight: Float = 0f,
    val endSpacerWeight: Float = 0f
)

data class KeyboardRowSpec(
    val id: String,
    val slots: List<KeySlotSpec>,
    val splitCutAfter: Int? = null,
    val duplicateMiddleKey: Boolean = false
)

data class KeyboardGridSpec(
    val id: String,
    val profile: KeyboardLayoutProfile,
    val isQwerty: Boolean,
    val isJapanese: Boolean,
    val rows: List<KeyboardRowSpec>
) {
    fun allSlots(): List<KeySlotSpec> = rows.flatMap { it.slots }
}

data class FlickSideColumnsSpec(
    val left: List<KeySlotSpec>,
    val right: List<KeySlotSpec>
) {
    init {
        require(left.size == 4) { "Flick left column must have 4 slots, got ${left.size}" }
        require(right.size == 4) { "Flick right column must have 4 slots, got ${right.size}" }
    }
}

data class FlickGridSpec(
    val profile: KeyboardLayoutProfile,
    val mode: KeyboardMode,
    val sideColumns: FlickSideColumnsSpec
) {
    fun toKeyboardGridSpec(centerRows: List<Triple<FlickKeyDef, FlickKeyDef, FlickKeyDef>>): KeyboardGridSpec {
        require(centerRows.size == 4) { "12-key flick grid requires 4 center rows" }
        val gridRows = (0 until 4).map { rowIndex ->
            val (c0, c1, c2) = centerRows[rowIndex]
            val leftSlot = sideColumns.left[rowIndex]
            val rightSlot = sideColumns.right[rowIndex]
            val centerSlots = listOf(
                KeySlotSpec(
                    id = "flick_r${rowIndex}_c0_${c0.center}",
                    action = KeyAction.FlickKey(c0, isRow3FirstSpecial = rowIndex == 3),
                    label = c0.label,
                    role = if (rowIndex == 3 && mode != KeyboardMode.NUMERIC) KeyVisualRole.MODIFIER else KeyVisualRole.CHARACTER
                ),
                KeySlotSpec(
                    id = "flick_r${rowIndex}_c1_${c1.center}",
                    action = KeyAction.FlickKey(c1),
                    label = c1.label
                ),
                KeySlotSpec(
                    id = "flick_r${rowIndex}_c2_${c2.center}",
                    action = KeyAction.FlickKey(c2),
                    label = c2.label
                )
            )
            KeyboardRowSpec(
                id = "flick_row_$rowIndex",
                slots = listOf(leftSlot) + centerSlots + listOf(rightSlot)
            )
        }
        return KeyboardGridSpec(
            id = "flick_${profile.id}_${mode.name.lowercase()}",
            profile = profile,
            isQwerty = false,
            isJapanese = mode == KeyboardMode.JAPANESE,
            rows = gridRows
        )
    }
}

data class QwertyGridSpec(
    val profile: KeyboardLayoutProfile,
    val isJapanese: Boolean,
    val numberRowNormal: List<Char>,
    val numberRowShift: List<Char>,
    val row1: List<Char>,
    val row2: List<Char>,
    val row2Staggered: Boolean,
    val row3: List<Char>,
    val bottomLeft: List<KeySlotSpec>,
    val bottomRight: List<KeySlotSpec>,
    val spaceLabel: String,
    val spaceWeight: Float,
    val splitSpaceWeight: Float
) {
    fun toKeyboardGridSpec(showNumberRow: Boolean): KeyboardGridSpec {
        val rows = buildList {
            if (showNumberRow) {
                add(
                    KeyboardRowSpec(
                        id = "qwerty_r0",
                        slots = numberRowNormal.indices.map { i ->
                            KeySlotSpec(
                                id = "r0_$i",
                                action = KeyAction.CommitChar(numberRowNormal[i], numberRowShift[i]),
                                label = numberRowNormal[i].toString()
                            )
                        },
                        splitCutAfter = (numberRowNormal.size + 1) / 2
                    )
                )
            }
            add(
                KeyboardRowSpec(
                    id = "qwerty_r1",
                    slots = row1.map { ch ->
                        KeySlotSpec(
                            id = "r1_$ch",
                            action = KeyAction.CommitChar(ch, ch.uppercaseChar()),
                            label = ch.toString()
                        )
                    },
                    splitCutAfter = (row1.size + 1) / 2
                )
            )
            add(
                KeyboardRowSpec(
                    id = "qwerty_r2",
                    slots = row2.mapIndexed { i, ch ->
                        val isStaggerLeft = row2Staggered && i == 0
                        val isStaggerRight = row2Staggered && i == row2.lastIndex
                        KeySlotSpec(
                            id = "r2_$ch",
                            action = KeyAction.CommitChar(ch, if (ch == 'ー') 'ー' else ch.uppercaseChar()),
                            label = ch.toString(),
                            weight = if (isStaggerLeft || isStaggerRight) 1.5f else 1f,
                            startSpacerWeight = if (isStaggerLeft) 0.5f else 0f,
                            endSpacerWeight = if (isStaggerRight) 0.5f else 0f
                        )
                    },
                    splitCutAfter = (row2.size + 1) / 2,
                    duplicateMiddleKey = row2Staggered
                )
            )
            add(
                KeyboardRowSpec(
                    id = "qwerty_r3",
                    slots = buildList {
                        add(
                            KeySlotSpec(
                                id = "r3_shift",
                                action = KeyAction.Shift,
                                label = "Shift",
                                weight = 1.35f,
                                role = KeyVisualRole.MODIFIER
                            )
                        )
                        row3.forEach { ch ->
                            add(
                                KeySlotSpec(
                                    id = "r3_$ch",
                                    action = KeyAction.CommitChar(ch, ch.uppercaseChar()),
                                    label = ch.toString()
                                )
                            )
                        }
                        add(
                            KeySlotSpec(
                                id = "r3_backspace",
                                action = KeyAction.Backspace,
                                label = "Backspace",
                                weight = 1.35f,
                                role = KeyVisualRole.MODIFIER,
                                supportsRepeat = true
                            )
                        )
                    },
                    splitCutAfter = (row3.size + 1) / 2,
                    duplicateMiddleKey = true
                )
            )
            val spaceSlot = KeySlotSpec(
                id = "r4_space",
                action = KeyAction.Space,
                label = spaceLabel,
                weight = spaceWeight,
                role = KeyVisualRole.SPACE
            )
            add(
                KeyboardRowSpec(
                    id = "qwerty_r4",
                    slots = bottomLeft + listOf(spaceSlot) + bottomRight,
                    splitCutAfter = bottomLeft.size + 1
                )
            )
        }
        return KeyboardGridSpec(
            id = "qwerty_${profile.id}_${if (isJapanese) "ja" else "en"}",
            profile = profile,
            isQwerty = true,
            isJapanese = isJapanese,
            rows = rows
        )
    }
}
