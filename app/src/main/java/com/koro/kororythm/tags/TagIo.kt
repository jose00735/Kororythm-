package com.koro.kororythm.tags

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/** Tags Kororythm reads and writes. Null means "leave empty". */
data class TagData(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNo: Int? = null,
    val trackTotal: Int? = null,
    val discNo: Int? = null,
    /** New front cover, or null to keep the existing one (unless [removeCover]). */
    val cover: Cover? = null,
    val removeCover: Boolean = false,
    /** Lyrics to store in the file (LRC or plain); null leaves existing lyrics alone. */
    val lyrics: String? = null,
    /** MP3 only: where the audio really came from, e.g. "youtube;opus;160" (see Quality). */
    val sourceNote: String? = null,
)

class Cover(val bytes: ByteArray, val mime: String) {
    companion object {
        fun sniff(bytes: ByteArray): Cover = Cover(
            bytes,
            if (bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) "image/png" else "image/jpeg",
        )
    }
}

enum class AudioFormat { FLAC, MP3, UNKNOWN }

class TagWriteException(message: String) : Exception(message)

/**
 * Writes tags into FLAC (Vorbis comments + PICTURE block) and MP3 (ID3v2.4) files.
 * The audio data is copied byte for byte; only the tag area changes. Tags we don't manage
 * (comments, ReplayGain, lyrics…) are kept. Pure JVM code, so it's tested against real files.
 */
object TagIo {

    fun detect(file: File): AudioFormat = RandomAccessFile(file, "r").use { raf ->
        var offset = 0L
        val h = ByteArray(10)
        if (raf.length() >= 10) {
            raf.readFully(h)
            if (h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte()) {
                offset = 10L + syncsafe(h, 6) + if (h[5].toInt() and 0x10 != 0) 10 else 0
            }
        }
        if (offset + 4 > raf.length()) return AudioFormat.UNKNOWN
        raf.seek(offset)
        val m = ByteArray(4)
        raf.readFully(m)
        when {
            String(m, Charsets.ISO_8859_1) == "fLaC" -> AudioFormat.FLAC
            (m[0].toInt() and 0xFF) == 0xFF && (m[1].toInt() and 0xE0) == 0xE0 -> AudioFormat.MP3
            offset > 0 -> AudioFormat.MP3 // ID3 tag followed by something: treat as MP3
            else -> AudioFormat.UNKNOWN
        }
    }

    /** Reads [input], writes a copy with new tags to [output]. */
    /** The text tags a file has now (title, artist, album…); cover and lyrics aren't read. */
    fun read(input: File): TagData = when (detect(input)) {
        AudioFormat.FLAC -> Flac.read(input)
        AudioFormat.MP3 -> Id3.read(input)
        else -> TagData()
    }

    /** Fields a bulk edit can change. */
    enum class Field { ARTIST, ALBUM, ALBUM_ARTIST, GENRE, YEAR }

    /**
     * Changes only [changes] and keeps every other tag the file has (title, track numbers,
     * cover, lyrics…). A blank value clears that field.
     */
    fun update(input: File, output: File, changes: Map<Field, String>) {
        write(input, output, merge(read(input), changes))
    }

    fun merge(base: TagData, changes: Map<Field, String>): TagData {
        var t = base
        changes.forEach { (f, v) ->
            val value = v.trim().ifEmpty { null }
            t = when (f) {
                Field.ARTIST -> t.copy(artist = value)
                Field.ALBUM -> t.copy(album = value)
                Field.ALBUM_ARTIST -> t.copy(albumArtist = value)
                Field.GENRE -> t.copy(genre = value)
                Field.YEAR -> t.copy(year = value?.take(4)?.toIntOrNull())
            }
        }
        return t
    }

    fun write(input: File, output: File, tags: TagData) {
        when (detect(input)) {
            AudioFormat.FLAC -> Flac.write(input, output, tags)
            AudioFormat.MP3 -> Id3.write(input, output, tags)
            AudioFormat.UNKNOWN -> throw TagWriteException("Not a FLAC or MP3 file")
        }
    }

