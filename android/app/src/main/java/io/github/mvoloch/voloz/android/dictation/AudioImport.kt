package io.github.mvoloch.voloz.android.dictation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import io.github.mvoloch.voloz.android.R
import io.github.mvoloch.voloz.android.VolozApp
import io.github.mvoloch.voloz.android.data.HistoryEntry
import io.github.mvoloch.voloz.android.net.DictationPipeline
import io.github.mvoloch.voloz.android.net.UploadAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop "Importar áudio": an audio file (shared to Voloz, e.g. a WhatsApp voice
 * note, or picked on the home screen) goes through the same transcription and
 * refinement as a dictation. The file is sent as it is: Groq and OpenAI read
 * ogg/opus, m4a, mp3, wav, flac and webm. The text lands in the history and on the
 * clipboard.
 */
class AudioImport(private val app: VolozApp) {
    companion object {
        /** Groq's upload limit on the free tier. */
        const val MAX_BYTES = 25L * 1024 * 1024

        /** Upload name for a MIME type or a file name the providers accept; null when unsupported. */
        fun uploadName(mime: String?, displayName: String?): Pair<String, String>? {
            val byMime = when (mime?.lowercase()?.substringBefore(';')) {
                "audio/ogg", "audio/opus", "application/ogg" -> "ogg"
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac", "audio/mp4a-latm" -> "m4a"
                "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
                "audio/flac", "audio/x-flac" -> "flac"
                "audio/webm", "video/webm" -> "webm"
                else -> null
            }
            val ext = byMime ?: when (displayName?.substringAfterLast('.', "")?.lowercase()) {
                "ogg", "oga", "opus" -> "ogg"
                "mp3", "mpga" -> "mp3"
                "m4a", "mp4", "aac" -> "m4a"
                "wav" -> "wav"
                "flac" -> "flac"
                "webm" -> "webm"
                else -> null
            } ?: return null
            val type = when (ext) {
                "ogg" -> "audio/ogg"; "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"; "flac" -> "audio/flac"; else -> "audio/webm"
            }
            return "audio.$ext" to type
        }
    }

    sealed interface Result {
        data class Done(val text: String, val entry: HistoryEntry) : Result
        data class Failed(val message: String) : Result
    }

    /** [onStep]: a string resource for the step now running (called on a background thread). */
    suspend fun run(uri: Uri, mimeHint: String?, onStep: (Int) -> Unit): Result = withContext(Dispatchers.IO) {
        val context: Context = app
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        val (fileName, type) = uploadName(resolver.getType(uri) ?: mimeHint, name)
            ?: return@withContext Result.Failed(context.getString(R.string.import_unsupported))
        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return@runCatching null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return@withContext Result.Failed(context.getString(R.string.import_unreadable))
        val durationMs = runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(context, uri)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally {
                    release()
                }
            }
        }.getOrDefault(0L)

        val s = app.settings.current
        if (!s.hasMainKey) return@withContext Result.Failed(context.getString(R.string.msg_need_key))
        val route = DictationPipeline.Route(s.sttProviders, s.refineProviders) { s.keyFor(it) }
        onStep(R.string.step_transcribing)
        val out = runCatching {
            app.pipeline.run(
                route, UploadAudio(bytes, fileName, type), s.vocabulary, s.refine,
                rules = DictationPipeline.TextRules(s.replacements, s.snippets), flags = s.refineFlags,
                language = s.language.takeIf { it != "auto" },
                onTranscribed = { if (s.refine) onStep(R.string.step_refining) },
            )
        }.getOrElse {
            app.log.add("import failed: ${it.javaClass.simpleName}: ${it.message.orEmpty().take(300)}")
            return@withContext Result.Failed(app.controller.describe(it))
        }
        if (out.text.isBlank()) return@withContext Result.Failed(context.getString(R.string.msg_nothing_heard))
        val entry = HistoryEntry(
            text = out.text, raw = out.raw, timestamp = System.currentTimeMillis(), refined = out.refined,
            targetPackage = null, durationMs = durationMs, refineModel = out.refineModel,
            refineBackup = out.refineBackup, shortcut = out.shortcut, imported = true,
        )
        app.history.add(entry)
        app.log.add("import ${bytes.size} bytes ($type); stt=${out.sttModel} ${out.sttMs} ms; " +
            if (out.refined) "refine ok (${out.refineModel}) ${out.refineMs} ms" else "not refined")
        withContext(Dispatchers.Main) {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Voloz", out.text))
        }
        Result.Done(out.text, entry)
    }
}
