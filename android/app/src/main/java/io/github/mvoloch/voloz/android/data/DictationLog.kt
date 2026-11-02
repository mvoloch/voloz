package io.github.mvoloch.voloz.android.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The desktop's dictation.log on the phone: one line per dictation (models, times,
 * where the text went) and per error, so a failure can be read and copied after the
 * toast is gone. Kept in the app's files, trimmed to the newest half past [MAX_BYTES].
 * Never holds keys; dictated text only in the "nothing heard" diagnostics.
 */
class DictationLog(context: Context) {
    companion object {
        const val MAX_BYTES = 256 * 1024
    }

    private val file = File(context.applicationContext.filesDir, "dictation.log")
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    @Synchronized
    fun add(line: String) {
        runCatching {
            file.appendText("${time.format(Date())} ${line.replace('\n', ' ')}\n")
            if (file.length() > MAX_BYTES) {
                val text = file.readText()
                file.writeText(text.substring(text.indexOf('\n', text.length / 2) + 1))
            }
        }
    }

    @Synchronized
    fun read(): String = runCatching { file.readText() }.getOrDefault("")

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }
}
