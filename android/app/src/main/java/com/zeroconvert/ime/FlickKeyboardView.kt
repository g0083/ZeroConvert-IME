package com.zeroconvert.ime

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.zeroconvert.ime.theme.textShadowOrNull
import com.zeroconvert.ime.theme.toComposeColor
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

private val nextFlickGestureId = AtomicLong(1L)

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

enum class KanaModifierKind {
    TOGGLE,
    SMALL,
    DAKUTEN,
    HANDAKUTEN
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

/**
 * フリックキーの方向ヒント。上下左右に割り当てられた文字をキーの縁へ小さく表示する。
 */
data class FlickDirectionHints(
    val up: String,
    val down: String,
    val left: String,
    val right: String
) {
    val isEmpty: Boolean
        get() = up.isEmpty() && down.isEmpty() && left.isEmpty() && right.isEmpty()
}

fun flickDirectionHints(keyDef: FlickKeyDef): FlickDirectionHints = FlickDirectionHints(
    up = keyDef.up.trim(),
    down = keyDef.down.trim(),
    left = keyDef.left.trim(),
    right = keyDef.right.trim()
)

/** Advance width of [label] in em: ASCII glyphs are narrower than kana and full-width symbols. */
internal fun flickLabelEmWidth(label: String): Float =
    label.fold(0.0) { width, ch -> width + if (ch.code < 0x80) 0.6 else 1.0 }.toFloat()

/**
 * Largest label size that still clears the left/right direction hints. Narrow keys such as
 * the floating keyboard cannot keep a long label centered between the two hints otherwise.
 */
internal fun fittedFlickLabelSizeSp(
    label: String,
    keyWidthDp: Float,
    hintSp: Float,
    hasLeftHint: Boolean,
    hasRightHint: Boolean,
    maxSizeSp: Float,
    fontScale: Float = 1f,
    minSizeSp: Float = 9f
): Float {
    val sideBandDp =
        (if (hasLeftHint) hintSp * fontScale + 5f else 0f) +
            (if (hasRightHint) hintSp * fontScale + 5f else 0f)
    val availableDp = (keyWidthDp - sideBandDp - 2f).coerceAtLeast(1f)
    val fitted = availableDp / (flickLabelEmWidth(label).coerceAtLeast(0.6f) * fontScale)
    return fitted.coerceIn(minOf(minSizeSp, maxSizeSp), maxSizeSp)
}

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

    val keyMod = FlickKeyDef(label = "小゛゜", center = "TOGGLE", left = "゛", up = "小", right = "゜", subText = "小・濁・半濁", isActionKey = true)
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

    private val smallForms: Map<Char, Char> = mapOf(
        'あ' to 'ぁ', 'い' to 'ぃ', 'う' to 'ぅ', 'え' to 'ぇ', 'お' to 'ぉ',
        'つ' to 'っ', 'や' to 'ゃ', 'ゆ' to 'ゅ', 'よ' to 'ょ', 'わ' to 'ゎ',
        'ア' to 'ァ', 'イ' to 'ィ', 'ウ' to 'ゥ', 'エ' to 'ェ', 'オ' to 'ォ',
        'ツ' to 'ッ', 'ヤ' to 'ャ', 'ユ' to 'ュ', 'ヨ' to 'ョ', 'ワ' to 'ヮ'
    )

    private val dakutenForms: Map<Char, Char> = mapOf(
        'う' to 'ゔ', 'ぅ' to 'ゔ', 'ゔ' to 'ゔ',
        'か' to 'が', 'が' to 'が', 'き' to 'ぎ', 'ぎ' to 'ぎ', 'く' to 'ぐ', 'ぐ' to 'ぐ',
        'け' to 'げ', 'げ' to 'げ', 'こ' to 'ご', 'ご' to 'ご',
        'さ' to 'ざ', 'ざ' to 'ざ', 'し' to 'じ', 'じ' to 'じ', 'す' to 'ず', 'ず' to 'ず',
        'せ' to 'ぜ', 'ぜ' to 'ぜ', 'そ' to 'ぞ', 'ぞ' to 'ぞ',
        'た' to 'だ', 'だ' to 'だ', 'ち' to 'ぢ', 'ぢ' to 'ぢ', 'つ' to 'づ', 'っ' to 'づ', 'づ' to 'づ',
        'て' to 'で', 'で' to 'で', 'と' to 'ど', 'ど' to 'ど',
        'は' to 'ば', 'ば' to 'ば', 'ぱ' to 'ば', 'ひ' to 'び', 'び' to 'び', 'ぴ' to 'び',
        'ふ' to 'ぶ', 'ぶ' to 'ぶ', 'ぷ' to 'ぶ', 'へ' to 'べ', 'べ' to 'べ', 'ぺ' to 'べ',
        'ほ' to 'ぼ', 'ぼ' to 'ぼ', 'ぽ' to 'ぼ',
        'ウ' to 'ヴ', 'ゥ' to 'ヴ', 'ヴ' to 'ヴ',
        'カ' to 'ガ', 'ガ' to 'ガ', 'キ' to 'ギ', 'ギ' to 'ギ', 'ク' to 'グ', 'グ' to 'グ',
        'ケ' to 'ゲ', 'ゲ' to 'ゲ', 'コ' to 'ゴ', 'ゴ' to 'ゴ',
        'サ' to 'ザ', 'ザ' to 'ザ', 'シ' to 'ジ', 'ジ' to 'ジ', 'ス' to 'ズ', 'ズ' to 'ズ',
        'セ' to 'ゼ', 'ゼ' to 'ゼ', 'ソ' to 'ゾ', 'ゾ' to 'ゾ',
        'タ' to 'ダ', 'ダ' to 'ダ', 'チ' to 'ヂ', 'ヂ' to 'ヂ', 'ツ' to 'ヅ', 'ッ' to 'ヅ', 'ヅ' to 'ヅ',
        'テ' to 'デ', 'デ' to 'デ', 'ト' to 'ド', 'ド' to 'ド',
        'ハ' to 'バ', 'バ' to 'バ', 'パ' to 'バ', 'ヒ' to 'ビ', 'ビ' to 'ビ', 'ピ' to 'ビ',
        'フ' to 'ブ', 'ブ' to 'ブ', 'プ' to 'ブ', 'ヘ' to 'ベ', 'ベ' to 'ベ', 'ペ' to 'ベ',
        'ホ' to 'ボ', 'ボ' to 'ボ', 'ポ' to 'ボ'
    )

