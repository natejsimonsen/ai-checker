package dev.aichecker.core.detect

import dev.aichecker.core.audio.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10

/**
 * Computes the "fakeprint" features from Afchar et al., "A Fourier Explanation of
 * AI-music Artifacts" (ISMIR 2025), as implemented by lofcz/ai-music-detector.
 *
 * Neural-vocoder upsampling layers leave a comb of narrow spectral peaks. The
 * fakeprint is the time-averaged dB spectrum (1-8 kHz at 16 kHz) minus its
 * local lower envelope, which isolates those peaks from the musical content.
 *
 * Every constant here must match training (see src/python/config.yaml upstream).
 */
object Fakeprint {
    const val SAMPLE_RATE = 16_000
    const val N_FFT = 8192
    const val HOP = N_FFT / 2
    const val FREQ_MIN = 1000.0
    const val FREQ_MAX = 8000.0
    const val HULL_AREA = 10
    const val MAX_DB = 5f
    const val MIN_DB = -45f
    const val MAX_SECONDS = 300

    private const val N_BINS = N_FFT / 2 + 1
    private val binHz = SAMPLE_RATE / 2.0 / (N_BINS - 1)
    val firstBin = (0 until N_BINS).first { it * binHz >= FREQ_MIN }
    val lastBin = (0 until N_BINS).last { it * binHz <= FREQ_MAX }
    val featureSize = lastBin - firstBin + 1

    /** Periodic Hann window, as torch.hann_window(N_FFT). */
    private val window = DoubleArray(N_FFT) { 0.5 - 0.5 * cos(2.0 * PI * it / N_FFT) }

    /**
     * Per-frame dB spectra restricted to the feature band, matching
     * `torchaudio.transforms.Spectrogram(n_fft=8192, power=2)` (center=True,
     * reflect padding, hop = n_fft / 2) followed by `10*log10(clamp(x, 1e-10, 1e6))`.
     *
     * @param audio mono samples at [SAMPLE_RATE]; must be longer than [HOP] samples.
     * @return frames x [featureSize] matrix.
     */
    fun bandSpectraDb(audio: FloatArray): Array<FloatArray> {
        require(audio.size > HOP) { "Audio too short for analysis (${audio.size} samples)" }
        val pad = N_FFT / 2
        val frames = 1 + audio.size / HOP
        val fft = Fft(N_FFT)
        val re = DoubleArray(N_FFT)
        val im = DoubleArray(N_FFT)
        return Array(frames) { f ->
            val start = f * HOP - pad
            for (i in 0 until N_FFT) {
                re[i] = reflect(audio, start + i) * window[i]
                im[i] = 0.0
            }
            fft.transform(re, im)
            FloatArray(featureSize) { b ->
                val k = firstBin + b
                val power = (re[k] * re[k] + im[k] * im[k]).coerceIn(1e-10, 1e6)
                (10.0 * log10(power)).toFloat()
            }
        }
    }

    /** Fakeprint from the mean of the given frame spectra (rows [from, to)). */
    fun fromSpectra(spectra: Array<FloatArray>, from: Int = 0, to: Int = spectra.size): FloatArray {
        require(to > from)
        val mean = DoubleArray(featureSize)
        for (f in from until to) {
            val row = spectra[f]
            for (b in 0 until featureSize) mean[b] += row[b]
        }
        val n = (to - from).toDouble()
        val spectrum = FloatArray(featureSize) { (mean[it] / n).toFloat() }
        return residue(spectrum)
    }

    /** Spectrum minus its lower hull, clipped and max-normalised. */
    internal fun residue(spectrum: FloatArray): FloatArray {
        val hull = minimumFilter(spectrum, HULL_AREA)
        val out = FloatArray(spectrum.size)
        var max = 0f
        for (i in spectrum.indices) {
            val h = maxOf(hull[i], MIN_DB)
            val r = (spectrum[i] - h).coerceIn(0f, MAX_DB)
            out[i] = r
            if (r > max) max = r
        }
        val denom = max + 1e-6f
        for (i in out.indices) out[i] /= denom
        return out
    }

    /** scipy.ndimage.minimum_filter1d(x, size, mode='nearest', origin=0). */
    internal fun minimumFilter(x: FloatArray, size: Int): FloatArray {
        val left = size / 2
        val right = size - left - 1
        return FloatArray(x.size) { i ->
            var m = Float.POSITIVE_INFINITY
            for (j in i - left..i + right) {
                val v = x[j.coerceIn(0, x.size - 1)]
                if (v < m) m = v
            }
            m
        }
    }

    /** torch 'reflect' padding: mirror without repeating the edge sample. */
    private fun reflect(x: FloatArray, i: Int): Double {
        val n = x.size
        var j = i
        while (j < 0 || j >= n) {
            j = if (j < 0) -j else 2 * (n - 1) - j
        }
        return x[j].toDouble()
    }
}
