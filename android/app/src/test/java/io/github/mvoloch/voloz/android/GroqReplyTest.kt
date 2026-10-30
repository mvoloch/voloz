package io.github.mvoloch.voloz.android

import io.github.mvoloch.voloz.android.net.GroqClient
import io.github.mvoloch.voloz.android.text.TextMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GroqReplyTest {
    private fun reply(content: String, finish: String) =
        """{"choices":[{"index":0,"message":{"role":"assistant","content":"$content"},"finish_reason":"$finish"}]}"""

    @Test
    fun completeReplyIsReturnedTrimmed() {
        assertEquals("Olá, tudo bem?", GroqClient().refinedText(reply(" Olá, tudo bem? ", "stop")))
    }

    @Test(expected = IOException::class)
    fun replyCutAtMaxTokensIsRejected() {
        // Inserting this would silently drop the end of a long dictation.
        GroqClient().refinedText(reply("Primeira parte do texto e", "length"))
    }

    @Test(expected = IOException::class)
    fun emptyReplyIsRejected() {
        GroqClient().refinedText(reply("  ", "stop"))
    }

    @Test
    fun selectionMustLieInsideTheKnownText() {
        assertTrue(TextMerge.selectionFits(0, 0, 0))
        assertTrue(TextMerge.selectionFits(5, 2, 5))
        // The app hides its text (node.text == null) but reports a real cursor.
        assertFalse(TextMerge.selectionFits(0, 12, 12))
        assertFalse(TextMerge.selectionFits(5, -1, -1))
        assertFalse(TextMerge.selectionFits(5, 3, 6))
    }
}
