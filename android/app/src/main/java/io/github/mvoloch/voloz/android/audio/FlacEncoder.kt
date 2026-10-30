package io.github.mvoloch.voloz.android.audio

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Lossless FLAC for the upload: 16-bit mono PCM in, a complete .flac file out.
 * Speech comes out at about half the WAV's size, so the upload takes about half
 * the time on a slow mobile link (the desktop uploads FLAC for the same reason).
 *
 * A small encoder, not libFLAC: fixed blocks of [BLOCK_SIZE] samples, each
 * subframe CONSTANT (digital silence), FIXED (orders 0–4, partitioned Rice
 * residual) or VERBATIM, whichever is smallest. Pure JVM so the unit tests
 * decode it back sample for sample. Format: https://www.rfc-editor.org/rfc/rfc9639
 */
object FlacEncoder {
    const val BLOCK_SIZE = 4096
    private const val MAX_RICE_PARAM = 14 // 4-bit parameters; 15 is the escape code
    private const val MAX_PARTITION_ORDER = 6

    fun encode(pcm: ByteArray, sampleRate: Int): ByteArray {
        require(sampleRate in 1..655_350) { "sample rate $sampleRate" }
        val n = pcm.size / 2
        // One block of samples at a time: a 10-minute take is 19 MB of PCM already.
        val md5 = MessageDigest.getInstance("MD5").apply { update(pcm, 0, n * 2) }.digest()
        val out = ByteArrayOutputStream(pcm.size / 2 + 128)
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        out.write(streamInfo(n, sampleRate, md5))
        val block = IntArray(BLOCK_SIZE)
        var frame = 0L
        var start = 0
        while (start < n) {
            val size = minOf(BLOCK_SIZE, n - start)
            for (i in 0 until size) {
                val j = 2 * (start + i)
                block[i] = (pcm[j].toInt() and 0xFF) or (pcm[j + 1].toInt() shl 8)
            }
            out.write(frame(block, 0, size, frame, sampleRate))
            start += size
            frame++
        }
        return out.toByteArray()
    }

    /** STREAMINFO, the only (so the last) metadata block. Frame sizes left at 0 = unknown. */
    private fun streamInfo(totalSamples: Int, sampleRate: Int, md5: ByteArray): ByteArray {
        val w = BitWriter()
        w.bits(1, 1) // last metadata block
        w.bits(0, 7) // type STREAMINFO
        w.bits(34, 24)
        val minBlock = if (totalSamples in 16 until BLOCK_SIZE) totalSamples else BLOCK_SIZE
        w.bits(minBlock.toLong(), 16)
        w.bits(minBlock.toLong(), 16)
        w.bits(0, 24)
        w.bits(0, 24)
        w.bits(sampleRate.toLong(), 20)
        w.bits(0, 3) // channels - 1
        w.bits(15, 5) // bits per sample - 1
        w.bits(totalSamples.toLong(), 36)
        for (b in md5) w.bits(b.toLong() and 0xFF, 8)
        return w.toByteArray()
    }

    private fun frame(s: IntArray, start: Int, size: Int, number: Long, sampleRate: Int): ByteArray {
        val w = BitWriter()
        w.bits(0b11111111111110, 14) // sync
        w.bits(0, 1)
        w.bits(0, 1) // fixed block size
        val sizeCode = when (size) {
            BLOCK_SIZE -> 12 // 256 * 2^(12-8)
            in 1..256 -> 6 // 8-bit (size - 1) after the header
            else -> 7 // 16-bit (size - 1)
        }
        w.bits(sizeCode.toLong(), 4)
        val rateCode = when (sampleRate) {
            8000 -> 4; 16000 -> 5; 22050 -> 6; 24000 -> 7; 32000 -> 8; 44100 -> 9; 48000 -> 10
            else -> 0 // from STREAMINFO
        }
        w.bits(rateCode.toLong(), 4)
        w.bits(0, 4) // mono
        w.bits(0b100, 3) // 16 bits per sample
        w.bits(0, 1)
        w.utf8(number)
        if (sizeCode == 6) w.bits((size - 1).toLong(), 8)
        if (sizeCode == 7) w.bits((size - 1).toLong(), 16)
        w.bits(crc8(w.toByteArray()).toLong(), 8)
        subframe(w, s, start, size)
        w.alignToByte()
        val body = w.toByteArray()
        val crc = crc16(body)
        return body + byteArrayOf((crc shr 8).toByte(), crc.toByte())
    }

    private fun subframe(w: BitWriter, s: IntArray, start: Int, size: Int) {
        if ((1 until size).all { s[start + it] == s[start] }) {
            w.bits(0, 1)
            w.bits(0, 6) // CONSTANT
            w.bits(0, 1)
            w.signed(s[start], 16)
            return
        }
        var best: Fixed? = null
        for (order in 0..minOf(4, size - 1)) {
            val candidate = fixed(s, start, size, order)
            if (best == null || candidate.bits < best.bits) best = candidate
        }
        val verbatimBits = 8L + 16L * size
        if (best == null || best.bits >= verbatimBits) {
            w.bits(0, 1)
            w.bits(1, 6) // VERBATIM
            w.bits(0, 1)
            for (i in 0 until size) w.signed(s[start + i], 16)
            return
        }
        w.bits(0, 1)
        w.bits((8 + best.order).toLong(), 6) // FIXED, order in the low bits
        w.bits(0, 1)
        for (i in 0 until best.order) w.signed(s[start + i], 16)
        w.bits(0, 2) // Rice, 4-bit parameters
        w.bits(best.partitionOrder.toLong(), 4)
        val parts = 1 shl best.partitionOrder
        val per = size shr best.partitionOrder
        var r = 0
        for (p in 0 until parts) {
            val count = if (p == 0) per - best.order else per
            val k = best.params[p]
            w.bits(k.toLong(), 4)
            repeat(count) {
                val v = best.residual[r++]
                val u = (v shl 1) xor (v shr 31) // zigzag
                w.unary(u ushr k)
                if (k > 0) w.bits((u and ((1 shl k) - 1)).toLong(), k)
            }
        }
    }

