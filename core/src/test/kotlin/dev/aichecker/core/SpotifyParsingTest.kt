package dev.aichecker.core

import dev.aichecker.core.spotify.SpotifyEmbedParser
import dev.aichecker.core.spotify.SpotifyLinks
import dev.aichecker.core.spotify.SpotifyLinks.Parsed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SpotifyParsingTest {
    private val id = "4PTG3Z6ehGkBFwjybzWkR8"

    @Test
    fun recognisesTrackReferences() {
        val inputs = listOf(
            "https://open.spotify.com/track/$id",
            "https://open.spotify.com/track/$id?si=abc123&context=x",
            "https://open.spotify.com/intl-de/track/$id",
            "https://open.spotify.com/embed/track/$id",
            "spotify:track:$id",
            "Never Gonna Give You Up by Rick Astley https://open.spotify.com/track/$id?si=1",
        )
        for (text in inputs) assertEquals(Parsed.Track(id), SpotifyLinks.parse(text), text)
    }

    @Test
    fun classifiesOtherLinks() {
        assertIs<Parsed.ShortLink>(SpotifyLinks.parse("Listen: https://spotify.link/AbCdEf123"))
        assertEquals(Parsed.Unsupported("album"), SpotifyLinks.parse("https://open.spotify.com/album/1A2GTWGtFfWp7KSQTwWOyo"))
        assertEquals(Parsed.NotSpotify, SpotifyLinks.parse("https://youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun parsesEmbedNextData() {
        val html = """
            <html><head></head><body><div id="__next"></div>
            <script id="__NEXT_DATA__" type="application/json">
            {"props":{"pageProps":{"state":{"data":{"entity":{
              "type":"track","name":"Never Gonna Give You Up","uri":"spotify:track:$id","id":"$id",
              "artists":[{"name":"Rick Astley","uri":"spotify:artist:0gxyHStUsqpMadRV0Di1Qt"}],
              "releaseDate":{"isoString":"1987-11-12T00:00:00Z"},"duration":213573,
              "audioPreview":{"format":"MP3","url":"https://p.scdn.co/mp3-preview/abc123"},
              "visualIdentity":{"image":[
                {"url":"https://i.scdn.co/image/small","maxHeight":64,"maxWidth":64},
                {"url":"https://i.scdn.co/image/medium","maxHeight":300,"maxWidth":300},
                {"url":"https://i.scdn.co/image/large","maxHeight":640,"maxWidth":640}]}
            }}}}}}
            </script></body></html>
        """.trimIndent()
        val t = SpotifyEmbedParser.parse(id, html)
        assertEquals("Never Gonna Give You Up", t.title)
        assertEquals(listOf("Rick Astley"), t.artists)
        assertEquals("https://p.scdn.co/mp3-preview/abc123", t.previewUrl)
        assertEquals("https://i.scdn.co/image/large", t.coverUrl)
        assertEquals("1987-11-12", t.releaseDate)
        assertEquals(213573L, t.durationMs)
    }

    @Test
    fun fallsBackToPreviewUrlInRawHtml() {
        val html = """<html><script>var x = {"url":"https://p.scdn.co/mp3-preview/deadbeef?cid=1"}</script></html>"""
        assertEquals("https://p.scdn.co/mp3-preview/deadbeef?cid=1", SpotifyEmbedParser.parse(id, html).previewUrl)
    }
}
