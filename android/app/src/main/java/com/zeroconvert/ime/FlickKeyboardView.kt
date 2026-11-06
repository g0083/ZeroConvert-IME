package com.zeroconvert.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import kotlin.math.abs
import kotlin.math.roundToInt

enum class JapaneseInputLayout {
    FLICK_12KEY,
    QWERTY
}

enum class FlickDirection {
    CENTER,
    LEFT,
    UP,
    RIGHT,
    DOWN
}

data class FlickKeyDef(
    val label: String,
    val center: String,
    val left: String = "",
    val up: String = "",
    val right: String = "",
    val down: String = "",
    val subText: String = "",
    val isActionKey: Boolean = false
)

object FlickKeyDefinitions {
    val keyA = FlickKeyDef(label = "あ", center = "あ", left = "い", up = "う", right = "え", down = "お", subText = "い・う・え・お")
    val keyKa = FlickKeyDef(label = "か", center = "か", left = "き", up = "く", right = "け", down = "こ", subText = "き・く・け・こ")
    val keySa = FlickKeyDef(label = "さ", center = "さ", left = "し", up = "す", right = "せ", down = "そ", subText = "し・す・せ・そ")

    val keyTa = FlickKeyDef(label = "た", center = "た", left = "ち", up = "つ", right = "て", down = "と", subText = "ち・つ・て・と")
    val keyNa = FlickKeyDef(label = "な", center = "な", left = "に", up = "ぬ", right = "ね", down = "の", subText = "に・ぬ・ね・の")
    val keyHa = FlickKeyDef(label = "は", center = "は", left = "ひ", up = "ふ", right = "へ", down = "ほ", subText = "ひ・ふ・へ・ほ")

    val keyMa = FlickKeyDef(label = "ま", center = "ま", left = "み", up = "む", right = "め", down = "も", subText = "み・む・め・も")
    val keyYa = FlickKeyDef(label = "や", center = "や", left = "（", up = "ゆ", right = "）", down = "よ", subText = "（・ゆ・）・よ")
    val keyRa = FlickKeyDef(label = "ら", center = "ら", left = "り", up = "る", right = "れ", down = "ろ", subText = "り・る・れ・ろ")

    val keyMod = FlickKeyDef(label = "小゛゜", center = "TOGGLE", left = "「", up = "『", right = "」", down = "』", subText = "小・濁・半濁", isActionKey = true)
    val keyWa = FlickKeyDef(label = "わ", center = "わ", left = "を", up = "ん", right = "ー", down = "〜", subText = "を・ん・ー・〜")
    val keyPunc = FlickKeyDef(label = "、。", center = "、", left = "。", up = "？", right = "！", down = "・", subText = "。・？・！・・")

    val toggleCycles = mapOf(
        "あ" to listOf("あ", "い", "う", "え", "お"),
        "か" to listOf("か", "き", "く", "け", "こ"),
        "さ" to listOf("さ", "し", "す", "せ", "そ"),
        "た" to listOf("た", "ち", "つ", "て", "と"),
        "な" to listOf("な", "に", "ぬ", "ね", "の"),
        "は" to listOf("は", "ひ", "ふ", "へ", "ほ"),
        "ま" to listOf("ま", "み", "む", "め", "も"),
        "や" to listOf("や", "ゆ", "よ", "（", "）"),
        "ら" to listOf("ら", "り", "る", "れ", "ろ"),
        "わ" to listOf("わ", "を", "ん", "ー", "〜")
    )
}

object FlickEnglishKeyDefinitions {
    fun getKey1() = FlickKeyDef(label = "@#/", center = "@", left = ".", up = "/", right = ":", down = "_", subText = ". / : _")

    fun getKey2(isShifted: Boolean) = if (isShifted) {
        FlickKeyDef(label = "ABC", center = "A", left = "B", up = "C", right = "2", down = "", subText = "B C 2")
    } else {
        FlickKeyDef(label = "abc", center = "a", left = "b", up = "c", right = "2", down = "", subText = "b c 2")
    }

    fun getKey3(isShifted: Boolean) = if (isShifted) {
        FlickKeyDef(label = "DEF", center = "D", left = "E", up = "F", right = "3", down = "", subText = "E F 3")
    } else {
        FlickKeyDef(label = "def", center = "d", left = "e", up = "f", right = "3", down = "", subText = "e f 3")
    }

    fun getKey4(isShifted: Boolean) = if (isShifted) {
        FlickKeyDef(label = "GHI", center = "G", left = "H", up = "I", right = "4", down = "", subText = "H I 4")
    } else {
        FlickKeyDef(label = "ghi", center = "g", left = "h", up = "i", right = "4", down = "", subText = "h i 4")
    }

    fun getKey5(isShifted: Boolean) = if (isShifted) {
        FlickKeyDef(label = "JKL", center = "J", left = "K", up = "L", right = "5", down = "", subText = "K L 5")
    } else {
        FlickKeyDef(label = "jkl", center = "j", left = "k", up = "l", right = "5", down = "", subText = "k l 5")
    }

    fun getKey6(isShifted: Boolean) = if (isShifted) {
        FlickKeyDef(label = "MNO", center = "M", left = "N", up = "O", right = "6", down = "", subText = "N O 6")
    } else {
        FlickKeyDef(label = "mno", center = "m", left = "n", up = "o", right = "6", down = "", subText = "n o 6")
    }

