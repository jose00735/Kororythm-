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
}

@Database(
    entities = [TrackEntity::class, TrackFts::class, UserAlbumEntity::class, UserAlbumTrack::class, SourceEntity::class, HotCue::class],
    version = 2,
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

/** App-wide access to the library database. */
object Library {
    @Volatile private var db: LibraryDb? = null

    fun init(context: Context) {
        if (db == null) {
            db = Room.databaseBuilder(context.applicationContext, LibraryDb::class.java, "library.db")
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }

    val dao: LibraryDao get() = checkNotNull(db) { "Library.init() not called" }.dao()
}