    // ---- shared helpers ----
    internal fun syncsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    internal fun syncsafeBytes(v: Int) = byteArrayOf(
        ((v shr 21) and 0x7F).toByte(), ((v shr 14) and 0x7F).toByte(), ((v shr 7) and 0x7F).toByte(), (v and 0x7F).toByte(),
    )

    internal fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    internal fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    internal fun copyRange(raf: RandomAccessFile, from: Long, to: Long, out: OutputStream) {
        raf.seek(from)
        val buf = ByteArray(256 * 1024)
        var left = to - from
        while (left > 0) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    internal fun InputStream.readExactly(n: Int): ByteArray {
        val b = ByteArray(n)
        var r = 0
        while (r < n) {
            val k = read(b, r, n - r)
            if (k < 0) throw TagWriteException("File ended early")
            r += k
        }
        return b
    }

    // =============================================================== FLAC

    internal object Flac {
        private const val STREAMINFO = 0
        private const val PADDING = 1
        private const val VORBIS = 4
        private const val PICTURE = 6
        private val MANAGED = setOf("TITLE", "ARTIST", "ALBUM", "ALBUMARTIST", "GENRE", "DATE", "YEAR", "TRACKNUMBER", "TRACKTOTAL", "TOTALTRACKS", "DISCNUMBER")

        class Block(val type: Int, val data: ByteArray)

        fun readBlocks(raf: RandomAccessFile): Pair<List<Block>, Long> {
            var pos = 0L
            val h = ByteArray(10)
            raf.seek(0)
            raf.readFully(h)
            if (String(h, 0, 3, Charsets.ISO_8859_1) == "ID3") pos = 10L + syncsafe(h, 6) + if (h[5].toInt() and 0x10 != 0) 10 else 0
            raf.seek(pos)
            val magic = ByteArray(4).also { raf.readFully(it) }
            if (String(magic, Charsets.ISO_8859_1) != "fLaC") throw TagWriteException("Not a FLAC file")
            val blocks = mutableListOf<Block>()
            while (true) {
                val hdr = raf.read()
                if (hdr < 0) throw TagWriteException("Broken FLAC header")
                val len = (raf.read() shl 16) or (raf.read() shl 8) or raf.read()
                val data = ByteArray(len).also { raf.readFully(it) }
                blocks += Block(hdr and 0x7F, data)
                if (hdr and 0x80 != 0) break
            }
            return blocks to raf.filePointer
        }

        /** Parses a VORBIS_COMMENT block: vendor + list of "KEY=value". */
        fun parseVorbis(d: ByteArray): Pair<String, List<String>> {
            fun le(at: Int) = (d[at].toInt() and 0xFF) or ((d[at + 1].toInt() and 0xFF) shl 8) or
                ((d[at + 2].toInt() and 0xFF) shl 16) or ((d[at + 3].toInt() and 0xFF) shl 24)
            var p = 0
            val vlen = le(p); p += 4
            val vendor = String(d, p, vlen, Charsets.UTF_8); p += vlen
            val n = le(p); p += 4
            val out = ArrayList<String>(n)
            repeat(n) {
                val l = le(p); p += 4
                out += String(d, p, l, Charsets.UTF_8); p += l
            }
            return vendor to out
        }

        fun buildVorbis(vendor: String, comments: List<String>): ByteArray {
            val o = ByteArrayOutputStream()
            val v = vendor.toByteArray(Charsets.UTF_8)
            o.write(le32(v.size)); o.write(v)
            o.write(le32(comments.size))
            comments.forEach { c -> val b = c.toByteArray(Charsets.UTF_8); o.write(le32(b.size)); o.write(b) }
            return o.toByteArray()
        }

        fun buildPicture(c: Cover): ByteArray {
            val o = ByteArrayOutputStream()
            val mime = c.mime.toByteArray(Charsets.US_ASCII)
            o.write(be32(3)) // front cover
            o.write(be32(mime.size)); o.write(mime)
            o.write(be32(0)) // description
            repeat(4) { o.write(be32(0)) } // width, height, depth, colours (optional)
            o.write(be32(c.bytes.size)); o.write(c.bytes)
            return o.toByteArray()
        }

        fun managedComments(t: TagData): List<String> = buildList {
            fun add(k: String, v: Any?) { v?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { add("$k=$it") } }
            add("TITLE", t.title); add("ARTIST", t.artist); add("ALBUM", t.album); add("ALBUMARTIST", t.albumArtist)
            add("GENRE", t.genre); add("DATE", t.year); add("TRACKNUMBER", t.trackNo); add("TRACKTOTAL", t.trackTotal)
            add("DISCNUMBER", t.discNo)
            add("LYRICS", t.lyrics)
        }

        fun read(input: File): TagData = RandomAccessFile(input, "r").use { raf ->
            val (blocks, _) = readBlocks(raf)
            val c = blocks.firstOrNull { it.type == VORBIS }?.let { parseVorbis(it.data).second }.orEmpty()
            fun v(vararg keys: String) = keys.firstNotNullOfOrNull { k ->
                c.firstOrNull { it.substringBefore('=').equals(k, ignoreCase = true) }?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }
            }
            val track = v("TRACKNUMBER")
            TagData(
                title = v("TITLE"), artist = v("ARTIST"), album = v("ALBUM"), albumArtist = v("ALBUMARTIST", "ALBUM ARTIST"),
                genre = v("GENRE"), year = v("DATE", "YEAR")?.take(4)?.toIntOrNull(),
                trackNo = track?.substringBefore('/')?.trim()?.toIntOrNull(),
                trackTotal = v("TRACKTOTAL", "TOTALTRACKS")?.toIntOrNull() ?: track?.substringAfter('/', "")?.trim()?.toIntOrNull(),
                discNo = v("DISCNUMBER")?.substringBefore('/')?.trim()?.toIntOrNull(),
            )
        }

        fun write(input: File, output: File, t: TagData) {
            RandomAccessFile(input, "r").use { raf ->
                val (blocks, audioStart) = readBlocks(raf)
                if (blocks.firstOrNull()?.type != STREAMINFO) throw TagWriteException("FLAC without STREAMINFO")
                val oldVorbis = blocks.firstOrNull { it.type == VORBIS }?.let { parseVorbis(it.data) }
                val vendor = oldVorbis?.first ?: "Kororythm"
                val lyricKeys = if (t.lyrics != null) setOf("LYRICS", "UNSYNCEDLYRICS") else emptySet()
                val kept = oldVorbis?.second.orEmpty().filter {
                    val k = it.substringBefore('=').uppercase()
                    k !in MANAGED && k !in lyricKeys
                }
                val newBlocks = mutableListOf<Block>()
                newBlocks += blocks.first()
                blocks.drop(1).forEach { b ->
                    val keep = when (b.type) {
                        PADDING, VORBIS -> false
                        PICTURE -> t.cover == null && !t.removeCover
                        else -> true
                    }
                    if (keep) newBlocks += b
                }
                newBlocks += Block(VORBIS, buildVorbis(vendor, kept + managedComments(t)))
                t.cover?.let {
                    if (it.bytes.size > 0xFFFFFF - 1024) throw TagWriteException("Cover image is too big (max 16 MB)")
                    newBlocks += Block(PICTURE, buildPicture(it))
                }
                newBlocks += Block(PADDING, ByteArray(4096))
                output.outputStream().buffered().use { out ->
                    out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
                    newBlocks.forEachIndexed { i, b ->
                        val last = i == newBlocks.lastIndex
                        out.write((if (last) 0x80 else 0) or b.type)
                        out.write((b.data.size shr 16) and 0xFF); out.write((b.data.size shr 8) and 0xFF); out.write(b.data.size and 0xFF)
                        out.write(b.data)
                    }
                    copyRange(raf, audioStart, raf.length(), out)
                }
            }
        }
    }