    fun getKey7(isShifted: Boolean) = if (isShifted) {
        FlickKeyDef(label = "PQRS", center = "P", left = "Q", up = "R", right = "S", down = "7", subText = "Q R S 7")
    } else {
        FlickKeyDef(label = "pqrs", center = "p", left = "q", up = "r", right = "s", down = "7", subText = "q r s 7")
    }

    fun getKey8(isShifted: Boolean) = if (isShifted) {
        FlickKeyDef(label = "TUV", center = "T", left = "U", up = "V", right = "8", down = "", subText = "U V 8")
    } else {
        FlickKeyDef(label = "tuv", center = "t", left = "u", up = "v", right = "8", down = "", subText = "u v 8")
    }

    fun getKey9(isShifted: Boolean) = if (isShifted) {
        FlickKeyDef(label = "WXYZ", center = "W", left = "X", up = "Y", right = "Z", down = "9", subText = "X Y Z 9")
    } else {
        FlickKeyDef(label = "wxyz", center = "w", left = "x", up = "y", right = "z", down = "9", subText = "x y z 9")
    }

    val keyCase = FlickKeyDef(label = "a/A", center = "CASE_TOGGLE", left = "(", up = "\"", right = ")", down = "'", subText = "a/A", isActionKey = true)
    val key0 = FlickKeyDef(label = "'(~)", center = "'", left = "\"", up = "~", right = "&", down = "0", subText = "\" ~ & 0")
    val keyPunc = FlickKeyDef(label = ".,?!", center = ".", left = ",", up = "?", right = "!", down = "-", subText = ", ? ! -")

    fun getToggleCycles(isShifted: Boolean): Map<String, List<String>> {
        return if (isShifted) {
            mapOf(
                "@" to listOf("@", ".", "/", ":", "_", "1"),
                "A" to listOf("A", "B", "C", "2"),
                "D" to listOf("D", "E", "F", "3"),
                "G" to listOf("G", "H", "I", "4"),
                "J" to listOf("J", "K", "L", "5"),
                "M" to listOf("M", "N", "O", "6"),
                "P" to listOf("P", "Q", "R", "S", "7"),
                "T" to listOf("T", "U", "V", "8"),
                "W" to listOf("W", "X", "Y", "Z", "9"),
                "'" to listOf("'", "\"", "~", "&", "0"),
                "." to listOf(".", ",", "?", "!", "-")
            )
        } else {
            mapOf(
                "@" to listOf("@", ".", "/", ":", "_", "1"),
                "a" to listOf("a", "b", "c", "2"),
                "d" to listOf("d", "e", "f", "3"),
                "g" to listOf("g", "h", "i", "4"),
                "j" to listOf("j", "k", "l", "5"),
                "m" to listOf("m", "n", "o", "6"),
                "p" to listOf("p", "q", "r", "s", "7"),
                "t" to listOf("t", "u", "v", "8"),
                "w" to listOf("w", "x", "y", "z", "9"),
                "'" to listOf("'", "\"", "~", "&", "0"),
                "." to listOf(".", ",", "?", "!", "-")
            )
        }
    }
}

object FlickNumericKeyDefinitions {
    val key1 = FlickKeyDef(label = "1", center = "1", left = "☆", up = "♪", right = "→", down = "←", subText = "☆ ♪ →")
    val key2 = FlickKeyDef(label = "2", center = "2", left = "¥", up = "$", right = "€", down = "£", subText = "¥ $ €")
    val key3 = FlickKeyDef(label = "3", center = "3", left = "%", up = "°", right = "#", down = "℃", subText = "% ° #")

    val key4 = FlickKeyDef(label = "4", center = "4", left = "○", up = "*", right = "・", down = "●", subText = "○ * ・")
    val key5 = FlickKeyDef(label = "5", center = "5", left = "+", up = "×", right = "÷", down = "±", subText = "+ × ÷")
    val key6 = FlickKeyDef(label = "6", center = "6", left = "<", up = "=", right = ">", down = "≠", subText = "< = >")

    val key7 = FlickKeyDef(label = "7", center = "7", left = "「", up = "」", right = ":", down = ";", subText = "「 」 :")
    val key8 = FlickKeyDef(label = "8", center = "8", left = "〒", up = "々", right = "〆", down = "〇", subText = "〒 々 〆")
    val key9 = FlickKeyDef(label = "9", center = "9", left = "^", up = "\\", right = "|", down = "_", subText = "^ \\ |")

    val keyBrackets = FlickKeyDef(label = "()[]", center = "(", left = ")", up = "[", right = "]", down = "{", subText = "( ) [ ]")
    val key0 = FlickKeyDef(label = "0", center = "0", left = "~", up = "…", right = "@", down = "°", subText = "~ … @")
    val keyPunc = FlickKeyDef(label = ".,-/", center = ".", left = ",", up = "-", right = "/", down = "_", subText = ". , - /")

    val toggleCycles = mapOf(
        "1" to listOf("1", "☆", "♪", "→", "←"),
        "2" to listOf("2", "¥", "$", "€", "£"),
        "3" to listOf("3", "%", "°", "#", "℃"),
        "4" to listOf("4", "○", "*", "・", "●"),
        "5" to listOf("5", "+", "×", "÷", "±"),
        "6" to listOf("6", "<", "=", ">", "≠"),
        "7" to listOf("7", "「", "」", ":", ";"),
        "8" to listOf("8", "〒", "々", "〆", "〇"),
        "9" to listOf("9", "^", "\\", "|", "_"),
        "(" to listOf("(", ")", "[", "]", "{"),
        "0" to listOf("0", "~", "…", "@"),
        "." to listOf(".", ",", "-", "/", "_")
    )
}

