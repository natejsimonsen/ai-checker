package dev.aichecker.core.cli

import dev.aichecker.core.detect.AiMusicDetector
import dev.aichecker.core.detect.AnalysisResult
import dev.aichecker.core.detect.PcmAudio
import dev.aichecker.core.detect.Verdict
import dev.aichecker.core.spotify.SpotifyClient
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

/**
 * Desktop harness around the same pipeline the app runs. Decodes with ffmpeg
 * (the app uses MediaCodec instead).
 *
 *   ./gradlew :core:run --args="https://open.spotify.com/track/..."
 *   ./gradlew :core:run --args="/path/to/song.mp3"
 *
 * `--expect-ai` / `--expect-human` before inputs turns them into regression checks: the run
 * fails unless each scores at least Possibly AI (>= 50%) / Likely human (<= 15%).
 * `--report` switches back to report-only.
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("usage: <spotify link | audio file> [...]")
        exitProcess(2)
    }
    val detector = AiMusicDetector()
    val spotify = SpotifyClient()
    var failures = 0
    var expect: Set<Verdict>? = null
    for (arg in args) {
        when (arg) {
            "--expect-ai" -> { expect = setOf(Verdict.POSSIBLY_AI, Verdict.LIKELY_AI); continue }
            "--expect-human" -> { expect = setOf(Verdict.LIKELY_HUMAN); continue }
            "--report" -> { expect = null; continue }
        }
        try {
            val file = File(arg)
            val (label, audio) = if (file.isFile) {
                file.name to decodeWithFfmpeg(file)
            } else {
                val id = spotify.resolveTrackId(arg)
                val track = spotify.fetchTrack(id)
                val preview = track.previewUrl ?: error("No preview available for ${track.title}")
                val tmp = File.createTempFile("preview", ".mp3").apply { deleteOnExit() }
                tmp.writeBytes(spotify.downloadPreview(preview))
                "${track.artistLine} - ${track.title} [$id]" to decodeWithFfmpeg(tmp)
            }
            val result = detector.analyze(audio)
            println(report(label, result))
            if (expect != null && result.verdict !in expect) {
                failures++
                println("  EXPECTATION FAILED: wanted one of $expect")
            }
        } catch (e: Exception) {
            failures++
            println("ERROR $arg: ${e.message}")
        }
    }
    if (failures > 0) exitProcess(1)
}

private fun report(label: String, r: AnalysisResult) = buildString {
    append("%-60s  AI %5.1f%%  %-13s  %.1fs".format(label.take(60), r.aiProbability * 100, r.verdict, r.analysedSeconds))
    if (r.segments.isNotEmpty()) {
        append("  segments=")
        append(r.segments.joinToString(" ") { "%.0f".format(it.aiProbability * 100) })
    }
}

/** Decodes any ffmpeg-readable file to float PCM at its native sample rate. */
fun decodeWithFfmpeg(file: File): PcmAudio {
    val proc = ProcessBuilder("ffmpeg", "-v", "error", "-i", file.absolutePath, "-vn", "-c:a", "pcm_f32le", "-f", "wav", "-")
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    val bytes = proc.inputStream.use { it.readBytes() }
    check(proc.waitFor() == 0) { "ffmpeg failed to decode ${file.name}" }
    return parseFloatWav(bytes)
}

internal fun parseFloatWav(bytes: ByteArray): PcmAudio {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    buf.position(12)
    var channels = 0
    var rate = 0
    while (buf.remaining() >= 8) {
        val id = String(ByteArray(4).also { buf.get(it) }, Charsets.US_ASCII)
        var size = buf.int.toLong() and 0xffffffffL
        when (id) {
            "fmt " -> {
                val start = buf.position()
                buf.short // format tag
                channels = buf.short.toInt()
                rate = buf.int
                buf.position(start + size.toInt())
            }
            "data" -> {
                // ffmpeg streaming to a pipe can't seek back, so the size may be a placeholder.
                if (size == 0xffffffffL || size > buf.remaining()) size = buf.remaining().toLong()
                val samples = FloatArray((size / 4).toInt()) { buf.float }
                return PcmAudio.fromInterleaved(samples, channels, rate)
            }
            else -> buf.position(buf.position() + size.toInt())
        }
    }
    error("WAV has no data chunk")
}
