package io.github.mvoloch.voloz.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** One finished take: raw PCM16 mono at [sampleRate]. */
class Take(val pcm: ByteArray, val sampleRate: Int, val durationMs: Long, val silent: Boolean)

/**
 * 16 kHz mono PCM16 capture into memory on a background thread.
 * [onLevel] and [onLimit] are called on the capture thread.
 */
class Recorder(
    private val sampleRate: Int = SAMPLE_RATE,
    private val maxDurationMs: Long = MAX_DURATION_MS,
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_DURATION_MS = 10 * 60 * 1000L
    }

    var onLevel: ((Float) -> Unit)? = null
    var onLimit: (() -> Unit)? = null

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var heardSignal = false
    private val buffer = ByteArrayOutputStream(sampleRate * 2 * 20)

    /** Bytes captured so far that held any non-zero sample. */
    val hasSignal: Boolean get() = heardSignal
    val capturedMs: Long get() = AudioMath.durationMs(synchronized(buffer) { buffer.size() }, sampleRate)

    @SuppressLint("MissingPermission") // checked by DictationController before start()
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) throw IllegalStateException("16 kHz PCM16 capture unsupported")
        val chunk = sampleRate / 10 * 2 // 100 ms
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, chunk) * 4,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord not initialized")
        }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            throw IllegalStateException("Microphone busy")
        }
        audioRecord = rec
        running = true
        val maxBytes = sampleRate.toLong() * 2 * maxDurationMs / 1000
        thread = Thread({
            val buf = ByteArray(chunk)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                val size = synchronized(buffer) {
                    buffer.write(buf, 0, n)
                    buffer.size()
                }
                if (!heardSignal && AudioMath.hasSignal(buf, n)) heardSignal = true
                onLevel?.invoke(AudioMath.level(buf, n))
                if (size >= maxBytes) {
                    running = false
                    onLimit?.invoke()
                    break
                }
            }
        }, "voloz-recorder").apply { start() }
    }

    /** Stops capture and returns everything recorded. Safe to call more than once. */
    fun stop(): Take {
        running = false
        audioRecord?.let { runCatching { it.stop() } }
        thread?.join(1000)
        thread = null
        audioRecord?.release()
        audioRecord = null
        val pcm = synchronized(buffer) { buffer.toByteArray() }
        return Take(pcm, sampleRate, AudioMath.durationMs(pcm.size, sampleRate), silent = !heardSignal)
    }
}