object KanaModifier {
    fun toggle(ch: Char): Char {
        return when (ch) {
            // あ行
            'あ' -> 'ぁ'
            'ぁ' -> 'あ'
            'い' -> 'ぃ'
            'ぃ' -> 'い'
            'う' -> 'ぅ'
            'ぅ' -> 'ゔ'
            'ゔ' -> 'う'
            'え' -> 'ぇ'
            'ぇ' -> 'え'
            'お' -> 'ぉ'
            'ぉ' -> 'お'

            // か行
            'か' -> 'が'
            'が' -> 'か'
            'き' -> 'ぎ'
            'ぎ' -> 'き'
            'く' -> 'ぐ'
            'ぐ' -> 'く'
            'け' -> 'げ'
            'げ' -> 'け'
            'こ' -> 'ご'
            'ご' -> 'こ'

            // さ行
            'さ' -> 'ざ'
            'ざ' -> 'さ'
            'し' -> 'じ'
            'じ' -> 'し'
            'す' -> 'ず'
            'ず' -> 'す'
            'せ' -> 'ぜ'
            'ぜ' -> 'せ'
            'そ' -> 'ぞ'
            'ぞ' -> 'そ'

            // た行
            'た' -> 'だ'
            'だ' -> 'た'
            'ち' -> 'ぢ'
            'ぢ' -> 'ち'
            'つ' -> 'っ'
            'っ' -> 'づ'
            'づ' -> 'つ'
            'て' -> 'で'
            'で' -> 'て'
            'と' -> 'ど'
            'ど' -> 'と'

            // は行
            'は' -> 'ば'
            'ば' -> 'ぱ'
            'ぱ' -> 'は'
            'ひ' -> 'び'
            'び' -> 'ぴ'
            'ぴ' -> 'ひ'
            'ふ' -> 'ぶ'
            'ぶ' -> 'ぷ'
            'ぷ' -> 'ふ'
            'へ' -> 'べ'
            'べ' -> 'ぺ'
            'ぺ' -> 'へ'
            'ほ' -> 'ぼ'
            'ぼ' -> 'ぽ'
            'ぽ' -> 'ほ'

            // や行
            'や' -> 'ゃ'
            'ゃ' -> 'や'
            'ゆ' -> 'ゅ'
            'ゅ' -> 'ゆ'
            'よ' -> 'ょ'
            'ょ' -> 'よ'

            // わ行
            'わ' -> 'ゎ'
            'ゎ' -> 'わ'

            // カタカナ対応
            'ア' -> 'ァ'
            'ァ' -> 'ア'
            'イ' -> 'ィ'
            'ィ' -> 'イ'
            'ウ' -> 'ゥ'
            'ゥ' -> 'ヴ'
            'ヴ' -> 'ウ'
            'エ' -> 'ェ'
            'ェ' -> 'エ'
            'オ' -> 'ォ'
            'ォ' -> 'オ'

            'カ' -> 'ガ'
            'ガ' -> 'カ'
            'キ' -> 'ギ'
            'ギ' -> 'キ'
            'ク' -> 'グ'
            'グ' -> 'ク'
            'ケ' -> 'ゲ'
            'ゲ' -> 'ケ'
            'コ' -> 'ゴ'
            'ゴ' -> 'コ'

            'サ' -> 'ザ'
            'ザ' -> 'サ'
            'シ' -> 'ジ'
            'ジ' -> 'シ'
            'ス' -> 'ズ'
            'ズ' -> 'ス'
            'セ' -> 'ゼ'
            'ゼ' -> 'セ'
            'ソ' -> 'ゾ'
            'ゾ' -> 'ソ'

            'タ' -> 'ダ'
            'ダ' -> 'タ'
            'チ' -> 'ヂ'
            'ヂ' -> 'チ'
            'ツ' -> 'ッ'
            'ッ' -> 'ヅ'
            'ヅ' -> 'ツ'
            'テ' -> 'デ'
            'デ' -> 'テ'
            'ト' -> 'ド'
            'ド' -> 'ト'

            'ハ' -> 'バ'
            'バ' -> 'パ'
            'パ' -> 'ハ'
            'ヒ' -> 'ビ'
            'ビ' -> 'ピ'
            'ピ' -> 'ヒ'
            'フ' -> 'ブ'
            'ブ' -> 'プ'
            'プ' -> 'フ'
            'ヘ' -> 'ベ'
            'ベ' -> 'ペ'
            'ペ' -> 'ヘ'
            'ホ' -> 'ボ'
            'ボ' -> 'ポ'
            'ポ' -> 'ホ'

            'ヤ' -> 'ャ'
            'ャ' -> 'ヤ'
            'ユ' -> 'ュ'
            'ュ' -> 'ユ'
            'ヨ' -> 'ョ'
            'ョ' -> 'ヨ'

            'ワ' -> 'ヮ'
            'ヮ' -> 'ワ'

            else -> ch
        }
    }
}

