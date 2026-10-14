package dev.aichecker.app.data

import android.content.Context
import dev.aichecker.core.detect.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class Source { SPOTIFY_PREVIEW, FILE }

@Serializable
data class SegmentScore(val startSeconds: Double, val endSeconds: Double, val aiProbability: Float)

/** One completed check, as shown on the result screen and stored in history. */
@Serializable
data class Report(
    val id: String,
    val createdAt: Long,
    val source: Source,
    val title: String,
    val artist: String,
    val coverUrl: String? = null,
    val spotifyTrackId: String? = null,
    val releaseDate: String? = null,
    val aiProbability: Float,
    val verdict: Verdict,
    val segments: List<SegmentScore>,
    val analysedSeconds: Double,
    val modelName: String,
) {
    val spotifyUrl: String? get() = spotifyTrackId?.let { "https://open.spotify.com/track/$it" }
}

/** Recent checks, persisted as a small JSON file in app storage. */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _reports = MutableStateFlow<List<Report>>(emptyList())
    val reports: StateFlow<List<Report>> = _reports.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val loaded = runCatching { json.decodeFromString<List<Report>>(file.readText()) }.getOrDefault(emptyList())
        _reports.value = loaded
    }

    suspend fun add(report: Report) {
        _reports.update { current -> (listOf(report) + current.filterNot { it.sameTrackAs(report) }).take(MAX) }
        persist()
    }

    suspend fun clear() {
        _reports.value = emptyList()
        persist()
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        file.writeText(json.encodeToString(_reports.value))
    }

    private fun Report.sameTrackAs(other: Report) =
        spotifyTrackId != null && spotifyTrackId == other.spotifyTrackId

    private companion object {
        const val MAX = 50
    }
}
