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
        val openError = openError(context, renamed)
        if (openError != null) {
            // Don't leave the library pointing at something it can't play: undo.
            if (oldName != null) runCatching {
                if (isMediaStore(renamed)) cr.update(renamed, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, oldName) }, null, null)
                else DocumentsContract.renameDocument(cr, renamed, oldName)
            }
            throw RenameUnreadable(
                "Renaming made the file unreadable (${renamed.authority}: $openError), so the old name was put back.",
                newName, isMediaStore(uri),
            )
        }
        renamed
    }

    /** A renamed document has a new URI: hold on to read/write access across restarts. */
    private fun keepAccess(context: Context, uri: Uri) {
        val cr = context.contentResolver
        runCatching { cr.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .recoverCatching { cr.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Null if the file opens and has data, else why not. */
    private fun openError(context: Context, uri: Uri): String? = try {
        val ins = context.contentResolver.openInputStream(uri) ?: return "no stream"
        ins.use { if (it.read() < 0) "empty" else null }
    } catch (e: Exception) {
        "${e.javaClass.simpleName}: ${e.message?.take(120)}"
    }

    /**
     * Rename by copying, for phones where renaming a media-library file in place breaks access
     * to it: writes the file again under [newName] in the same folder (Kororythm owns the copy,
     * so it can always read it) and returns the copy's URI. The caller removes the old file.
     */
    suspend fun renameByCopy(context: Context, uri: Uri, newName: String): Uri = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val dir = File(context.cacheDir, "rename").apply { deleteRecursively(); mkdirs() }
        val tmp = File(dir, "copy.bin")
        try {
            (cr.openInputStream(uri) ?: throw TagWriteException("Can't read the file")).use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
            val mime = cr.getType(uri) ?: com.koro.kororythm.downloads.YtLogic.mimeFor(newName.substringAfterLast('.', ""))
            val copy = if (Build.VERSION.SDK_INT >= 29) {
                com.koro.kororythm.downloads.MusicStore.save(context, tmp, newName, mime, com.koro.kororythm.downloads.MusicStore.relativePathOf(context, uri) ?: "Music/")
            } else {
                @Suppress("DEPRECATION")
                val parent = cr.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { File(it).parentFile } else null }
                parent?.let { com.koro.kororythm.downloads.MusicStore.saveLegacy(context, tmp, newName, mime, it) }
            } ?: throw TagWriteException("Couldn't write the renamed copy")
            openError(context, copy)?.let { throw TagWriteException("The renamed copy can't be read either ($it)") }
            copy
        } finally {
            dir.deleteRecursively()
        }
    }
}

open class RenameUnsupported(message: String) : Exception(message)

/** The rename worked but the file couldn't be opened afterwards (it was renamed back). */
class RenameUnreadable(message: String, val wantedName: String, val mediaStore: Boolean) : RenameUnsupported(message)
