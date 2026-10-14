package dev.aichecker.core.spotify

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Credential-free access to public Spotify pages. Uses HttpURLConnection so it
 * runs unchanged on the JVM (CLI, tests) and on Android.
 */
class SpotifyClient(
    private val timeoutMillis: Int = 15_000,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {
    /** Turns pasted/shared text into a track ID, resolving spotify.link short URLs. */
    fun resolveTrackId(text: String): String = when (val parsed = SpotifyLinks.parse(text.trim())) {
        is SpotifyLinks.Parsed.Track -> parsed.id
        is SpotifyLinks.Parsed.ShortLink -> {
            val (finalUrl, body) = get(parsed.url)
            SpotifyLinks.findTrackId(finalUrl) ?: SpotifyLinks.findTrackId(body)
                ?: throw SpotifyException("That short link doesn't point to a Spotify track")
        }
        is SpotifyLinks.Parsed.Unsupported ->
            throw SpotifyException("That's a Spotify ${parsed.kind} link. Share a single song instead.")
        SpotifyLinks.Parsed.NotSpotify ->
            throw SpotifyException("That doesn't look like a Spotify song link")
    }

    fun fetchTrack(trackId: String): TrackInfo =
        SpotifyEmbedParser.parse(trackId, get(SpotifyLinks.embedUrl(trackId)).second)

    fun downloadPreview(url: String, maxBytes: Int = 8 shl 20): ByteArray {
        val conn = open(url)
        try {
            checkStatus(conn)
            return conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("Preview is unexpectedly large")
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** GET returning (final URL after redirects, body text). */
    private fun get(url: String): Pair<String, String> {
        var current = url
        repeat(MAX_REDIRECTS) {
            val conn = open(current)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without Location")
                    current = URI(current).resolve(location).toString()
                    return@repeat
                }
                checkStatus(conn)
                return current to conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun open(url: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            // Redirects are followed manually so https->http hops and the final URL are visible.
            instanceFollowRedirects = false
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }

    private fun checkStatus(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code == 404) throw SpotifyException("Spotify couldn't find that track")
        if (code !in 200..299) throw IOException("Spotify returned HTTP $code")
    }

    companion object {
        private const val MAX_REDIRECTS = 8
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
    }
}
