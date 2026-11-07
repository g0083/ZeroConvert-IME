package com.zeroconvert.ime

import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class FlickInputAndModifierTest {

    @Test
    fun testKanaModifierHiraganaCycles() {
        // か -> が -> か
        assertEquals('が', KanaModifier.toggle('か'))
        assertEquals('か', KanaModifier.toggle('が'))

        // は -> ば -> ぱ -> は
        assertEquals('ば', KanaModifier.toggle('は'))
        assertEquals('ぱ', KanaModifier.toggle('ば'))
        assertEquals('は', KanaModifier.toggle('ぱ'))

        // つ -> っ -> づ -> つ
        assertEquals('っ', KanaModifier.toggle('つ'))
        assertEquals('づ', KanaModifier.toggle('っ'))
        assertEquals('つ', KanaModifier.toggle('づ'))

        // あ -> ぁ -> あ
        assertEquals('ぁ', KanaModifier.toggle('あ'))
        assertEquals('あ', KanaModifier.toggle('ぁ'))

        // う -> ぅ -> ゔ -> う
        assertEquals('ぅ', KanaModifier.toggle('う'))
        assertEquals('ゔ', KanaModifier.toggle('ぅ'))
        assertEquals('う', KanaModifier.toggle('ゔ'))

        // や -> ゃ -> や
        assertEquals('ゃ', KanaModifier.toggle('や'))
        assertEquals('や', KanaModifier.toggle('ゃ'))

        // よ -> ょ -> よ
        assertEquals('ょ', KanaModifier.toggle('よ'))
        assertEquals('よ', KanaModifier.toggle('ょ'))

        // わ -> ゎ -> わ
        assertEquals('ゎ', KanaModifier.toggle('わ'))
        assertEquals('わ', KanaModifier.toggle('ゎ'))

        // Unmodified characters remain as-is
        assertEquals('ま', KanaModifier.toggle('ま'))
        assertEquals('な', KanaModifier.toggle('な'))
        assertEquals('ら', KanaModifier.toggle('ら'))
    }

    @Test
    fun testKanaModifierKatakanaCycles() {
        assertEquals('ガ', KanaModifier.toggle('カ'))
        assertEquals('カ', KanaModifier.toggle('ガ'))

        assertEquals('バ', KanaModifier.toggle('ハ'))
        assertEquals('パ', KanaModifier.toggle('バ'))
        assertEquals('ハ', KanaModifier.toggle('パ'))

        assertEquals('ッ', KanaModifier.toggle('ツ'))
        assertEquals('ヅ', KanaModifier.toggle('ッ'))
        assertEquals('ツ', KanaModifier.toggle('ヅ'))
    }

    @Test
    fun testKanaModifierDirectForms() {
        // Gboard's 小゛゜ key flicks select the target form directly.
        assertEquals('っ', KanaModifier.toSmall('つ'))
        assertEquals('っ', KanaModifier.toSmall('っ'))
        assertEquals('ゃ', KanaModifier.toSmall('や'))
        assertEquals('ぁ', KanaModifier.toSmall('あ'))
        assertEquals('ま', KanaModifier.toSmall('ま'))
        assertEquals('ッ', KanaModifier.toSmall('ツ'))

        assertEquals('が', KanaModifier.toDakuten('か'))
        assertEquals('が', KanaModifier.toDakuten('が'))
        assertEquals('ぎ', KanaModifier.toDakuten('き'))
        assertEquals('づ', KanaModifier.toDakuten('つ'))
        assertEquals('づ', KanaModifier.toDakuten('っ'))
        assertEquals('ゔ', KanaModifier.toDakuten('う'))
        assertEquals('ば', KanaModifier.toDakuten('ぱ'))
        assertEquals('あ', KanaModifier.toDakuten('あ'))
        assertEquals('ガ', KanaModifier.toDakuten('カ'))

        assertEquals('ぱ', KanaModifier.toHandakuten('は'))
        assertEquals('ぱ', KanaModifier.toHandakuten('ば'))
        assertEquals('ぱ', KanaModifier.toHandakuten('ぱ'))
        assertEquals('ぴ', KanaModifier.toHandakuten('ひ'))
        assertEquals('か', KanaModifier.toHandakuten('か'))
        assertEquals('パ', KanaModifier.toHandakuten('ハ'))

        assertEquals('が', KanaModifier.modify('か', KanaModifierKind.DAKUTEN))
        assertEquals('ぱ', KanaModifier.modify('は', KanaModifierKind.HANDAKUTEN))
        assertEquals('っ', KanaModifier.modify('つ', KanaModifierKind.SMALL))
        assertEquals('が', KanaModifier.modify('か', KanaModifierKind.TOGGLE))
    }

    @Test
    fun testModifierKeyFlickDirectionsMatchGboard() {
        val keyMod = FlickKeyDefinitions.keyMod
        assertEquals("゛", keyMod.left)
        assertEquals("小", keyMod.up)
        assertEquals("゜", keyMod.right)
        assertEquals("", keyMod.down)
    }

    @Test
    fun testCalculateFlickDirection() {
        val threshold = 20f

        // Below threshold: CENTER
        assertEquals(FlickDirection.CENTER, calculateFlickDirection(5f, -10f, threshold))
        assertEquals(FlickDirection.CENTER, calculateFlickDirection(0f, 0f, threshold))

        // Exceeds threshold:
        // Left
        assertEquals(FlickDirection.LEFT, calculateFlickDirection(-30f, 5f, threshold))
        // Right
        assertEquals(FlickDirection.RIGHT, calculateFlickDirection(35f, -10f, threshold))
        // Up
        assertEquals(FlickDirection.UP, calculateFlickDirection(8f, -40f, threshold))
        // Down
        assertEquals(FlickDirection.DOWN, calculateFlickDirection(-12f, 45f, threshold))
    }

    @Test
    fun testDirectKanaComposingAndDakutenWorkflow() {
        val c = ComposingText()

        // Type "か" (via 12-key center tap)
        c.insertAtCursorPosition("か", InputStyle.DIRECT)
        assertEquals("か", c.convertTarget)

        // User taps 「小゛゜」 toggle: "か" -> "が"
        c.deleteBackwardFromCursorPosition(1)
        c.insertAtCursorPosition(KanaModifier.toggle('か').toString(), InputStyle.DIRECT)
        assertEquals("が", c.convertTarget)

        // Type "っ" (by typing "つ" and tapping toggle)
        c.insertAtCursorPosition("つ", InputStyle.DIRECT)
        assertEquals("がつ", c.convertTarget)
        c.deleteBackwardFromCursorPosition(1)
        c.insertAtCursorPosition(KanaModifier.toggle('つ').toString(), InputStyle.DIRECT)
        assertEquals("がっ", c.convertTarget)

        // Type "こ" (by flicking down on "か" key)
        c.insertAtCursorPosition("こ", InputStyle.DIRECT)
        assertEquals("がっこ", c.convertTarget)

        // Type "う" (by flicking up on "あ" key)
        c.insertAtCursorPosition("う", InputStyle.DIRECT)
        assertEquals("がっこう", c.convertTarget)
    }

    @Test
    fun testFlickKeyDefinitionsCoverage() {
        // Verify all 12 key definitions
        val keyKa = FlickKeyDefinitions.keyKa
        assertEquals("か", keyKa.center)
        assertEquals("き", keyKa.left)
        assertEquals("く", keyKa.up)
        assertEquals("け", keyKa.right)
        assertEquals("こ", keyKa.down)

        val keyWa = FlickKeyDefinitions.keyWa
        assertEquals("わ", keyWa.center)
        assertEquals("を", keyWa.left)
        assertEquals("ん", keyWa.up)
        assertEquals("ー", keyWa.right)
        assertEquals("〜", keyWa.down)
    }

    @Test
    fun testEnglish12KeyDefinitionsCoverage() {
        val key2Lower = FlickEnglishKeyDefinitions.getKey2(false)
        assertEquals("a", key2Lower.center)
        assertEquals("b", key2Lower.left)
        assertEquals("c", key2Lower.up)
        assertEquals("2", key2Lower.right)

        val key2Upper = FlickEnglishKeyDefinitions.getKey2(true)
        assertEquals("A", key2Upper.center)
        assertEquals("B", key2Upper.left)
        assertEquals("C", key2Upper.up)
        assertEquals("2", key2Upper.right)

        val key1 = FlickEnglishKeyDefinitions.getKey1()
        assertEquals("@", key1.center)
        assertEquals(".", key1.left)
        assertEquals("/", key1.up)
        assertEquals(":", key1.right)
        assertEquals("_", key1.down)
    }

    @Test
    fun testNumericPadFlickDefinitionsCoverage() {
        // Key 1: 1, ☆, ♪, →, ←
        val k1 = FlickNumericKeyDefinitions.key1
        assertEquals("1", k1.center)
        assertEquals("☆", k1.left)
        assertEquals("♪", k1.up)
        assertEquals("→", k1.right)

        // Key 2: 2, ¥, $, €, £
        val k2 = FlickNumericKeyDefinitions.key2
        assertEquals("2", k2.center)
        assertEquals("¥", k2.left)
        assertEquals("$", k2.up)
        assertEquals("€", k2.right)

        // Key 5: 5, +, ×, ÷, ±
        val k5 = FlickNumericKeyDefinitions.key5
        assertEquals("5", k5.center)
        assertEquals("+", k5.left)
        assertEquals("×", k5.up)
        assertEquals("÷", k5.right)

        // Key Brackets: (, ), [, ], {
        val kBrackets = FlickNumericKeyDefinitions.keyBrackets
        assertEquals("(", kBrackets.center)
        assertEquals(")", kBrackets.left)
        assertEquals("[", kBrackets.up)
        assertEquals("]", kBrackets.right)

        // Key 0: 0, ~, …, @, °
        val k0 = FlickNumericKeyDefinitions.key0
        assertEquals("0", k0.center)
        assertEquals("~", k0.left)
        assertEquals("…", k0.up)
        assertEquals("@", k0.right)

        // Key Punc: ., ,, -, /, _
        val kPunc = FlickNumericKeyDefinitions.keyPunc
        assertEquals(".", kPunc.center)
        assertEquals(",", kPunc.left)
        assertEquals("-", kPunc.up)
        assertEquals("/", kPunc.right)
    }
}
