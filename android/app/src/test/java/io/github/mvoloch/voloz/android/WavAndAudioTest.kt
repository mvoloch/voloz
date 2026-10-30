package io.github.mvoloch.voloz.android

import io.github.mvoloch.voloz.android.audio.AudioMath
import io.github.mvoloch.voloz.android.audio.WavWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavAndAudioTest {
    private fun ascii(b: ByteArray, from: Int) = String(b, from, 4, Charsets.US_ASCII)

    @Test
    fun headerFieldsFor16kMono() {
        val pcm = ByteArray(3200) { (it % 251).toByte() }
        val wav = WavWriter.pcm16ToWav(pcm, 16_000)
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", ascii(wav, 0))
        assertEquals(36 + pcm.size, bb.getInt(4))
        assertEquals("WAVE", ascii(wav, 8))
        assertEquals("fmt ", ascii(wav, 12))
        assertEquals(16, bb.getInt(16))
        assertEquals(1, bb.getShort(20).toInt()) // PCM
        assertEquals(1, bb.getShort(22).toInt()) // mono
        assertEquals(16_000, bb.getInt(24))
        assertEquals(32_000, bb.getInt(28)) // byte rate
        assertEquals(2, bb.getShort(32).toInt()) // block align
        assertEquals(16, bb.getShort(34).toInt()) // bits
        assertEquals("data", ascii(wav, 36))
        assertEquals(pcm.size, bb.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun emptyPcmIsHeaderOnly() {
        val wav = WavWriter.pcm16ToWav(ByteArray(0), 16_000)
        assertEquals(44, wav.size)
        assertEquals(36, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
    }

    @Test
    fun stereoByteRate() {
        val wav = WavWriter.pcm16ToWav(ByteArray(8), 48_000, channels = 2)
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2, bb.getShort(22).toInt())
        assertEquals(192_000, bb.getInt(28))
        assertEquals(4, bb.getShort(32).toInt())
    }

    @Test
    fun durationAndThresholds() {
        assertEquals(1000L, AudioMath.durationMs(32_000, 16_000))
        assertEquals(499L, AudioMath.durationMs(15_968, 16_000))
        assertEquals(10 * 60 * 1000L, AudioMath.durationMs(16_000 * 2 * 600, 16_000))
    }

    @Test
    fun levelAndSignal() {
        val zeros = ByteArray(320)
        assertEquals(0f, AudioMath.level(zeros, zeros.size))
        assertFalse(AudioMath.hasSignal(zeros, zeros.size))

        val loud = ByteArray(320)
        val bb = ByteBuffer.wrap(loud).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 160) bb.putShort(if (i % 2 == 0) 32_000 else -32_000)
        assertTrue(AudioMath.level(loud, loud.size) > 0.98f)
        assertTrue(AudioMath.hasSignal(loud, loud.size))

        val quiet = ByteArray(320)
        ByteBuffer.wrap(quiet).order(ByteOrder.LITTLE_ENDIAN).putShort(2 * 100, 1)
        assertTrue(AudioMath.hasSignal(quiet, quiet.size))
        assertTrue(AudioMath.level(quiet, quiet.size) < 0.05f)
    }
}
