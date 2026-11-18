package com.koro.kororythm.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import com.koro.kororythm.playback.TrackInfo

/** One audio file in the library. Files stay where they are; this row only indexes them. */
@Entity(
    tableName = "tracks",
    indices = [Index(value = ["uri"], unique = true), Index("contentKey"), Index("album"), Index("artist")],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val genre: String?,
    val year: Int?,
    val trackNo: Int?,
    val discNo: Int?,
    val durationMs: Long,
    val bitrateKbps: Int?,
    val sampleRate: Int?,
    val bits: Int?,
    val codec: String?,
    val sizeBytes: Long,
    /** Hash of the first 64 KB + file size; finds the same song imported twice. */
    val contentKey: String,
    /** Downscaled cover art in app storage, if the file has one. */
    val artPath: String?,
    /** "mediastore" or "folder". */
    val source: String,
    /** Folder (tree URI) the file was found under, for folder sources. */
    val sourceRoot: String?,
    val dateAdded: Long,
    /** Title came from the file name, or artist / album is empty. */
    val tagsMissing: Boolean,
) {
    fun toTrackInfo() = TrackInfo(uri, title, artist, album, durationMs, bitrateKbps, sampleRate, bits, codec)
}

/** Full-text search index over the tags (kept in sync with [TrackEntity] by Room). */
@Fts4(contentEntity = TrackEntity::class)
@Entity(tableName = "tracks_fts")
data class TrackFts(val title: String, val artist: String?, val album: String?, val genre: String?)

/** Album made with the "Compile Program" wizard. */
@Entity(tableName = "user_albums")
data class UserAlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String?,
    val year: Int?,
    val genre: String?,
    /** content:// image the user picked, or null to use the first track's art. */
    val coverUri: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "user_album_tracks",
    primaryKeys = ["albumId", "position"],
    foreignKeys = [
        ForeignKey(UserAlbumEntity::class, ["id"], ["albumId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TrackEntity::class, ["id"], ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("trackId")],
)
data class UserAlbumTrack(val albumId: Long, val position: Int, val trackId: Long)

/** Where music comes from: the phone's music library or a folder the user picked. */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val key: String,
    val label: String,
    val addedAt: Long,
    val lastScanAt: Long? = null,
) {
    val isMediaStore: Boolean get() = key == MEDIASTORE_KEY

    companion object {
        const val MEDIASTORE_KEY = "mediastore"
    }
}

// ---- query results ----

data class AlbumSummary(
    val album: String,
    val artist: String?,
    val trackCount: Int,
    val durationMs: Long,
    val artPath: String?,
    val year: Int?,
)

data class ArtistSummary(val artist: String, val albumCount: Int, val trackCount: Int)

data class UserAlbumSummary(
    val id: Long,
    val title: String,
    val artist: String?,
    val year: Int?,
    val genre: String?,
    val coverUri: String?,
    val artPath: String?,
    val trackCount: Int,
    val durationMs: Long,
)
