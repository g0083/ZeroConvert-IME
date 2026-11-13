package com.zeroconvert.ime

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
            onFlickComplete = onFlickComplete
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
            onFlickComplete = onFlickComplete
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
            onFlickComplete = onFlickComplete
        )

        rightKey(Modifier.weight(1.05f).fillMaxHeight(), rightPadding)
    }
}

@Composable
private fun FlickSideSymbolsButton(
    modifier: Modifier,
    visualPadding: PaddingValues,
    actionKeyBg: Color,
    textColor: Color,
    onOpenSymbols: () -> Unit
) {
    KeyboardActionKey(
        modifier = modifier,
        visualPadding = visualPadding,
        cornerRadius = 8.dp,
        baseColor = actionKeyBg,
        onClick = onOpenSymbols
    ) {
        Text(text = "!?#", color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FlickSideEmojiButton(
    modifier: Modifier,
    visualPadding: PaddingValues,
    actionKeyBg: Color,
    textColor: Color,
    onOpenEmoji: () -> Unit
) {
    KeyboardActionKey(
        modifier = modifier,
        visualPadding = visualPadding,
        cornerRadius = 8.dp,
        baseColor = actionKeyBg,
        onClick = onOpenEmoji
    ) {
        Icon(imageVector = Icons.Default.Mood, contentDescription = "Emoji", tint = textColor, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun FlickSideEnterButton(
    modifier: Modifier,
    visualPadding: PaddingValues,
    palette: ZeroConPalette,
    onEnter: () -> Unit
) {
    KeyboardActionKey(
        modifier = modifier,
        visualPadding = visualPadding,
        cornerRadius = 8.dp,
        baseColor = palette.primaryAccent,
        isAccent = true,
        onClick = onEnter
    ) {
        Text(
            text = "確定",
            color = palette.onAccent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
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
    onLongPressSpace: () -> Unit
) {
    KeyboardActionKey(
        modifier = modifier,
        visualPadding = visualPadding,
        cornerRadius = 8.dp,
        baseColor = actionKeyBg,
        contentDescription = "空白",
        onClick = onSpace,
        onLongClick = onLongPressSpace
    ) {
        Text(text = "␣", color = textColor, fontSize = 20.sp)
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
        cornerRadius = 8.dp,
        baseColor = actionKeyBg,
        palette = palette,
        hapticOnPress = false
    ) {
        Icon(imageVector = Icons.Default.Backspace, contentDescription = "Backspace", tint = textColor, modifier = Modifier.size(22.dp))
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
    RepeatingIconButton(
        onClick = onClick,
        modifier = modifier,
        initialDelayMs = 300L,
        repeatIntervalMs = 50L,
        visualPadding = visualPadding,
        cornerRadius = 8.dp,
        baseColor = actionKeyBg,
        palette = palette
    ) {
        Text(text = text, color = textColor, fontSize = 14.sp)
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
    onReplaceLastKana: (String) -> Unit,
    onToggleDakuten: () -> Unit,
    onKanaModifier: (KanaModifierKind) -> Unit = {},
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
    // State for toggle input (when isFlickOnly is false)
    var lastTappedKey by remember { mutableStateOf<String?>(null) }
    var currentCycleIndex by remember { mutableStateOf(0) }
    var lastTapTimestamp by remember { mutableStateOf(0L) }

    val handleFlickResult = { def: FlickKeyDef, dir: FlickDirection ->
        if (def == FlickKeyDefinitions.keyMod) {
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

    FlickKeyboardContainer(palette = palette) { getRootCoords, onFlickUpdateById ->
        FlickGridRow(
            rowIndex = 0,
            keys = Triple(FlickKeyDefinitions.keyA, FlickKeyDefinitions.keyKa, FlickKeyDefinitions.keySa),
            keyBgColor = keyBgColor,
            actionKeyBg = actionKeyBg,
            textColor = textColor,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = handleFlickResult,
            leftKey = { mod, pad -> FlickSideSymbolsButton(mod, pad, actionKeyBg, textColor, onOpenSymbols) },
            rightKey = { mod, pad -> FlickSideBackspaceButton(mod, pad, actionKeyBg, textColor, palette, onDelete) }
        )

        FlickGridRow(
            rowIndex = 1,
            keys = Triple(FlickKeyDefinitions.keyTa, FlickKeyDefinitions.keyNa, FlickKeyDefinitions.keyHa),
            keyBgColor = keyBgColor,
            actionKeyBg = actionKeyBg,
            textColor = textColor,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = handleFlickResult,
            leftKey = { mod, pad -> FlickSideCursorButton("◀", mod, pad, actionKeyBg, textColor, palette, onCursorLeft) },
            rightKey = { mod, pad -> FlickSideSpaceButton(mod, pad, actionKeyBg, textColor, onSpace, onLongPressSpace) }
        )

        FlickGridRow(
            rowIndex = 2,
            keys = Triple(FlickKeyDefinitions.keyMa, FlickKeyDefinitions.keyYa, FlickKeyDefinitions.keyRa),
            keyBgColor = keyBgColor,
            actionKeyBg = actionKeyBg,
            textColor = textColor,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = handleFlickResult,
            leftKey = { mod, pad -> FlickSideCursorButton("▶", mod, pad, actionKeyBg, textColor, palette, onCursorRight) },
            rightKey = { mod, pad -> FlickSideEmojiButton(mod, pad, actionKeyBg, textColor, onOpenEmoji) }
        )

        FlickGridRow(
            rowIndex = 3,
            keys = Triple(FlickKeyDefinitions.keyMod, FlickKeyDefinitions.keyWa, FlickKeyDefinitions.keyPunc),
            keyBgColor = keyBgColor,
            actionKeyBg = actionKeyBg,
            textColor = textColor,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = handleFlickResult,
            firstKeyBg = actionKeyBg,
            firstIsActionKey = true,
            leftKey = { mod, pad ->
                Aa1Button(
                    mode = KeyboardMode.JAPANESE,
                    modifier = mod,
                    actionKeyBg = actionKeyBg,
                    fillAvailableHeight = true,
                    visualPadding = pad,
                    palette = palette,
                    onClick = onCycleMode
                )
            },
            rightKey = { mod, pad -> FlickSideEnterButton(mod, pad, palette, onEnter) }
        )
    }
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

    FlickKeyboardContainer(palette = palette) { getRootCoords, onFlickUpdateById ->
        FlickGridRow(
            rowIndex = 0,
            keys = Triple(
                FlickEnglishKeyDefinitions.getKey1(),
                FlickEnglishKeyDefinitions.getKey2(isShifted),
                FlickEnglishKeyDefinitions.getKey3(isShifted)
            ),
            keyBgColor = keyBgColor,
            actionKeyBg = actionKeyBg,
            textColor = textColor,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = handleFlickResult,
            leftKey = { mod, pad -> FlickSideSymbolsButton(mod, pad, actionKeyBg, textColor, onOpenSymbols) },
            rightKey = { mod, pad -> FlickSideBackspaceButton(mod, pad, actionKeyBg, textColor, palette, onDelete) }
        )

        FlickGridRow(
            rowIndex = 1,
            keys = Triple(
                FlickEnglishKeyDefinitions.getKey4(isShifted),
                FlickEnglishKeyDefinitions.getKey5(isShifted),
                FlickEnglishKeyDefinitions.getKey6(isShifted)
            ),
            keyBgColor = keyBgColor,
            actionKeyBg = actionKeyBg,
            textColor = textColor,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = handleFlickResult,
            leftKey = { mod, pad -> FlickSideCursorButton("◀", mod, pad, actionKeyBg, textColor, palette, onCursorLeft) },
            rightKey = { mod, pad -> FlickSideSpaceButton(mod, pad, actionKeyBg, textColor, onSpace, onLongPressSpace) }
        )

        FlickGridRow(
            rowIndex = 2,
            keys = Triple(
                FlickEnglishKeyDefinitions.getKey7(isShifted),
                FlickEnglishKeyDefinitions.getKey8(isShifted),
                FlickEnglishKeyDefinitions.getKey9(isShifted)
            ),
            keyBgColor = keyBgColor,
            actionKeyBg = actionKeyBg,
            textColor = textColor,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = handleFlickResult,
            leftKey = { mod, pad -> FlickSideCursorButton("▶", mod, pad, actionKeyBg, textColor, palette, onCursorRight) },
            rightKey = { mod, pad -> FlickSideEmojiButton(mod, pad, actionKeyBg, textColor, onOpenEmoji) }
        )

        FlickGridRow(
            rowIndex = 3,
            keys = Triple(
                FlickEnglishKeyDefinitions.keyCase,
                FlickEnglishKeyDefinitions.key0,
                FlickEnglishKeyDefinitions.keyPunc
            ),
            keyBgColor = keyBgColor,
            actionKeyBg = actionKeyBg,
            textColor = textColor,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = handleFlickResult,
            firstKeyBg = if (isShifted) palette.primaryAccent.copy(alpha = 0.28f) else actionKeyBg,
            firstKeyTextColor = if (isShifted) {
                if (palette.isDark) palette.primaryAccentSoft else palette.primaryAccent
            } else {
                textColor
            },
            firstIsActionKey = true,
            leftKey = { mod, pad ->
                Aa1Button(
                    mode = KeyboardMode.ENGLISH,
                    modifier = mod,
                    actionKeyBg = actionKeyBg,
                    fillAvailableHeight = true,
                    visualPadding = pad,
                    palette = palette,
                    onClick = onCycleMode
                )
            },
            rightKey = { mod, pad -> FlickSideEnterButton(mod, pad, palette, onEnter) }
        )
    }
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
    onLongPressSpace: () -> Unit = {},
    onEnter: () -> Unit,
    onCycleMode: () -> Unit,
    onOpenSymbols: () -> Unit,
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

    FlickKeyboardContainer(palette = palette) { getRootCoords, onFlickUpdateById ->
        FlickGridRow(
            rowIndex = 0,
            keys = Triple(FlickNumericKeyDefinitions.key1, FlickNumericKeyDefinitions.key2, FlickNumericKeyDefinitions.key3),
            keyBgColor = keyBgColor,
            actionKeyBg = actionKeyBg,
            textColor = textColor,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = handleFlickResult,
            leftKey = { mod, pad ->
                RepeatingIconButton(
                    onClick = onDelete,
                    modifier = mod,
                    visualPadding = pad,
                    cornerRadius = 8.dp,
                    baseColor = actionKeyBg,
                    palette = palette,
                    hapticOnPress = false
                ) {
                    Text(text = "↶", color = textColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            },
            rightKey = { mod, pad -> FlickSideBackspaceButton(mod, pad, actionKeyBg, textColor, palette, onDelete) }
        )

        FlickGridRow(
            rowIndex = 1,
            keys = Triple(FlickNumericKeyDefinitions.key4, FlickNumericKeyDefinitions.key5, FlickNumericKeyDefinitions.key6),
            keyBgColor = keyBgColor,
            actionKeyBg = actionKeyBg,
            textColor = textColor,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = handleFlickResult,
            leftKey = { mod, pad -> FlickSideCursorButton("◀", mod, pad, actionKeyBg, textColor, palette, onCursorLeft) },
            rightKey = { mod, pad -> FlickSideCursorButton("▶", mod, pad, actionKeyBg, textColor, palette, onCursorRight) }
        )

        FlickGridRow(
            rowIndex = 2,
            keys = Triple(FlickNumericKeyDefinitions.key7, FlickNumericKeyDefinitions.key8, FlickNumericKeyDefinitions.key9),
            keyBgColor = keyBgColor,
            actionKeyBg = actionKeyBg,
            textColor = textColor,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = handleFlickResult,
            leftKey = { mod, pad -> FlickSideSymbolsButton(mod, pad, actionKeyBg, textColor, onOpenSymbols) },
            rightKey = { mod, pad ->
                FlickSideSpaceButton(mod, pad, actionKeyBg, textColor, { onCommitText(" ") }, onLongPressSpace)
            }
        )

        FlickGridRow(
            rowIndex = 3,
            keys = Triple(FlickNumericKeyDefinitions.keyBrackets, FlickNumericKeyDefinitions.key0, FlickNumericKeyDefinitions.keyPunc),
            keyBgColor = keyBgColor,
            actionKeyBg = actionKeyBg,
            textColor = textColor,
            palette = palette,
            getRootCoords = getRootCoords,
            onFlickUpdateById = onFlickUpdateById,
            onFlickComplete = handleFlickResult,
            leftKey = { mod, pad ->
                Aa1Button(
                    mode = KeyboardMode.NUMERIC,
                    modifier = mod,
                    actionKeyBg = actionKeyBg,
                    fillAvailableHeight = true,
                    visualPadding = pad,
                    palette = palette,
                    onClick = onCycleMode
                )
            },
            rightKey = { mod, pad -> FlickSideEnterButton(mod, pad, palette, onEnter) }
        )
    }
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
    onFlickComplete: (FlickKeyDef, FlickDirection) -> Unit
) {
    val currentKeyDef by rememberUpdatedState(keyDef)
    val currentGetRootCoords by rememberUpdatedState(getRootCoords)
    val currentOnFlickUpdate by rememberUpdatedState(onFlickUpdate)
    val currentOnFlickUpdateById by rememberUpdatedState(onFlickUpdateById)
    val currentOnFlickComplete by rememberUpdatedState(onFlickComplete)
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
                    currentOnFlickComplete(currentKeyDef, FlickDirection.CENTER)
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
                            if (!change.pressed) {
                                change.consume()
                                currentOnFlickComplete(gestureKeyDef, currentDir)
                                break
                            }
                            change.consume()
                            val thresholdPx = with(density) { thresholdDp.toPx() }
                            val pos = change.position
                            val dx = pos.x - startOffset.x
                            val dy = pos.y - startOffset.y
                            val newDir = calculateFlickDirectionWithHysteresis(dx, dy, thresholdPx, currentDir)
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
        cornerRadius = 8.dp,
        baseColor = bgColor,
        isPressed = isPressed,
        palette = palette
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
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
