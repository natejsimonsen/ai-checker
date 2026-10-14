package dev.aichecker.core

import dev.aichecker.core.audio.Resampler
import dev.aichecker.core.detect.Fakeprint
import dev.aichecker.core.detect.FakeprintModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compares the Kotlin pipeline with the upstream Python/torchaudio one on
 * fixtures written by tools/reference/make_fixtures.py.
 */
class ReferenceParityTest {

    private fun json(name: String): JsonObject =
        Json.parseToJsonElement(resource(name).decodeToString()).jsonObject

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }.readBytes()

    private fun pcm16(name: String): FloatArray {
        val sb = ByteBuffer.wrap(resource(name)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(sb.remaining()) { sb.get(it) / 32768f }
    }

    private fun JsonObject.floats(key: String) =
        this[key]!!.jsonArray.map { it.jsonPrimitive.double.toFloat() }.toFloatArray()

    @Test
    fun resamplerMatchesTorchaudio() {
        val fixture = json("resample.json")
        for (rate in listOf(44100, 48000, 22050)) {
            val expected = fixture[rate.toString()]!!.jsonObject
            val out = Resampler(rate, 16_000).process(pcm16("resample_$rate.pcm"))
            assertEquals(expected["length"]!!.jsonPrimitive.int, out.size, "length @ $rate")
            val ref = expected.floats("samples")
            val maxErr = out.indices.maxOf { abs(out[it] - ref[it]) }
            assertTrue(maxErr < 1e-5f, "max sample error $maxErr @ $rate Hz")
        }
    }

    @Test
    fun minimumFilterMatchesScipy() {
        val fixture = json("minfilter.json")
        val out = Fakeprint.minimumFilter(fixture.floats("input"), Fakeprint.HULL_AREA)
        val ref = fixture.floats("output")
        for (i in ref.indices) assertEquals(ref[i], out[i], 1e-6f, "index $i")
    }

    @Test
    fun endToEndFakeprintAndLogitMatchReference() {
        val model = FakeprintModel.bundled()
        val fixture = json("e2e.json")
        for (name in listOf("plain", "comb")) {
            val case = fixture[name]!!.jsonObject
            val rate = case["sampleRate"]!!.jsonPrimitive.int
            val audio = Resampler(rate, Fakeprint.SAMPLE_RATE).process(pcm16("e2e_$name.pcm"))
            val fp = Fakeprint.fromSpectra(Fakeprint.bandSpectraDb(audio))
            val ref = case.floats("fakeprint")
            assertEquals(ref.size, fp.size)
            // Bins whose power is near the noise floor in one frame (e.g. -87 dB) are
            // ill-conditioned even between torch float32 and float64, so bound the
            // mean error tightly and the worst bin loosely.
            val errs = fp.indices.map { abs(fp[it] - ref[it]) }
            assertTrue(errs.average() < 1e-4, "$name: mean fakeprint error ${errs.average()}")
            assertTrue(errs.max() < 0.02f, "$name: max fakeprint error ${errs.max()}")
            val logit = model.logit(fp)
            val refLogit = case["logit"]!!.jsonPrimitive.double
            assertEquals(refLogit, logit, 0.05, "$name logit")
        }
    }
}
