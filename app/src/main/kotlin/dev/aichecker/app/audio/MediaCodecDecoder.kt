package dev.aichecker.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dev.aichecker.core.detect.AnalysisException
import dev.aichecker.core.detect.PcmAudio
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes MP3/AAC/FLAC/OGG/WAV/... to mono float PCM with the platform codecs.
 * Stops after [maxSeconds], which is all the detector looks at anyway.
 */
object MediaCodecDecoder {

    fun decode(file: File, maxSeconds: Int): PcmAudio =
        decode(maxSeconds) { it.setDataSource(file.absolutePath) }

    fun decode(context: Context, uri: Uri, maxSeconds: Int): PcmAudio =
        decode(maxSeconds) { it.setDataSource(context, uri, null) }

    private fun decode(maxSeconds: Int, setSource: (MediaExtractor) -> Unit): PcmAudio {
        val extractor = MediaExtractor()
        try {
            setSource(extractor)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AnalysisException("No audio track found in this file")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            // Ask for float output; decoders that can't will report 16-bit in the output format.
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)

            val codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw AnalysisException("This phone can't decode $mime audio")
            }
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return drain(codec, extractor, format, maxSeconds)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drain(codec: MediaCodec, extractor: MediaExtractor, inputFormat: MediaFormat, maxSeconds: Int): PcmAudio {
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val out = FloatBuffer()
        val info = MediaCodec.BufferInfo()
        var inputDone = false

        while (true) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AudioFormat.ENCODING_PCM_16BIT
                    }
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    when (encoding) {
                        AudioFormat.ENCODING_PCM_FLOAT -> {
                            val fb = buffer.asFloatBuffer()
                            while (fb.hasRemaining()) out.add(fb.get())
                        }
                        AudioFormat.ENCODING_PCM_16BIT -> {
                            val sb = buffer.asShortBuffer()
                            while (sb.hasRemaining()) out.add(sb.get() / 32768f)
                        }
                        else -> throw AnalysisException("Unsupported PCM encoding $encoding")
                    }
                    codec.releaseOutputBuffer(index, false)
                    val enough = out.size >= maxSeconds.toLong() * sampleRate * channels
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || enough) break
                }
            }
        }
        if (out.size == 0) throw AnalysisException("Couldn't decode any audio")
        return PcmAudio.fromInterleaved(out.toArray(), channels, sampleRate)
    }

    private const val TIMEOUT_US = 10_000L

    /** Growable float array without boxing. */
    private class FloatBuffer {
        private var data = FloatArray(1 shl 20)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