fun calculateFlickDirection(dx: Float, dy: Float, thresholdPx: Float): FlickDirection {
    val absX = abs(dx)
    val absY = abs(dy)
    if (absX < thresholdPx && absY < thresholdPx) {
        return FlickDirection.CENTER
    }
    return if (absX > absY) {
        if (dx < 0f) FlickDirection.LEFT else FlickDirection.RIGHT
    } else {
        if (dy < 0f) FlickDirection.UP else FlickDirection.DOWN
    }
}

data class ActiveFlickPopupState(
    val keyDef: FlickKeyDef,
    val centerOffset: Offset,
    val direction: FlickDirection,
    val rootWidthPx: Float = 0f,
    val rootHeightPx: Float = 0f
)

@Composable
fun Japanese12KeyFlickLayout(
    keyBgColor: Color,
    actionKeyBg: Color,
    textColor: Color,
    palette: ZeroConPalette,
    rowHeight: Dp,
    isFlickOnly: Boolean = true,
    onKanaInput: (String) -> Unit,
    onReplaceLastKana: (String) -> Unit,
    onToggleDakuten: () -> Unit,
    onDelete: () -> Unit,
    onSpace: () -> Unit,
    onLongPressSpace: () -> Unit = {},
    onEnter: () -> Unit,
    onCycleMode: () -> Unit,
    onOpenSymbols: () -> Unit,
    onOpenEmoji: () -> Unit,
    onCursorLeft: () -> Unit,
    onCursorRight: () -> Unit
) {
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var activePopupState by remember { mutableStateOf<ActiveFlickPopupState?>(null) }

    // State for toggle input (when isFlickOnly is false)
    var lastTappedKey by remember { mutableStateOf<String?>(null) }
    var currentCycleIndex by remember { mutableStateOf(0) }
    var lastTapTimestamp by remember { mutableStateOf(0L) }

    val handleFlickResult = { def: FlickKeyDef, dir: FlickDirection ->
        if (def == FlickKeyDefinitions.keyMod) {
            when (dir) {
                FlickDirection.CENTER -> onToggleDakuten()
                FlickDirection.LEFT -> onKanaInput("「")
                FlickDirection.RIGHT -> onKanaInput("」")
                FlickDirection.UP -> onKanaInput("『")
                FlickDirection.DOWN -> onKanaInput("』")
            }
        } else {
            val output = when (dir) {
                FlickDirection.CENTER -> {
                    if (isFlickOnly) {
                        def.center
                    } else {
                        val now = System.currentTimeMillis()
                        val cycle = FlickKeyDefinitions.toggleCycles[def.center]
                        if (cycle != null && lastTappedKey == def.center && (now - lastTapTimestamp) < 850L) {
                            currentCycleIndex = (currentCycleIndex + 1) % cycle.size
                            lastTapTimestamp = now
                            onReplaceLastKana(cycle[currentCycleIndex])
                            null
                        } else {
                            lastTappedKey = def.center
                            currentCycleIndex = 0
                            lastTapTimestamp = now
                            def.center
                        }
                    }
                }
                FlickDirection.LEFT -> { lastTappedKey = null; def.left }
                FlickDirection.UP -> { lastTappedKey = null; def.up }
                FlickDirection.RIGHT -> { lastTappedKey = null; def.right }
                FlickDirection.DOWN -> { lastTappedKey = null; def.down }
            }
            if (output != null && output.isNotEmpty()) {
                onKanaInput(output)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootCoordinates = it }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Row 0: [ !?# ] [ あ ] [ か ] [ さ ] [ ⌫ ]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Box(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionKeyBg)
                        .clickable(onClick = onOpenSymbols),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "!?#", color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }

                FlickKeyCell(
                    keyDef = FlickKeyDefinitions.keyA,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickKeyDefinitions.keyKa,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickKeyDefinitions.keySa,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                RepeatingIconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionKeyBg)
                ) {
                    Icon(imageVector = Icons.Default.Backspace, contentDescription = "Backspace", tint = textColor, modifier = Modifier.size(22.dp))
                }
            }

            // Row 1: [ ◀ ] [ た ] [ な ] [ は ] [ ␣ (空白) ]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                RepeatingIconButton(
                    onClick = onCursorLeft,
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionKeyBg),
                    initialDelayMs = 300L,
                    repeatIntervalMs = 50L
                ) {
                    Text(text = "◀", color = textColor, fontSize = 14.sp)
                }

                FlickKeyCell(
                    keyDef = FlickKeyDefinitions.keyTa,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickKeyDefinitions.keyNa,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickKeyDefinitions.keyHa,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                val currentOnSpace by rememberUpdatedState(onSpace)
                val currentOnLongPressSpace by rememberUpdatedState(onLongPressSpace)
                Box(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionKeyBg)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { currentOnSpace() },
                                onLongPress = { currentOnLongPressSpace() }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "␣", color = textColor, fontSize = 20.sp)
                }
            }

            // Row 2: [ ▶ ] [ ま ] [ や ] [ ら ] [ 😀 ]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                RepeatingIconButton(
                    onClick = onCursorRight,
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionKeyBg),
                    initialDelayMs = 300L,
                    repeatIntervalMs = 50L
                ) {
                    Text(text = "▶", color = textColor, fontSize = 14.sp)
                }

                FlickKeyCell(
                    keyDef = FlickKeyDefinitions.keyMa,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickKeyDefinitions.keyYa,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickKeyDefinitions.keyRa,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                Box(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionKeyBg)
                        .clickable(onClick = onOpenEmoji),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Default.Mood, contentDescription = "Emoji", tint = textColor, modifier = Modifier.size(22.dp))
                }
            }

            // Row 3: [ あa1 ] [ 小゛゜] [ わ ] [ 、。] [ ↩ ]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Aa1Button(
                    mode = KeyboardMode.JAPANESE,
                    modifier = Modifier.weight(1.05f),
                    actionKeyBg = actionKeyBg,
                    fillAvailableHeight = true,
                    onClick = onCycleMode
                )

                FlickKeyCell(
                    keyDef = FlickKeyDefinitions.keyMod,
                    modifier = Modifier.weight(1.15f),
                    bgColor = actionKeyBg,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    isActionKey = true,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickKeyDefinitions.keyWa,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickKeyDefinitions.keyPunc,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                Box(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.primaryAccent)
                        .clickable(onClick = onEnter),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "確定",
                        color = palette.onAccent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Overlay Flick Popup Guide
        activePopupState?.let { popup ->
            FlickPopupGuide(
                popupState = popup,
                palette = palette
            )
        }
    }
}

