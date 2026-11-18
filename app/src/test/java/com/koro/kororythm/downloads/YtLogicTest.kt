package com.koro.kororythm.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtLogicTest {

    @Test fun extractsLinkFromSharedText() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ?si=abc", YtLogic.extractUrl("Watch this! https://youtu.be/dQw4w9WgXcQ?si=abc."))
        assertEquals("https://www.youtube.com/watch?v=x1", YtLogic.extractUrl("https://www.youtube.com/watch?v=x1"))
        assertNull(YtLogic.extractUrl("no link here"))
        assertTrue(YtLogic.isYoutube("https://music.youtube.com/watch?v=x"))
        assertFalse(YtLogic.isYoutube("https://soundcloud.com/a/b"))
    }

    @Test fun cleansVideoTitles() {
        assertEquals("Daft Punk" to "Get Lucky", YtLogic.guessArtistTitle("Daft Punk - Get Lucky (Official Video)", "DaftPunkVEVO"))
        assertEquals("Shakira" to "Ojos Así", YtLogic.guessArtistTitle("Shakira – Ojos Así [Video Oficial]", "shakiraVEVO"))
        assertEquals("Grid Pilots" to "Neon Runway", YtLogic.guessArtistTitle("Neon Runway (Lyrics)", "Grid Pilots - Topic"))
        assertEquals("Muse" to "Uprising", YtLogic.guessArtistTitle("Muse | \"Uprising\" (Remastered 2009)", "Muse"))
        assertEquals(2019, YtLogic.year("20190514"))
    }

    @Test fun picksBestAudioAndLabelsHonestly() {
        val streams = listOf(
            AudioStream("139", "mp4a.40.5", 48, 22050, "m4a"),
            AudioStream("140", "mp4a.40.2", 129, 44100, "m4a"),
            AudioStream("251", "opus", 135, 48000, "webm"),
            AudioStream("sb0", "none", 0, 0, "mhtml"),
        )
        val best = YtLogic.bestAudio(streams)!!
        assertEquals("251", best.formatId)
        assertEquals("Source Opus 135 kbps · 48 kHz → MP3 V0 (sound stays at source quality)", YtLogic.qualityLabel(best, DlFormat.MP3_V0))
        assertEquals("Source Opus 135 kbps · 48 kHz, saved as is", YtLogic.qualityLabel(best, DlFormat.ORIGINAL))
        assertEquals("youtube;opus;135", YtLogic.sourceNote("Youtube", best))
    }

    @Test fun safeFileNamesAndProgress() {
        assertEquals("AC_DC - Back In Black_.mp3", YtLogic.fileName("AC/DC", "Back In Black?", "mp3"))
        assertEquals("Solo.opus", YtLogic.fileName(null, "  Solo  ", "opus"))
        assertEquals(0.423f, YtLogic.progressOf("[download]  42.3% of   3.51MiB at  1.2MiB/s ETA 00:02")!!, 1e-4f)
        assertNull(YtLogic.progressOf("[ExtractAudio] Destination: x.mp3"))
        assertEquals("audio/ogg", YtLogic.mimeFor("opus"))
    }
}
