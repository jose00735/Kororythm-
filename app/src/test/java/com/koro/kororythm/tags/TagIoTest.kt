package com.koro.kororythm.tags

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Writes tags into real FLAC/MP3 files made with ffmpeg (src/test/resources/tags) and checks
 * the audio bytes come through unchanged. The written files are left in build/tagtest/ so
 * an independent reader (mutagen, ffprobe) can check the tags too (scripts/verify_tags.py).
 */
class TagIoTest {

    private val res = File(javaClass.classLoader!!.getResource("tags/in16.flac")!!.toURI()).parentFile!!
    private val out = File("build/tagtest").apply { mkdirs() }
    private val cover = Cover.sniff(File(res, "cover.png").readBytes())

    private val tags = TagData(
        title = "Ñandú — 日本の夜", artist = "Beyoncé & Grid Pilots", album = "Runways", albumArtist = "Various Artists",
        genre = "Synthwave", year = 2018, trackNo = 3, trackTotal = 12, discNo = 1, cover = cover,
    )

    private fun flacAudio(f: File): ByteArray = RandomAccessFile(f, "r").use { raf ->
        val (_, start) = TagIo.Flac.readBlocks(raf)
        ByteArray((raf.length() - start).toInt()).also { raf.seek(start); raf.readFully(it) }
    }

    private fun mp3Audio(f: File): ByteArray = RandomAccessFile(f, "r").use { raf ->
        val (_, start) = TagIo.Id3.readOld(raf)
        var end = raf.length()
        if (end - start >= 128) {
            raf.seek(end - 128)
            val t = ByteArray(3).also { raf.readFully(it) }
            if (String(t, Charsets.ISO_8859_1) == "TAG") end -= 128
        }
        ByteArray((end - start).toInt()).also { raf.seek(start); raf.readFully(it) }
    }

    private fun roundTrip(name: String, outName: String, t: TagData = tags): File {
        val input = File(res, name)
        val output = File(out, outName)
        TagIo.write(input, output, t)
        return output
    }

    @Test fun detectsFormats() {
        assertEquals(AudioFormat.FLAC, TagIo.detect(File(res, "in16.flac")))
        assertEquals(AudioFormat.MP3, TagIo.detect(File(res, "in_v23.mp3")))
        assertEquals(AudioFormat.MP3, TagIo.detect(File(res, "in_bare.mp3")))
        assertEquals(AudioFormat.UNKNOWN, TagIo.detect(File(res, "cover.png")))
    }

    @Test fun flac16KeepsAudioAndUnmanagedTags() {
        val o = roundTrip("in16.flac", "out16.flac")
        assertArrayEquals(flacAudio(File(res, "in16.flac")), flacAudio(o))
        val vorbis = RandomAccessFile(o, "r").use { raf -> TagIo.Flac.readBlocks(raf).first.first { it.type == 4 } }
        val (_, comments) = TagIo.Flac.parseVorbis(vorbis.data)
        assertTrue(comments.contains("TITLE=Ñandú — 日本の夜"))
        assertTrue(comments.any { it.startsWith("REPLAYGAIN_TRACK_GAIN=") || it.startsWith("replaygain_track_gain=") })
        assertTrue(comments.none { it.uppercase().startsWith("TITLE=OLD") })
    }

    @Test fun flac24HiResAudioUntouched() {
        val o = roundTrip("in24.flac", "out24.flac")
        assertArrayEquals(flacAudio(File(res, "in24.flac")), flacAudio(o))
        // STREAMINFO (sample rate, bit depth) must stay first and identical
        val a = RandomAccessFile(File(res, "in24.flac"), "r").use { TagIo.Flac.readBlocks(it).first.first() }
        val b = RandomAccessFile(o, "r").use { TagIo.Flac.readBlocks(it).first.first() }
        assertEquals(0, b.type)
        assertArrayEquals(a.data, b.data)
    }

    @Test fun flacRewriteIsStableAndCoverCanBeRemoved() {
        val once = roundTrip("in16.flac", "once.flac")
        val twice = File(out, "twice.flac").also { TagIo.write(once, it, tags) }
        assertArrayEquals(once.readBytes(), twice.readBytes())
        val noCover = File(out, "nocover.flac").also { TagIo.write(once, it, tags.copy(cover = null, removeCover = true)) }
        val types = RandomAccessFile(noCover, "r").use { TagIo.Flac.readBlocks(it).first.map { b -> b.type } }
        assertTrue(6 !in types)
        assertArrayEquals(flacAudio(once), flacAudio(noCover))
    }

    @Test fun mp3FromId3v23KeepsAudioAndDropsId3v1() {
        val o = roundTrip("in_v23.mp3", "out_v23.mp3")
        assertArrayEquals(mp3Audio(File(res, "in_v23.mp3")), mp3Audio(o))
        val bytes = o.readBytes()
        assertEquals("ID3", String(bytes, 0, 3, Charsets.ISO_8859_1))
        assertEquals(4, bytes[3].toInt())
        assertTrue("ID3v1 trailer removed", String(bytes, bytes.size - 128, 3, Charsets.ISO_8859_1) != "TAG")
    }

    @Test fun mp3FromId3v24AndBare() {
        val a = roundTrip("in_v24.mp3", "out_v24.mp3")
        assertArrayEquals(mp3Audio(File(res, "in_v24.mp3")), mp3Audio(a))
        val b = roundTrip("in_bare.mp3", "out_bare.mp3")
        assertArrayEquals(File(res, "in_bare.mp3").readBytes(), mp3Audio(b))
    }

    @Test fun mp3RewriteIsStable() {
        val once = roundTrip("in_v23.mp3", "once.mp3")
        val twice = File(out, "twice.mp3").also { TagIo.write(once, it, tags) }
        assertArrayEquals(once.readBytes(), twice.readBytes())
    }

    @Test fun emptyFieldsAreLeftOut() {
        val o = roundTrip("in16.flac", "sparse.flac", TagData(title = "Only title", artist = "  "))
        val vorbis = RandomAccessFile(o, "r").use { raf -> TagIo.Flac.readBlocks(raf).first.first { it.type == 4 } }
        val (_, comments) = TagIo.Flac.parseVorbis(vorbis.data)
        assertTrue(comments.contains("TITLE=Only title"))
        assertTrue(comments.none { it.uppercase().startsWith("ARTIST=") })
    }

    @Test fun lyricsAreWrittenAndKeptOnLaterEdits() {
        val lrc = "[00:01.00] Ñandú en la noche\n[00:02.50] second line"
        val withLyrics = roundTrip("in16.flac", "lyrics.flac", tags.copy(lyrics = lrc))
        val later = File(out, "lyrics_kept.flac").also { TagIo.write(withLyrics, it, tags.copy(title = "Renamed")) }
        val vorbis = RandomAccessFile(later, "r").use { raf -> TagIo.Flac.readBlocks(raf).first.first { it.type == 4 } }
        val (_, comments) = TagIo.Flac.parseVorbis(vorbis.data)
        assertTrue("lyrics survive a tag edit", comments.contains("LYRICS=$lrc"))
        assertTrue(comments.contains("TITLE=Renamed"))

        val mp3 = roundTrip("in_v24.mp3", "lyrics.mp3", tags.copy(lyrics = lrc))
        val mp3Later = File(out, "lyrics_kept.mp3").also { TagIo.write(mp3, it, tags) }
        assertArrayEquals(mp3Audio(File(res, "in_v24.mp3")), mp3Audio(mp3Later))
    }
}
