package io.github.mvoloch.voloz.android.dictation

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.mvoloch.voloz.android.R
import io.github.mvoloch.voloz.android.audio.AudioMath
import io.github.mvoloch.voloz.android.audio.Recorder
import io.github.mvoloch.voloz.android.audio.Take
import io.github.mvoloch.voloz.android.audio.WavWriter
import io.github.mvoloch.voloz.android.data.HistoryEntry
import io.github.mvoloch.voloz.android.data.HistoryRepository
import io.github.mvoloch.voloz.android.data.SettingsRepository
import io.github.mvoloch.voloz.android.net.DictationPipeline
import io.github.mvoloch.voloz.android.net.GroqHttpException
import io.github.mvoloch.voloz.android.service.RecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** What the bubble shows. Everything else in the app reads this too. */
sealed interface BubbleState {
    data object Idle : BubbleState
    data class Recording(val level: Float, val handsFree: Boolean) : BubbleState
    data object Processing : BubbleState
    data object Done : BubbleState
    data class Error(val message: String) : BubbleState
    /** Insertion failed; the text is on the clipboard and a tap pastes it. */
    data object PasteReady : BubbleState
}

enum class InsertOutcome { SET_TEXT, PASTED, FAILED, NO_TARGET }

data class InsertResult(val outcome: InsertOutcome, val packageName: String?)

/** Implemented by the accessibility service, the only component that can reach other apps' fields. */
interface TextInserter {
    fun insert(text: String): InsertResult
    fun pasteClipboard(): Boolean
}

/**
 * The dictation state machine: bubble gestures in, text into the focused field out.
 * All methods run on the main thread.
 *
 * Recording runs in this process (the accessibility service's). A microphone
 * foreground service is started alongside for the "Voloz está gravando"
 * notification and as the Android 14+ fallback: if the first [SILENCE_PROBE_MS]
 * come back as exact digital silence (how Android mutes a background mic), the
 * capture is restarted once the foreground service is up. See android/README.md.
 */
