package io.github.mvoloch.voloz.android

import io.github.mvoloch.voloz.android.audio.AudioMath
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPeakTest {
    private fun pcm(samples: IntArray) = ByteArray(samples.size * 2).also { b ->
        samples.forEachIndexed { i, s ->
            b[2 * i] = (s and 0xFF).toByte()
            b[2 * i + 1] = (s shr 8).toByte()
        }
    }

    @Test
    fun peakIsTheLoudestWindowNotTheAverage() {
        // 1 s of near-silence with one loud 100 ms burst in the middle (16 kHz).
        val samples = IntArray(16_000) { 3 }
        for (i in 8_000 until 9_600) samples[i] = if (i % 2 == 0) 16_384 else -16_384
        val peak = AudioMath.peakLevel(pcm(samples), 16_000)
        // -6 dBFS square wave -> (60 - 6) / 60 = 0.9
        assertEquals(0.9f, peak, 0.01f)
    }

    @Test
    fun silenceHasNoPeak() {
        assertEquals(0f, AudioMath.peakLevel(pcm(IntArray(4_000)), 16_000), 0f)
    }
}
