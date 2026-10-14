package dev.aichecker.core.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class TrackInfo(
    val id: String,
    val title: String,
    val artists: List<String>,
    val coverUrl: String?,
    /** 30-second MP3 preview; null when Spotify offers none for this track. */
    val previewUrl: String?,
    val durationMs: Long?,
    val releaseDate: String?,
) {
    val artistLine: String get() = artists.joinToString(", ").ifEmpty { "Unknown artist" }
}

/**
 * Extracts track metadata and the preview MP3 from Spotify's public embed page
 * (open.spotify.com/embed/track/{id}). The page is a Next.js app whose state is
 * serialised in a `__NEXT_DATA__` script tag.
 *
 * This is an undocumented format, so parsing is deliberately defensive: it
 * searches the JSON for the track entity instead of relying on one exact path,
 * and falls back to scanning the raw HTML for the preview CDN URL.
 */
object SpotifyEmbedParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val nextData = Regex("""<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val previewCdn = Regex("""https://p\.scdn\.co/mp3-preview/[A-Za-z0-9]+(?:\?[^"'\s\\]*)?""")

    fun parse(trackId: String, html: String): TrackInfo {
        val root = nextData.find(html)?.groupValues?.get(1)?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        val entity = root?.let { findEntity(it, trackId) }

        val title = entity?.string("name") ?: entity?.string("title")
        val artists = entity?.get("artists")?.let { el ->
            (el as? JsonArray)?.mapNotNull { (it as? JsonObject)?.string("name") }
        }.orEmpty().ifEmpty { listOfNotNull(entity?.string("subtitle")) }
        val preview = (entity?.get("audioPreview") as? JsonObject)?.string("url")
            ?: previewCdn.find(html)?.value
        val cover = entity?.let { bestImage(it) }
        val duration = (entity?.get("duration") as? JsonPrimitive)?.longOrNull
        val release = (entity?.get("releaseDate") as? JsonObject)?.string("isoString")
            ?: entity?.string("releaseDate")

        if (title == null && preview == null) {
            throw SpotifyException("Couldn't read this track from Spotify (the embed page format may have changed)")
        }
        return TrackInfo(
            id = trackId,
            title = title ?: "Unknown title",
            artists = artists,
            coverUrl = cover,
            previewUrl = preview,
            durationMs = duration,
            releaseDate = release?.take(10),
        )
    }

    /** Depth-first search for the object describing this track. */
    private fun findEntity(el: JsonElement, trackId: String): JsonObject? {
        when (el) {
            is JsonObject -> {
                val isTrack = el.string("uri") == "spotify:track:$trackId" ||
                    (el.string("id") == trackId && el.containsKey("name")) ||
                    (el.containsKey("audioPreview") && (el.containsKey("name") || el.containsKey("title")))
                if (isTrack) return el
                for (v in el.values) findEntity(v, trackId)?.let { return it }
            }
            is JsonArray -> for (v in el) findEntity(v, trackId)?.let { return it }
            else -> Unit
        }
        return null
    }

    private fun bestImage(entity: JsonObject): String? {
        val candidates = mutableListOf<Pair<String, Long>>()
        fun collect(arr: JsonElement?) {
            (arr as? JsonArray)?.forEach { img ->
                val o = img as? JsonObject ?: return@forEach
                val url = o.string("url") ?: return@forEach
                val size = (o["maxWidth"] ?: o["width"])?.jsonPrimitive?.longOrNull ?: 0
                candidates += url to size
            }
        }
        (entity["visualIdentity"] as? JsonObject)?.let { collect(it["image"]) }
        (entity["coverArt"] as? JsonObject)?.let { collect(it["sources"]) }
        collect(entity["images"])
        // Prefer ~300-640 px: sharp on phones without downloading the 2000 px original.
        return candidates.filter { it.second in 250..700 }.maxByOrNull { it.second }?.first
            ?: candidates.maxByOrNull { it.second }?.first
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

class SpotifyException(message: String) : Exception(message)
