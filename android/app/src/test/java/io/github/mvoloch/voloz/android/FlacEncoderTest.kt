package io.github.mvoloch.voloz.android

import io.github.mvoloch.voloz.android.audio.FlacEncoder
import io.github.mvoloch.voloz.android.audio.WavWriter
import io.github.mvoloch.voloz.android.net.DictationPipeline
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** The encoder's output decoded back by an independent reader (below) must give the same samples. */
class FlacEncoderTest {
    private fun pcm(samples: IntArray): ByteArray {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bb.putShort(s.toShort())
        return bb.array()
    }

    /** Speech-like: two tones with a slow envelope, some noise, a silent gap, full-scale clips. */
    private fun speechLike(n: Int, seed: Int = 7): IntArray {
        val r = Random(seed)
        return IntArray(n) { i ->
            if (i in n / 3 until n / 3 + 5000) return@IntArray 0
            val env = 0.5 + 0.5 * sin(2 * PI * i / 9000.0)
            val v = env * (9000 * sin(2 * PI * 180 * i / 16000.0) + 4000 * sin(2 * PI * 1250 * i / 16000.0)) + r.nextInt(-300, 300)
            v.toInt().coerceIn(-32768, 32767)
        }.also { it[100] = 32767; it[101] = -32768; it[102] = 32767 }
    }

    private fun roundTrip(samples: IntArray, rate: Int = 16000) {
        val raw = pcm(samples)
        val flac = FlacEncoder.encode(raw, rate)
        val decoded = FlacReader(flac)
        assertEquals(rate, decoded.sampleRate)
        assertEquals(samples.size.toLong(), decoded.totalSamples)
        assertArrayEquals(samples, decoded.samples)
        assertArrayEquals(MessageDigest.getInstance("MD5").digest(raw), decoded.md5)
    }

    @Test fun speechRoundTripsExactly() = roundTrip(speechLike(16000 * 7 + 123))
    @Test fun whiteNoiseRoundTripsExactly() = roundTrip(Random(1).let { r -> IntArray(20_000) { r.nextInt(-32768, 32768) } })
    @Test fun silenceRoundTripsExactly() = roundTrip(IntArray(9000))
    @Test fun shortTakeRoundTripsExactly() = roundTrip(speechLike(200))
    @Test fun otherRateRoundTripsExactly() = roundTrip(speechLike(30_000), rate = 44_100)

    @Test
    fun speechShrinksAndTheUploadIsFlac() {
        val raw = pcm(speechLike(16000 * 10))
        val upload = DictationPipeline.uploadAudio(raw, 16000)
        assertEquals("audio/flac", upload.mime)
        assertEquals("audio.flac", upload.fileName)
        assertTrue("FLAC ${upload.bytes.size} vs PCM ${raw.size}", upload.bytes.size < raw.size * 0.8)
    }

    @Test
    fun noiseThatWouldNotShrinkIsSentAsWav() {
        // Full-scale white noise can't be compressed; the upload must never grow.
        val raw = pcm(Random(3).let { r -> IntArray(8000) { r.nextInt(-32768, 32768) } })
        val upload = DictationPipeline.uploadAudio(raw, 16000)
        assertTrue(upload.bytes.size <= raw.size + WavWriter.HEADER_BYTES)
    }

    /** Writes a fixture's FLAC for an external decoder check when VOLOZ_FLAC_OUT is set. */
    @Test
    fun writeForExternalCheck() {
        val out = System.getenv("VOLOZ_FLAC_OUT")?.takeIf { it.isNotBlank() } ?: return
        val raw = pcm(speechLike(16000 * 5))
        File("$out.pcm").writeBytes(raw)
        File(out).writeBytes(FlacEncoder.encode(raw, 16000))
    }
}

/** Minimal FLAC reader for the tests: one channel, 16 bits, the subframe types the encoder writes. */
private class FlacReader(private val data: ByteArray) {
    var sampleRate = 0
    var totalSamples = 0L
    lateinit var md5: ByteArray
    val samples: IntArray

    private var pos = 0L // bit position

    private fun bit(): Int {
        val b = (data[(pos ushr 3).toInt()].toInt() shr (7 - (pos and 7).toInt())) and 1
        pos++
        return b
    }

    private fun bits(n: Int): Long {
        var v = 0L
        repeat(n) { v = (v shl 1) or bit().toLong() }
        return v
    }

    private fun signed(n: Int): Int {
        val v = bits(n)
        return (if (v and (1L shl (n - 1)) != 0L) v - (1L shl n) else v).toInt()
    }

    init {
        check(String(data, 0, 4, Charsets.US_ASCII) == "fLaC")
        pos = 32
        var last = false
        while (!last) {
            last = bits(1) == 1L
            val type = bits(7).toInt()
            val length = bits(24).toInt()
            val start = pos
            if (type == 0) {
                bits(16); bits(16); bits(24); bits(24)
                sampleRate = bits(20).toInt()
                check(bits(3) == 0L) { "mono" }
                check(bits(5) == 15L) { "16 bit" }
                totalSamples = bits(36)
                md5 = ByteArray(16) { bits(8).toByte() }
            }
            pos = start + length * 8L
        }
        val out = ArrayList<Int>()
        while (pos / 8 < data.size) {
            check(bits(14) == 0b11111111111110L) { "sync at ${pos / 8}" }
            bits(2)
            val sizeCode = bits(4).toInt()
            bits(4); bits(4); bits(3); bits(1)
            // UTF-8 frame number
            val first = bits(8).toInt()
            var extra = 0
            while (first and (0x80 shr extra) != 0) extra++
            repeat(maxOf(0, extra - 1)) { bits(8) }
            val size = when (sizeCode) {
                6 -> bits(8).toInt() + 1
                7 -> bits(16).toInt() + 1
                in 8..15 -> 256 shl (sizeCode - 8)
                else -> error("block size code $sizeCode")
            }
            bits(8) // header CRC
            check(bits(1) == 0L)
            val type = bits(6).toInt()
            check(bits(1) == 0L) { "wasted bits" }
            val block = IntArray(size)
            when {
                type == 0 -> block.fill(signed(16))
                type == 1 -> for (i in 0 until size) block[i] = signed(16)
                type in 8..12 -> {
                    val order = type - 8
                    for (i in 0 until order) block[i] = signed(16)
                    check(bits(2) == 0L)
                    val po = bits(4).toInt()
                    val per = size shr po
                    var i = order
                    for (p in 0 until (1 shl po)) {
                        val k = bits(4).toInt()
                        val count = if (p == 0) per - order else per
                        repeat(count) {
                            var q = 0
                            while (bit() == 0) q++
                            val u = (q.toLong() shl k) or bits(k)
                            val r = ((u ushr 1) xor -(u and 1)).toInt()
                            val pred = when (order) {
                                0 -> 0
                                1 -> block[i - 1]
                                2 -> 2 * block[i - 1] - block[i - 2]
                                3 -> 3 * block[i - 1] - 3 * block[i - 2] + block[i - 3]
                                else -> 4 * block[i - 1] - 6 * block[i - 2] + 4 * block[i - 3] - block[i - 4]
                            }
                            block[i++] = pred + r
                        }
                    }
                }
                else -> error("subframe type $type")
            }
            if (pos % 8 != 0L) pos += 8 - pos % 8
            bits(16) // frame CRC
            block.forEach { out += it }
        }
        samples = out.toIntArray()
    }
}
