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
import io.github.mvoloch.voloz.android.data.HistoryEntry
import io.github.mvoloch.voloz.android.data.HistoryRepository
import io.github.mvoloch.voloz.android.data.SettingsRepository
import io.github.mvoloch.voloz.android.data.VolozSettings
import io.github.mvoloch.voloz.android.net.DictationPipeline
import io.github.mvoloch.voloz.android.net.ProviderException
import io.github.mvoloch.voloz.android.service.RecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import io.github.mvoloch.voloz.android.net.DictationCancelled
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
    /** Processing failed or was cancelled; the take is kept and a tap sends it again. */
    data object RetryReady : BubbleState
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
        /** How long a failed take waits for a tap to send it again. */
        private const val RETRY_READY_MS = 60_000L
        /**
         * Upload, transcription and refinement together, over every provider tried: 150 s
         * plus the take's own length, so a long dictation on a slow link isn't cut off.
         */
        private const val PROCESS_DEADLINE_MS = 150_000L
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

    /** The take being processed and its cancel signal; null when nothing is in flight. */
    private var processing: Pair<Take, DictationPipeline.Cancel>? = null
    /** A take whose processing failed, kept so a tap can send it again. */
    private var failedTake: Take? = null
    private val toRetryReady = Runnable {
        _state.value = BubbleState.RetryReady
        main.postDelayed(dropRetry, RETRY_READY_MS)
    }
    private val dropRetry = Runnable {
        failedTake = null
        _state.value = BubbleState.Idle
    }
    private val deadline = Runnable { cancelProcessing(context.getString(R.string.msg_too_slow)) }

    // ---- gestures -------------------------------------------------------

    fun onTap() {
        when (_state.value) {
            is BubbleState.Recording -> stop()
            BubbleState.Processing -> cancelProcessing(context.getString(R.string.msg_cancelled))
            BubbleState.PasteReady -> pasteFromClipboard()
            BubbleState.RetryReady -> retry()
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
        if (!s.hasMainKey) return flashError(context.getString(R.string.msg_need_key))
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return flashError(context.getString(R.string.msg_need_mic))

        cancelTimers()
        failedTake = null // a new take replaces the one waiting for a retry
        startForegroundService()
        retriedAfterSilence = false
        if (!beginCapture()) {
            stopForegroundService()
            return flashError(context.getString(R.string.msg_mic_unavailable))
        }
        _state.value = BubbleState.Recording(0f, handsFree)
        main.postDelayed(silenceProbe, SILENCE_PROBE_MS)
        // The TLS handshake happens while the user speaks, not after they stop.
        scope.launch(Dispatchers.IO) { pipeline.warm(route(s)) }
    }

    private fun route(s: VolozSettings) = DictationPipeline.Route(s.sttProviders, s.refineProviders) { s.keyFor(it) }

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
        cancelTimers()
        _state.value = BubbleState.Processing
        val s = settings.current
        val cancel = pipeline.newCancel()
        processing = take to cancel
        main.postDelayed(deadline, PROCESS_DEADLINE_MS + take.durationMs)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    pipeline.run(route(s), DictationPipeline.uploadAudio(take.pcm, take.sampleRate), s.vocabulary, s.refine, cancel)
                }
            }
            // Cancelled (tap or deadline): cancelProcessing already moved the bubble on.
            if (processing?.second !== cancel) return@launch
            processing = null
            main.removeCallbacks(deadline)
            result.fold(
                onSuccess = {
                    failedTake = null
                    deliver(it, take)
                },
                onFailure = {
                    // The stack trace is what makes an "Erro inesperado" diagnosable.
                    Log.w(TAG, "processing failed", it)
                    failKeepingTake(describe(it), take)
                },
            )
        }
    }

    /** A tap while processing, or the deadline: abort the requests and keep the take for a retry. */
    private fun cancelProcessing(message: String) {
        val (take, cancel) = processing ?: return
        processing = null
        main.removeCallbacks(deadline)
        cancel.cancel()
        failKeepingTake(message, take)
    }

    /** The error shows, then the bubble offers to send the same take again (no speech is lost). */
    private fun failKeepingTake(message: String, take: Take) {
        cancelTimers()
        failedTake = take
        val shown = "$message ${context.getString(R.string.msg_retry_hint)}"
        Log.i(TAG, "error: $message (take kept for a retry)")
        _state.value = BubbleState.Error(shown)
        onUserMessage?.invoke(shown)
        main.postDelayed(toRetryReady, ERROR_MS)
    }

    private fun retry() {
        val take = failedTake ?: return
        failedTake = null
        process(take)
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
                durationMs = take.durationMs,
                refineModel = out.refineModel,
                refineBackup = out.refineBackup,
            ),
        )
        if (out.refineError != null) Log.w(TAG, "refinement failed, inserted raw: ${out.refineError}")
        if (out.sttBackup || out.refineBackup) Log.i(TAG, "served by a backup: stt=${out.sttModel} refine=${out.refineModel}")
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
        is ProviderException -> when {
            e.missingKey -> context.getString(R.string.msg_need_key)
            e.status == 401 || e.status == 403 -> context.getString(R.string.msg_key_invalid)
            e.status == 429 -> context.getString(R.string.msg_rate_limit)
            e.status == 413 -> context.getString(R.string.msg_too_large)
            e.status != null -> context.getString(R.string.msg_provider_http, e.provider?.displayName ?: "", e.status)
            // OkHttp's read/call timeouts: the provider was reached but didn't answer in time.
            e.cause is java.io.InterruptedIOException -> context.getString(R.string.msg_timeout)
            else -> context.getString(R.string.msg_network)
        }
        is DictationCancelled -> context.getString(R.string.msg_cancelled)
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
        main.removeCallbacks(toRetryReady)
        main.removeCallbacks(dropRetry)
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
