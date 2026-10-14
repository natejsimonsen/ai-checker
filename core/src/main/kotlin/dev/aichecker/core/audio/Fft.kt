package dev.aichecker.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 complex FFT of a fixed power-of-two size.
 * Twiddles and the bit-reversal table are precomputed, so one instance can be
 * reused for every STFT frame.
 */
class Fft(val size: Int) {
    private val levels: Int
    private val cosTable = DoubleArray(size / 2)
    private val sinTable = DoubleArray(size / 2)
    private val bitReverse = IntArray(size)

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two, was $size" }
        levels = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size / 2) {
            cosTable[i] = cos(2.0 * PI * i / size)
            sinTable[i] = sin(2.0 * PI * i / size)
        }
        for (i in 0 until size) {
            bitReverse[i] = Integer.reverse(i) ushr (32 - levels)
        }
    }

    /** Forward transform (e^{-i...} convention, unnormalized), operating in place. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        require(re.size == size && im.size == size)
        for (i in 0 until size) {
            val j = bitReverse[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val l = j + half
                    val c = cosTable[k]
                    val s = sinTable[k]
                    val tre = re[l] * c + im[l] * s
                    val tim = -re[l] * s + im[l] * c
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                }
                start += half * 2
            }
            half *= 2
        }
    }
}