class DictationController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    private val pipeline: DictationPipeline,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "VolozDictation"
        const val MIN_DURATION_MS = 500L
        private const val SILENCE_PROBE_MS = 700L
        private const val DONE_MS = 1000L
        private const val ERROR_MS = 2000L
        private const val PASTE_READY_MS = 6000L
    }

    private val _state = MutableStateFlow<BubbleState>(BubbleState.Idle)
    val state: StateFlow<BubbleState> = _state

    var inserter: TextInserter? = null
    /** Shows a short message to the user (a Toast from the service). */
    var onUserMessage: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var recorder: Recorder? = null
    private var retriedAfterSilence = false

    private enum class Fgs { STOPPED, STARTING, RUNNING }
    private var fgs = Fgs.STOPPED
    private var stopFgsWhenStarted = false

    private val toIdle = Runnable { _state.value = BubbleState.Idle }
    private val toPasteReady = Runnable {
        _state.value = BubbleState.PasteReady
        main.postDelayed(toIdle, PASTE_READY_MS)
    }
    private val silenceProbe = Runnable { checkSilence() }

    // ---- gestures -------------------------------------------------------

    fun onTap() {
        when (_state.value) {
            is BubbleState.Recording -> stop()
            BubbleState.Processing -> Unit
            BubbleState.PasteReady -> pasteFromClipboard()
            else -> start(handsFree = true)
        }
    }

    fun onHoldStart() {
        val s = _state.value
        if (s !is BubbleState.Recording && s != BubbleState.Processing) start(handsFree = false)
    }

    fun onHoldEnd() {
        if (_state.value is BubbleState.Recording) stop()
    }

    /** Notification "Terminar" action. */
    fun finishFromNotification() {
        if (_state.value is BubbleState.Recording) stop()
    }

    /** Drops an in-progress take without uploading it (the accessibility service went away). */
    fun abortRecording() {
        main.removeCallbacks(silenceProbe)
        val rec = recorder ?: return
        recorder = null
        rec.stop()
        stopForegroundService()
        cancelTimers()
        _state.value = BubbleState.Idle
    }

    // ---- recording ------------------------------------------------------

    private fun start(handsFree: Boolean) {
        val s = settings.current
        if (s.groqKey.isBlank()) return flashError(context.getString(R.string.msg_need_key))
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return flashError(context.getString(R.string.msg_need_mic))

        cancelTimers()
        startForegroundService()
        retriedAfterSilence = false
        if (!beginCapture()) {
            stopForegroundService()
            return flashError(context.getString(R.string.msg_mic_unavailable))
        }
        _state.value = BubbleState.Recording(0f, handsFree)
        main.postDelayed(silenceProbe, SILENCE_PROBE_MS)
    }

    private fun beginCapture(): Boolean {
        val rec = Recorder()
        rec.onLevel = { level ->
            main.post {
                val cur = _state.value
                if (recorder === rec && cur is BubbleState.Recording) _state.value = cur.copy(level = level)
            }
        }
        rec.onLimit = { main.post { if (recorder === rec) stop() } }
        return try {
            rec.start()
            recorder = rec
            true
        } catch (e: Exception) {
            Log.w(TAG, "capture failed to start", e)
            false
        }
    }

    /** Android delivers exact zeros to a mic it has muted. Restart once the FGS holds the mic. */
    private fun checkSilence() {
        val rec = recorder ?: return
        if (rec.hasSignal || retriedAfterSilence) return
        if (fgs != Fgs.RUNNING) {
            if (fgs == Fgs.STARTING) main.postDelayed(silenceProbe, 300)
            return
        }
        Log.i(TAG, "silent capture; restarting under the foreground service")
        retriedAfterSilence = true
        rec.stop()
        recorder = null
        if (!beginCapture()) {
            stopForegroundService()
            flashError(context.getString(R.string.msg_mic_unavailable))
        }
    }

    private fun stop() {
        main.removeCallbacks(silenceProbe)
        val rec = recorder ?: return
        recorder = null
        val take = rec.stop()
        stopForegroundService()
        when {
            take.durationMs < MIN_DURATION_MS -> flashError(context.getString(R.string.msg_too_short))
            take.silent -> flashError(context.getString(R.string.msg_mic_silent))
            else -> process(take)
        }
    }

    private fun process(take: Take) {
        _state.value = BubbleState.Processing
        val s = settings.current
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    pipeline.run(s.groqKey, WavWriter.pcm16ToWav(take.pcm, take.sampleRate), s.vocabulary, s.refine)
                }
            }
            result.fold(
                onSuccess = { deliver(it, take) },
                onFailure = {
                    // The stack trace is what makes an "Erro inesperado" diagnosable.
                    Log.w(TAG, "processing failed", it)
                    flashError(describe(it))
                },
            )
        }
    }

    private fun deliver(out: DictationPipeline.Output, take: Take) {
        if (out.text.isBlank()) {
            recordNothingHeard(out, take)
            return flashError(context.getString(R.string.msg_nothing_heard))
        }
        val inserted = inserter?.insert(out.text) ?: InsertResult(InsertOutcome.NO_TARGET, null)
        val ok = inserted.outcome == InsertOutcome.SET_TEXT || inserted.outcome == InsertOutcome.PASTED
        history.add(
            HistoryEntry(
                text = out.text,
                raw = out.raw,
                timestamp = System.currentTimeMillis(),
                refined = out.refined,
                targetPackage = if (ok) inserted.packageName else null,
            ),
        )
        if (out.refineError != null) Log.w(TAG, "refinement failed, inserted raw: ${out.refineError}")
        if (ok) {
            if (out.refineError != null) onUserMessage?.invoke(context.getString(R.string.msg_refine_failed))
            _state.value = BubbleState.Done
            main.postDelayed(toIdle, DONE_MS)
            return
        }
        // The text must survive: on the clipboard, with the bubble offering to paste it.
        copyToClipboard(out.text)
        val msg = context.getString(R.string.msg_insert_failed)
        _state.value = BubbleState.Error(msg)
        onUserMessage?.invoke(msg)
        main.postDelayed(toPasteReady, ERROR_MS)
    }

    /**
     * Diagnostic for a take that had sound but no words: kept in the history so a
     * failure on a phone without logcat can still be told apart (quiet mic vs. Groq).
     */
    private fun recordNothingHeard(out: DictationPipeline.Output, take: Take) {
        val seconds = String.format(java.util.Locale.ROOT, "%.1f", take.durationMs / 1000.0).replace('.', ',')
        val peak = (AudioMath.peakLevel(take.pcm, take.sampleRate) * 100).toInt()
        val groq = out.raw.ifBlank { context.getString(R.string.diag_empty) }.take(120)
        val line = context.getString(R.string.diag_nothing_heard, seconds, peak, groq)
        Log.i(TAG, line)
        history.add(HistoryEntry(text = line, raw = out.raw, timestamp = System.currentTimeMillis(), refined = false, targetPackage = null))
    }

    private fun pasteFromClipboard() {
        cancelTimers()
        if (inserter?.pasteClipboard() == true) {
            _state.value = BubbleState.Done
            main.postDelayed(toIdle, DONE_MS)
        } else {
            flashError(context.getString(R.string.msg_paste_failed))
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is GroqHttpException -> when (e.status) {
            401, 403 -> context.getString(R.string.msg_key_invalid)
            429 -> context.getString(R.string.msg_rate_limit)
            413 -> context.getString(R.string.msg_too_large)
            else -> context.getString(R.string.msg_groq_http, e.status)
        }
        is IOException -> context.getString(R.string.msg_network)
        else -> context.getString(R.string.msg_unexpected, e.javaClass.simpleName)
    }

    private fun flashError(message: String) {
        cancelTimers()
        Log.i(TAG, "error: $message")
        _state.value = BubbleState.Error(message)
        onUserMessage?.invoke(message)
        main.postDelayed(toIdle, ERROR_MS)
    }

    private fun cancelTimers() {
        main.removeCallbacks(toIdle)
        main.removeCallbacks(toPasteReady)
    }

    private fun copyToClipboard(text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Voloz", text))
    }

    // ---- microphone foreground service ---------------------------------

    private fun startForegroundService() {
        if (fgs != Fgs.STOPPED) {
            stopFgsWhenStarted = false
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
            fgs = Fgs.STARTING
            stopFgsWhenStarted = false
        } catch (e: Exception) {
            // Android 12+ may refuse a background start; capture continues in-process.
            Log.w(TAG, "foreground service not started", e)
            fgs = Fgs.STOPPED
        }
    }

    val isForegroundServiceActive: Boolean get() = fgs != Fgs.STOPPED

    /** Called by [RecordingService] once startForeground() succeeded or failed. */
    fun onForegroundServiceResult(running: Boolean) {
        if (!running) {
            fgs = Fgs.STOPPED
            return
        }
        fgs = Fgs.RUNNING
        if (stopFgsWhenStarted) stopForegroundService()
    }

    private fun stopForegroundService() {
        when (fgs) {
            // Stopping before startForeground() ran would crash the app; wait for the callback.
            Fgs.STARTING -> stopFgsWhenStarted = true
            Fgs.RUNNING -> {
                context.stopService(Intent(context, RecordingService::class.java))
                fgs = Fgs.STOPPED
                stopFgsWhenStarted = false
            }
            Fgs.STOPPED -> Unit
        }
    }
}
