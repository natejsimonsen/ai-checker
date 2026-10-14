package dev.aichecker.core.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Band-limited sinc resampler that reproduces `torchaudio.functional.resample`
 * with its defaults (sinc_interp_hann, lowpass_filter_width = 6, rolloff = 0.99).
 *
 * The detector was trained on torchaudio-resampled audio, so matching its
 * filter keeps the 1-8 kHz spectrum the model sees identical to training.
 */
class Resampler(
    val fromRate: Int,
    val toRate: Int,
    private val lowpassFilterWidth: Int = 6,
    private val rolloff: Double = 0.99,
) {
    private val orig: Int
    private val new: Int
    private val width: Int
    /** kernels[phase][tap], phase in 0 until new, tap in 0 until 2*width + orig. */
    private val kernels: Array<FloatArray>

    init {
        require(fromRate > 0 && toRate > 0)
        val g = gcd(fromRate, toRate)
        orig = fromRate / g
        new = toRate / g
        val baseFreq = min(orig, new) * rolloff
        width = ceil(lowpassFilterWidth * orig / baseFreq).toInt()
        val taps = 2 * width + orig
        val scale = baseFreq / orig
        kernels = Array(new) { phase ->
            FloatArray(taps) { tap ->
                val idx = (tap - width).toDouble() / orig
                var t = (-phase.toDouble() / new + idx) * baseFreq
                t = t.coerceIn(-lowpassFilterWidth.toDouble(), lowpassFilterWidth.toDouble())
                val w = cos(t * PI / lowpassFilterWidth / 2).let { it * it }
                val tp = t * PI
                val sinc = if (tp == 0.0) 1.0 else sin(tp) / tp
                (sinc * w * scale).toFloat()
            }
        }
    }

    fun process(input: FloatArray): FloatArray {
        if (fromRate == toRate) return input.copyOf()
        val length = input.size
        val blocks = (length + 2 * width + orig - kernels[0].size) / orig + 1
        val target = ceil(new.toDouble() * length / orig).toInt()
        val out = FloatArray(min(target, blocks * new))
        val taps = kernels[0].size
        for (block in 0 until blocks) {
            // padded[p] = input[p - width]; frame starts at padded index block*orig.
            val origin = block * orig - width
            for (phase in 0 until new) {
                val o = block * new + phase
                if (o >= out.size) return out
                val k = kernels[phase]
                var acc = 0.0
                val lo = maxOf(0, -origin)
                val hi = min(taps, length - origin)
                for (tap in lo until hi) acc += k[tap] * input[origin + tap]
                out[o] = acc.toFloat()
            }
        }
        return out
    }

    private companion object {
        tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
