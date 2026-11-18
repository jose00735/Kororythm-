package com.koro.kororythm.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading yt-dlp's --print-to-file JSON lines: playlists, single videos, the worker's source line. */
class ProbeParseTest {

    private val playlist = listOf(
        "{\"_type\": \"url\", \"ie_key\": \"Youtube\", \"id\": \"aaa111bbb22\", \"url\": \"https://www.youtube.com/watch?v=aaa111bbb22\", \"title\": \"Grid Pilots - Neon Runway (Official Video)\", \"duration\": 245.0, \"channel\": \"Grid Pilots\", \"thumbnails\": [{\"url\": \"https://i.ytimg.com/vi/aaa111bbb22/hqdefault.jpg\"}], \"playlist_title\": \"Synth Mix\", \"playlist_index\": 1, \"playlist_count\": 57, \"webpage_url\": \"https://www.youtube.com/watch?v=aaa111bbb22\"}",
        "{\"_type\": \"url\", \"ie_key\": \"Youtube\", \"id\": \"ccc333ddd44\", \"url\": \"https://www.youtube.com/watch?v=ccc333ddd44\", \"title\": \"Static Coast \\u2013 Analog Horizon\", \"duration\": 401, \"channel\": \"Static Coast\", \"playlist_title\": \"Synth Mix\", \"playlist_index\": 2, \"playlist_count\": 57}",
    )

    @Test fun flatPlaylistBecomesAPickList() {
        val p = YtLogic.parseProbe(playlist, "https://www.youtube.com/watch?v=ccc333ddd44&list=PL123") as Probe.Playlist
        assertEquals("Synth Mix", p.title)
        assertEquals(57, p.total)
        assertEquals(2, p.entries.size)
        val e = p.entries[0]
        assertEquals("https://www.youtube.com/watch?v=aaa111bbb22", e.url)
        assertEquals("Grid Pilots" to "Neon Runway", e.artist to e.title)
        assertEquals(245, e.durationSec)
        assertEquals("https://i.ytimg.com/vi/aaa111bbb22/hqdefault.jpg", e.thumbnail)
        assertEquals("Analog Horizon", p.entries[1].title)
        assertEquals("ccc333ddd44", YtLogic.videoId("https://www.youtube.com/watch?v=ccc333ddd44&list=PL123"))
        assertEquals("ccc333ddd44", YtLogic.videoId("https://youtu.be/ccc333ddd44?list=RD1&si=x"))
        assertTrue(YtLogic.hasPlaylist("https://youtu.be/ccc333ddd44?list=RD1"))
    }

    @Test fun singleVideoKeepsItsStreams() {
        val s = YtLogic.parseProbe(listOf("{\"id\": \"aaa111bbb22\", \"title\": \"Grid Pilots - Neon Runway (Official Video)\", \"channel\": \"Grid Pilots\", \"duration\": 245, \"thumbnail\": \"https://i.ytimg.com/vi/x/maxresdefault.jpg\", \"extractor_key\": \"Youtube\", \"upload_date\": \"20180514\", \"webpage_url\": \"https://www.youtube.com/watch?v=aaa111bbb22\", \"formats\": [{\"format_id\": \"140\", \"acodec\": \"mp4a.40.2\", \"vcodec\": \"none\", \"abr\": 129.5, \"asr\": 44100, \"ext\": \"m4a\"}, {\"format_id\": \"251\", \"acodec\": \"opus\", \"vcodec\": \"none\", \"abr\": 135.2, \"asr\": 48000, \"ext\": \"webm\"}, {\"format_id\": \"18\", \"acodec\": \"mp4a.40.2\", \"vcodec\": \"avc1\", \"tbr\": 500, \"asr\": 44100, \"ext\": \"mp4\"}]}"), "https://youtu.be/aaa111bbb22") as Probe.Single
        assertEquals("Neon Runway", s.info.title)
        assertEquals("Grid Pilots", s.info.artist)
        assertEquals(2018, s.info.year)
        assertEquals("251", s.info.best!!.formatId)
        assertEquals(135, s.info.best!!.abrKbps)
    }

    @Test fun workerSourceLine() {
        val (st, thumb) = YtLogic.parseSourceLine("Youtube;opus;135.2;48000;https://i.ytimg.com/a.jpg")!!
        assertEquals("youtube;opus;135", YtLogic.sourceNote("Youtube", st))
        assertEquals("https://i.ytimg.com/a.jpg", thumb)
        val (unk, t2) = YtLogic.parseSourceLine("HTML5MediaEmbed;mp3;NA;NA;NA")!!
        assertEquals(0, unk.abrKbps)
        assertEquals(null, t2)
    }
}
