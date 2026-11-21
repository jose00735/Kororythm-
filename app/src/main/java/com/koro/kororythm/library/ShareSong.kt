package com.koro.kororythm.library

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** "Share…" in a song's menu: hands the audio file itself to WhatsApp, Drive, Bluetooth, email… */
object ShareSong {
    fun share(context: Context, t: TrackEntity) {
        val uri = Uri.parse(t.uri)
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "audio/*"
        val label = listOfNotNull(t.artist, t.title).joinToString(" – ")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, label)
            putExtra(Intent.EXTRA_SUBJECT, label)
            // The receiving app may read just this file, nothing else.
            clipData = ClipData.newRawUri(label, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(send, "Share \"${t.title}\"").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(context, "No app can receive audio files", Toast.LENGTH_SHORT).show()
        }
    }
}
