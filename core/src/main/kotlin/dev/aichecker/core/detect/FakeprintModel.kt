package dev.aichecker.core.detect

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * Logistic-regression classifier over fakeprint features.
 *
 * The upstream ONNX graph (lofcz/ai-music-detector, MIT) is exactly
 * sigmoid(w · x + b); its weights are exported by tools/reference/export_weights.py
 * into a tiny binary so the app needs no ML runtime:
 *
 *     "FPLR" | int32 version | int32 n | float32 bias | float32[n] weights   (little-endian)
 */
class FakeprintModel(private val weights: FloatArray, private val bias: Float) {

    val featureSize: Int get() = weights.size

    /** Probability (0..1) that the audio behind [features] is AI-generated. */
    fun predict(features: FloatArray): Float = (1.0 / (1.0 + exp(-logit(features)))).toFloat()

    /** Raw decision value w · x + b (positive means AI). */
    fun logit(features: FloatArray): Double {
        require(features.size == weights.size) { "Expected ${weights.size} features, got ${features.size}" }
        var z = bias.toDouble()
        for (i in weights.indices) z += weights[i] * features[i]
        return z
    }

    companion object {
        const val RESOURCE = "/models/fakeprint_lr_v1.bin"

        fun load(input: InputStream): FakeprintModel {
            val bytes = input.use { it.readBytes() }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            require(String(magic, Charsets.US_ASCII) == "FPLR") { "Not a fakeprint model file" }
            val version = buf.int
            require(version == 1) { "Unsupported model version $version" }
            val n = buf.int
            val bias = buf.float
            val weights = FloatArray(n) { buf.float }
            return FakeprintModel(weights, bias)
        }

        /** Loads the model bundled on the classpath (works on JVM and Android). */
        fun bundled(): FakeprintModel =
            load(requireNotNull(FakeprintModel::class.java.getResourceAsStream(RESOURCE)) { "Missing $RESOURCE" })
    }
}
