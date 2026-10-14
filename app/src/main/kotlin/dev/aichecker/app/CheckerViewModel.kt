package dev.aichecker.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.aichecker.app.audio.MediaCodecDecoder
import dev.aichecker.app.data.HistoryStore
import dev.aichecker.app.data.Report
import dev.aichecker.app.data.SegmentScore
import dev.aichecker.app.data.Source
import dev.aichecker.core.detect.AiMusicDetector
import dev.aichecker.core.detect.AnalysisException
import dev.aichecker.core.detect.AnalysisResult
import dev.aichecker.core.detect.Fakeprint
import dev.aichecker.core.spotify.SpotifyClient
import dev.aichecker.core.spotify.SpotifyException
import dev.aichecker.core.spotify.TrackInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

enum class Step(val label: String) {
    RESOLVING("Finding the track"),
    DOWNLOADING("Downloading the 30-second preview"),
    DECODING("Decoding audio"),
    ANALYSING("Scanning the spectrum for AI fingerprints"),
}

sealed interface CheckState {
    data object Idle : CheckState
    data class Working(val step: Step, val progress: Float, val title: String? = null) : CheckState
    data class Failed(val message: String) : CheckState
}

class CheckerViewModel(application: Application) : AndroidViewModel(application) {

    private val spotify = SpotifyClient()
    private val detector by lazy { AiMusicDetector() }
    val history = HistoryStore(application)

    private val _state = MutableStateFlow<CheckState>(CheckState.Idle)
    val state: StateFlow<CheckState> = _state.asStateFlow()

    /** The report currently open on the result screen, or null for the home screen. */
    private val _openReport = MutableStateFlow<Report?>(null)
    val openReport: StateFlow<Report?> = _openReport.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch { history.load() }
    }

    fun open(report: Report?) {
        _openReport.value = report
    }

    fun dismissError() {
        if (_state.value is CheckState.Failed) _state.value = CheckState.Idle
    }

    fun clearHistory() = viewModelScope.launch { history.clear() }

    fun checkSpotify(text: String) = launchCheck {
        _state.value = CheckState.Working(Step.RESOLVING, 0f)
        val track = withContext(Dispatchers.IO) {
            val id = spotify.resolveTrackId(text)
            spotify.fetchTrack(id)
        }
        val previewUrl = track.previewUrl
            ?: throw SpotifyException("Spotify doesn't offer a preview for this song. Use \"Check an audio file\" instead.")

        _state.value = CheckState.Working(Step.DOWNLOADING, 0.15f, track.title)
        val file = withContext(Dispatchers.IO) {
            File(getApplication<Application>().cacheDir, "preview-${track.id}.mp3").apply {
                writeBytes(spotify.downloadPreview(previewUrl))
            }
        }

        _state.value = CheckState.Working(Step.DECODING, 0.3f, track.title)
        val audio = withContext(Dispatchers.Default) {
            try {
                MediaCodecDecoder.decode(file, Fakeprint.MAX_SECONDS)
            } finally {
                file.delete()
            }
        }
        val result = analyse(audio, track.title)
        toReport(result, Source.SPOTIFY_PREVIEW, track)
    }

    fun checkFile(uri: Uri) = launchCheck {
        val app = getApplication<Application>()
        val name = withContext(Dispatchers.IO) { displayName(uri) } ?: "Audio file"
        _state.value = CheckState.Working(Step.DECODING, 0.1f, name)
        val audio = withContext(Dispatchers.Default) { MediaCodecDecoder.decode(app, uri, Fakeprint.MAX_SECONDS) }
        val result = analyse(audio, name)
        toReport(result, Source.FILE, track = null, fileName = name)
    }

    private suspend fun analyse(audio: dev.aichecker.core.detect.PcmAudio, title: String): AnalysisResult =
        withContext(Dispatchers.Default) {
            detector.analyze(audio) { p ->
                _state.value = CheckState.Working(Step.ANALYSING, 0.4f + 0.6f * p, title)
            }
        }

    private fun launchCheck(block: suspend () -> Report) {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val report = block()
                history.add(report)
                _state.value = CheckState.Idle
                _openReport.value = report
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpotifyException) {
                fail(e.message)
            } catch (e: AnalysisException) {
                fail(e.message)
            } catch (e: IOException) {
                fail("Network problem: ${e.message ?: "couldn't reach Spotify"}")
            } catch (e: Exception) {
                fail("Something went wrong: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    private fun fail(message: String?) {
        _state.value = CheckState.Failed(message ?: "Something went wrong")
    }

    private fun toReport(result: AnalysisResult, source: Source, track: TrackInfo?, fileName: String? = null) = Report(
        id = UUID.randomUUID().toString(),
        createdAt = System.currentTimeMillis(),
        source = source,
        title = track?.title ?: fileName ?: "Audio file",
        artist = track?.artistLine ?: "Uploaded file",
        coverUrl = track?.coverUrl,
        spotifyTrackId = track?.id,
        releaseDate = track?.releaseDate,
        aiProbability = result.aiProbability,
        verdict = result.verdict,
        segments = result.segments.map { SegmentScore(it.startSeconds, it.endSeconds, it.aiProbability) },
        analysedSeconds = result.analysedSeconds,
        modelName = result.modelName,
    )

    private fun displayName(uri: Uri): String? =
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}
