package com.koro.kororythm.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Same fixtures as the desktop's fingerprint_test.go: both sides must agree. */
class FingerprintTest {
    private val dir = listOf(File("../protocol/fixtures"), File("protocol/fixtures")).first { it.isDirectory }

    @Test fun matchesTheDesktop() {
        val want = JSONObject(File(dir, "fingerprints.json").readText())
        for (name in want.keys()) assertEquals(name, want.getString(name), Fingerprint.of(File(dir, name)))
    }

    @Test fun proofMatchesTheDesktop() {
        // Go: Proof("123456", "kororythm-pair", "nonce", "fp")
        assertEquals("c84991081e3f35bd73990f08d6eec93c61c83f6745ce9a8724f02feb8fe8a126", SyncProtocol.proof("123456", "kororythm-pair", "nonce", "fp"))
    }
}
