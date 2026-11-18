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

    /** Renames the file; returns its (possibly new) URI. */
    suspend fun rename(context: Context, uri: Uri, newName: String): Uri = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        if (isMediaStore(uri)) {
            if (Build.VERSION.SDK_INT >= 29) {
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }, null, null)
            }
            uri
        } else if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.renameDocument(cr, uri, newName) ?: uri
        } else {
            uri
        }
    }
}