    // =============================================================== MP3 / ID3v2.4

    internal object Id3 {
        private val MANAGED = setOf("TIT2", "TPE1", "TALB", "TPE2", "TCON", "TDRC", "TYER", "TDAT", "TIME", "TRDA", "TORY", "TSIZ", "TRCK", "TPOS")

        class Frame(val id: String, val flags: ByteArray, val body: ByteArray)

        /** Returns (frames we can keep, end of the old tag). */
        /** Text of a T*** frame (any of the four ID3 encodings). */
        private fun text(body: ByteArray): String? {
            if (body.isEmpty()) return null
            val cs = when (body[0].toInt()) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
            return String(body, 1, body.size - 1, cs).split('\u0000').firstOrNull { it.isNotBlank() }?.trim()
        }

        fun read(input: File): TagData = RandomAccessFile(input, "r").use { raf ->
            val (frames, _) = readOld(raf, keepManaged = true)
            fun v(id: String) = frames.firstOrNull { it.id == id }?.let { text(it.body) }?.takeIf { it.isNotEmpty() }
            val trck = v("TRCK")
            TagData(
                title = v("TIT2"), artist = v("TPE1"), album = v("TALB"), albumArtist = v("TPE2"), genre = v("TCON"),
                year = (v("TDRC") ?: v("TYER") ?: v("TORY"))?.take(4)?.toIntOrNull(),
                trackNo = trck?.substringBefore('/')?.trim()?.toIntOrNull(),
                trackTotal = trck?.substringAfter('/', "")?.trim()?.toIntOrNull(),
                discNo = v("TPOS")?.substringBefore('/')?.trim()?.toIntOrNull(),
            )
        }

