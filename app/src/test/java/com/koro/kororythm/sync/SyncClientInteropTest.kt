package com.koro.kororythm.sync

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The Android client against the real Go server (desktop/cmd/kororythm-synctest). Runs when
 * KORO_SYNCTEST points at that binary:
 *   go build -o /tmp/synctest ./desktop/cmd/kororythm-synctest && KORO_SYNCTEST=/tmp/synctest ./gradlew :app:testDebugUnitTest
 */
class SyncClientInteropTest {
    private val dir = listOf(File("../protocol/fixtures"), File("protocol/fixtures")).first { it.isDirectory }

    @Test fun pairSyncAndFinish() = runBlocking {
        val bin = System.getenv("KORO_SYNCTEST")
        assumeTrue("KORO_SYNCTEST not set", bin != null)
        val proc = ProcessBuilder(bin, "-fixtures", dir.path).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            val info = JSONObject(proc.inputStream.bufferedReader().readLine())
            val (host, port) = info.getString("addr").split(":").let { it[0] to it[1].toInt() }
            val code = info.getString("code")

            // wrong code first, then the right one
            val wrong = if (code == "000000") "111111" else "000000"
            val bad = runCatching { SyncClient(host, port, null).pair(wrong, "dev-1", "Test phone") }
            assertTrue(bad.exceptionOrNull() is SyncException)
            val (token, hello) = SyncClient(host, port, null).pair(code, "dev-1", "Test phone")
            assertEquals(info.getString("fp"), hello.fp)
            assertEquals("TEST-PC", hello.name)

            // a client pinned to another key refuses to talk
            val pinnedWrong = runCatching { SyncClient(host, port, "00".repeat(32), token).hello() }
            assertTrue(pinnedWrong.isFailure)

            val c = SyncClient(host, port, hello.fp, token)
            val night = File(dir, "a-nomd5.flac")
            val tracks = listOf(
                ManifestTrack(Fingerprint.of(File(dir, "t-retagged.mp3")), "t.mp3", 1, "Retagged", null, null, 0, 2, 5),
                ManifestTrack(Fingerprint.of(night), "night.flac", night.length(), "Night", "Phone Band", "Pocket", 0, 0, 0),
            )
            val plan = c.start("dev-1", tracks, listOf(ManifestPlaylist("Phone mix", tracks.map { it.fp }, 7)), SyncOptions())
            assertEquals(1, plan.toPhone.size)
            assertEquals(Fingerprint.of(File(dir, "a.flac")), plan.toPhone[0].fp)
            assertEquals(listOf(tracks[1].fp), plan.toPc.map { it.fp })

            val dest = File.createTempFile("sync-dl", ".part")
            dest.writeBytes(File(dir, "a.flac").readBytes().copyOf(1000)) // pretend a cut-off download: it resumes
            var got = 0L
            c.download(plan.session, plan.toPhone[0].fp, dest) { got += it }
            assertArrayEquals(File(dir, "a.flac").readBytes(), dest.readBytes())
            assertEquals(File(dir, "a.flac").length() - 1000, got)

            c.upload(plan.session, tracks[1].fp, night.length(), { night.inputStream() }) {}
            val merged = c.finish(plan.session, mapOf(tracks[0].fp to PlayCount(2, 5)))
            assertEquals(2, merged[tracks[0].fp]?.plays)
        } finally {
            proc.outputStream.close()
            proc.waitFor()
        }
    }
}
