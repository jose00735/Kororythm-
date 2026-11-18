package com.koro.kororythm.tags

import com.koro.kororythm.lyrics.Lrc
import com.koro.kororythm.lyrics.LrcLib
import com.koro.kororythm.lyrics.LrcLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v1.1 logic: lyrics parsing, LRCLIB responses, quality upgrade rules, YouTube source note. */
class V11LogicTest {

    @Test fun parsesLrcWithMultipleStampsFractionsAndOffset() {
        val lrc = """
            [ar: Grid Pilots]
            [offset:+500]
            [00:12.50]First line
            [00:10.5][01:20.123]Chorus
            [00:15]Third
            plain text without stamp
        """.trimIndent()
        val lines = Lrc.parse(lrc)
        assertEquals(
            listOf(LrcLine(10_000, "Chorus"), LrcLine(12_000, "First line"), LrcLine(14_500, "Third"), LrcLine(79_623, "Chorus")),
            lines,
        )
        assertEquals(-1, Lrc.currentIndex(lines, 9_999))
        assertEquals(0, Lrc.currentIndex(lines, 10_000))
        assertEquals(1, Lrc.currentIndex(lines, 13_000))
        assertEquals(3, Lrc.currentIndex(lines, 600_000))
        assertEquals("Chorus\nFirst line\nThird\nChorus", Lrc.toPlain(lrc))
    }

    @Test fun lrclibGetAndSearch() {
        val get = """{"id":1,"trackName":"Neon Runway","artistName":"Grid Pilots","albumName":"Runways","duration":245,
            "instrumental":false,"plainLyrics":"Hello\nWorld","syncedLyrics":"[00:01.00] Hello\n[00:02.00] World"}"""
        val f = LrcLib.parseGet(get)!!
        assertEquals("[00:01.00] Hello\n[00:02.00] World", f.synced)
        assertEquals("Hello\nWorld", f.plain)
        assertNull(LrcLib.parseGet("""{"id":2,"instrumental":false,"plainLyrics":null,"syncedLyrics":null}"""))

        val search = """[
          {"id":1,"duration":400,"plainLyrics":"wrong version","syncedLyrics":"[00:01.00] wrong"},
          {"id":2,"duration":246,"plainLyrics":"plain only","syncedLyrics":null},
          {"id":3,"duration":243,"plainLyrics":"best","syncedLyrics":"[00:01.00] best"},
          {"id":4,"duration":245,"plainLyrics":null,"syncedLyrics":null}
        ]"""
        assertEquals("[00:01.00] best", LrcLib.pickBest(search, 245)!!.synced)
    }

    private val mp3_192 = Quality.Spec("MP3", 192, 44100, null)

    @Test fun flacReplacingMp3IsAnUpgrade() {
        val v = Quality.compare(mp3_192, Quality.Spec("FLAC", 900, 44100, 16))
        assertTrue(v is Quality.Verdict.Better)
        v as Quality.Verdict.Better
        assertEquals(1411.2 / 192, v.powerFactor, 0.01)
        assertEquals("×7.4", Quality.powerLabel(v.powerFactor))
        assertEquals("MP3 · 192 kbps → FLAC · 16-bit / 44.1 kHz: lossless now", v.reason)
    }

    @Test fun higherBitrateMp3IsAnUpgradeButNotASmallBump() {
        assertTrue(Quality.compare(mp3_192, Quality.Spec("MP3", 320, 44100, null)) is Quality.Verdict.Better)
        assertTrue(Quality.compare(mp3_192, Quality.Spec("MP3", 200, 44100, null)) is Quality.Verdict.NotBetter)
        assertTrue(Quality.compare(Quality.Spec("FLAC", null, 44100, 16), Quality.Spec("MP3", 320, 44100, null)) is Quality.Verdict.NotBetter)
    }

    @Test fun hiResFlacBeatsCdFlac() {
        val cd = Quality.Spec("FLAC", null, 44100, 16)
        assertTrue(Quality.compare(cd, Quality.Spec("FLAC", null, 96000, 24)) is Quality.Verdict.Better)
        assertTrue(Quality.compare(cd, Quality.Spec("FLAC", null, 44100, 16)) is Quality.Verdict.NotBetter)
    }

    @Test fun youtubeDownloadCountsAtItsSourceBitrate() {
        // a YouTube download re-encoded to ~245 kbps MP3 from a 160 kbps Opus stream
        val res = File(javaClass.classLoader!!.getResource("tags/in_v24.mp3")!!.toURI())
        val out = File("build/tagtest").apply { mkdirs() }
        val dl = File(out, "youtube.mp3")
        TagIo.write(res, dl, TagData(title = "Clip", sourceNote = "youtube;opus;160"))
        val note = Quality.sourceNote(dl.readBytes().copyOf(256 * 1024))
        assertEquals("youtube;opus;160", note)
        val spec = Quality.withSource(Quality.Spec("MP3", 245, 48000, null), note)
        assertEquals(160, spec.bitrateKbps)
        // so it can't "upgrade" a 192 kbps MP3
        assertTrue(Quality.compare(mp3_192, spec) is Quality.Verdict.NotBetter)
        // and a later tag edit keeps exactly one note
        val again = File(out, "youtube2.mp3").also { TagIo.write(dl, it, TagData(title = "Clip 2", sourceNote = "youtube;opus;160")) }
        val text = String(again.readBytes(), Charsets.ISO_8859_1)
        assertEquals(1, Regex(Quality.SOURCE_DESC).findAll(text).count())
    }
}