    private val handakutenForms: Map<Char, Char> = mapOf(
        'は' to 'ぱ', 'ば' to 'ぱ', 'ぱ' to 'ぱ', 'ひ' to 'ぴ', 'び' to 'ぴ', 'ぴ' to 'ぴ',
        'ふ' to 'ぷ', 'ぶ' to 'ぷ', 'ぷ' to 'ぷ', 'へ' to 'ぺ', 'べ' to 'ぺ', 'ぺ' to 'ぺ',
        'ほ' to 'ぽ', 'ぼ' to 'ぽ', 'ぽ' to 'ぽ',
        'ハ' to 'パ', 'バ' to 'パ', 'パ' to 'パ', 'ヒ' to 'ピ', 'ビ' to 'ピ', 'ピ' to 'ピ',
        'フ' to 'プ', 'ブ' to 'プ', 'プ' to 'プ', 'ヘ' to 'ペ', 'ベ' to 'ペ', 'ペ' to 'ペ',
        'ホ' to 'ポ', 'ボ' to 'ポ', 'ポ' to 'ポ'
    )

    fun modify(ch: Char, kind: KanaModifierKind): Char = when (kind) {
        KanaModifierKind.TOGGLE -> toggle(ch)
        KanaModifierKind.SMALL -> toSmall(ch)
        KanaModifierKind.DAKUTEN -> toDakuten(ch)
        KanaModifierKind.HANDAKUTEN -> toHandakuten(ch)
    }

    fun toSmall(ch: Char): Char = smallForms[ch] ?: ch

    fun toDakuten(ch: Char): Char = dakutenForms[ch] ?: ch

    fun toHandakuten(ch: Char): Char = handakutenForms[ch] ?: ch
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
private fun FlickKeyboardContainer(
    palette: ZeroConPalette,
    content: @Composable ColumnScope.(
        getRootCoords: () -> LayoutCoordinates?,
        onFlickUpdateById: (Long, ActiveFlickPopupState?) -> Unit
    ) -> Unit
) {
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val activePopups = remember { mutableStateMapOf<Long, ActiveFlickPopupState>() }
    DisposableEffect(Unit) {
        onDispose { activePopups.clear() }
    }
    val onFlickUpdateById: (Long, ActiveFlickPopupState?) -> Unit = { gestureId, state ->
        if (state == null) {
            activePopups.remove(gestureId)
        } else {
            activePopups[gestureId] = state
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootCoordinates = it }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            content({ rootCoordinates }, onFlickUpdateById)
        }

        // Overlay Flick Popup Guides keyed by gesture ID so multi-finger fast taps never erase another popup.
        for (popup in activePopups.values) {
            FlickPopupGuide(
                popupState = popup,
                palette = palette
            )
        }
    }
}

private fun flickRowVisualPadding(
    rowIndex: Int,
    isFirstInRow: Boolean = false,
    isLastInRow: Boolean = false
): PaddingValues = keyRowVisualPadding(
    isFirstInRow = isFirstInRow,
    isLastInRow = isLastInRow,
    baseHorizontal = 3.dp,
    edgeExtraHorizontal = 3.dp,
    top = if (rowIndex == 0) 6.dp else 2.5.dp,
    bottom = if (rowIndex == 3) 7.5.dp else 2.5.dp
)

@Composable
private fun ColumnScope.FlickGridRow(
    rowIndex: Int,
    keys: Triple<FlickKeyDef, FlickKeyDef, FlickKeyDef>,
    keyBgColor: Color,
    actionKeyBg: Color,
    textColor: Color,
    palette: ZeroConPalette,
    getRootCoords: () -> LayoutCoordinates?,
    onFlickUpdateById: (Long, ActiveFlickPopupState?) -> Unit,
    onFlickComplete: (FlickKeyDef, FlickDirection) -> Unit,
    onFlickCompleteWithBias: ((FlickKeyDef, FlickDirection, TouchKeyBias?) -> Unit)? = null,
    onFlickCompleteWithGeometry: ((FlickKeyDef, FlickDirection, FlickTouchGeometry?) -> Unit)? = null,
    firstKeyBg: Color = keyBgColor,
    firstKeyTextColor: Color = textColor,
    firstIsActionKey: Boolean = false,
    leftKey: @Composable RowScope.(Modifier, PaddingValues) -> Unit,
    rightKey: @Composable RowScope.(Modifier, PaddingValues) -> Unit
) {
    val leftPadding = flickRowVisualPadding(rowIndex, isFirstInRow = true)
    val midPadding = flickRowVisualPadding(rowIndex)
    val rightPadding = flickRowVisualPadding(rowIndex, isLastInRow = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
    ) {
        leftKey(Modifier.weight(1.05f).fillMaxHeight(), leftPadding)

        FlickKeyCell(
            keyDef = keys.first,
            modifier = Modifier.weight(1.15f),
            bgColor = firstKeyBg,
            actionKeyBg = actionKeyBg,
            textColor = firstKeyTextColor,
            isActionKey = firstIsActionKey,
            visualPadding = midPadding,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = onFlickComplete,
            onFlickCompleteWithBias = onFlickCompleteWithBias,
            onFlickCompleteWithGeometry = onFlickCompleteWithGeometry
        )
        FlickKeyCell(
            keyDef = keys.second,
            modifier = Modifier.weight(1.15f),
            bgColor = keyBgColor,
            actionKeyBg = actionKeyBg,
            textColor = textColor,
            visualPadding = midPadding,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = onFlickComplete,
            onFlickCompleteWithBias = onFlickCompleteWithBias,
            onFlickCompleteWithGeometry = onFlickCompleteWithGeometry
        )
        FlickKeyCell(
            keyDef = keys.third,
            modifier = Modifier.weight(1.15f),
            bgColor = keyBgColor,
            actionKeyBg = actionKeyBg,
            textColor = textColor,
            visualPadding = midPadding,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = onFlickComplete,
            onFlickCompleteWithBias = onFlickCompleteWithBias,
            onFlickCompleteWithGeometry = onFlickCompleteWithGeometry
        )

        rightKey(Modifier.weight(1.05f).fillMaxHeight(), rightPadding)
    }
}

