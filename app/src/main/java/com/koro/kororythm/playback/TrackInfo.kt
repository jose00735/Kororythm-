package com.koro.kororythm.playback

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** What the main panel shows about a track: tags plus the kbps / kHz / bit readout. */
data class TrackInfo(
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val bitrateKbps: Int?,
    val sampleRate: Int?,
    val bits: Int?,
    val codec: String?,
    /** Added on the fly (play next / add to end), not part of the list that was started. */
    val queued: Boolean = false,
) {
    fun toMediaItem(): MediaItem {
        val extras = Bundle().apply {
            putLong(K_DURATION, durationMs)
            bitrateKbps?.let { putInt(K_BITRATE, it) }
            sampleRate?.let { putInt(K_RATE, it) }
            bits?.let { putInt(K_BITS, it) }
            codec?.let { putString(K_CODEC, it) }
            if (queued) putBoolean(K_QUEUED, true)
        }
        return MediaItem.Builder()
            .setMediaId(uri)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(uri)).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    companion object {
        private const val K_DURATION = "koro.duration"
        private const val K_BITRATE = "koro.kbps"
        private const val K_RATE = "koro.rate"
        private const val K_BITS = "koro.bits"
        private const val K_CODEC = "koro.codec"
        private const val K_QUEUED = "koro.queued"

        fun fromMediaItem(item: MediaItem): TrackInfo {
            val md = item.mediaMetadata
            val ex = md.extras
            return TrackInfo(
                uri = item.mediaId,
                title = md.title?.toString() ?: "Unknown",
                artist = md.artist?.toString(),
                album = md.albumTitle?.toString(),
                durationMs = ex?.getLong(K_DURATION) ?: 0L,
                bitrateKbps = ex?.takeIf { it.containsKey(K_BITRATE) }?.getInt(K_BITRATE),
                sampleRate = ex?.takeIf { it.containsKey(K_RATE) }?.getInt(K_RATE),
                bits = ex?.takeIf { it.containsKey(K_BITS) }?.getInt(K_BITS),
                codec = ex?.getString(K_CODEC),
                queued = ex?.getBoolean(K_QUEUED, false) ?: false,
            )
        }

        /** Reads tags and stream info. Runs on a background thread. */
        fun read(context: Context, uri: Uri): TrackInfo {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                fun s(key: Int) = r.extractMetadata(key)?.takeIf { it.isNotBlank() }
                val mime = s(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                val rate = if (Build.VERSION.SDK_INT >= 31) s(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() else null
                val bits = if (Build.VERSION.SDK_INT >= 31) s(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() else null
                return TrackInfo(
                    uri = uri.toString(),
                    title = s(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: displayName(context, uri),
                    artist = s(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album = s(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    durationMs = s(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                    bitrateKbps = s(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.let { it / 1000 },
                    sampleRate = rate,
                    bits = bits?.takeIf { it > 0 },
                    codec = when {
                        mime == null -> null
                        mime.contains("flac") -> "FLAC"
                        mime.contains("mpeg") || mime.contains("mp3") -> "MP3"
                        else -> mime.substringAfter('/').uppercase()
                    },
                )
            } catch (e: Exception) {
                return TrackInfo(uri.toString(), displayName(context, uri), null, null, 0L, null, null, null, null)
            } finally {
                r.release()
            }
        }

        private fun displayName(context: Context, uri: Uri): String =
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()?.substringBeforeLast('.') ?: uri.lastPathSegment ?: "Unknown"
    }
}
