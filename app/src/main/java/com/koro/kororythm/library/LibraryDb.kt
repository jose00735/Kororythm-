package com.koro.kororythm.library

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LibraryDao {

    // ---- tracks ----
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE")
    abstract fun tracks(): Flow<List<TrackEntity>>

    @Query(
        "SELECT tracks.* FROM tracks JOIN tracks_fts ON tracks.id = tracks_fts.rowid " +
            "WHERE tracks_fts MATCH :match ORDER BY tracks.title COLLATE NOCASE",
    )
    abstract fun search(match: String): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM tracks")
    abstract fun count(): Flow<Int>

    @Query("SELECT * FROM tracks WHERE uri = :uri")
    abstract suspend fun byUri(uri: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE contentKey = :key LIMIT 1")
    abstract suspend fun byContentKey(key: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    abstract suspend fun byIds(ids: List<Long>): List<TrackEntity>

    @Query("SELECT uri FROM tracks WHERE source = :source AND sourceRoot IS :root")
    abstract suspend fun urisFor(source: String, root: String?): List<String>

    @Query("SELECT * FROM tracks WHERE tagsMissing = 1 ORDER BY title COLLATE NOCASE")
    abstract fun missingTags(): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(t: TrackEntity): Long

    @Update
    abstract suspend fun update(t: TrackEntity)

    @Query("DELETE FROM tracks WHERE uri IN (:uris)")
    abstract suspend fun deleteUris(uris: List<String>)

    @Query("DELETE FROM tracks WHERE source = :source AND sourceRoot IS :root")
    abstract suspend fun deleteSourceTracks(source: String, root: String?)

    // ---- albums & artists (from tags) ----
    @Query(
        """SELECT COALESCE(album, 'Unknown album') AS album, COALESCE(albumArtist, artist) AS artist,
           COUNT(*) AS trackCount, SUM(durationMs) AS durationMs, MAX(artPath) AS artPath, MAX(year) AS year
           FROM tracks GROUP BY COALESCE(album, 'Unknown album'), COALESCE(albumArtist, artist)
           ORDER BY album COLLATE NOCASE""",
    )
    abstract fun albums(): Flow<List<AlbumSummary>>

    @Query(
        """SELECT * FROM tracks WHERE COALESCE(album, 'Unknown album') = :album
           AND COALESCE(albumArtist, artist) IS :artist ORDER BY discNo, trackNo, title COLLATE NOCASE""",
    )
    abstract suspend fun albumTracks(album: String, artist: String?): List<TrackEntity>

    @Query(
        """SELECT COALESCE(artist, 'Unknown artist') AS artist, COUNT(DISTINCT album) AS albumCount,
           COUNT(*) AS trackCount FROM tracks GROUP BY COALESCE(artist, 'Unknown artist')
           ORDER BY artist COLLATE NOCASE""",
    )
    abstract fun artists(): Flow<List<ArtistSummary>>

    @Query("SELECT * FROM tracks WHERE COALESCE(artist, 'Unknown artist') = :artist ORDER BY album, discNo, trackNo")
    abstract suspend fun artistTracks(artist: String): List<TrackEntity>

    // ---- sources ----
    @Query("SELECT * FROM sources ORDER BY addedAt")
    abstract fun sources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY addedAt")
    abstract suspend fun sourcesNow(): List<SourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSource(s: SourceEntity)

    @Query("DELETE FROM sources WHERE `key` = :key")
    abstract suspend fun deleteSource(key: String)

    @Query("UPDATE sources SET lastScanAt = :time WHERE `key` = :key")
    abstract suspend fun markScanned(key: String, time: Long)

    // ---- user albums ----
    @Insert
    abstract suspend fun insertAlbum(a: UserAlbumEntity): Long

    @Insert
    abstract suspend fun insertAlbumTracks(rows: List<UserAlbumTrack>)

    @Transaction
    open suspend fun createAlbum(album: UserAlbumEntity, trackIds: List<Long>): Long {
        val id = insertAlbum(album)
        insertAlbumTracks(trackIds.mapIndexed { i, t -> UserAlbumTrack(id, i, t) })
        return id
    }

    @Query(
        """SELECT a.id, a.title, a.artist, a.year, a.genre, a.coverUri,
           (SELECT tr.artPath FROM user_album_tracks x JOIN tracks tr ON tr.id = x.trackId
              WHERE x.albumId = a.id AND tr.artPath IS NOT NULL ORDER BY x.position LIMIT 1) AS artPath,
           COUNT(t.trackId) AS trackCount, COALESCE(SUM(tr.durationMs), 0) AS durationMs
           FROM user_albums a
           LEFT JOIN user_album_tracks t ON t.albumId = a.id
           LEFT JOIN tracks tr ON tr.id = t.trackId
           GROUP BY a.id ORDER BY a.createdAt DESC""",
    )
    abstract fun userAlbums(): Flow<List<UserAlbumSummary>>

    @Query(
        "SELECT tracks.* FROM user_album_tracks JOIN tracks ON tracks.id = user_album_tracks.trackId " +
            "WHERE albumId = :id ORDER BY position",
    )
    abstract suspend fun userAlbumTracks(id: Long): List<TrackEntity>

    @Query("DELETE FROM user_albums WHERE id = :id")
    abstract suspend fun deleteUserAlbum(id: Long)

    // ---- analysis (BPM + loudness) ----
    @Query("SELECT * FROM tracks WHERE analyzed = 0 ORDER BY dateAdded DESC LIMIT :limit")
    abstract suspend fun unanalyzed(limit: Int): List<TrackEntity>

    @Query("UPDATE tracks SET bpm = :bpm, gainDb = :gainDb, analyzed = 1 WHERE id = :id")
    abstract suspend fun setAnalysis(id: Long, bpm: Float?, gainDb: Float?)

    @Query("SELECT COUNT(*) FROM tracks WHERE analyzed = 1")
    abstract fun analyzedCount(): Flow<Int>

    // ---- hot cues ----
    @Query("SELECT * FROM hot_cues WHERE trackId = :trackId ORDER BY slot")
    abstract fun cues(trackId: Long): Flow<List<HotCue>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun setCue(c: HotCue)

    @Query("DELETE FROM hot_cues WHERE trackId = :trackId AND slot = :slot")
    abstract suspend fun clearCue(trackId: Long, slot: Int)

    // ---- playlists (v1.1) ----
    @Query(
        """SELECT p.id, p.name, COUNT(t.trackId) AS trackCount, COALESCE(SUM(tr.durationMs), 0) AS durationMs,
           (SELECT tr2.artPath FROM playlist_tracks x JOIN tracks tr2 ON tr2.id = x.trackId
              WHERE x.playlistId = p.id AND tr2.artPath IS NOT NULL ORDER BY x.position LIMIT 1) AS artPath
           FROM playlists p
           LEFT JOIN playlist_tracks t ON t.playlistId = p.id
           LEFT JOIN tracks tr ON tr.id = t.trackId
           GROUP BY p.id ORDER BY p.name COLLATE NOCASE""",
    )
    abstract fun playlists(): Flow<List<PlaylistSummary>>

    @Query(
        "SELECT tracks.* FROM playlist_tracks JOIN tracks ON tracks.id = playlist_tracks.trackId " +
            "WHERE playlistId = :id ORDER BY position",
    )
    abstract suspend fun playlistTracks(id: Long): List<TrackEntity>

    @Insert
    abstract suspend fun insertPlaylist(p: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    abstract suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :id")
    abstract suspend fun clearPlaylistTracks(id: Long)

    @Insert
    abstract suspend fun insertPlaylistTracks(rows: List<PlaylistTrack>)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :id")
    abstract suspend fun lastPosition(id: Long): Int

    /** Replaces the playlist's contents with [trackIds] in this order. */
    @Transaction
    open suspend fun setPlaylistOrder(id: Long, trackIds: List<Long>) {
        clearPlaylistTracks(id)
        insertPlaylistTracks(trackIds.mapIndexed { i, t -> PlaylistTrack(id, i, t) })
    }

    @Transaction
    open suspend fun appendToPlaylist(id: Long, trackIds: List<Long>) {
        val start = lastPosition(id) + 1
        insertPlaylistTracks(trackIds.mapIndexed { i, t -> PlaylistTrack(id, start + i, t) })
    }

    @Transaction
    open suspend fun createPlaylist(name: String, trackIds: List<Long>): Long {
        val id = insertPlaylist(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))
        insertPlaylistTracks(trackIds.mapIndexed { i, t -> PlaylistTrack(id, i, t) })
        return id
    }

    // ---- lyrics (v1.1) ----
    @Query("SELECT * FROM lyrics WHERE uri = :uri")
    abstract fun lyrics(uri: String): Flow<LyricsEntity?>

    @Query("SELECT * FROM lyrics WHERE uri = :uri")
    abstract suspend fun lyricsNow(uri: String): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putLyrics(l: LyricsEntity)

    @Query("UPDATE lyrics SET uri = :newUri WHERE uri = :oldUri")
    abstract suspend fun moveLyrics(oldUri: String, newUri: String)
}

@Database(
    entities = [
        TrackEntity::class, TrackFts::class, UserAlbumEntity::class, UserAlbumTrack::class, SourceEntity::class, HotCue::class,
        PlaylistEntity::class, PlaylistTrack::class, LyricsEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class LibraryDb : RoomDatabase() {
    abstract fun dao(): LibraryDao
}

/** v2 (phase 3): BPM + loudness per track, and DJ hot cues. Keeps the existing library. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN bpm REAL")
        db.execSQL("ALTER TABLE tracks ADD COLUMN gainDb REAL")
        db.execSQL("ALTER TABLE tracks ADD COLUMN analyzed INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS hot_cues (trackId INTEGER NOT NULL, slot INTEGER NOT NULL, positionMs INTEGER NOT NULL, " +
                "PRIMARY KEY(trackId, slot), FOREIGN KEY(trackId) REFERENCES tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
    }
}

/** v3 (v1.1): custom playlists and lyrics. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS playlists (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS playlist_tracks (playlistId INTEGER NOT NULL, position INTEGER NOT NULL, trackId INTEGER NOT NULL, " +
                "PRIMARY KEY(playlistId, position), " +
                "FOREIGN KEY(playlistId) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(trackId) REFERENCES tracks(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_tracks_trackId ON playlist_tracks (trackId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS lyrics (uri TEXT NOT NULL, synced TEXT, plain TEXT, source TEXT NOT NULL, fetchedAt INTEGER NOT NULL, PRIMARY KEY(uri))")
    }
}

/** App-wide access to the library database. */
object Library {
    @Volatile private var db: LibraryDb? = null

    fun init(context: Context) {
        if (db == null) {
            db = Room.databaseBuilder(context.applicationContext, LibraryDb::class.java, "library.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }
    }

    val dao: LibraryDao get() = checkNotNull(db) { "Library.init() not called" }.dao()
}
