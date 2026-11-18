package com.koro.kororythm.tags

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class WriteAccess {
    data object Ok : WriteAccess()
    /** Android needs the user to allow editing this file (system dialog). */
    class NeedsConsent(val sender: IntentSender) : WriteAccess()
    class Denied(val reason: String) : WriteAccess()
}

/** Reading, rewriting and renaming music files through Android's storage APIs. */
object FileAccess {

    private fun isMediaStore(uri: Uri) = uri.authority == MediaStore.AUTHORITY

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun check(context: Context, uri: Uri): WriteAccess {
        return try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.close()
            WriteAccess.Ok
        } catch (e: SecurityException) {
            when {
                Build.VERSION.SDK_INT >= 30 && isMediaStore(uri) ->
                    WriteAccess.NeedsConsent(MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender)
                Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException ->
                    WriteAccess.NeedsConsent(e.userAction.actionIntent.intentSender)
                isMediaStore(uri) -> WriteAccess.Denied("Android didn't allow Kororythm to edit this file.")
                else -> WriteAccess.Denied(
                    "Kororythm can only read this folder. In Library ▸ Import, remove the folder and add it again to allow editing.",
                )
            }
        } catch (e: Exception) {
            WriteAccess.Denied("This file can't be opened for editing (${e.javaClass.simpleName}).")
        }
    }

    /**
     * Writes [tags] into the file: copies it to app storage, writes the tagged copy, then puts
     * it back. If putting it back fails, the original bytes are restored.
     * Returns the new file (in cache) so the caller can re-index it; delete it afterwards.
     */
    suspend fun rewrite(context: Context, uri: Uri, tags: TagData): File = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val dir = File(context.cacheDir, "tagedit").apply { mkdirs() }
        val original = File.createTempFile("orig", ".bin", dir)
        val tagged = File.createTempFile("new", ".bin", dir)
        try {
            (cr.openInputStream(uri) ?: throw TagWriteException("Can't read the file")).use { ins ->
                original.outputStream().use { ins.copyTo(it) }
            }
            TagIo.write(original, tagged, tags)
            try {
                (cr.openOutputStream(uri, "wt") ?: throw TagWriteException("Can't write the file")).use { out ->
                    tagged.inputStream().use { it.copyTo(out) }
                }
            } catch (e: Exception) {
                runCatching { cr.openOutputStream(uri, "wt")?.use { out -> original.inputStream().use { it.copyTo(out) } } }
                throw TagWriteException("Saving failed, the original file was kept (${e.message})")
            }
            tagged
        } finally {
            original.delete()
        }
    }

    /**
     * Renames the file and returns the URI that plays it afterwards. The extension is kept,
     * a renamed document keeps Kororythm's permission (it gets a new URI), and the result is
     * checked by opening it; if it can't be opened the old name is put back.
     * Throws [RenameUnsupported] when this storage can't rename files (tags are still saved).
     */
    suspend fun rename(context: Context, uri: Uri, wanted: String): Uri = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val oldName = displayName(context, uri)
        val ext = oldName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
        val newName = if (ext != null && !wanted.endsWith(".$ext", ignoreCase = true)) "$wanted.$ext" else wanted
        if (oldName == newName) return@withContext uri
        val renamed: Uri = when {
            isMediaStore(uri) -> {
                if (Build.VERSION.SDK_INT < 29) throw RenameUnsupported("Renaming needs Android 10 or newer for this file.")
                val n = cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }, null, null)
                if (n <= 0) throw RenameUnsupported("Android didn't rename the file.")
                uri // MediaStore keeps the same id
            }
            DocumentsContract.isDocumentUri(context, uri) -> {
                val r = try {
                    DocumentsContract.renameDocument(cr, uri, newName)
                } catch (e: UnsupportedOperationException) {
                    throw RenameUnsupported("This storage location can't rename files.")
                } catch (e: IllegalArgumentException) {
                    throw RenameUnsupported("This storage location can't rename files.")
                } ?: uri
                if (r != uri) keepAccess(context, r)
                r
            }
            else -> throw RenameUnsupported("This file can't be renamed from here.")
        }
        if (!canOpen(context, renamed)) {
            // Don't leave the library pointing at something it can't play: undo.
            if (oldName != null) runCatching {
                if (isMediaStore(renamed)) cr.update(renamed, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, oldName) }, null, null)
                else DocumentsContract.renameDocument(cr, renamed, oldName)
            }
            throw RenameUnsupported("Renaming made the file unreadable, so the old name was kept.")
        }
        renamed
    }

    /** A renamed document has a new URI: hold on to read/write access across restarts. */
    private fun keepAccess(context: Context, uri: Uri) {
        val cr = context.contentResolver
        runCatching { cr.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .recoverCatching { cr.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun canOpen(context: Context, uri: Uri): Boolean =
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true }.getOrDefault(false)
}

class RenameUnsupported(message: String) : Exception(message)
