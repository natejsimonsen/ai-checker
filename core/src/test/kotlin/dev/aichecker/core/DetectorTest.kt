package dev.aichecker.core

import dev.aichecker.core.detect.AiMusicDetector
import dev.aichecker.core.detect.AnalysisException
import dev.aichecker.core.detect.PcmAudio
import dev.aichecker.core.detect.Verdict
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DetectorTest {

    @Test
    fun producesSegmentsForThirtySecondClip() {
        val rate = 44100
        val samples = FloatArray(30 * rate) { i ->
            val t = i.toDouble() / rate
            (0.3 * sin(2 * PI * 220 * t) + 0.1 * sin(2 * PI * 1375 * t) + 0.01 * ((i * 1103515245 + 12345) % 1000 / 1000.0 - 0.5)).toFloat()
        }
        val r = AiMusicDetector().analyze(PcmAudio(samples, rate))
        assertEquals(30.0, r.analysedSeconds, 0.01)
        assertEquals(5, r.segments.size) // 0-10, 5-15, ..., 20-30
        assert(r.aiProbability in 0f..1f)
    }

    @Test
    fun rejectsShortAndSilentAudio() {
        assertFailsWith<AnalysisException> { AiMusicDetector().analyze(PcmAudio(FloatArray(16000 * 5), 16000)) }
        assertFailsWith<AnalysisException> { AiMusicDetector().analyze(PcmAudio(FloatArray(16000 * 20), 16000)) }
    }

    @Test
    fun verdictBands() {
        assertEquals(Verdict.LIKELY_AI, AiMusicDetector.verdictFor(0.97f))
        assertEquals(Verdict.LIKELY_AI, AiMusicDetector.verdictFor(0.85f))
        assertEquals(Verdict.POSSIBLY_AI, AiMusicDetector.verdictFor(0.6f))
        assertEquals(Verdict.INCONCLUSIVE, AiMusicDetector.verdictFor(0.3f))
        assertEquals(Verdict.LIKELY_HUMAN, AiMusicDetector.verdictFor(0.15f))
        assertEquals(Verdict.LIKELY_HUMAN, AiMusicDetector.verdictFor(0.01f))
    }
}
