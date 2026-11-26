package com.koro.kororythm.sync

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.koro.kororythm.downloads.MusicStore
import com.koro.kororythm.library.Library
import com.koro.kororythm.library.Scanner
import com.koro.kororythm.library.TrackEntity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

enum class SyncPhase { IDLE, COMPARING, MOVING, DONE, ERROR }

/** What the Sync screen draws. */
data class SyncUi(
    val phase: SyncPhase = SyncPhase.IDLE,
    val pc: String? = null,
    val message: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val bytes: Long = 0,
    val totalBytes: Long = 0,
    val current: String? = null,
    val toPhone: Int = 0,
    val toPc: Int = 0,
    val skipped: Int = 0,
    val at: Long = System.currentTimeMillis(),
)

/** A song on its way (one flying cover in the animation). */
data class Flight(val toPhone: Boolean, val title: String, val art: ByteArray?, val at: Long = System.currentTimeMillis())

/** Shared between the sync worker and the screen (same process). */
object SyncBus {
    val ui = MutableStateFlow(SyncUi())
    val flights = MutableSharedFlow<Flight>(extraBufferCapacity = 32)
}

/** Runs one sync with a paired PC. */
class SyncEngine(private val context: Context, private val store: SyncStore = SyncStore(context)) {
    private val dao = Library.dao
    private fun ui(f: (SyncUi) -> SyncUi) = SyncBus.ui.update(f)

    /** The phone's songs with fingerprints (computed once per file, then kept in the library). */
    private suspend fun manifest(onCount: (Int, Int) -> Unit): Pair<List<TrackEntity>, List<ManifestTrack>> {
        val tracks = dao.tracksNow()
        val out = ArrayList<ManifestTrack>(tracks.size)
        val withFp = ArrayList<TrackEntity>(tracks.size)
        tracks.forEachIndexed { i, t ->
            var fp = t.syncFp
            if (fp == null) {
                fp = runCatching { Fingerprint.of(context, android.net.Uri.parse(t.uri)) }.getOrNull() ?: return@forEachIndexed
                dao.setSyncFp(t.id, fp)
                if (i % 10 == 0) onCount(i, tracks.size)
            }
            val f: String = fp
            withFp += t.copy(syncFp = f)
            out += ManifestTrack(f, fileName(t), t.sizeBytes, t.title, t.artist, t.album, t.durationMs, t.plays, t.lastPlayed)
        }
        return withFp to out
    }

    private fun fileName(t: TrackEntity): String {
        val ext = when (t.codec?.uppercase()) { "FLAC" -> "flac"; "MP3" -> "mp3"; null -> "bin"; else -> t.codec.lowercase() }
        val base = listOfNotNull(t.artist, t.title).joinToString(" - ").replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
        return "$base.$ext"
    }