@Composable
fun English12KeyFlickLayout(
    keyBgColor: Color,
    actionKeyBg: Color,
    textColor: Color,
    palette: ZeroConPalette,
    rowHeight: Dp,
    isFlickOnly: Boolean = true,
    onCommitText: (String) -> Unit,
    onReplaceLastCommittedText: (String, Int) -> Unit = { _, _ -> },
    onToggleLastCharCase: () -> Unit,
    isLastCharLetter: () -> Boolean,
    onDelete: () -> Unit,
    onSpace: () -> Unit,
    onLongPressSpace: () -> Unit = {},
    onEnter: () -> Unit,
    onCycleMode: () -> Unit,
    onOpenSymbols: () -> Unit,
    onOpenEmoji: () -> Unit,
    onCursorLeft: () -> Unit,
    onCursorRight: () -> Unit
) {
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var activePopupState by remember { mutableStateOf<ActiveFlickPopupState?>(null) }
    var isShifted by remember { mutableStateOf(false) }

    var lastTappedKey by remember { mutableStateOf<String?>(null) }
    var currentCycleIndex by remember { mutableStateOf(0) }
    var lastTapTimestamp by remember { mutableStateOf(0L) }

    val handleFlickResult = { def: FlickKeyDef, dir: FlickDirection ->
        if (def == FlickEnglishKeyDefinitions.keyCase) {
            when (dir) {
                FlickDirection.CENTER -> {
                    if (isLastCharLetter()) {
                        onToggleLastCharCase()
                    } else {
                        isShifted = !isShifted
                    }
                }
                FlickDirection.LEFT -> onCommitText("(")
                FlickDirection.UP -> onCommitText("\"")
                FlickDirection.RIGHT -> onCommitText(")")
                FlickDirection.DOWN -> onCommitText("'")
            }
        } else {
            val output = when (dir) {
                FlickDirection.CENTER -> {
                    if (isFlickOnly) {
                        def.center
                    } else {
                        val now = System.currentTimeMillis()
                        val cycles = FlickEnglishKeyDefinitions.getToggleCycles(isShifted)
                        val cycle = cycles[def.center]
                        if (cycle != null && lastTappedKey == def.center && (now - lastTapTimestamp) < 850L) {
                            currentCycleIndex = (currentCycleIndex + 1) % cycle.size
                            lastTapTimestamp = now
                            onReplaceLastCommittedText(cycle[currentCycleIndex], 1)
                            null
                        } else {
                            lastTappedKey = def.center
                            currentCycleIndex = 0
                            lastTapTimestamp = now
                            def.center
                        }
                    }
                }
                FlickDirection.LEFT -> { lastTappedKey = null; def.left }
                FlickDirection.UP -> { lastTappedKey = null; def.up }
                FlickDirection.RIGHT -> { lastTappedKey = null; def.right }
                FlickDirection.DOWN -> { lastTappedKey = null; def.down }
            }
            if (output != null && output.isNotEmpty()) {
                onCommitText(output)
                if (isShifted) {
                    isShifted = false
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootCoordinates = it }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Row 0: [ !?# ] [ @#/ ] [ abc ] [ def ] [ ⌫ ]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Box(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionKeyBg)
                        .clickable(onClick = onOpenSymbols),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "!?#", color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }

                FlickKeyCell(
                    keyDef = FlickEnglishKeyDefinitions.getKey1(),
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickEnglishKeyDefinitions.getKey2(isShifted),
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickEnglishKeyDefinitions.getKey3(isShifted),
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                RepeatingIconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionKeyBg)
                ) {
                    Icon(imageVector = Icons.Default.Backspace, contentDescription = "Backspace", tint = textColor, modifier = Modifier.size(22.dp))
                }
            }

            // Row 1: [ ◀ ] [ ghi ] [ jkl ] [ mno ] [ ␣ ]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                RepeatingIconButton(
                    onClick = onCursorLeft,
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionKeyBg),
                    initialDelayMs = 300L,
                    repeatIntervalMs = 50L
                ) {
                    Text(text = "◀", color = textColor, fontSize = 14.sp)
                }

                FlickKeyCell(
                    keyDef = FlickEnglishKeyDefinitions.getKey4(isShifted),
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickEnglishKeyDefinitions.getKey5(isShifted),
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickEnglishKeyDefinitions.getKey6(isShifted),
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                val currentOnSpace by rememberUpdatedState(onSpace)
                val currentOnLongPressSpace by rememberUpdatedState(onLongPressSpace)
                Box(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionKeyBg)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { currentOnSpace() },
                                onLongPress = { currentOnLongPressSpace() }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "␣", color = textColor, fontSize = 20.sp)
                }
            }

            // Row 2: [ ▶ ] [ pqrs ] [ tuv ] [ wxyz ] [ 😀 ]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                RepeatingIconButton(
                    onClick = onCursorRight,
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionKeyBg),
                    initialDelayMs = 300L,
                    repeatIntervalMs = 50L
                ) {
                    Text(text = "▶", color = textColor, fontSize = 14.sp)
                }

                FlickKeyCell(
                    keyDef = FlickEnglishKeyDefinitions.getKey7(isShifted),
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickEnglishKeyDefinitions.getKey8(isShifted),
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickEnglishKeyDefinitions.getKey9(isShifted),
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                Box(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionKeyBg)
                        .clickable(onClick = onOpenEmoji),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Default.Mood, contentDescription = "Emoji", tint = textColor, modifier = Modifier.size(22.dp))
                }
            }

            // Row 3: [ あa1 ] [ a/A ] [ '(~) ] [ .,?! ] [ ↩ ]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Aa1Button(
                    mode = KeyboardMode.ENGLISH,
                    modifier = Modifier.weight(1.05f),
                    actionKeyBg = actionKeyBg,
                    fillAvailableHeight = true,
                    onClick = onCycleMode
                )

                FlickKeyCell(
                    keyDef = FlickEnglishKeyDefinitions.keyCase,
                    modifier = Modifier.weight(1.15f),
                    bgColor = if (isShifted) palette.primaryAccent.copy(alpha = 0.28f) else actionKeyBg,
                    actionKeyBg = actionKeyBg,
                    textColor = if (isShifted) palette.primaryAccentSoft else textColor,
                    isActionKey = true,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickEnglishKeyDefinitions.key0,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickEnglishKeyDefinitions.keyPunc,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                Box(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.primaryAccent)
                        .clickable(onClick = onEnter),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "確定",
                        color = palette.onAccent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Overlay Flick Popup Guide
        activePopupState?.let { popup ->
            FlickPopupGuide(
                popupState = popup,
                palette = palette
            )
        }
    }
}

