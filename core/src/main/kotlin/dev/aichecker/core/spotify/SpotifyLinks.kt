package dev.aichecker.core.spotify

/** Recognises Spotify track references in pasted or shared text. */
object SpotifyLinks {
    private val trackUrl = Regex("""open\.spotify\.com/(?:intl-[A-Za-z-]+/)?(?:embed/)?track/([A-Za-z0-9]{22})""")
    private val trackUri = Regex("""spotify:track:([A-Za-z0-9]{22})""")
    private val shortLink = Regex("""https?://(?:spotify\.link|spotify\.app\.link)/[A-Za-z0-9_\-?=&%.]+""")
    private val otherEntity = Regex("""open\.spotify\.com/(?:intl-[A-Za-z-]+/)?(album|playlist|artist|episode|show)/""")

    sealed interface Parsed {
        data class Track(val id: String) : Parsed
        /** A spotify.link short URL; resolve it over the network first. */
        data class ShortLink(val url: String) : Parsed
        data class Unsupported(val kind: String) : Parsed
        data object NotSpotify : Parsed
    }

    fun parse(text: String): Parsed {
        trackUrl.find(text)?.let { return Parsed.Track(it.groupValues[1]) }
        trackUri.find(text)?.let { return Parsed.Track(it.groupValues[1]) }
        shortLink.find(text)?.let { return Parsed.ShortLink(it.value) }
        otherEntity.find(text)?.let { return Parsed.Unsupported(it.groupValues[1]) }
        return Parsed.NotSpotify
    }

    /** Finds a track ID anywhere in a body of text (e.g. a short-link landing page). */
    fun findTrackId(text: String): String? =
        trackUrl.find(text)?.groupValues?.get(1) ?: trackUri.find(text)?.groupValues?.get(1)

    fun trackUrl(id: String) = "https://open.spotify.com/track/$id"
    fun embedUrl(id: String) = "https://open.spotify.com/embed/track/$id"
}
