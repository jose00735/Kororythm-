package com.koro.kororythm.downloads

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/** Saves finished files into the phone's shared Music folder (Music/Kororythm by default). */
object MusicStore {
    const val FOLDER = "Music/Kororythm"

    /** Copies [file] into [relativePath] as [displayName]; returns its MediaStore URI. */
    suspend fun save(context: Context, file: File, displayName: String, mime: String, relativePath: String = FOLDER): Uri? {
        val cr = context.contentResolver
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath.trimEnd('/') + "/")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = cr.insert(collection, values) ?: return null
            try {
                cr.openOutputStream(uri, "w")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                uri
            } catch (e: Exception) {
                cr.delete(uri, null, null)
                throw e
            }
        } else {
            // Android 8–9: plain file in the public Music folder (needs WRITE_EXTERNAL_STORAGE), then index it.
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStorageDirectory()
            val dir = File(root, relativePath).apply { mkdirs() }
            var dst = File(dir, displayName)
            var n = 2
            while (dst.exists()) dst = File(dir, displayName.substringBeforeLast('.') + " ($n)." + displayName.substringAfterLast('.')).also { n++ }
            file.copyTo(dst)
            scan(context, dst, mime)
        }
    }

    private suspend fun scan(context: Context, file: File, mime: String): Uri? = suspendCancellableCoroutine { c ->
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime)) { _, uri -> if (c.isActive) c.resume(uri) }
    }

    /** "Music/Kororythm/" style folder of an existing MediaStore file (for Upgrade quality). */
    fun relativePathOf(context: Context, uri: Uri): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
    }
}