@Composable
private fun FlickSideUndoButton(
    modifier: Modifier,
    visualPadding: PaddingValues,
    actionKeyBg: Color,
    textColor: Color,
    palette: ZeroConPalette,
    onUndo: () -> Unit
) {
    KeyboardActionKey(
        modifier = modifier,
        visualPadding = visualPadding,
        baseColor = actionKeyBg,
        isModifier = true,
        palette = palette,
        contentDescription = "元に戻す",
        onClick = onUndo
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Undo,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun FlickSideSymbolsButton(
    modifier: Modifier,
    visualPadding: PaddingValues,
    actionKeyBg: Color,
    textColor: Color,
    palette: ZeroConPalette,
    onOpenSymbols: () -> Unit,
    onOpenEmoji: () -> Unit
) {
    KeyboardActionKey(
        modifier = modifier,
        visualPadding = visualPadding,
        baseColor = actionKeyBg,
        isModifier = true,
        palette = palette,
        contentDescription = "記号",
        onFlick = { direction ->
            when (direction) {
                FlickDirection.UP, FlickDirection.RIGHT -> onOpenEmoji()
                FlickDirection.CENTER -> onOpenSymbols()
                FlickDirection.LEFT, FlickDirection.DOWN -> Unit
            }
        },
        onClick = onOpenSymbols
    ) {
        Text(text = "!?#", color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FlickSideJpEnButton(
    modifier: Modifier,
    visualPadding: PaddingValues,
    mode: KeyboardMode,
    actionKeyBg: Color,
    textColor: Color,
    palette: ZeroConPalette,
    onToggleJpEn: () -> Unit
) {
    val activeColor = textColor
    val dimColor = palette.textMuted.copy(alpha = 0.72f)
    KeyboardActionKey(
        modifier = modifier,
        visualPadding = visualPadding,
        baseColor = actionKeyBg,
        isModifier = true,
        palette = palette,
        contentDescription = "あ/a",
        onClick = onToggleJpEn
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "あ",
                color = if (mode == KeyboardMode.JAPANESE) activeColor else dimColor,
                fontSize = 15.sp,
                fontWeight = if (mode == KeyboardMode.JAPANESE) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = "/",
                color = dimColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal
            )
            Text(
                text = "a",
                color = if (mode == KeyboardMode.ENGLISH) activeColor else dimColor,
                fontSize = 14.sp,
                fontWeight = if (mode == KeyboardMode.ENGLISH) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun FlickSideEnterButton(
    modifier: Modifier,
    visualPadding: PaddingValues,
    palette: ZeroConPalette,
    onEnter: () -> Unit
) {
    // Without a conversion the key stands for the editor's imeOptions action (検索, 次へ, ...).
    val enterAction = LocalEnterKeyAction.current
    val enterRadius = resolve12KeyEnterCornerRadiusDp(LocalKeyboardTheme.current.keySurface.enterCornerRadiusDp).dp
    KeyboardActionKey(
        modifier = modifier,
        visualPadding = visualPadding,
        cornerRadius = enterRadius,
        baseColor = palette.primaryAccent,
        isAccent = true,
        contentDescription = enterAction?.label ?: "確定",
        onClick = onEnter
    ) {
        Text(
            text = enterAction?.label ?: "確定",
            color = palette.onAccent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun FlickSideSpaceButton(
    modifier: Modifier,
    visualPadding: PaddingValues,
    actionKeyBg: Color,
    textColor: Color,
    onSpace: () -> Unit,
    onLongPressSpace: () -> Unit,
    onCursorLeft: () -> Unit,
    onCursorRight: () -> Unit
) {
    KeyboardActionKey(
        modifier = modifier,
        visualPadding = visualPadding,
        baseColor = actionKeyBg,
        isModifier = false,
        contentDescription = "空白",
        onClick = onSpace,
        onLongClick = onLongPressSpace,
        onHorizontalStep = { step -> if (step < 0) onCursorLeft() else onCursorRight() }
    ) {
        Icon(
            imageVector = Icons.Default.SpaceBar,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun FlickSideBackspaceButton(
    modifier: Modifier,
    visualPadding: PaddingValues,
    actionKeyBg: Color,
    textColor: Color,
    palette: ZeroConPalette,
    onDelete: () -> Unit
) {
    RepeatingIconButton(
        onClick = onDelete,
        modifier = modifier,
        visualPadding = visualPadding,
        baseColor = actionKeyBg,
        isModifier = true,
        palette = palette,
        hapticOnPress = false
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Backspace,
            contentDescription = "Backspace",
            tint = textColor,
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
private fun FlickSideCursorButton(
    text: String,
    modifier: Modifier,
    visualPadding: PaddingValues,
    actionKeyBg: Color,
    textColor: Color,
    palette: ZeroConPalette,
    onClick: () -> Unit
) {
    val icon = if (text == "◀") {
        Icons.AutoMirrored.Filled.KeyboardArrowLeft
    } else {
        Icons.AutoMirrored.Filled.KeyboardArrowRight
    }
    RepeatingIconButton(
        onClick = onClick,
        modifier = modifier,
        initialDelayMs = 300L,
        repeatIntervalMs = 50L,
        visualPadding = visualPadding,
        baseColor = actionKeyBg,
        isModifier = true,
        palette = palette,
        hapticOnPress = false,
        contentDescription = text
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * Shared 5x4 12-key grid keeping common utility keys in identical positions across
 * Japanese, English, and Numeric modes (following Gboard's 12-key layout):
 * Left column:  [Undo (↶)] [◀] [あ/a / !?#] [あa1]
 * Right column: [Backspace] [▶] [␣] [確定]
 */
@Composable
private fun Flick12KeyGrid(
    mode: KeyboardMode,
    rows: List<Triple<FlickKeyDef, FlickKeyDef, FlickKeyDef>>,
    keyBgColor: Color,
    actionKeyBg: Color,
    textColor: Color,
    palette: ZeroConPalette,
    row3FirstKeyBg: Color = keyBgColor,
    row3FirstKeyTextColor: Color = textColor,
    row3FirstIsActionKey: Boolean = false,
    onFlickComplete: (FlickKeyDef, FlickDirection) -> Unit,
    onFlickCompleteWithBias: ((FlickKeyDef, FlickDirection, TouchKeyBias?) -> Unit)? = null,
    onFlickCompleteWithGeometry: ((FlickKeyDef, FlickDirection, FlickTouchGeometry?) -> Unit)? = null,
    onUndo: () -> Unit,
    onDelete: () -> Unit,
    onCursorLeft: () -> Unit,
    onCursorRight: () -> Unit,
    onOpenEmoji: () -> Unit,
    onOpenSymbols: () -> Unit,
    onToggleJpEn: () -> Unit = {},
    onSpace: () -> Unit,
    onLongPressSpace: () -> Unit,
    onSwitchMode: (FlickDirection) -> Unit,
    onEnter: () -> Unit
) {
    FlickKeyboardContainer(palette = palette) { getRootCoords, onFlickUpdateById ->
        FlickGridRow(
            rowIndex = 0,
            keys = rows[0],
            keyBgColor = keyBgColor,
            actionKeyBg = actionKeyBg,
            textColor = textColor,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = onFlickComplete,
            onFlickCompleteWithBias = onFlickCompleteWithBias,
            onFlickCompleteWithGeometry = onFlickCompleteWithGeometry,
            leftKey = { mod, pad -> FlickSideUndoButton(mod, pad, actionKeyBg, textColor, palette, onUndo) },
            rightKey = { mod, pad -> FlickSideBackspaceButton(mod, pad, actionKeyBg, textColor, palette, onDelete) }
        )

        FlickGridRow(
            rowIndex = 1,
            keys = rows[1],
            keyBgColor = keyBgColor,
            actionKeyBg = actionKeyBg,
            textColor = textColor,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = onFlickComplete,
            onFlickCompleteWithBias = onFlickCompleteWithBias,
            onFlickCompleteWithGeometry = onFlickCompleteWithGeometry,
            leftKey = { mod, pad -> FlickSideCursorButton("◀", mod, pad, actionKeyBg, textColor, palette, onCursorLeft) },
            rightKey = { mod, pad -> FlickSideCursorButton("▶", mod, pad, actionKeyBg, textColor, palette, onCursorRight) }
        )

        FlickGridRow(
            rowIndex = 2,
            keys = rows[2],
            keyBgColor = keyBgColor,
            actionKeyBg = actionKeyBg,
            textColor = textColor,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = onFlickComplete,
            onFlickCompleteWithBias = onFlickCompleteWithBias,
            onFlickCompleteWithGeometry = onFlickCompleteWithGeometry,
            leftKey = { mod, pad ->
                if (mode == KeyboardMode.NUMERIC) {
                    FlickSideSymbolsButton(mod, pad, actionKeyBg, textColor, palette, onOpenSymbols, onOpenEmoji)
                } else {
                    FlickSideJpEnButton(mod, pad, mode, actionKeyBg, textColor, palette, onToggleJpEn)
                }
            },
            rightKey = { mod, pad -> FlickSideSpaceButton(mod, pad, actionKeyBg, textColor, onSpace, onLongPressSpace, onCursorLeft, onCursorRight) }
        )

        FlickGridRow(
            rowIndex = 3,
            keys = rows[3],
            keyBgColor = keyBgColor,
            actionKeyBg = actionKeyBg,
            textColor = textColor,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = onFlickComplete,
            onFlickCompleteWithBias = onFlickCompleteWithBias,
            onFlickCompleteWithGeometry = onFlickCompleteWithGeometry,
            firstKeyBg = row3FirstKeyBg,
            firstKeyTextColor = row3FirstKeyTextColor,
            firstIsActionKey = row3FirstIsActionKey,
            leftKey = { mod, pad ->
                Aa1Button(
                    mode = mode,
                    modifier = mod,
                    actionKeyBg = actionKeyBg,
                    fillAvailableHeight = true,
                    visualPadding = pad,
                    palette = palette,
                    onSwitchMode = onSwitchMode
                )
            },
            rightKey = { mod, pad -> FlickSideEnterButton(mod, pad, palette, onEnter) }
        )
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun Japanese12KeyFlickLayout(
    keyBgColor: Color,
    actionKeyBg: Color,
    textColor: Color,
    palette: ZeroConPalette,
    rowHeight: Dp,
    isFlickOnly: Boolean = true,
    onKanaInput: (String) -> Unit,
    onKanaInputWithBias: ((String, TouchKeyBias?) -> Unit)? = null,
    onReplaceLastKana: (String) -> Unit,
    onReplaceLastKanaWithBias: ((String, TouchKeyBias?) -> Unit)? = null,
    onToggleDakuten: () -> Unit,
    onKanaModifier: (KanaModifierKind) -> Unit = {},
    onDelete: () -> Unit,
    onUndo: () -> Unit = {},
    onSpace: () -> Unit,
    onLongPressSpace: () -> Unit = {},
    onEnter: () -> Unit,
    onSwitchMode: (FlickDirection) -> Unit,
    onOpenSymbols: () -> Unit = {},
    onOpenEmoji: () -> Unit = {},
    onCursorLeft: () -> Unit,
    onCursorRight: () -> Unit,
    onToggleJpEn: () -> Unit = {}
) {
    // State for toggle input (when isFlickOnly is false)
    var lastTappedKey by remember { mutableStateOf<String?>(null) }
    var currentCycleIndex by remember { mutableStateOf(0) }
    var lastTapTimestamp by remember { mutableStateOf(0L) }

    val handleFlickResultWithGeometry = { def: FlickKeyDef, dir: FlickDirection, geometry: FlickTouchGeometry? ->
        if (def == FlickKeyDefinitions.keyMod) {
            lastTappedKey = null
            when (dir) {
                FlickDirection.CENTER -> onToggleDakuten()
                FlickDirection.LEFT -> onKanaModifier(KanaModifierKind.DAKUTEN)
                FlickDirection.UP -> onKanaModifier(KanaModifierKind.SMALL)
                FlickDirection.RIGHT -> onKanaModifier(KanaModifierKind.HANDAKUTEN)
                FlickDirection.DOWN -> {}
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
                            val nextChar = cycle[currentCycleIndex]
                            val cycleBias = nextChar.singleOrNull()?.let { ch ->
                                geometry?.let { g ->
                                    computeFlickToggleTouchBias(def, ch, g.startX, g.startY, g.widthPx, g.heightPx)
                                }
                            }
                            val replaceWithBias = onReplaceLastKanaWithBias
                            if (replaceWithBias != null) {
                                replaceWithBias(nextChar, cycleBias)
                            } else {
                                onReplaceLastKana(nextChar)
                            }
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
                val bias = geometry?.let { g ->
                    computeFlickTouchBias(
                        def, dir, g.dx, g.dy, g.thresholdPx, g.startX, g.startY, g.widthPx, g.heightPx,
                        isToggleMode = !isFlickOnly
                    )
                }
                if (onKanaInputWithBias != null) {
                    onKanaInputWithBias(output, bias)
                } else {
                    onKanaInput(output)
                }
            }
        }
    }
    val handleFlickResult: (FlickKeyDef, FlickDirection) -> Unit = { def, dir ->
        handleFlickResultWithGeometry(def, dir, null)
    }

    val handleUndo = {
        val now = System.currentTimeMillis()
        val key = lastTappedKey
        val cycle = if (!isFlickOnly && key != null) FlickKeyDefinitions.toggleCycles[key] else null
        if (cycle != null && currentCycleIndex > 0 && (now - lastTapTimestamp) < 850L) {
            currentCycleIndex--
            lastTapTimestamp = now
            onReplaceLastKana(cycle[currentCycleIndex])
        } else {
            lastTappedKey = null
            currentCycleIndex = 0
            onUndo()
        }
    }

    Flick12KeyGrid(
        mode = KeyboardMode.JAPANESE,
        rows = listOf(
            Triple(FlickKeyDefinitions.keyA, FlickKeyDefinitions.keyKa, FlickKeyDefinitions.keySa),
            Triple(FlickKeyDefinitions.keyTa, FlickKeyDefinitions.keyNa, FlickKeyDefinitions.keyHa),
            Triple(FlickKeyDefinitions.keyMa, FlickKeyDefinitions.keyYa, FlickKeyDefinitions.keyRa),
            Triple(FlickKeyDefinitions.keyMod, FlickKeyDefinitions.keyWa, FlickKeyDefinitions.keyPunc)
        ),
        keyBgColor = keyBgColor,
        actionKeyBg = actionKeyBg,
        textColor = textColor,
        palette = palette,
        row3FirstKeyBg = actionKeyBg,
        row3FirstIsActionKey = true,
        onFlickComplete = handleFlickResult,
        onFlickCompleteWithGeometry = handleFlickResultWithGeometry,
        onUndo = handleUndo,
        onDelete = {
            lastTappedKey = null
            onDelete()
        },
        onCursorLeft = {
            lastTappedKey = null
            onCursorLeft()
        },
        onCursorRight = {
            lastTappedKey = null
            onCursorRight()
        },
        onOpenEmoji = {
            lastTappedKey = null
            onOpenEmoji()
        },
        onOpenSymbols = {
            lastTappedKey = null
            onOpenSymbols()
        },
        onToggleJpEn = {
            lastTappedKey = null
            onToggleJpEn()
        },
        onSpace = {
            lastTappedKey = null
            onSpace()
        },
        onLongPressSpace = onLongPressSpace,
        onSwitchMode = { direction ->
            lastTappedKey = null
            onSwitchMode(direction)
        },
        onEnter = {
            lastTappedKey = null
            onEnter()
        }
    )
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun English12KeyFlickLayout(
    keyBgColor: Color,
    actionKeyBg: Color,
    textColor: Color,
    palette: ZeroConPalette,
    rowHeight: Dp,
    isFlickOnly: Boolean = true,
    onCommitText: (String) -> Unit,
    onCommitTextWithBias: ((String, TouchKeyBias?) -> Unit)? = null,
    onReplaceLastCommittedText: (String, Int) -> Unit = { _, _ -> },
    onReplaceLastCommittedTextWithBias: ((String, Int, TouchKeyBias?) -> Unit)? = null,
    onToggleLastCharCase: () -> Unit,
    isLastCharLetter: () -> Boolean,
    onDelete: () -> Unit,
    onUndo: () -> Unit = {},
    onSpace: () -> Unit,
    onLongPressSpace: () -> Unit = {},
    onEnter: () -> Unit,
    onSwitchMode: (FlickDirection) -> Unit,
    onOpenSymbols: () -> Unit = {},
    onOpenEmoji: () -> Unit = {},
    onCursorLeft: () -> Unit,
    onCursorRight: () -> Unit,
    onToggleJpEn: () -> Unit = {}
) {
    var isShifted by remember { mutableStateOf(false) }

    var lastTappedKey by remember { mutableStateOf<String?>(null) }
    var currentCycleIndex by remember { mutableStateOf(0) }
    var lastTapTimestamp by remember { mutableStateOf(0L) }

    val handleFlickResultWithGeometry = { def: FlickKeyDef, dir: FlickDirection, geometry: FlickTouchGeometry? ->
        if (def == FlickEnglishKeyDefinitions.keyCase) {
            lastTappedKey = null
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
                            val nextText = cycle[currentCycleIndex]
                            val cycleBias = nextText.singleOrNull()?.let { ch ->
                                geometry?.let { g ->
                                    computeEnglishFlickToggleTouchBias(def, ch, g.startX, g.startY, g.widthPx, g.heightPx)
                                }
                            }
                            val replaceWithBias = onReplaceLastCommittedTextWithBias
                            if (replaceWithBias != null) {
                                replaceWithBias(nextText, 1, cycleBias)
                            } else {
                                onReplaceLastCommittedText(nextText, 1)
                            }
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
                val bias = geometry?.let { g ->
                    computeEnglishFlickTouchBias(def, dir, g.dx, g.dy, g.thresholdPx, g.startX, g.startY, g.widthPx, g.heightPx)
                }
                val commitWithBias = onCommitTextWithBias
                if (commitWithBias != null) {
                    commitWithBias(output, bias)
                } else {
                    onCommitText(output)
                }
                if (isShifted) {
                    isShifted = false
                }
            }
        }
    }
    val handleFlickResult: (FlickKeyDef, FlickDirection) -> Unit = { def, dir ->
        handleFlickResultWithGeometry(def, dir, null)
    }

    val handleUndo = {
        val now = System.currentTimeMillis()
        val key = lastTappedKey
        val cycle = if (!isFlickOnly && key != null) {
            FlickEnglishKeyDefinitions.getToggleCycles(isShifted)[key]
                ?: FlickEnglishKeyDefinitions.getToggleCycles(!isShifted)[key]
        } else {
            null
        }
        if (cycle != null && currentCycleIndex > 0 && (now - lastTapTimestamp) < 850L) {
            currentCycleIndex--
            lastTapTimestamp = now
            onReplaceLastCommittedText(cycle[currentCycleIndex], 1)
        } else {
            lastTappedKey = null
            currentCycleIndex = 0
            onUndo()
        }
    }

    Flick12KeyGrid(
        mode = KeyboardMode.ENGLISH,
        rows = listOf(
            Triple(
                FlickEnglishKeyDefinitions.getKey1(),
                FlickEnglishKeyDefinitions.getKey2(isShifted),
                FlickEnglishKeyDefinitions.getKey3(isShifted)
            ),
            Triple(
                FlickEnglishKeyDefinitions.getKey4(isShifted),
                FlickEnglishKeyDefinitions.getKey5(isShifted),
                FlickEnglishKeyDefinitions.getKey6(isShifted)
            ),
            Triple(
                FlickEnglishKeyDefinitions.getKey7(isShifted),
                FlickEnglishKeyDefinitions.getKey8(isShifted),
                FlickEnglishKeyDefinitions.getKey9(isShifted)
            ),
            Triple(
                FlickEnglishKeyDefinitions.keyCase,
                FlickEnglishKeyDefinitions.key0,
                FlickEnglishKeyDefinitions.keyPunc
            )
        ),
        keyBgColor = keyBgColor,
        actionKeyBg = actionKeyBg,
        textColor = textColor,
        palette = palette,
        row3FirstKeyBg = if (isShifted) palette.primaryAccent.copy(alpha = 0.28f) else actionKeyBg,
        row3FirstKeyTextColor = if (isShifted) {
            if (palette.isDark) palette.primaryAccentSoft else palette.primaryAccent
        } else {
            textColor
        },
        row3FirstIsActionKey = true,
        onFlickComplete = handleFlickResult,
        onFlickCompleteWithGeometry = handleFlickResultWithGeometry,
        onUndo = handleUndo,
        onDelete = {
            lastTappedKey = null
            onDelete()
        },
        onCursorLeft = {
            lastTappedKey = null
            onCursorLeft()
        },
        onCursorRight = {
            lastTappedKey = null
            onCursorRight()
        },
        onOpenEmoji = {
            lastTappedKey = null
            onOpenEmoji()
        },
        onOpenSymbols = {
            lastTappedKey = null
            onOpenSymbols()
        },
        onToggleJpEn = {
            lastTappedKey = null
            onToggleJpEn()
        },
        onSpace = {
            lastTappedKey = null
            onSpace()
        },
        onLongPressSpace = onLongPressSpace,
        onSwitchMode = { direction ->
            lastTappedKey = null
            onSwitchMode(direction)
        },
        onEnter = {
            lastTappedKey = null
            onEnter()
        }
    )
}

@Suppress("UNUSED_PARAMETER")
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
    onUndo: () -> Unit = {},
    onLongPressSpace: () -> Unit = {},
    onEnter: () -> Unit,
    onSwitchMode: (FlickDirection) -> Unit,
    onOpenSymbols: () -> Unit,
    onOpenEmoji: () -> Unit = {},
    onCursorLeft: () -> Unit,
    onCursorRight: () -> Unit
) {
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

    val handleUndo = {
        val now = System.currentTimeMillis()
        val key = lastTappedKey
        val cycle = if (!isFlickOnly && key != null) FlickNumericKeyDefinitions.toggleCycles[key] else null
        if (cycle != null && currentCycleIndex > 0 && (now - lastTapTimestamp) < 850L) {
            currentCycleIndex--
            lastTapTimestamp = now
            onReplaceLastCommittedText(cycle[currentCycleIndex], 1)
        } else {
            lastTappedKey = null
            currentCycleIndex = 0
            onUndo()
        }
    }

    Flick12KeyGrid(
        mode = KeyboardMode.NUMERIC,
        rows = listOf(
            Triple(FlickNumericKeyDefinitions.key1, FlickNumericKeyDefinitions.key2, FlickNumericKeyDefinitions.key3),
            Triple(FlickNumericKeyDefinitions.key4, FlickNumericKeyDefinitions.key5, FlickNumericKeyDefinitions.key6),
            Triple(FlickNumericKeyDefinitions.key7, FlickNumericKeyDefinitions.key8, FlickNumericKeyDefinitions.key9),
            Triple(FlickNumericKeyDefinitions.keyBrackets, FlickNumericKeyDefinitions.key0, FlickNumericKeyDefinitions.keyPunc)
        ),
        keyBgColor = keyBgColor,
        actionKeyBg = actionKeyBg,
        textColor = textColor,
        palette = palette,
        onFlickComplete = handleFlickResult,
        onUndo = handleUndo,
        onDelete = {
            lastTappedKey = null
            onDelete()
        },
        onCursorLeft = {
            lastTappedKey = null
            onCursorLeft()
        },
        onCursorRight = {
            lastTappedKey = null
            onCursorRight()
        },
        onOpenEmoji = {
            lastTappedKey = null
            onOpenEmoji()
        },
        onOpenSymbols = {
            lastTappedKey = null
            onOpenSymbols()
        },
        onSpace = {
            lastTappedKey = null
            onCommitText(" ")
        },
        onLongPressSpace = onLongPressSpace,
        onSwitchMode = { direction ->
            lastTappedKey = null
            onSwitchMode(direction)
        },
        onEnter = {
            lastTappedKey = null
            onEnter()
        }
    )
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun FlickKeyCell(
    keyDef: FlickKeyDef,
    modifier: Modifier = Modifier,
    bgColor: Color,
    actionKeyBg: Color,
    textColor: Color,
    isActionKey: Boolean = false,
    visualPadding: PaddingValues = PaddingValues(horizontal = 3.dp, vertical = 2.5.dp),
    palette: ZeroConPalette = LocalZeroConPalette.current,
    getRootCoords: () -> LayoutCoordinates?,
    onFlickUpdate: (ActiveFlickPopupState?) -> Unit = {},
    onFlickUpdateById: ((Long, ActiveFlickPopupState?) -> Unit)? = null,
    onFlickComplete: (FlickKeyDef, FlickDirection) -> Unit,
    onFlickCompleteWithBias: ((FlickKeyDef, FlickDirection, TouchKeyBias?) -> Unit)? = null,
    onFlickCompleteWithGeometry: ((FlickKeyDef, FlickDirection, FlickTouchGeometry?) -> Unit)? = null
) {
    val currentKeyDef by rememberUpdatedState(keyDef)
    val currentGetRootCoords by rememberUpdatedState(getRootCoords)
    val currentOnFlickUpdate by rememberUpdatedState(onFlickUpdate)
    val currentOnFlickUpdateById by rememberUpdatedState(onFlickUpdateById)
    val currentOnFlickComplete by rememberUpdatedState(onFlickComplete)
    val currentOnFlickCompleteWithBias by rememberUpdatedState(onFlickCompleteWithBias)
    val currentOnFlickCompleteWithGeometry by rememberUpdatedState(onFlickCompleteWithGeometry)
    val haptic by rememberUpdatedState(LocalOnKeyPressHaptic.current)
    val thresholdDp by rememberUpdatedState(LocalFlickThresholdDp.current)

    var cellCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var isPressed by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    KeyCapFrame(
        modifier = modifier
            .fillMaxHeight()
            .onGloballyPositioned { cellCoordinates = it }
            .semantics(mergeDescendants = true) {
                contentDescription = "${keyDef.label}を入力"
                role = Role.Button
                onClick {
                    val withGeometryHandler = currentOnFlickCompleteWithGeometry
                    val withBiasHandler = currentOnFlickCompleteWithBias
                    when {
                        withGeometryHandler != null -> withGeometryHandler(currentKeyDef, FlickDirection.CENTER, null)
                        withBiasHandler != null -> withBiasHandler(currentKeyDef, FlickDirection.CENTER, null)
                        else -> currentOnFlickComplete(currentKeyDef, FlickDirection.CENTER)
                    }
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val gestureKeyDef = currentKeyDef
                    val startOffset = down.position
                    var currentDir = FlickDirection.CENTER
                    var lastDx = 0f
                    var lastDy = 0f
                    val gestureId = nextFlickGestureId.incrementAndGet()

                    haptic()
                    isPressed = true

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

                    val initialPopup = ActiveFlickPopupState(gestureKeyDef, centerInRoot, currentDir, rootWidthPx, rootHeightPx)
                    currentOnFlickUpdateById?.invoke(gestureId, initialPopup)
                    currentOnFlickUpdate(initialPopup)

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) break
                            val thresholdPx = with(density) { thresholdDp.toPx() }
                            if (!change.pressed) {
                                change.consume()
                                lastDx = change.position.x - startOffset.x
                                lastDy = change.position.y - startOffset.y
                                val geometry = cellCoordinates?.size?.let { sz ->
                                    FlickTouchGeometry(
                                        dx = lastDx,
                                        dy = lastDy,
                                        thresholdPx = thresholdPx,
                                        startX = startOffset.x,
                                        startY = startOffset.y,
                                        widthPx = sz.width.toFloat(),
                                        heightPx = sz.height.toFloat()
                                    )
                                }
                                val withGeometryHandler = currentOnFlickCompleteWithGeometry
                                val withBiasHandler = currentOnFlickCompleteWithBias
                                when {
                                    withGeometryHandler != null -> withGeometryHandler(gestureKeyDef, currentDir, geometry)
                                    withBiasHandler != null -> {
                                        val bias = geometry?.let { g ->
                                            computeFlickTouchBias(
                                                keyDef = gestureKeyDef,
                                                committedDir = currentDir,
                                                dx = g.dx,
                                                dy = g.dy,
                                                thresholdPx = g.thresholdPx,
                                                startX = g.startX,
                                                startY = g.startY,
                                                widthPx = g.widthPx,
                                                heightPx = g.heightPx
                                            )
                                        }
                                        withBiasHandler(gestureKeyDef, currentDir, bias)
                                    }
                                    else -> currentOnFlickComplete(gestureKeyDef, currentDir)
                                }
                                break
                            }
                            change.consume()
                            val pos = change.position
                            lastDx = pos.x - startOffset.x
                            lastDy = pos.y - startOffset.y
                            val newDir = calculateFlickDirectionWithHysteresis(lastDx, lastDy, thresholdPx, currentDir)
                            if (newDir != currentDir) {
                                currentDir = newDir
                                val updatedPopup = ActiveFlickPopupState(gestureKeyDef, centerInRoot, currentDir, rootWidthPx, rootHeightPx)
                                currentOnFlickUpdateById?.invoke(gestureId, updatedPopup)
                                currentOnFlickUpdate(updatedPopup)
                            }
                        }
                    } finally {
                        isPressed = false
                        currentOnFlickUpdateById?.invoke(gestureId, null)
                        currentOnFlickUpdate(null)
                    }
                }
            },
        visualPadding = visualPadding,
        baseColor = bgColor,
        isPressed = isPressed,
        isModifier = isActionKey,
        palette = palette
    ) {
        val typography = LocalKeyboardTheme.current.typography
        val textShadow = typography.textShadowOrNull()
        val hintColor = typography.hintTextColor.toComposeColor()
        val showHints = typography.showFlickDirectionHints
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Keep the compact-size decision inside the shared key component so the
            // IME and the settings preview stay identical.
            val isCompactKey = maxHeight < 44.dp
            val hintTextSize = if (isCompactKey) 7.sp else 8.5.sp
            val directionHints = remember(keyDef) { flickDirectionHints(keyDef) }
            val baseTextSize = when {
                keyDef.label.length > 2 && isCompactKey -> 12.sp
                keyDef.label.length > 2 -> 14.sp
                isCompactKey -> 17.sp
                else -> 19.sp
            }
            // Side hints take half of the free side margin; keep a long label from
            // squeezing them out on narrow keys (floating keyboard, large font).
            val mainTextSize = (fittedFlickLabelSizeSp(
                label = keyDef.label,
                keyWidthDp = maxWidth.value,
                hintSp = hintTextSize.value,
                hasLeftHint = showHints && directionHints.left.isNotEmpty(),
                hasRightHint = showHints && directionHints.right.isNotEmpty(),
                maxSizeSp = baseTextSize.value,
                fontScale = density.fontScale
            ) * typography.labelScale).sp

            if (!showHints || directionHints.isEmpty) {
                // Definitions without directional output keep the legacy centered stack.
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = keyDef.label,
                        color = textColor,
                        fontSize = mainTextSize,
                        fontWeight = FontWeight.Bold,
                        style = androidx.compose.ui.text.TextStyle(shadow = textShadow)
                    )
                    if (showHints && keyDef.subText.isNotEmpty()) {
                        Text(
                            text = keyDef.subText,
                            color = hintColor.copy(alpha = 0.72f),
                            fontSize = hintTextSize,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            } else {
                // Each hint is centered inside the free margin next to the label instead
                // of sitting on the key edge.
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (directionHints.up.isNotEmpty() || directionHints.down.isNotEmpty()) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (directionHints.up.isNotEmpty()) {
                                FlickDirectionHint(directionHints.up, hintColor, hintTextSize)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (directionHints.left.isNotEmpty() || directionHints.right.isNotEmpty()) {
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                if (directionHints.left.isNotEmpty()) {
                                    FlickDirectionHint(directionHints.left, hintColor, hintTextSize)
                                }
                            }
                        }
                        Text(
                            text = keyDef.label,
                            color = textColor,
                            fontSize = mainTextSize,
                            fontWeight = FontWeight.Bold,
                            style = androidx.compose.ui.text.TextStyle(shadow = textShadow)
                        )
                        if (directionHints.left.isNotEmpty() || directionHints.right.isNotEmpty()) {
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                if (directionHints.right.isNotEmpty()) {
                                    FlickDirectionHint(directionHints.right, hintColor, hintTextSize)
                                }
                            }
                        }
                    }
                    if (directionHints.up.isNotEmpty() || directionHints.down.isNotEmpty()) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (directionHints.down.isNotEmpty()) {
                                FlickDirectionHint(directionHints.down, hintColor, hintTextSize)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlickDirectionHint(
    text: String,
    color: Color,
    fontSize: TextUnit
) {
    Text(
        text = text,
        color = color.copy(alpha = 0.44f),
        fontSize = fontSize,
        fontWeight = FontWeight.Normal,
        maxLines = 1
    )
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

    // Only clamp against the bottom edge. Clamping the top edge would push the guide
    // below the pressed key for the top row, which reads as a one-key downward shift.
    val clampedCenterY = if (popupState.rootHeightPx > 0f) {
        (rawCenter.y - liftY).coerceAtMost(popupState.rootHeightPx - halfExtentPx - marginPx)
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
    val popupStyle = LocalKeyboardTheme.current.popup
    val bgColor = if (isSelected) {
        popupStyle.accentColor.toComposeColor()
    } else {
        popupStyle.backgroundColor.toComposeColor().copy(alpha = 0.95f)
    }
    val borderColor = if (isSelected) {
        palette.primaryAccentSoft
    } else {
        popupStyle.borderColor.toComposeColor()
    }
    val cellTextColor = if (isSelected) {
        popupStyle.onAccentColor.toComposeColor()
    } else {
        popupStyle.textColor.toComposeColor()
    }
    Surface(
        modifier = modifier.size(cellSize),
        shape = RoundedCornerShape(popupStyle.cornerRadiusDp.dp),
        color = bgColor,
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = borderColor
        ),
        shadowElevation = if (isSelected) 8.dp else 3.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = cellTextColor,
                fontSize = if (isSelected) 20.sp else 16.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}
