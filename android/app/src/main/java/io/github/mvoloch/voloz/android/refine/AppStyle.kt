package io.github.mvoloch.voloz.android.refine

/**
 * Writing style per target app (port of the desktop's backend/services/app_context.py):
 * the app that receives a dictation decides how refinement formats it. The desktop
 * keys its table on Windows executables and macOS bundle ids; Android needs its own
 * package names. Browsers are not classified: unlike the desktop, the window title
 * of a tab is not available here. An app not in the table gets no style.
 */
enum class AppStyle(val instruction: String) {
    EMAIL(RefinementPromptText.STYLE_EMAIL),
    CHAT(RefinementPromptText.STYLE_CHAT),
    CODE(RefinementPromptText.STYLE_CODE),
    ASSISTANT(RefinementPromptText.STYLE_ASSISTANT);

    companion object {
        private val PACKAGES: Map<AppStyle, Set<String>> = mapOf(
            EMAIL to setOf(
                "com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider",
                "ch.protonmail.android", "com.yahoo.mobile.client.android.mail", "net.thunderbird.android",
                "com.fsck.k9", "me.bluemail.mail", "com.readdle.spark",
            ),
            CHAT to setOf(
                "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.plus",
                "org.thunderdog.challegram", "com.Slack", "com.microsoft.teams", "com.discord",
                "org.thoughtcrime.securesms", "com.google.android.apps.messaging", "com.samsung.android.messaging",
                "com.facebook.orca", "jp.naver.line.android", "com.viber.voip",
            ),
            CODE to setOf(
                "com.termux", "org.connectbot", "com.sonelli.juicessh", "com.server.auditor.ssh.client",
                "com.foxdebug.acode", "com.rhmsoft.code",
            ),
            // AI assistants: the dictation is a prompt.
            ASSISTANT to setOf(
                "com.anthropic.claude", "com.openai.chatgpt", "com.google.android.apps.bard",
                "ai.perplexity.app.android", "com.microsoft.copilot",
            ),
        )

        fun forPackage(pkg: String?): AppStyle? = pkg?.let { p -> PACKAGES.entries.firstOrNull { p in it.value }?.key }

        // A chat message this short with no sentence break inside is "a single short
        // sentence": models keep its final period despite the prompt line (desktop
        // measurement, 2026-10-29), so it is dropped here.
        private const val SHORT_CHAT_MAX_CHARS = 120
        private val SENTENCE_BREAK = Regex("[.!?…]\\s")

        /** app_context.py `finish_for_style`: the parts of a style a model doesn't follow reliably. */
        fun finish(text: String, style: AppStyle?): String {
            if (style != CHAT) return text
            if (text.endsWith(".") && !text.endsWith("..") && text.length <= SHORT_CHAT_MAX_CHARS &&
                '\n' !in text && !SENTENCE_BREAK.containsMatchIn(text.dropLast(1))
            ) return text.dropLast(1)
            return text
        }
    }
}
