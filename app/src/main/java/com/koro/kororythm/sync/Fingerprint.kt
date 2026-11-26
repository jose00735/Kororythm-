package com.koro.kororythm.sync

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * The sync fingerprint (protocol/SYNC.md, same as desktop/internal/syncer/fingerprint.go):
 * identifies a song by its audio, not its tags. FLAC: STREAMINFO's MD5 ("flac-<md5>");
 * otherwise SHA-256 of up to 4 MiB of the audio between an ID3v2 tag and an ID3v1 / APE tag:
 * "sha-<32 hex>-<audio length>".
 */
object Fingerprint {
    private const val SPAN = 4L shl 20

    /** Random access to a file's bytes. */
    interface Source {
        val size: Long
        /** Reads up to [len] bytes at [pos]; returns how many were read. */
        fun read(pos: Long, buf: ByteArray, off: Int = 0, len: Int = buf.size): Int
    }

    class ChannelSource(private val ch: FileChannel) : Source {
        override val size: Long = ch.size()
        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            var done = 0
            while (done < len) {
                val n = ch.read(ByteBuffer.wrap(buf, off + done, len - done), pos + done)
                if (n <= 0) break
                done += n
            }
            return done
        }
    }

    fun of(file: File): String = RandomAccessFile(file, "r").use { compute(ChannelSource(it.channel)) }

    fun of(context: Context, uri: Uri): String =
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { compute(ChannelSource(it.channel)) }
        }

    fun compute(src: Source): String {
        val size = src.size
        val head = ByteArray(42)
        val n = src.read(0, head)
        var start = 0L
        var end = size
        fun u(i: Int) = head[i].toInt() and 0xff

        if (n >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "fLaC") {
            if (n >= 42 && (u(4) and 0x7f) == 0 && (26 until 42).any { head[it].toInt() != 0 }) {
                return "flac-" + hex(head.copyOfRange(26, 42))
            }
            var pos = 4L
            val h = ByteArray(4)
            while (true) {
                require(src.read(pos, h) == 4) { "flac: bad metadata" }
                val l = ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
                pos += 4 + l
                if (h[0].toInt() and 0x80 != 0) break
            }
            start = pos
        } else {
            if (n >= 10 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3") {
                start = 10 + ((u(6) and 0x7f).toLong() shl 21 or ((u(7) and 0x7f).toLong() shl 14) or ((u(8) and 0x7f).toLong() shl 7) or (u(9) and 0x7f).toLong())
                if (u(5) and 0x10 != 0) start += 10
            }
            if (end - start >= 128) {
                val t = ByteArray(3)
                src.read(end - 128, t)
                if (String(t, Charsets.ISO_8859_1) == "TAG") end -= 128
            }
            if (end - start >= 32) {
                val ft = ByteArray(32)
                src.read(end - 32, ft)
                if (String(ft, 0, 8, Charsets.ISO_8859_1) == "APETAGEX") {
                    fun le(o: Int) = (ft[o].toLong() and 0xff) or ((ft[o + 1].toLong() and 0xff) shl 8) or
                        ((ft[o + 2].toLong() and 0xff) shl 16) or ((ft[o + 3].toLong() and 0xff) shl 24)
                    end -= le(12)
                    if (le(20) and (1L shl 31) != 0L) end -= 32
                }
            }
        }
        if (start > end) start = end
        val span = minOf(end - start, SPAN)
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var pos = start
        while (pos < start + span) {
            val want = minOf(buf.size.toLong(), start + span - pos).toInt()
            val got = src.read(pos, buf, 0, want)
            if (got <= 0) break
            md.update(buf, 0, got)
            pos += got
        }
        return "sha-" + hex(md.digest()).substring(0, 32) + "-" + (end - start)
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
