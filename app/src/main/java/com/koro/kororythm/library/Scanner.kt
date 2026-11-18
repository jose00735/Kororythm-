package com.koro.kororythm.library

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class ScanProgress(val found: Int, val processed: Int, val current: String)

data class ScanResult(
    val added: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val duplicates: Int = 0,
    val removed: Int = 0,
    val failed: Int = 0,
) {
    operator fun plus(o: ScanResult) = ScanResult(
        added + o.added, updated + o.updated, unchanged + o.unchanged,
        duplicates + o.duplicates, removed + o.removed, failed + o.failed,
    )
}

/**
 * Finds FLAC/MP3 files in the phone's music library (MediaStore) and in folders picked with
 * the system folder picker, reads their tags and cover art, and keeps the library in sync:
 * new files are added, changed files updated, deleted files removed, duplicates skipped.
 */
class Scanner(private val context: Context, private val dao: LibraryDao) {

    private class Candidate(val uri: Uri, val name: String, val size: Long, val source: String, val root: String?)

    private val keyLock = Mutex()

    suspend fun scan(sources: List<SourceEntity>, onProgress: suspend (ScanProgress) -> Unit): ScanResult =
        withContext(Dispatchers.IO) {
            val perSource = sources.map { src ->
                src to runCatching {
                    if (src.isMediaStore) listMediaStore() else listTree(Uri.parse(src.key))
                }.getOrDefault(emptyList())
            }
            val total = perSource.sumOf { it.second.size }
            val done = AtomicInteger(0)
            var result = ScanResult()
            for ((src, files) in perSource) {
                result += scanSource(src, files, total, done, onProgress)
                dao.markScanned(src.key, System.currentTimeMillis())
            }
            result
        }

    private suspend fun scanSource(
        src: SourceEntity,
        files: List<Candidate>,
        total: Int,
        done: AtomicInteger,
        onProgress: suspend (ScanProgress) -> Unit,
    ): ScanResult = coroutineScope {
        val gate = Semaphore(4)
        val results = files.map { c ->
            async {
                gate.withPermit {
                    val r = runCatching { process(c) }.getOrElse { ScanResult(failed = 1) }
                    val n = done.incrementAndGet()
                    if (n % 5 == 0 || n == total) onProgress(ScanProgress(total, n, c.name))
                    r
                }
            }
        }.awaitAll()
        // Files that were in the library for this source but are gone now.
        val sourceType = if (src.isMediaStore) "mediastore" else "folder"
        val root = if (src.isMediaStore) null else src.key
        val seen = files.map { it.uri.toString() }.toHashSet()
        val gone = dao.urisFor(sourceType, root).filter { it !in seen }
        if (gone.isNotEmpty()) gone.chunked(500).forEach { dao.deleteUris(it) }
        results.fold(ScanResult(removed = gone.size)) { a, b -> a + b }
    }

    /** Adds (or refreshes) one file right away, e.g. a finished download or an upgraded track. */
    suspend fun addOne(uri: Uri, name: String, size: Long, source: String = SourceEntity.MEDIASTORE_KEY, root: String? = null): TrackEntity? =
        withContext(Dispatchers.IO) {
            process(Candidate(uri, name, size, source, root))
            dao.byUri(uri.toString())
        }

    /**
     * Points an existing library row at a replacement file (Upgrade quality): keeps the row id
     * (so playlists, hot cues and play history stay), keeps the curated tags, takes the new
     * file's technical data and queues it for a fresh BPM / loudness analysis.
     */
    suspend fun replaceFile(old: TrackEntity, newUri: Uri, name: String, size: Long, sampleRate: Int?, bits: Int?): TrackEntity =
        withContext(Dispatchers.IO) {
            val head = ByteArray(64 * 1024)
            val len = context.contentResolver.openInputStream(newUri)?.use { ins ->
                var read = 0
                while (read < head.size) { val n = ins.read(head, read, head.size - read); if (n < 0) break; read += n }
                read
            } ?: 0
            val fresh = readTags(Candidate(newUri, name, size, old.source, old.sourceRoot), TagRules.contentKey(head, len, size), old)
            val row = old.copy(
                uri = newUri.toString(),
                durationMs = fresh.durationMs.takeIf { it > 0 } ?: old.durationMs,
                bitrateKbps = fresh.bitrateKbps, sampleRate = sampleRate ?: fresh.sampleRate, bits = bits ?: fresh.bits,
                codec = fresh.codec, sizeBytes = size, contentKey = fresh.contentKey,
                artPath = old.artPath ?: fresh.artPath,
                bpm = null, gainDb = null, analyzed = false,
            )
            dao.update(row)
            row
        }

