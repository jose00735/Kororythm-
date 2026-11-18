package com.koro.kororythm.tags

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.koro.kororythm.downloads.MusicStore
import com.koro.kororythm.downloads.YtLogic
import com.koro.kororythm.library.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A replacement file copied next to the old one. */
data class Placed(val uri: Uri, val name: String, val wantedName: String, val size: Long)

sealed class DeleteStep {
    data object Done : DeleteStep()
    /** Android asks the user to allow deleting the old file; [retry] = call delete again after OK. */
    class NeedsConsent(val sender: IntentSender, val retry: Boolean) : DeleteStep()
    class Failed(val reason: String) : DeleteStep()
}

/**
 * Upgrade quality, Android side: measures files, writes the replacement into the old file's
 * folder with the old tags, and removes the old file.
 */
object Upgrader {

    fun specOf(track: TrackEntity, context: Context): Quality.Spec {
        val note = runCatching { readHead(context, Uri.parse(track.uri), 256 * 1024)?.let { Quality.sourceNote(it) } }.getOrNull()
        return Quality.withSource(Quality.Spec(track.codec, track.bitrateKbps, track.sampleRate, track.bits), note)
    }

    suspend fun inspect(context: Context, uri: Uri): Quality.Spec = withContext(Dispatchers.IO) {
        val head = readHead(context, uri, 256 * 1024) ?: throw TagWriteException("Can't read that file")
        val flac = Quality.flacInfo(head)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            fun s(k: Int) = r.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
            val mime = s(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            val codec = when {
                flac != null || mime.contains("flac") -> "FLAC"
                mime.contains("mpeg") -> "MP3"
                mime.contains("wav") -> "WAV"
                mime.contains("mp4") -> "AAC"
                mime.contains("ogg") || mime.contains("opus") -> "OPUS"
                else -> mime.substringAfter('/').uppercase().ifEmpty { "AUDIO" }
            }
            val rate = flac?.first ?: if (Build.VERSION.SDK_INT >= 31) s(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() else null
            val bits = flac?.second ?: if (Build.VERSION.SDK_INT >= 31) s(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()?.takeIf { it > 0 } else null
            val spec = Quality.Spec(codec, s(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.div(1000), rate, bits)
            Quality.withSource(spec, Quality.sourceNote(head))
        } finally {
            r.release()
        }
    }

    private fun readHead(context: Context, uri: Uri, max: Int): ByteArray? =
        context.contentResolver.openInputStream(uri)?.use { ins ->
            val b = ByteArray(max)
            var n = 0
            while (n < max) { val r = ins.read(b, n, max - n); if (r < 0) break; n += r }
            b.copyOf(n)
        }

    /**
     * Copies [picked] into the folder of [old] as "<old name>.<new ext>", with the old tags,
     * cover and lyrics written in (FLAC and MP3).
     */
    suspend fun place(context: Context, old: TrackEntity, picked: Uri, tags: TagData): Placed = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val oldUri = Uri.parse(old.uri)
        val pickedName = FileAccess.displayName(context, picked) ?: "replacement"
        val ext = pickedName.substringAfterLast('.', "").lowercase().ifEmpty {
            when (cr.getType(picked)) { "audio/flac", "audio/x-flac" -> "flac"; "audio/mpeg" -> "mp3"; else -> "bin" }
        }
        val oldName = FileAccess.displayName(context, oldUri) ?: YtLogic.fileName(old.artist, old.title, "x")
        val wanted = oldName.substringBeforeLast('.') + "." + ext
        val mime = YtLogic.mimeFor(ext)

        val dir = File(context.cacheDir, "upgrade").apply { deleteRecursively(); mkdirs() }
        val src = File(dir, "picked.$ext")
        (cr.openInputStream(picked) ?: throw TagWriteException("Can't read the new file")).use { ins -> src.outputStream().use { ins.copyTo(it) } }
        val ready = if (ext == "flac" || ext == "mp3") File(dir, "tagged.$ext").also { TagIo.write(src, it, tags) } else src

        try {
            val uri: Uri = when {
                oldUri.authority == MediaStore.AUTHORITY -> {
                    val rel = MusicStore.relativePathOf(context, oldUri)
                    if (Build.VERSION.SDK_INT >= 29) {
                        MusicStore.save(context, ready, wanted, mime, rel ?: MusicStore.FOLDER)
                    } else {
                        legacyDir(context, oldUri)?.let { MusicStore.saveLegacy(context, ready, wanted, mime, it) }
                    } ?: throw TagWriteException("Couldn't write into the song's folder")
                }
                DocumentsContract.isDocumentUri(context, oldUri) && old.sourceRoot != null -> {
                    val tree = Uri.parse(old.sourceRoot)
                    val parent = parentDocument(context, tree, oldUri) ?: throw TagWriteException("Can't find the song's folder")
                    val created = DocumentsContract.createDocument(cr, parent, mime, wanted)
                        ?: throw TagWriteException("The folder didn't accept a new file")
                    cr.openOutputStream(created, "w")!!.use { out -> ready.inputStream().use { it.copyTo(out) } }
                    created
                }
                else -> throw TagWriteException("Only songs in your library folders can be upgraded")
            }
            Placed(uri, FileAccess.displayName(context, uri) ?: wanted, wanted, ready.length())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun parentDocument(context: Context, tree: Uri, doc: Uri): Uri? {
        val cr = context.contentResolver
        val parentId = runCatching { DocumentsContract.findDocumentPath(cr, doc)?.path?.let { it.getOrNull(it.size - 2) } }.getOrNull()
            ?: DocumentsContract.getDocumentId(doc).let { id -> if (id.contains('/')) id.substringBeforeLast('/') else null }
            ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
    }

    @Suppress("DEPRECATION")
    private fun legacyDir(context: Context, uri: Uri): File? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.let { p -> File(p).parentFile } else null
        }
    }.getOrNull()

    /** Removes the old file; may need the user's OK first. */
    fun deleteOld(context: Context, oldUri: Uri): DeleteStep {
        val cr = context.contentResolver
        return try {
            if (oldUri.authority == MediaStore.AUTHORITY) {
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        if (cr.delete(oldUri, null, null) > 0) DeleteStep.Done
                        else DeleteStep.NeedsConsent(MediaStore.createDeleteRequest(cr, listOf(oldUri)).intentSender, false)
                    } catch (e: SecurityException) {
                        DeleteStep.NeedsConsent(MediaStore.createDeleteRequest(cr, listOf(oldUri)).intentSender, false)
                    }
                } else {
                    try {
                        cr.delete(oldUri, null, null); DeleteStep.Done
                    } catch (e: SecurityException) {
                        if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) {
                            DeleteStep.NeedsConsent(e.userAction.actionIntent.intentSender, true)
                        } else DeleteStep.Failed("Android didn't allow deleting the old file")
                    }
                }
            } else {
                if (DocumentsContract.deleteDocument(cr, oldUri)) DeleteStep.Done else DeleteStep.Failed("The folder didn't allow deleting the old file")
            }
        } catch (e: Exception) {
            DeleteStep.Failed(e.message ?: "Couldn't delete the old file")
        }
    }

    /** After the old file is gone, give the new one the old name if Android had to add " (1)". */
    suspend fun fixName(context: Context, placed: Placed): Uri =
        if (placed.name == placed.wantedName) placed.uri
        else runCatching { FileAccess.rename(context, placed.uri, placed.wantedName) }.getOrDefault(placed.uri)
}