        fun readOld(raf: RandomAccessFile, keepManaged: Boolean = false): Pair<List<Frame>, Long> {
            if (raf.length() < 10) return emptyList<Frame>() to 0L
            val h = ByteArray(10)
            raf.seek(0)
            raf.readFully(h)
            if (String(h, 0, 3, Charsets.ISO_8859_1) != "ID3") return emptyList<Frame>() to 0L
            val major = h[3].toInt()
            val flags = h[5].toInt()
            val size = syncsafe(h, 6)
            val end = 10L + size + if (flags and 0x10 != 0) 10 else 0
            // Unsynchronised, extended or v2.2 tags: drop the old frames, keep the audio.
            if (major !in 3..4 || flags and 0x80 != 0) return emptyList<Frame>() to end
            val tag = ByteArray(size).also { raf.readFully(it) }
            var p = 0
            if (flags and 0x40 != 0) {
                p = if (major == 4) syncsafe(tag, 0) else 4 + (((tag[0].toInt() and 0xFF) shl 24) or ((tag[1].toInt() and 0xFF) shl 16) or ((tag[2].toInt() and 0xFF) shl 8) or (tag[3].toInt() and 0xFF))
            }
            val frames = mutableListOf<Frame>()
            while (p + 10 <= size) {
                if (tag[p].toInt() == 0) break // padding
                val id = String(tag, p, 4, Charsets.ISO_8859_1)
                if (!id.all { it.isUpperCase() || it.isDigit() }) break
                val len = if (major == 4) syncsafe(tag, p + 4) else
                    ((tag[p + 4].toInt() and 0xFF) shl 24) or ((tag[p + 5].toInt() and 0xFF) shl 16) or ((tag[p + 6].toInt() and 0xFF) shl 8) or (tag[p + 7].toInt() and 0xFF)
                if (len < 0 || p + 10 + len > size) break
                val fflags = byteArrayOf(tag[p + 8], tag[p + 9])
                val body = tag.copyOfRange(p + 10, p + 10 + len)
                p += 10 + len
                // Skip frames whose flags change their layout between versions (compression, encryption…).
                val layoutFlags = if (major == 4) fflags[1].toInt() and 0x4F else fflags[1].toInt() and 0xE0
                if ((id in MANAGED && !keepManaged) || layoutFlags != 0) continue
                if (id == "APIC") { frames += Frame(id, byteArrayOf(0, 0), body); continue }
                frames += Frame(id, byteArrayOf(0, 0), body)
            }
            return frames to end
        }