    private suspend fun process(c: Candidate): ScanResult {
        val uriStr = c.uri.toString()
        val existing = dao.byUri(uriStr)
        if (existing != null && existing.sizeBytes == c.size) return ScanResult(unchanged = 1)

        val head = ByteArray(64 * 1024)
        val len = context.contentResolver.openInputStream(c.uri)?.use { ins ->
            var read = 0
            while (read < head.size) {
                val n = ins.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            read
        } ?: return ScanResult(failed = 1)
        val key = TagRules.contentKey(head, len, c.size)

        val entity = readTags(c, key, existing)
        return keyLock.withLock {
            val twin = dao.byContentKey(key)
            when {
                existing != null -> { dao.update(entity.copy(id = existing.id)); ScanResult(updated = 1) }
                twin != null && twin.uri != uriStr -> ScanResult(duplicates = 1)
                dao.insert(entity) > 0 -> ScanResult(added = 1)
                else -> ScanResult(unchanged = 1)
            }
        }
    }

    private fun readTags(c: Candidate, key: String, existing: TrackEntity?): TrackEntity {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, c.uri)
            fun s(k: Int) = r.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
            val tagTitle = s(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val (nameArtist, nameTitle) = TagRules.parseFileName(c.name)
            val artist = s(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = s(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val mime = s(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val rate = if (Build.VERSION.SDK_INT >= 31) s(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() else null
            val bits = if (Build.VERSION.SDK_INT >= 31) s(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()?.takeIf { it > 0 } else null
            val art = r.embeddedPicture?.let { ArtStore.save(context, it) }
            return TrackEntity(
                uri = c.uri.toString(),
                title = tagTitle ?: nameTitle,
                artist = artist ?: nameArtist,
                album = album,
                albumArtist = s(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                genre = s(MediaMetadataRetriever.METADATA_KEY_GENRE),
                year = TagRules.parseYear(s(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: s(MediaMetadataRetriever.METADATA_KEY_DATE)),
                trackNo = TagRules.parseNumber(s(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                discNo = TagRules.parseNumber(s(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                durationMs = s(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                bitrateKbps = s(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.div(1000),
                sampleRate = rate,
                bits = bits,
                codec = when {
                    mime == null -> c.name.substringAfterLast('.').uppercase()
                    mime.contains("flac") -> "FLAC"
                    mime.contains("mpeg") || mime.contains("mp3") -> "MP3"
                    else -> mime.substringAfter('/').uppercase()
                },
                sizeBytes = c.size,
                contentKey = key,
                artPath = art,
                source = c.source,
                sourceRoot = c.root,
                dateAdded = existing?.dateAdded ?: System.currentTimeMillis(),
                tagsMissing = TagRules.tagsMissing(tagTitle, artist ?: nameArtist, album),
            )
        } finally {
            r.release()
        }
    }

    // ---- listing ----

    private fun listMediaStore(): List<Candidate> {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val out = mutableListOf<Candidate>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE,
        )
        context.contentResolver.query(collection, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(1) ?: continue
                if (!TagRules.isAudio(cur.getString(3), name)) continue
                out += Candidate(ContentUris.withAppendedId(collection, cur.getLong(0)), name, cur.getLong(2), "mediastore", null)
            }
        }
        return out
    }

    private fun listTree(tree: Uri): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val stack = ArrayDeque<String>().apply { add(DocumentsContract.getTreeDocumentId(tree)) }
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        )
        while (stack.isNotEmpty()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, stack.removeLast())
            context.contentResolver.query(children, proj, null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getString(0)
                    val name = cur.getString(1) ?: ""
                    val mime = cur.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) stack.add(id)
                    } else if (TagRules.isAudio(mime, name)) {
                        out += Candidate(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, cur.getLong(3), "folder", tree.toString())
                    }
                }
            }
        }
        return out
    }
}