@Composable
fun FlickNumericPadLayout(
    keyBgColor: Color,
    actionKeyBg: Color,
    textColor: Color,
    palette: ZeroConPalette,
    rowHeight: Dp,
    isFlickOnly: Boolean = true,
    onCommitText: (String) -> Unit,
    onReplaceLastCommittedText: (String, Int) -> Unit = { _, _ -> },
    onDelete: () -> Unit,
    onLongPressSpace: () -> Unit = {},
    onEnter: () -> Unit,
    onCycleMode: () -> Unit,
    onOpenSymbols: () -> Unit,
    onCursorLeft: () -> Unit,
    onCursorRight: () -> Unit
) {
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var activePopupState by remember { mutableStateOf<ActiveFlickPopupState?>(null) }

    var lastTappedKey by remember { mutableStateOf<String?>(null) }
    var currentCycleIndex by remember { mutableStateOf(0) }
    var lastTapTimestamp by remember { mutableStateOf(0L) }

    val handleFlickResult = { def: FlickKeyDef, dir: FlickDirection ->
        val output = when (dir) {
            FlickDirection.CENTER -> {
                if (isFlickOnly) {
                    def.center
                } else {
                    val now = System.currentTimeMillis()
                    val cycle = FlickNumericKeyDefinitions.toggleCycles[def.center]
                    if (cycle != null && lastTappedKey == def.center && (now - lastTapTimestamp) < 850L) {
                        currentCycleIndex = (currentCycleIndex + 1) % cycle.size
                        lastTapTimestamp = now
                        onReplaceLastCommittedText(cycle[currentCycleIndex], 1)
                        null
                    } else {
                        lastTappedKey = def.center
                        currentCycleIndex = 0
                        lastTapTimestamp = now
                        def.center
                    }
                }
            }
            FlickDirection.LEFT -> { lastTappedKey = null; def.left }
            FlickDirection.UP -> { lastTappedKey = null; def.up }
            FlickDirection.RIGHT -> { lastTappedKey = null; def.right }
            FlickDirection.DOWN -> { lastTappedKey = null; def.down }
        }
        if (output != null && output.isNotEmpty()) {
            onCommitText(output)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootCoordinates = it }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Row 0: [ ↶ ] [ 1 (☆♪→) ] [ 2 (¥$€) ] [ 3 (%°#) ] [ ⌫ ]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                RepeatingIconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionKeyBg)
                ) {
                    Text(text = "↶", color = textColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }

                FlickKeyCell(
                    keyDef = FlickNumericKeyDefinitions.key1,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickNumericKeyDefinitions.key2,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickNumericKeyDefinitions.key3,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                RepeatingIconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionKeyBg)
                ) {
                    Icon(imageVector = Icons.Default.Backspace, contentDescription = "Backspace", tint = textColor, modifier = Modifier.size(22.dp))
                }
            }

            // Row 1: [ ◀ ] [ 4 (○*・) ] [ 5 (+×÷) ] [ 6 (<=>) ] [ ▶ ]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                RepeatingIconButton(
                    onClick = onCursorLeft,
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionKeyBg),
                    initialDelayMs = 300L,
                    repeatIntervalMs = 50L
                ) {
                    Text(text = "◀", color = textColor, fontSize = 14.sp)
                }

                FlickKeyCell(
                    keyDef = FlickNumericKeyDefinitions.key4,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickNumericKeyDefinitions.key5,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickNumericKeyDefinitions.key6,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                RepeatingIconButton(
                    onClick = onCursorRight,
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionKeyBg),
                    initialDelayMs = 300L,
                    repeatIntervalMs = 50L
                ) {
                    Text(text = "▶", color = textColor, fontSize = 14.sp)
                }
            }

            // Row 2: [ !?# ] [ 7 (「」:) ] [ 8 (〒々〆) ] [ 9 (^\\) ] [ ␣ ]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Box(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionKeyBg)
                        .clickable(onClick = onOpenSymbols),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "!?#", color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }

                FlickKeyCell(
                    keyDef = FlickNumericKeyDefinitions.key7,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickNumericKeyDefinitions.key8,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickNumericKeyDefinitions.key9,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                val currentOnCommitText by rememberUpdatedState(onCommitText)
                val currentOnLongPressSpace by rememberUpdatedState(onLongPressSpace)
                Box(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(actionKeyBg)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { currentOnCommitText(" ") },
                                onLongPress = { currentOnLongPressSpace() }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "␣", color = textColor, fontSize = 20.sp)
                }
            }

            // Row 3: [ あa1 ] [ ()[] ] [ 0 (~...@) ] [ .,-/ ] [ ↩ ]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Aa1Button(
                    mode = KeyboardMode.NUMERIC,
                    modifier = Modifier.weight(1.05f),
                    actionKeyBg = actionKeyBg,
                    fillAvailableHeight = true,
                    onClick = onCycleMode
                )

                FlickKeyCell(
                    keyDef = FlickNumericKeyDefinitions.keyBrackets,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickNumericKeyDefinitions.key0,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                FlickKeyCell(
                    keyDef = FlickNumericKeyDefinitions.keyPunc,
                    modifier = Modifier.weight(1.15f),
                    bgColor = keyBgColor,
                    actionKeyBg = actionKeyBg,
                    textColor = textColor,
                    getRootCoords = { rootCoordinates },
                    onFlickUpdate = { state -> activePopupState = state },
                    onFlickComplete = handleFlickResult
                )

                Box(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.primaryAccent)
                        .clickable(onClick = onEnter),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "確定",
                        color = palette.onAccent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Overlay Flick Popup Guide
        activePopupState?.let { popup ->
            FlickPopupGuide(
                popupState = popup,
                palette = palette
            )
        }
    }
}

