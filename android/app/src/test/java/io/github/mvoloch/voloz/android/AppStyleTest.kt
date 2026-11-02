package io.github.mvoloch.voloz.android

import io.github.mvoloch.voloz.android.refine.AppStyle
import io.github.mvoloch.voloz.android.refine.PromptOptions
import io.github.mvoloch.voloz.android.refine.RefinementPrompt
import io.github.mvoloch.voloz.android.refine.RefinementPromptText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Per-app style: prompt and chat finishing compared with fixtures from the desktop's app_context.py. */
class AppStyleTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader!!.getResource("refinement/$name")) { name }.readText()

    @Test
    fun eachStyleBuildsTheDesktopPrompt() {
        val styles = Json.parseToJsonElement(fixture("prompt_styles.json")).jsonObject
        assertEquals(setOf("email", "chat", "code", "assistant"), styles.keys)
        for ((name, prompt) in styles) {
            val style = AppStyle.valueOf(name.uppercase())
            assertEquals(
                name,
                prompt.jsonPrimitive.content + "\n\n" + RefinementPromptText.KEEP_LANGUAGE,
                RefinementPrompt.buildSystemPrompt(PromptOptions(listOf("Voloz"), appStyle = style)),
            )
        }
    }

    @Test
    fun chatFinishingMatchesTheDesktop() {
        for (case in Json.parseToJsonElement(fixture("finish_chat.json")).jsonArray) {
            val (input, expected) = case.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(input, expected, AppStyle.finish(input, AppStyle.CHAT))
        }
        assertEquals("Beleza, valeu.", AppStyle.finish("Beleza, valeu.", AppStyle.EMAIL))
        assertEquals("Beleza, valeu.", AppStyle.finish("Beleza, valeu.", null))
    }

    @Test
    fun androidPackagesMapToStyles() {
        assertEquals(AppStyle.EMAIL, AppStyle.forPackage("com.google.android.gm"))
        assertEquals(AppStyle.CHAT, AppStyle.forPackage("com.whatsapp"))
        assertEquals(AppStyle.CODE, AppStyle.forPackage("com.termux"))
        assertEquals(AppStyle.ASSISTANT, AppStyle.forPackage("com.anthropic.claude"))
        assertNull(AppStyle.forPackage("com.android.chrome"))
        assertNull(AppStyle.forPackage(null))
    }
}