    private class Fixed(val order: Int, val residual: IntArray, val partitionOrder: Int, val params: IntArray, val bits: Long)

    /** Residual of the order-[order] fixed predictor and its cheapest Rice partitioning. */
    private fun fixed(s: IntArray, start: Int, size: Int, order: Int): Fixed {
        val res = IntArray(size - order)
        for (i in order until size) {
            val x = start + i
            val prediction = when (order) {
                0 -> 0
                1 -> s[x - 1]
                2 -> 2 * s[x - 1] - s[x - 2]
                3 -> 3 * s[x - 1] - 3 * s[x - 2] + s[x - 3]
                else -> 4 * s[x - 1] - 6 * s[x - 2] + 4 * s[x - 3] - s[x - 4]
            }
            res[i - order] = s[x] - prediction
        }
        // Sums of the zigzagged residual per finest partition; coarser ones add them up.
        var maxOrder = 0
        while (maxOrder < MAX_PARTITION_ORDER && size % (1 shl (maxOrder + 1)) == 0 && (size shr (maxOrder + 1)) > order) maxOrder++
        val finest = 1 shl maxOrder
        val perFinest = size shr maxOrder
        val sums = LongArray(finest)
        for (i in order until size) {
            val v = res[i - order]
            sums[i / perFinest] += ((v shl 1) xor (v shr 31)).toLong() and 0xFFFFFFFFL
        }
        var bestBits = Long.MAX_VALUE
        var bestOrder = 0
        var bestParams = IntArray(1)
        for (po in 0..maxOrder) {
            val parts = 1 shl po
            val per = size shr po
            val span = finest / parts
            val params = IntArray(parts)
            var bits = 6L + 4L * parts
            for (p in 0 until parts) {
                var sum = 0L
                for (f in p * span until (p + 1) * span) sum += sums[f]
                val count = if (p == 0) per - order else per
                val (k, cost) = bestRice(sum, count)
                params[p] = k
                bits += cost
            }
            if (bits < bestBits) {
                bestBits = bits
                bestOrder = po
                bestParams = params
            }
        }
        return Fixed(order, res, bestOrder, bestParams, 8L + 16L * order + bestBits)
    }

    /**
     * The Rice parameter for [count] values summing to [sum], with its estimated
     * cost (libFLAC's estimate: sum >> k stands in for the sum of the quotients).
     * Only the choice depends on it; the bits written are exact either way.
     */
    private fun bestRice(sum: Long, count: Int): Pair<Int, Long> {
        var bestK = 0
        var bestCost = Long.MAX_VALUE
        for (k in 0..MAX_RICE_PARAM) {
            val cost = count.toLong() * (k + 1) + (sum ushr k)
            if (cost < bestCost) {
                bestCost = cost
                bestK = k
            }
        }
        return bestK to bestCost
    }

    private fun crc8(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    /** MSB-first bit writer: whole values go into a 64-bit accumulator, full bytes come out. */
    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var acc = 0L // the low [count] bits are pending
        private var count = 0

        fun bits(value: Long, n: Int) {
            if (n > 32) {
                bits(value ushr 32, n - 32)
                bits(value and 0xFFFFFFFFL, 32)
                return
            }
            if (n == 0) return
            acc = (acc shl n) or (value and ((1L shl n) - 1))
            count += n
            while (count >= 8) {
                count -= 8
                out.write(((acc ushr count) and 0xFF).toInt())
            }
            acc = acc and ((1L shl count) - 1)
        }

        fun signed(value: Int, n: Int) = bits(value.toLong() and ((1L shl n) - 1), n)

        fun unary(zeros: Int) {
            var z = zeros
            while (z >= 32) {
                bits(0, 32)
                z -= 32
            }
            bits(1, z + 1)
        }

        /** FLAC's "UTF-8" coding of the frame number. */
        fun utf8(v: Long) {
            if (v < 0x80) return bits(v, 8)
            val extra = when {
                v < 0x800 -> 1; v < 0x10000 -> 2; v < 0x200000 -> 3; v < 0x4000000 -> 4; v < 0x80000000L -> 5
                else -> 6
            }
            val lead = (0xFF00 shr (extra + 1)) and 0xFF
            bits(lead.toLong() or (v ushr (6 * extra)), 8)
            for (i in extra - 1 downTo 0) bits(0x80L or ((v ushr (6 * i)) and 0x3F), 8)
        }

        fun alignToByte() {
            if (count != 0) bits(0, 8 - count)
        }

        /** Whole bytes written so far (call at a byte boundary). */
        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
