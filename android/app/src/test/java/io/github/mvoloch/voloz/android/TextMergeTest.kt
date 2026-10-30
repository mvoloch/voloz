package io.github.mvoloch.voloz.android

import android.text.InputType
import io.github.mvoloch.voloz.android.text.FieldPolicy
import io.github.mvoloch.voloz.android.text.Merge
import io.github.mvoloch.voloz.android.text.TextMerge
import io.github.mvoloch.voloz.android.net.KeyRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMergeTest {
    @Test
    fun emptyField() {
        assertEquals(Merge("Hello there.", 12), TextMerge.merge("", 0, 0, "Hello there."))
    }

    @Test
    fun appendAtEndAddsSpace() {
        assertEquals(Merge("Hi. How are you?", 16), TextMerge.merge("Hi.", 3, 3, "How are you?"))
    }

    @Test
    fun insertInMiddleKeepsBothSides() {
        // cursor between "hello" and "world"
        val m = TextMerge.merge("hello world", 5, 5, "big")
        assertEquals("hello big world", m.text)
        assertEquals(9, m.cursor)
    }

    @Test
    fun insertBeforeWordAddsTrailingSpace() {
        val m = TextMerge.merge("world", 0, 0, "Hello")
        assertEquals("Hello world", m.text)
        assertEquals(6, m.cursor) // after the inserted text and its seam space
        // A second dictation at that cursor joins cleanly.
        assertEquals("Hello there world", TextMerge.merge(m.text, m.cursor, m.cursor, "there").text)
    }

    @Test
    fun replacesSelectionOnly() {
        val m = TextMerge.merge("one two three", 4, 7, "2")
        assertEquals("one 2 three", m.text)
        assertEquals(5, m.cursor)
    }

    @Test
    fun reversedSelectionIsNormalised() {
        assertEquals("one 2 three", TextMerge.merge("one two three", 7, 4, "2").text)
    }

    @Test
    fun unknownSelectionAppends() {
        assertEquals(Merge("abc def", 7), TextMerge.merge("abc", -1, -1, "def"))
        assertEquals(Merge("abc def", 7), TextMerge.merge("abc", 10, 10, "def"))
    }

    @Test
    fun noSpaceBeforePunctuationOrAfterOpeningBracket() {
        assertEquals("Hello, world", TextMerge.merge("Hello", 5, 5, ", world").text)
        assertEquals("(note)", TextMerge.merge("()", 1, 1, "note").text)
    }

    @Test
    fun existingWhitespaceIsNotDoubled() {
        assertEquals("Hi there", TextMerge.merge("Hi ", 3, 3, "there").text)
        assertEquals("line\nnext", TextMerge.merge("line\n", 5, 5, "next").text)
    }

    @Test
    fun dictationIsTrimmed() {
        assertEquals(Merge("ok", 2), TextMerge.merge("", 0, 0, "  ok \n"))
        assertEquals(Merge("abc", 3), TextMerge.merge("abc", 3, 3, "   "))
    }

    @Test
    fun fieldPolicyRejectsSecretsAndNumbers() {
        val text = InputType.TYPE_CLASS_TEXT
        assertTrue(FieldPolicy.isDictationTarget(text, false))
        assertTrue(FieldPolicy.isDictationTarget(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE, false))
        assertTrue(FieldPolicy.isDictationTarget(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, false))
        assertTrue(FieldPolicy.isDictationTarget(0, false)) // web views often report no input type
        assertFalse(FieldPolicy.isDictationTarget(text, true))
        assertFalse(FieldPolicy.isDictationTarget(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, false))
        assertFalse(FieldPolicy.isDictationTarget(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, false))
        assertFalse(FieldPolicy.isDictationTarget(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, false))
        assertFalse(FieldPolicy.isDictationTarget(InputType.TYPE_CLASS_NUMBER, false))
        assertFalse(FieldPolicy.isDictationTarget(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, false))
        assertFalse(FieldPolicy.isDictationTarget(InputType.TYPE_CLASS_PHONE, false))
    }

    @Test
    fun keyMasking() {
        // Desktop style (settings API): first 6, an ellipsis, last 4; never the whole key.
        assertEquals("abc_4f…nQx9", KeyRules.mask("abc_4fJ2abcdefghijklmnQx9"))
        assertEquals("…89", KeyRules.mask("123456789"))
    }
}
