package dev.aichecker.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aichecker.app.audio.MediaCodecDecoder
import dev.aichecker.core.audio.Resampler
import dev.aichecker.core.detect.AiMusicDetector
import dev.aichecker.core.detect.Fakeprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Runs the real on-device path (MediaCodec decode -> detector) and checks it against
 * the fakeprint the desktop/ffmpeg path produces for the same MP3
 * (assets/decode_test_fakeprint.txt, written from core's ffmpeg decoder).
 */
@RunWith(AndroidJUnit4::class)
class OnDevicePipelineTest {

    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val appContext = InstrumentationRegistry.getInstrumentation().targetContext

    private fun asset(name: String): File =
        File(appContext.cacheDir, name).apply {
            testContext.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
        }

    @Test
    fun mediaCodecDecodeMatchesFfmpegReference() {
        val audio = MediaCodecDecoder.decode(asset("decode_test.mp3"), Fakeprint.MAX_SECONDS)
        val lines = testContext.assets.open("decode_test_fakeprint.txt").bufferedReader().readLines()
        val (refRate, refSamples) = lines.first().split(" ").map { it.toInt() }
        val ref = lines.drop(1).map { it.toFloat() }

        assertEquals(refRate, audio.sampleRate)
        // Decoders differ in how they trim MP3 encoder delay/padding; allow ~0.1 s.
        assertTrue("length ${audio.samples.size} vs $refSamples", abs(audio.samples.size - refSamples) < refRate / 10)

        val fp = Fakeprint.fromSpectra(Fakeprint.bandSpectraDb(Resampler(audio.sampleRate, Fakeprint.SAMPLE_RATE).process(audio.samples)))
        val meanErr = fp.indices.sumOf { abs(fp[it] - ref[it]).toDouble() } / fp.size
        assertTrue("mean fakeprint error $meanErr", meanErr < 0.01)
    }

    @Test
    fun detectorRunsOnDevice() {
        val audio = MediaCodecDecoder.decode(asset("decode_test.mp3"), Fakeprint.MAX_SECONDS)
        val result = AiMusicDetector().analyze(audio)
        assertTrue(result.aiProbability in 0f..1f)
        assertEquals(15.0, result.analysedSeconds, 0.2)
        assertTrue(result.segments.isNotEmpty())
    }
}
