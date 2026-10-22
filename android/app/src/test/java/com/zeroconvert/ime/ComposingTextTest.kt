package com.zeroconvert.ime

import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposingTextTest {

    @Test
    fun testBasicTyping() {
        val c = ComposingText()
        "kyouha".forEach { ch ->
            c.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        assertEquals("きょうは", c.convertTarget)
        assertEquals(4, c.convertTargetCursorPosition)
    }

    @Test
    fun testLongSentenceNoTruncation() {
        val c = ComposingText()
        val text = "ashitanotenkigaharedattararyokounyikitaina"
        text.forEach { ch ->
            c.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        assertEquals("あしたのてんきがはれだったらりょこうにぃきたいな", c.convertTarget)
    }

    @Test
    fun testDoubleConsonantSokuon() {
        val c = ComposingText()
        "gakkou".forEach { ch ->
            c.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        assertEquals("がっこう", c.convertTarget)
    }

    @Test
    fun testHatsuonN() {
        val c = ComposingText()
        "kannna".forEach { ch ->
            c.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        assertEquals("かんな", c.convertTarget)

        val c2 = ComposingText()
        "konnya".forEach { ch ->
            c2.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        assertEquals("こんや", c2.convertTarget)

        val c3 = ComposingText()
        "kanji".forEach { ch ->
            c3.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        assertEquals("かんじ", c3.convertTarget)
    }

    @Test
    fun testBackspaceDeletion() {
        val c = ComposingText()
        "kansha".forEach { ch ->
            c.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        assertEquals("かんしゃ", c.convertTarget)

        // Delete 'ゃ'
        c.deleteBackwardFromCursorPosition(1)
        assertEquals("かんし", c.convertTarget)

        // Delete 'し'
        c.deleteBackwardFromCursorPosition(1)
        assertEquals("かん", c.convertTarget)
    }

    @Test
    fun testCursorMovementAndInsertion() {
        val c = ComposingText()
        "ja".forEach { ch ->
            c.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        }
        assertEquals("じゃ", c.convertTarget)

        // Move cursor between 'じ' and 'ゃ'
        c.moveCursorFromCursorPosition(-1)
        assertEquals(1, c.convertTargetCursorPosition)

        // Insert 'u'
        c.insertAtCursorPosition("u", InputStyle.ROMAN2KANA)
        assertEquals("じうゃ", c.convertTarget)
    }
}