    /** Free space on the phone's shared storage. */
    private fun free(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(0)

    suspend fun run(pc: PairedPc) {
        val prefs = store.prefs()
        ui { SyncUi(SyncPhase.COMPARING, pc.name, "Comparing libraries…") }
        val client = SyncClient(pc.host, pc.port, pc.fp, pc.token)
        try {
            val (tracks, manifest) = manifest { i, n -> ui { it.copy(message = "Reading your songs… $i / $n") } }
            val byFp = tracks.associateBy { it.syncFp!! }
            val lists = dao.playlistsNow().map { p ->
                ManifestPlaylist(p.name, dao.playlistTracks(p.id).mapNotNull { it.syncFp }, p.modified)
            }
            val budget = (free() - prefs.keepFree).coerceAtLeast(1)
            val plan = client.start(
                store.deviceId, manifest, lists,
                SyncOptions(noUpload = !prefs.upload, only = prefs.only, budget = budget, maxBits = if (prefs.to16Bit) 16 else 0),
            )
            val total = plan.toPhone.size + plan.toPc.size
            ui {
                it.copy(phase = if (total > 0) SyncPhase.MOVING else SyncPhase.COMPARING, total = total, totalBytes = plan.bytes,
                    toPhone = plan.toPhone.size, toPc = plan.toPc.size, skipped = plan.skipped,
                    message = "${plan.toPhone.size} to the phone · ${plan.toPc.size} to the PC")
            }
            val work = File(context.cacheDir, "sync").apply { mkdirs() }
            var done = 0
            val got = HashMap<String, Long>() // fp → new track id

            for (t in plan.toPhone) {
                ui { it.copy(current = t.title) }
                SyncBus.flights.tryEmit(Flight(true, t.title, t.artId?.let { client.art(it) }))
                val part = File(work, t.fp + ".part")
                client.download(plan.session, t.fp, part) { n -> ui { it.copy(bytes = it.bytes + n) } }
                val ext = t.name.substringAfterLast('.', "flac").lowercase()
                val mime = if (ext == "mp3") "audio/mpeg" else if (ext == "flac") "audio/flac" else "audio/*"
                val uri = MusicStore.save(context, part, t.name, mime, "Music/Kororythm/Sync/" + t.folder)
                if (uri != null) {
                    val track = Scanner(context, dao).addOne(uri, t.name, part.length())
                    // Remember the PC's fingerprint (a 16-bit copy hashes differently but is the same song).
                    track?.let { dao.setSyncFp(it.id, t.fp); got[t.fp] = it.id }
                }
                part.delete()
                done++
                ui { it.copy(done = done) }
            }
            for (t in plan.toPc) {
                val local = byFp[t.fp] ?: continue
                ui { it.copy(current = t.title) }
                SyncBus.flights.tryEmit(Flight(false, t.title, local.artPath?.let { p -> runCatching { File(p).readBytes() }.getOrNull() }))
                val uri = android.net.Uri.parse(local.uri)
                client.upload(plan.session, t.fp, t.size, { context.contentResolver.openInputStream(uri)!! }) { n -> ui { it.copy(bytes = it.bytes + n) } }
                done++
                ui { it.copy(done = done) }
            }

            // Playlists the PC sent: same name → replaced, otherwise created. They keep the PC's edit time.
            val idOf = HashMap<String, Long>()
            dao.tracksNow().forEach { tr -> tr.syncFp?.let { idOf[it] = tr.id } }
            idOf.putAll(got)
            val existing = dao.playlistsNow().associateBy { it.name.trim().lowercase() }
            for (l in plan.playlists) {
                val ids = l.items.mapNotNull { idOf[it] }
                if (ids.isEmpty()) continue
                val id = existing[l.name.trim().lowercase()]?.id?.also { dao.setPlaylistOrder(it, ids) } ?: dao.createPlaylist(l.name, ids)
                dao.touchPlaylist(id, l.modified)
            }

            // Play counts: both sides' new plays added up.
            val plays = dao.tracksNow().mapNotNull { tr -> tr.syncFp?.let { it to PlayCount(tr.plays, tr.lastPlayed) } }.toMap()
            val merged = client.finish(plan.session, plays)
            merged.forEach { (fp, c) -> idOf[fp]?.let { dao.setPlays(it, c.plays, c.lastPlayed) } }

            store.put(pc.copy(lastSync = System.currentTimeMillis()))
            ui {
                it.copy(phase = SyncPhase.DONE, current = null, at = System.currentTimeMillis(),
                    message = if (total == 0) "Already in sync" else "${plan.toPhone.size} to the phone · ${plan.toPc.size} to the PC" +
                        if (plan.skipped > 0) " · ${plan.skipped} wait for more space" else "")
            }
            if (plan.toPhone.isNotEmpty()) {
                com.koro.kororythm.dj.AnalysisWorker.runNow(context)
                com.koro.kororythm.genres.GenreWorker.runNow(context)
            }
        } catch (e: Exception) {
            ui { it.copy(phase = SyncPhase.ERROR, message = e.message ?: e.javaClass.simpleName, at = System.currentTimeMillis()) }
            throw e
        }
    }
}
