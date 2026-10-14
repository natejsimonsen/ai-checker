package dev.aichecker.core.detect

import dev.aichecker.core.audio.Resampler
import kotlin.math.sqrt

/** Decoded audio: interleaved-free mono samples in [-1, 1]. */
class PcmAudio(val samples: FloatArray, val sampleRate: Int) {
    val durationSeconds: Double get() = samples.size.toDouble() / sampleRate

    companion object {
        /** Averages interleaved channels into mono. */
        fun fromInterleaved(interleaved: FloatArray, channels: Int, sampleRate: Int): PcmAudio {
            if (channels == 1) return PcmAudio(interleaved, sampleRate)
            val frames = interleaved.size / channels
            val mono = FloatArray(frames) { f ->
                var s = 0f
                for (c in 0 until channels) s += interleaved[f * channels + c]
                s / channels
            }
            return PcmAudio(mono, sampleRate)
        }
    }
}

/**
 * Bands on the whole-clip probability. 85% matches SubmitHub's blocking line.
 * POSSIBLY_AI covers partly-AI tracks (AI stems/vocals) and heavily processed AI audio.
 */
enum class Verdict { LIKELY_HUMAN, INCONCLUSIVE, POSSIBLY_AI, LIKELY_AI }

data class Segment(val startSeconds: Double, val endSeconds: Double, val aiProbability: Float)

data class AnalysisResult(
    /** Fakeprint-model probability over the whole analysed clip, 0..1. */
    val aiProbability: Float,
    val verdict: Verdict,
    /**
     * Sliding-window probabilities, shown as a timeline. The model was trained on whole
     * tracks, so 10 s windows are much noisier than the overall score (on AI songs they
     * swing between ~0 and 100%); they're informational and don't drive the verdict.
     */
    val segments: List<Segment>,
    val analysedSeconds: Double,
    val modelName: String,
)

class AnalysisException(message: String) : Exception(message)

/**
 * On-device detector. Pipeline: mono PCM -> torchaudio-compatible resample to
 * 16 kHz -> STFT -> fakeprint -> logistic regression; plus the same model on
 * sliding windows for a per-segment timeline.
 */
class AiMusicDetector(
    private val model: FakeprintModel = FakeprintModel.bundled(),
    private val segmentSeconds: Double = 10.0,
    private val segmentHopSeconds: Double = 5.0,
) {
    init {
        require(model.featureSize == Fakeprint.featureSize) {
            "Model expects ${model.featureSize} features but extractor produces ${Fakeprint.featureSize}"
        }
    }

    fun analyze(audio: PcmAudio, onProgress: (Float) -> Unit = {}): AnalysisResult {
        if (audio.durationSeconds < MIN_SECONDS) {
            throw AnalysisException("Need at least ${MIN_SECONDS.toInt()} seconds of audio (got %.1f s)".format(audio.durationSeconds))
        }
        val maxIn = Fakeprint.MAX_SECONDS * audio.sampleRate
        val clipped = if (audio.samples.size > maxIn) audio.samples.copyOf(maxIn) else audio.samples
        if (rms(clipped) < SILENCE_RMS) throw AnalysisException("Audio is silent or too quiet to analyse")
        onProgress(0.05f)

        val resampled = Resampler(audio.sampleRate, Fakeprint.SAMPLE_RATE).process(clipped)
        onProgress(0.45f)
        val spectra = Fakeprint.bandSpectraDb(resampled)
        onProgress(0.85f)

        val overall = model.predict(Fakeprint.fromSpectra(spectra))
        val segments = segments(spectra, resampled.size)
        onProgress(1f)
        return AnalysisResult(
            aiProbability = overall,
            verdict = verdictFor(overall),
            segments = segments,
            analysedSeconds = resampled.size.toDouble() / Fakeprint.SAMPLE_RATE,
            modelName = MODEL_NAME,
        )
    }

    private fun segments(spectra: Array<FloatArray>, samples: Int): List<Segment> {
        val frameSec = Fakeprint.HOP.toDouble() / Fakeprint.SAMPLE_RATE
        val totalSec = samples.toDouble() / Fakeprint.SAMPLE_RATE
        if (totalSec < segmentSeconds * 1.5) return emptyList()
        val out = mutableListOf<Segment>()
        var start = 0.0
        while (start + segmentSeconds <= totalSec + 1e-6) {
            // Frame f is centred on f * HOP samples.
            val from = (start / frameSec).toInt().coerceIn(0, spectra.size - 1)
            val to = ((start + segmentSeconds) / frameSec).toInt().coerceIn(from + 1, spectra.size)
            out += Segment(start, start + segmentSeconds, model.predict(Fakeprint.fromSpectra(spectra, from, to)))
            start += segmentHopSeconds
        }
        return out
    }

    companion object {
        const val MODEL_NAME = "Fakeprint LR v1 (lofcz/ai-music-detector, ISMIR 2025 method)"
        const val MIN_SECONDS = 10.0
        const val AI_THRESHOLD = 0.85f
        const val HUMAN_THRESHOLD = 0.15f
        private const val SILENCE_RMS = 1e-4

        fun verdictFor(aiProbability: Float): Verdict = when {
            aiProbability >= AI_THRESHOLD -> Verdict.LIKELY_AI
            aiProbability >= 0.5f -> Verdict.POSSIBLY_AI
            aiProbability > HUMAN_THRESHOLD -> Verdict.INCONCLUSIVE
            else -> Verdict.LIKELY_HUMAN
        }

        private fun rms(x: FloatArray): Double {
            var s = 0.0
            for (v in x) s += v * v
            return sqrt(s / x.size)
        }
    }
}