@Composable
fun FlickKeyCell(
    keyDef: FlickKeyDef,
    modifier: Modifier = Modifier,
    bgColor: Color,
    actionKeyBg: Color,
    textColor: Color,
    isActionKey: Boolean = false,
    getRootCoords: () -> LayoutCoordinates?,
    onFlickUpdate: (ActiveFlickPopupState?) -> Unit,
    onFlickComplete: (FlickKeyDef, FlickDirection) -> Unit
) {
    val currentGetRootCoords by rememberUpdatedState(getRootCoords)
    val currentOnFlickUpdate by rememberUpdatedState(onFlickUpdate)
    val currentOnFlickComplete by rememberUpdatedState(onFlickComplete)

    var cellCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 3.dp, vertical = 2.5.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActionKey) actionKeyBg else bgColor)
            .onGloballyPositioned { cellCoordinates = it }
            .semantics(mergeDescendants = true) {
                contentDescription = "${keyDef.label}を入力"
                role = Role.Button
                onClick {
                    currentOnFlickComplete(keyDef, FlickDirection.CENTER)
                    true
                }
            }
            .pointerInput(keyDef) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val startOffset = down.position
                    var currentDir = FlickDirection.CENTER

                    val rootCoords = currentGetRootCoords()
                    val myCoords = cellCoordinates
                    val rootWidthPx = rootCoords?.size?.width?.toFloat() ?: 0f
                    val rootHeightPx = rootCoords?.size?.height?.toFloat() ?: 0f
                    val centerInRoot = if (rootCoords != null && myCoords != null && rootCoords.isAttached && myCoords.isAttached) {
                        rootCoords.localPositionOf(
                            myCoords,
                            Offset(myCoords.size.width / 2f, myCoords.size.height / 2f)
                        )
                    } else {
                        Offset.Zero
                    }

                    currentOnFlickUpdate(ActiveFlickPopupState(keyDef, centerInRoot, currentDir, rootWidthPx, rootHeightPx))

                    val thresholdPx = with(density) { 18.dp.toPx() }

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            // Touch released!
                            currentOnFlickUpdate(null)
                            currentOnFlickComplete(keyDef, currentDir)
                            break
                        }
                        change.consume()
                        val pos = change.position
                        val dx = pos.x - startOffset.x
                        val dy = pos.y - startOffset.y
                        val newDir = calculateFlickDirection(dx, dy, thresholdPx)
                        if (newDir != currentDir) {
                            currentDir = newDir
                            currentOnFlickUpdate(ActiveFlickPopupState(keyDef, centerInRoot, currentDir, rootWidthPx, rootHeightPx))
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // At the minimum user-selected height the original fixed text metrics no
        // longer leave enough room for both lines.  Keep this decision inside the
        // shared key component so the IME and the settings preview stay identical.
        val isCompactKey = maxHeight < 44.dp
        val mainTextSize = when {
            keyDef.label.length > 2 && isCompactKey -> 12.sp
            keyDef.label.length > 2 -> 14.sp
            isCompactKey -> 17.sp
            else -> 19.sp
        }
        val subTextSize = if (isCompactKey) 7.sp else 8.5.sp

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = keyDef.label,
                color = textColor,
                fontSize = mainTextSize,
                fontWeight = FontWeight.Bold
            )
            if (keyDef.subText.isNotEmpty()) {
                Text(
                    text = keyDef.subText,
                    color = textColor.copy(alpha = 0.5f),
                    fontSize = subTextSize,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun FlickPopupGuide(
    popupState: ActiveFlickPopupState,
    palette: ZeroConPalette
) {
    val density = LocalDensity.current
    val cellSize = 42.dp
    val gap = 44.dp

    val cellSizePx = with(density) { cellSize.toPx() }
    val gapPx = with(density) { gap.toPx() }

    val rawCenter = popupState.centerOffset
    val keyDef = popupState.keyDef
    val dir = popupState.direction

    // Gboard lifts the popup up by ~38dp so finger doesn't obscure it
    val liftY = with(density) { 38.dp.toPx() }

    // Clamp coordinates to ensure popup doesn't overflow keyboard root bounds (especially at bottom rows)
    val halfExtentPx = gapPx + cellSizePx / 2f
    val marginPx = with(density) { 6.dp.toPx() }

    val clampedCenterX = if (popupState.rootWidthPx > 0f) {
        rawCenter.x.coerceIn(halfExtentPx + marginPx, popupState.rootWidthPx - halfExtentPx - marginPx)
    } else {
        rawCenter.x
    }

    val clampedCenterY = if (popupState.rootHeightPx > 0f) {
        (rawCenter.y - liftY).coerceIn(halfExtentPx + marginPx, popupState.rootHeightPx - halfExtentPx - marginPx)
    } else {
        rawCenter.y - liftY
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(99f)
    ) {
        // Center Cell
        PopupCell(
            text = when (keyDef.center) {
                "TOGGLE" -> "小゛゜"
                "CASE_TOGGLE" -> "a/A"
                else -> keyDef.center
            },
            isSelected = (dir == FlickDirection.CENTER),
            palette = palette,
            cellSize = cellSize,
            modifier = Modifier.offset {
                IntOffset(
                    (clampedCenterX - cellSizePx / 2f).roundToInt(),
                    (clampedCenterY - cellSizePx / 2f).roundToInt()
                )
            }
        )

        // Left Cell (←)
        if (keyDef.left.isNotEmpty()) {
            PopupCell(
                text = keyDef.left,
                isSelected = (dir == FlickDirection.LEFT),
                palette = palette,
                cellSize = cellSize,
                modifier = Modifier.offset {
                    IntOffset(
                        (clampedCenterX - cellSizePx / 2f - gapPx).roundToInt(),
                        (clampedCenterY - cellSizePx / 2f).roundToInt()
                    )
                }
            )
        }

        // Up Cell (↑)
        if (keyDef.up.isNotEmpty()) {
            PopupCell(
                text = keyDef.up,
                isSelected = (dir == FlickDirection.UP),
                palette = palette,
                cellSize = cellSize,
                modifier = Modifier.offset {
                    IntOffset(
                        (clampedCenterX - cellSizePx / 2f).roundToInt(),
                        (clampedCenterY - cellSizePx / 2f - gapPx).roundToInt()
                    )
                }
            )
        }

        // Right Cell (→)
        if (keyDef.right.isNotEmpty()) {
            PopupCell(
                text = keyDef.right,
                isSelected = (dir == FlickDirection.RIGHT),
                palette = palette,
                cellSize = cellSize,
                modifier = Modifier.offset {
                    IntOffset(
                        (clampedCenterX - cellSizePx / 2f + gapPx).roundToInt(),
                        (clampedCenterY - cellSizePx / 2f).roundToInt()
                    )
                }
            )
        }

        // Down Cell (↓)
        if (keyDef.down.isNotEmpty()) {
            PopupCell(
                text = keyDef.down,
                isSelected = (dir == FlickDirection.DOWN),
                palette = palette,
                cellSize = cellSize,
                modifier = Modifier.offset {
                    IntOffset(
                        (clampedCenterX - cellSizePx / 2f).roundToInt(),
                        (clampedCenterY - cellSizePx / 2f + gapPx).roundToInt()
                    )
                }
            )
        }
    }
}

@Composable
private fun PopupCell(
    text: String,
    isSelected: Boolean,
    palette: ZeroConPalette,
    cellSize: androidx.compose.ui.unit.Dp = 42.dp,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(cellSize),
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) palette.primaryAccent else palette.surfaceElevated.copy(alpha = 0.95f),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) palette.primaryAccentSoft else palette.borderSubtle
        ),
        shadowElevation = if (isSelected) 8.dp else 3.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (isSelected) palette.onAccent else palette.textPrimary,
                fontSize = if (isSelected) 20.sp else 16.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}
