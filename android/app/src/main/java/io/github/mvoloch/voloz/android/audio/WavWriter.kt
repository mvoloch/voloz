package io.github.mvoloch.voloz.android.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/** Wraps raw little-endian PCM16 in a canonical 44-byte RIFF/WAVE header. */
object WavWriter {
    const val HEADER_BYTES = 44

    fun pcm16ToWav(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        require(sampleRate > 0 && channels > 0)
        val blockAlign = channels * 2
        val out = ByteArray(HEADER_BYTES + pcm.size)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcm.size)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM fmt chunk size
            putShort(1) // audio format: PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * blockAlign) // byte rate
            putShort(blockAlign.toShort())
            putShort(16) // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
            put(pcm)
        }
        return out
    }
}

/** Pure helpers over PCM16 mono buffers (no Android types, unit-tested). */
object AudioMath {
    fun durationMs(pcmBytes: Int, sampleRate: Int): Long = pcmBytes / 2 * 1000L / sampleRate

    private fun sample(buf: ByteArray, i: Int): Int =
        (buf[2 * i].toInt() and 0xFF) or (buf[2 * i + 1].toInt() shl 8)

    /** True when any sample in the first [length] bytes is non-zero. A muted mic delivers exact zeros. */
    fun hasSignal(buf: ByteArray, length: Int): Boolean {
        for (i in 0 until length / 2) if (sample(buf, i) != 0) return true
        return false
    }

    /** RMS level mapped from -60..0 dBFS to 0..1. */
    fun level(buf: ByteArray, length: Int): Float {
        val n = length / 2
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val s = sample(buf, i).toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / n)
        if (rms < 1.0) return 0f
        val db = 20 * log10(rms / 32768.0)
        return ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
    }
}