        fun textFrame(id: String, value: String) =
            Frame(id, byteArrayOf(0, 0), byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)) // 3 = UTF-8

        fun apic(c: Cover): Frame {
            val o = ByteArrayOutputStream()
            o.write(0) // ISO-8859-1 description
            o.write(c.mime.toByteArray(Charsets.ISO_8859_1)); o.write(0)
            o.write(3) // front cover
            o.write(0) // empty description
            o.write(c.bytes)
            return Frame("APIC", byteArrayOf(0, 0), o.toByteArray())
        }

        fun newFrames(t: TagData): List<Frame> = buildList {
            fun add(id: String, v: String?) { v?.trim()?.takeIf { it.isNotEmpty() }?.let { add(textFrame(id, it)) } }
            add("TIT2", t.title); add("TPE1", t.artist); add("TALB", t.album); add("TPE2", t.albumArtist)
            add("TCON", t.genre); add("TDRC", t.year?.toString())
            add("TRCK", t.trackNo?.let { n -> t.trackTotal?.let { "$n/$it" } ?: "$n" })
            add("TPOS", t.discNo?.toString())
            t.cover?.let { add(apic(it)) }
            t.lyrics?.trim()?.takeIf { it.isNotEmpty() }?.let { add(uslt(it)) }
            t.sourceNote?.let { add(txxx(Quality.SOURCE_DESC, it)) }
        }

        private fun isSourceFrame(f: Frame): Boolean =
            f.id == "TXXX" && f.body.size > 1 &&
                String(f.body, 1, f.body.size - 1, Charsets.UTF_8).startsWith(Quality.SOURCE_DESC + "\u0000")

        /** User text frame: UTF-8 "description\0value". */
        fun txxx(desc: String, value: String) =
            Frame("TXXX", byteArrayOf(0, 0), byteArrayOf(3) + desc.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + value.toByteArray(Charsets.UTF_8))

        /** Unsynchronised lyrics: UTF-8, language "eng", empty description. */
        fun uslt(text: String): Frame {
            val o = ByteArrayOutputStream()
            o.write(3)
            o.write("eng".toByteArray(Charsets.ISO_8859_1))
            o.write(0) // empty description, terminated
            o.write(text.toByteArray(Charsets.UTF_8))
            return Frame("USLT", byteArrayOf(0, 0), o.toByteArray())
        }

        fun write(input: File, output: File, t: TagData) {
            RandomAccessFile(input, "r").use { raf ->
                val (old, audioStart) = readOld(raf)
                val kept = old.filter { f ->
                    (f.id != "APIC" || (t.cover == null && !t.removeCover)) && (f.id != "USLT" || t.lyrics == null) &&
                        (t.sourceNote == null || !isSourceFrame(f))
                }
                val frames = kept + newFrames(t)
                val body = ByteArrayOutputStream()
                frames.forEach { f ->
                    body.write(f.id.toByteArray(Charsets.ISO_8859_1))
                    body.write(syncsafeBytes(f.body.size))
                    body.write(f.flags)
                    body.write(f.body)
                }
                body.write(ByteArray(2048)) // padding for future edits
                val tagBytes = body.toByteArray()
                // Drop a trailing ID3v1 tag so it can't contradict the new tags.
                var audioEnd = raf.length()
                if (audioEnd - audioStart >= 128) {
                    raf.seek(audioEnd - 128)
                    val t1 = ByteArray(3).also { raf.readFully(it) }
                    if (String(t1, Charsets.ISO_8859_1) == "TAG") audioEnd -= 128
                }
                output.outputStream().buffered().use { out ->
                    out.write("ID3".toByteArray(Charsets.ISO_8859_1))
                    out.write(byteArrayOf(4, 0, 0))
                    out.write(syncsafeBytes(tagBytes.size))
                    out.write(tagBytes)
                    copyRange(raf, audioStart, audioEnd, out)
                }
            }
        }
    }
}
